package com.example.launcher

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.Locale

class HomeActivity : Activity() {

    companion object {
        private const val IDLE_TIMEOUT_MS = 120_000L       // دقيقتين بدون لمس
        private const val SWIPE_TRIGGER_PX = 50f           // مسافة السحب للأعلى اللي تفتح الدرج
        private const val TICK_MS = 15_000L
        private const val WEATHER_MAX_AGE_MS = 30 * 60_000L
        private const val WEATHER_RETRY_MS = 2 * 60_000L
        private const val PERMISSION_REQ = 1
        private const val WALLPAPER_REQ = 2
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val SAUDI_UTC_OFFSET = 3.0

        // مواضع الصلوات الخمس في نتيجة PrayerCalc (نتجاوز الشروق)
        private val PRAYERS = intArrayOf(0, 2, 3, 4, 5)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val arabic = Locale("ar")

    private lateinit var store: AppStore
    private lateinit var idle: IdleController
    private lateinit var music: MusicTile
    private lateinit var audio: AudioManager
    private lateinit var carVolume: CarVolume

    private lateinit var root: View
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var speedText: TextView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherDesc: TextView
    private lateinit var nextPrayerText: TextView
    private lateinit var idleOverlay: FrameLayout
    private lateinit var idleClock: TextView
    private lateinit var idlePrayer: TextView
    private lateinit var idleNowPlaying: TextView
    private lateinit var volBar: VolumeBar
    private lateinit var volText: TextView
    private lateinit var volumeColumn: View
    private lateinit var drawer: View
    private lateinit var drawerTitle: TextView
    private lateinit var wallpaperView: ImageView
    private lateinit var wallpaperScrim: View

    private val prayerNameViews = ArrayList<TextView>()
    private val prayerTimeViews = ArrayList<TextView>()

    private val drawerAdapter = DrawerAdapter()
    private var pickerSlot: String? = null

    private var appliedDay = false
    private var nextPrayerLabel = ""
    private var weatherLoading = false
    private var lastWeatherAttempt = 0L

    // حالة اللمس
    private var downX = 0f
    private var downY = 0f
    private var swipeArmed = false
    private var swallowGesture = false

    private val tiles = listOf(
        R.id.mapsTile to AppStore.MAPS,
        R.id.slot1 to AppStore.SLOT_1,
        R.id.slot2 to AppStore.SLOT_2,
        R.id.slot3 to AppStore.SLOT_3
    )

    private val tick = object : Runnable {
        override fun run() {
            refreshTimeViews()
            maybeRefreshWeather()
            // الوضع التلقائي: يتبدل عند الشروق والمغرب
            if (store.themeMode() == AppStore.MODE_AUTO && isDayNow() != appliedDay) {
                recreate()
                return
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshVolume()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (location.hasSpeed()) {
                val kmh = Math.round(location.speed * 3.6f)
                speedText.text = String.format(arabic, "%d", kmh)
            }
            syncClock(location.time)
            val moved = Math.abs(location.latitude - store.lat()) > 0.05 ||
                Math.abs(location.longitude - store.lng()) > 0.05
            if (moved) {
                store.setLocation(location.latitude, location.longitude)
                refreshTimeViews()
                lastWeatherAttempt = 0L
                maybeRefreshWeather(force = true)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        store = AppStore(this)
        TimeSource.offsetMs = store.clockOffset()
        appliedDay = isDayNow()
        setTheme(if (appliedDay) R.style.LauncherDay else R.style.LauncherNight)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        root = findViewById(R.id.root)
        dateText = findViewById(R.id.dateText)
        clockText = findViewById(R.id.clockText)
        speedText = findViewById(R.id.speedText)
        weatherTemp = findViewById(R.id.weatherTemp)
        weatherDesc = findViewById(R.id.weatherDesc)
        nextPrayerText = findViewById(R.id.nextPrayerText)
        idleOverlay = findViewById(R.id.idleOverlay)
        idleClock = findViewById(R.id.idleClock)
        idlePrayer = findViewById(R.id.idlePrayer)
        idleNowPlaying = findViewById(R.id.idleNowPlaying)
        volBar = findViewById(R.id.volBar)
        volText = findViewById(R.id.volText)
        volumeColumn = findViewById(R.id.volumeColumn)
        drawer = findViewById(R.id.drawer)
        drawerTitle = findViewById(R.id.drawerTitle)
        wallpaperView = findViewById(R.id.wallpaper)
        wallpaperScrim = findViewById(R.id.wallpaperScrim)

        music = MusicTile(this, handler)

        setupThemeToggle()
        setupWallpaper()
        setupPrayerRow()
        setupVolume()
        setupTiles()
        setupDrawer()

        idle = IdleController(
            handler,
            IDLE_TIMEOUT_MS,
            onIdle = {
                refreshTimeViews()
                idleOverlay.visibility = View.VISIBLE
            },
            onWake = { idleOverlay.visibility = View.GONE }
        )

        showWeather()
        requestMissingPermissions()
    }

    // ---------- الوضع: تلقائي / نهاري / ليلي ----------

    private fun isDayNow(): Boolean {
        return when (store.themeMode()) {
            AppStore.MODE_DAY -> true
            AppStore.MODE_NIGHT -> false
            else -> {
                val times = prayerTimes()
                val now = nowHours()
                now >= times[1] && now < times[4]   // من الشروق إلى المغرب
            }
        }
    }

    private fun setupThemeToggle() {
        val toggle = findViewById<Button>(R.id.themeToggle)
        toggle.text = when (store.themeMode()) {
            AppStore.MODE_DAY -> "الوضع: نهاري"
            AppStore.MODE_NIGHT -> "الوضع: ليلي"
            else -> "الوضع: تلقائي"
        }
        toggle.setOnClickListener {
            val next = when (store.themeMode()) {
                AppStore.MODE_AUTO -> AppStore.MODE_DAY
                AppStore.MODE_DAY -> AppStore.MODE_NIGHT
                else -> AppStore.MODE_AUTO
            }
            store.setThemeMode(next)
            recreate()
        }
    }

    // ---------- الخلفية ----------

    private fun wallpaperFile(): File = File(filesDir, "wallpaper.jpg")

    private fun setupWallpaper() {
        findViewById<Button>(R.id.wallpaperButton).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("الخلفية")
                .setItems(arrayOf("اختيار صورة", "إزالة الخلفية")) { _, which ->
                    if (which == 0) {
                        pickWallpaper()
                    } else {
                        wallpaperFile().delete()
                        loadWallpaper()
                    }
                }
                .show()
        }
        loadWallpaper()
    }

    private fun pickWallpaper() {
        try {
            val intent = Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
            startActivityForResult(intent, WALLPAPER_REQ)
        } catch (e: Exception) {
            toast("ما لقيت تطبيق يختار الصور على الراديو")
        }
    }

    private fun loadWallpaper() {
        val file = wallpaperFile()
        if (!file.exists()) {
            wallpaperView.setImageDrawable(null)
            wallpaperView.visibility = View.GONE
            wallpaperScrim.visibility = View.GONE
            return
        }
        Thread {
            val bitmap = try {
                BitmapFactory.decodeFile(file.absolutePath)
            } catch (e: Throwable) {
                null
            }
            handler.post {
                if (bitmap != null && !isFinishing) {
                    wallpaperView.setImageBitmap(bitmap)
                    wallpaperView.visibility = View.VISIBLE
                    wallpaperScrim.visibility = View.VISIBLE
                }
            }
        }.start()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != WALLPAPER_REQ || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Thread {
            // نصغّر الصورة لمقاس الشاشة قبل الحفظ، عشان ما تاخذ ذاكرة الراديو
            val saved = try {
                val temp = File(cacheDir, "wallpaper_pick")
                contentResolver.openInputStream(uri)!!.use { input ->
                    FileOutputStream(temp).use { output -> input.copyTo(output) }
                }
                val bounds = BitmapFactory.Options()
                bounds.inJustDecodeBounds = true
                BitmapFactory.decodeFile(temp.absolutePath, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= 1024) sample *= 2
                val options = BitmapFactory.Options()
                options.inSampleSize = sample
                val bitmap = BitmapFactory.decodeFile(temp.absolutePath, options)
                temp.delete()
                if (bitmap == null) {
                    false
                } else {
                    FileOutputStream(wallpaperFile()).use {
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it)
                    }
                    true
                }
            } catch (e: Throwable) {
                false
            }
            handler.post {
                if (saved) loadWallpaper() else toast("تعذر فتح الصورة")
            }
        }.start()
    }

    // ---------- الوقت ومواقيت الصلاة ----------

    private fun prayerTimes(): DoubleArray {
        val c = TimeSource.calendar()
        return PrayerCalc.compute(
            c.get(Calendar.YEAR),
            c.get(Calendar.MONTH) + 1,
            c.get(Calendar.DAY_OF_MONTH),
            store.lat(),
            store.lng(),
            SAUDI_UTC_OFFSET
        )
    }

    private fun nowHours(): Double {
        val c = TimeSource.calendar()
        return c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0 +
            c.get(Calendar.SECOND) / 3600.0
    }

    /** يحوّل 14.75 إلى "٢:٤٥" بنظام 12 ساعة. */
    private fun formatHours(value: Double): String {
        var hour = Math.floor(value).toInt()
        var minute = Math.round((value - hour) * 60.0).toInt()
        if (minute == 60) {
            hour += 1
            minute = 0
        }
        val hour12 = ((hour % 24) + 11) % 12 + 1
        return String.format(arabic, "%d:%02d", hour12, minute)
    }

    private fun setupPrayerRow() {
        val row = findViewById<LinearLayout>(R.id.prayerRow)
        val cells = ArrayList<LinearLayout>()
        for (i in PRAYERS.indices) {
            val name = TextView(this)
            name.text = PrayerCalc.NAMES[PRAYERS[i]]
            name.textSize = 14f
            name.gravity = Gravity.CENTER
            val time = TextView(this)
            time.textSize = 16f
            time.gravity = Gravity.CENTER
            time.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            val cell = LinearLayout(this)
            cell.orientation = LinearLayout.VERTICAL
            cell.gravity = Gravity.CENTER
            cell.addView(name)
            cell.addView(time)
            prayerNameViews.add(name)
            prayerTimeViews.add(time)
            cells.add(cell)
        }
        // الفجر على اليمين
        for (cell in cells.reversed()) {
            row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    /** يحدّث الساعة والتاريخ والصلاة القادمة، في الواجهة وفي شاشة الانتظار. */
    private fun refreshTimeViews() {
        val time = TimeSource.format("HH:mm")
        clockText.text = time
        dateText.text = TimeSource.format("EEEE d MMMM")

        val times = prayerTimes()
        val now = nowHours()
        var next = -1
        for (i in PRAYERS.indices) {
            if (times[PRAYERS[i]] > now) {
                next = i
                break
            }
        }
        // بعد العشاء: القادمة فجر بكرة (نفس وقت اليوم تقريباً)
        val nextTime = if (next >= 0) times[PRAYERS[next]] else times[0] + 24.0
        val nextIndex = if (next >= 0) next else 0
        val remaining = Math.max(0, Math.round((nextTime - now) * 60.0).toInt())
        val name = PrayerCalc.NAMES[PRAYERS[nextIndex]]
        nextPrayerLabel = if (remaining >= 60) {
            String.format(arabic, "%s بعد %d:%02d", name, remaining / 60, remaining % 60)
        } else {
            String.format(arabic, "%s بعد %d دقيقة", name, remaining)
        }
        nextPrayerText.text = nextPrayerLabel

        val accent = themeColor(R.attr.lAccent)
        val normal = themeColor(R.attr.lText)
        val dim = themeColor(R.attr.lTextDim)
        for (i in PRAYERS.indices) {
            prayerTimeViews[i].text = formatHours(times[PRAYERS[i]])
            val highlight = i == nextIndex
            prayerTimeViews[i].setTextColor(if (highlight) accent else normal)
            prayerNameViews[i].setTextColor(if (highlight) accent else dim)
        }

        idleClock.text = time
        idlePrayer.text = nextPrayerLabel
        idleNowPlaying.text = music.nowPlaying
    }

    /** ساعة الراديو قد تكون غلط، فنصححها من وقت الـGPS. */
    private fun syncClock(gpsTime: Long) {
        val check = Calendar.getInstance(TimeSource.zone)
        check.timeInMillis = gpsTime
        if (check.get(Calendar.YEAR) < 2025) return   // وقت GPS غير موثوق
        var offset = gpsTime - System.currentTimeMillis()
        if (Math.abs(offset) < 60_000L) offset = 0L
        if (Math.abs(offset - TimeSource.offsetMs) > 30_000L) {
            TimeSource.offsetMs = offset
            store.setClockOffset(offset)
            refreshTimeViews()
        }
    }

    // ---------- الطقس ----------

    private fun showWeather() {
        if (store.weatherTime() == 0L) {
            weatherTemp.text = ""
            weatherDesc.text = "الطقس يحتاج اتصال بالإنترنت"
            return
        }
        val info = store.weather()
        weatherTemp.text = String.format(arabic, "%d°", Math.round(info.temp))
        weatherDesc.text = String.format(
            arabic, "%s\nرياح %d كم/س", Weather.describe(info.code), Math.round(info.wind)
        )
    }

    private fun maybeRefreshWeather(force: Boolean = false) {
        if (weatherLoading) return
        val age = System.currentTimeMillis() - store.weatherTime()
        val fresh = store.weatherTime() != 0L && age >= 0 && age < WEATHER_MAX_AGE_MS
        if (fresh && !force) return
        val sinceAttempt = SystemClock.elapsedRealtime() - lastWeatherAttempt
        if (lastWeatherAttempt != 0L && sinceAttempt < WEATHER_RETRY_MS) return
        lastWeatherAttempt = SystemClock.elapsedRealtime()
        weatherLoading = true
        Weather.fetch(store.lat(), store.lng()) { info ->
            weatherLoading = false
            if (info != null) {
                store.setWeather(info, System.currentTimeMillis())
                showWeather()
            }
        }
    }

    // ---------- الصوت ----------

    private fun setupVolume() {
        carVolume = CarVolume(this)
        volBar.max = carVolume.max
        volBar.onChange = { setVolume(it) }
        findViewById<Button>(R.id.volUp).setOnClickListener { setVolume(volBar.value + 1) }
        findViewById<Button>(R.id.volDown).setOnClickListener { setVolume(volBar.value - 1) }
        refreshVolume()
    }

    private fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, volBar.max)
        volBar.value = clamped
        volText.text = String.format(arabic, "%d", clamped)
        carVolume.set(clamped)
    }

    private fun refreshVolume() {
        // والمستخدم يحرك الشريط، ما نرجّعه للقيمة القديمة
        if (carVolume.busy) return
        val current = carVolume.current()
        volBar.value = current
        volText.text = String.format(arabic, "%d", current)
    }

    // ---------- مربعات التطبيقات ----------

    private fun setupTiles() {
        for ((viewId, key) in tiles) {
            val tile = findViewById<View>(viewId)
            tile.setOnClickListener {
                val pkg = store.get(key)
                if (pkg == null || !launchPackage(pkg)) {
                    openDrawer(key)
                }
            }
            // ضغطة مطولة تغيّر التطبيق
            tile.setOnLongClickListener {
                openDrawer(key)
                true
            }
        }
        refreshTiles()
    }

    private fun refreshTiles() {
        for ((viewId, key) in tiles) {
            val tile = findViewById<View>(viewId)
            val icon = tile.findViewById<ImageView>(R.id.tileIcon)
            val label = tile.findViewById<TextView>(R.id.tileLabel)
            val pkg = store.get(key)
            val entry = if (pkg != null) AppRepo.find(pkg) else null

            if (entry != null) {
                icon.visibility = View.VISIBLE
                icon.setImageDrawable(entry.icon)
                label.text = if (key == AppStore.MAPS) "الخرائط" else entry.label
            } else {
                icon.visibility = View.GONE
                label.text = if (key == AppStore.MAPS) "الخرائط" else "+ اختر تطبيق"
            }
        }
    }

    private fun onAppsLoaded() {
        drawerAdapter.notifyDataSetChanged()
        refreshTiles()
    }

    private fun launchPackage(pkg: String): Boolean {
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        return try {
            startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ---------- درج التطبيقات ----------

    private fun setupDrawer() {
        val grid = findViewById<GridView>(R.id.drawerGrid)
        grid.adapter = drawerAdapter
        grid.setOnItemClickListener { _, _, position, _ ->
            val entry = AppRepo.apps.getOrNull(position) ?: return@setOnItemClickListener
            val slot = pickerSlot
            if (slot != null) {
                store.set(slot, entry.pkg)
                refreshTiles()
                closeDrawer(animate = true)
            } else {
                if (!launchPackage(entry.pkg)) toast("تعذر فتح التطبيق")
                closeDrawer(animate = false)
            }
        }
        findViewById<Button>(R.id.drawerClose).setOnClickListener { closeDrawer(animate = true) }
        findViewById<View>(R.id.drawerHandle).setOnClickListener { openDrawer(null) }
        findViewById<Button>(R.id.drawerDiag).setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
    }

    private fun isDrawerOpen(): Boolean = drawer.visibility == View.VISIBLE

    /** slot = null يفتح التطبيقات عادي. غير كذا يختار تطبيق للمربع المحدد. */
    private fun openDrawer(slot: String?) {
        pickerSlot = slot
        drawerTitle.text = if (slot == null) "كل التطبيقات" else "اختر تطبيق للمربع"
        if (isDrawerOpen()) return
        drawer.animate().cancel()
        drawer.translationY = root.height.toFloat()
        drawer.visibility = View.VISIBLE
        drawer.animate().translationY(0f).setDuration(160L).start()
    }

    private fun closeDrawer(animate: Boolean) {
        pickerSlot = null
        if (!isDrawerOpen()) return
        drawer.animate().cancel()
        if (animate) {
            drawer.animate()
                .translationY(root.height.toFloat())
                .setDuration(140L)
                .withEndAction { drawer.visibility = View.GONE }
                .start()
        } else {
            drawer.visibility = View.GONE
        }
    }

    private inner class DrawerAdapter : BaseAdapter() {
        override fun getCount(): Int = AppRepo.apps.size
        override fun getItem(position: Int): Any = AppRepo.apps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = AppRepo.apps[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.appLabel).text = entry.label
            return view
        }
    }

    // ---------- دورة الحياة ----------

    override fun onResume() {
        super.onResume()
        music.start()
        refreshVolume()
        val volumeFilter = IntentFilter(VOLUME_CHANGED)
        volumeFilter.addAction(CarVolume.MAIN_VOLUME_CHANGED)
        registerReceiver(volumeReceiver, volumeFilter)
        startSpeed()
        AppRepo.load(this) { onAppsLoaded() }
        idle.poke()
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        idle.stop()
        music.stop()
        stopSpeed()
        carVolume.stop()
        try {
            unregisterReceiver(volumeReceiver)
        } catch (_: Exception) {
        }
    }

    /** زر البيت وأنت في اللانشر: يقفل الدرج ويرجع للواجهة. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        closeDrawer(animate = false)
    }

    /** زر الرجوع ما يطلع من اللانشر. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isDrawerOpen()) closeDrawer(animate = true)
    }

    // ---------- الصلاحيات والسرعة ----------

    private fun granted(permission: String): Boolean {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), PERMISSION_REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ) startSpeed()
    }

    private fun startSpeed() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } catch (_: Exception) {
            // ما فيه GPS أو الصلاحية مرفوضة
        }
    }

    private fun stopSpeed() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locationListener)
        } catch (_: Exception) {
        }
    }

    // ---------- اللمس: شاشة الانتظار والسحب للأعلى ----------

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val wasIdle = idle.isIdle
        idle.poke()

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // أول لمسة على شاشة الانتظار ترجّع الواجهة فقط، وما تضغط على شي تحتها
                swallowGesture = wasIdle
                downX = ev.x
                downY = ev.y
                val location = IntArray(2)
                volumeColumn.getLocationOnScreen(location)
                val onVolume = ev.rawX >= location[0]
                swipeArmed = !wasIdle && !isDrawerOpen() && !onVolume &&
                    ev.y > root.height * 0.72f
            }
            MotionEvent.ACTION_MOVE -> {
                if (swipeArmed) {
                    val dy = downY - ev.y
                    val dx = Math.abs(ev.x - downX)
                    if (dy > SWIPE_TRIGGER_PX && dy > dx) {
                        swipeArmed = false
                        swallowGesture = true
                        val cancel = MotionEvent.obtain(ev)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        openDrawer(null)
                    }
                }
            }
        }

        if (swallowGesture) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
