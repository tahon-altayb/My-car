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
    private lateinit var photos: PhotoTile

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

    /** التطبيقات المعروضة في القائمة: بترتيب المستخدم وبدون المخفية. */
    private val drawerApps = ArrayList<AppEntry>()
    private var dragging: AppEntry? = null
    private var dragPos = -1
    private var lastMoveAt = 0L
    private var pickerSlot: String? = null

    private var appliedDay = false
    private var appliedRev = 0
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
        R.id.slot1 to AppStore.SLOT_1
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
                speedText.text = String.format(Locale.US, "%d", kmh)
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
        store.setLastDay(appliedDay)
        appliedRev = store.rev()
        ThemeHelper.apply(this, store, appliedDay)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        ThemeHelper.tintBars(this)

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

        photos = PhotoTile(
            handler, findViewById(R.id.photoImage), findViewById(R.id.photoHint),
            (store.str("photo_sec", "7").toLongOrNull() ?: 7L) * 1000L
        )
        findViewById<View>(R.id.photoTile).setOnClickListener { photos.next() }

        applyLayoutPrefs()
        setupThemeToggle()
        setupBrightness()
        setupWallpaper()
        setupPrayerRow()
        setupVolume()
        setupTiles()
        setupDrawer()

        idle = IdleController(
            handler,
            idleTimeoutMs(),
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
                if (!TimeSource.valid()) return appliedDay
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

    // ---------- إعدادات المستخدم ----------

    private fun idleTimeoutMs(): Long {
        val seconds = store.str("idle_sec", "120").toLongOrNull() ?: 120L
        // صفر = لا تظهر شاشة الانتظار
        return if (seconds <= 0L) 365L * 24 * 3600 * 1000 else seconds * 1000L
    }

    // ---------- لوحة المربعات: الأماكن والأحجام ----------

    private lateinit var board: GridBoard

    private val defaultCells = mapOf(
        "clock" to android.graphics.Rect(0, 0, 3, 2),
        "speed" to android.graphics.Rect(0, 2, 3, 8),
        "weather" to android.graphics.Rect(3, 0, 7, 5),
        "music" to android.graphics.Rect(7, 0, 11, 5),
        "maps" to android.graphics.Rect(3, 5, 5, 8),
        "app1" to android.graphics.Rect(5, 5, 7, 8),
        "photo" to android.graphics.Rect(7, 5, 11, 8),
        "volume" to android.graphics.Rect(11, 0, 12, 8)
    )

    private fun applyLayoutPrefs() {
        board = findViewById(R.id.mainRow)
        board.add("clock", clockText, 2, 1, "الساعة")
        board.add("speed", findViewById(R.id.speedCard), 2, 2, "السرعة")
        board.add("weather", findViewById(R.id.weatherCard), 3, 3, "الطقس والصلاة")
        board.add("music", findViewById(R.id.musicCard), 3, 4, "الموسيقى")
        board.add("maps", findViewById(R.id.mapsTile), 1, 2, "الخرائط")
        board.add("app1", findViewById(R.id.slot1), 1, 2, "تطبيق")
        board.add("photo", findViewById(R.id.photoTile), 2, 2, "الصور")
        board.add("volume", volumeColumn, 1, 4, "الصوت")
        board.load(store.str("grid_layout", ""), defaultCells)

        val shown = mapOf(
            "clock" to "show_clock", "speed" to "show_speed", "weather" to "show_weather",
            "music" to "show_music", "photo" to "show_photo", "volume" to "show_volume"
        )
        for (wd in board.widgets) {
            val key = shown[wd.key] ?: continue
            wd.view.visibility = if (store.str(key, "1") == "1") View.VISIBLE else View.GONE
        }
        // مربع رجع يظهر ومكانه صار مشغول: ندوّر له على مكان فاضي
        for (wd in board.widgets) {
            if (wd.view.visibility == View.GONE || !board.overlaps(wd.cell, wd)) continue
            if (!board.findFreeSpot(wd)) {
                wd.view.visibility = View.GONE
                toast("ما فيه مكان لـ " + wd.label + "، صغّر مربع ثاني وأظهره من الإعدادات")
            }
        }

        board.onChanged = { store.setQuiet("grid_layout", board.serialize()) }
        board.onRejected = { toast("المكان مشغول بمربع ثاني") }
        findViewById<Button>(R.id.editDone).setOnClickListener { setEditMode(false) }
        findViewById<Button>(R.id.editReset).setOnClickListener {
            board.load("", defaultCells)
            store.setQuiet("grid_layout", "")
        }

        findViewById<GridView>(R.id.drawerGrid).numColumns =
            store.str("drawer_cols", "6").toIntOrNull() ?: 6
        applyGlass()
    }

    private fun setEditMode(on: Boolean) {
        board.editMode = on
        findViewById<View>(R.id.editBar).visibility = if (on) View.VISIBLE else View.GONE
        if (!on) store.setQuiet("grid_layout", board.serialize())
    }

    // ---------- المربعات الزجاجية ----------

    private fun glassOn(): Boolean = store.str("glass", "0") == "1"

    private fun applyGlass() {
        if (!glassOn()) return
        GlassDrawable.root = root
        val d = resources.displayMetrics.density
        val fill = if (appliedDay) 0x80FFFFFF.toInt() else 0x2EFFFFFF
        val stroke = if (appliedDay) 0xB3FFFFFF.toInt() else 0x4DFFFFFF
        val ids = intArrayOf(
            R.id.speedCard, R.id.weatherCard, R.id.musicCard, R.id.mapsTile,
            R.id.slot1, R.id.photoTile, R.id.volumeColumn,
            R.id.themeToggle, R.id.wallpaperButton, R.id.brightDown, R.id.brightUp
        )
        for (id in ids) {
            val view = findViewById<View>(id) ?: continue
            val glass = GlassDrawable(view, 20 * d, fill, stroke, 1.5f * d)
            view.background = if (view.isClickable) {
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x33FFFFFF), glass, null
                )
            } else glass
        }
    }

    /** طلب من صفحة الإعدادات: اختيار خلفية أو إزالتها، أو تعديل أماكن المربعات. */
    private fun handlePendingWallpaper() {
        if (store.str("pending_edit", "") == "1") {
            store.setQuiet("pending_edit", "")
            if (store.rev() == appliedRev) setEditMode(true) else store.setQuiet("pending_edit_after", "1")
        }
        val pending = store.str("pending_wallpaper", "")
        if (pending.isEmpty()) return
        store.setQuiet("pending_wallpaper", "")
        if (pending == "pick") {
            pickWallpaper()
        } else {
            wallpaperFile().delete()
            loadWallpaper()
        }
    }

    // ---------- السطوع ----------

    private fun setupBrightness() {
        findViewById<Button>(R.id.brightDown).setOnClickListener { applyBrightness(store.brightness() - 1) }
        findViewById<Button>(R.id.brightUp).setOnClickListener { applyBrightness(store.brightness() + 1) }
        applyBrightness(store.brightness())
    }

    private fun applyBrightness(requested: Int) {
        val level = requested.coerceIn(1, 10)
        store.setBrightness(level)
        findViewById<TextView>(R.id.brightText).text = String.format(Locale.US, "سطوع %d", level)
        // سطوع نافذة اللانشر
        val attrs = window.attributes
        attrs.screenBrightness = level / 10f
        window.attributes = attrs
        // وسطوع النظام كله لو مسموح لنا
        try {
            if (Build.VERSION.SDK_INT < 23 || android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(
                    contentResolver, android.provider.Settings.System.SCREEN_BRIGHTNESS, level * 255 / 10
                )
            }
        } catch (_: Throwable) {
        }
    }

    // ---------- الخلفية ----------

    private fun wallpaperFile(): File = File(filesDir, "wallpaper.jpg")

    private fun setupWallpaper() {
        findViewById<Button>(R.id.wallpaperButton).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
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
            GlassDrawable.blurred = null
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
                    if (glassOn()) {
                        // مع الزجاج نخلي الصورة أوضح، والمربعات تغبّش اللي خلفها
                        wallpaperScrim.alpha = 0.25f
                        Thread {
                            val soft = try {
                                GlassDrawable.blur(bitmap)
                            } catch (e: Throwable) {
                                null
                            }
                            handler.post {
                                GlassDrawable.blurred = soft
                                board.invalidate()
                                for (i in 0 until board.childCount) board.getChildAt(i).invalidate()
                                findViewById<View>(R.id.themeToggle).rootView.invalidate()
                            }
                        }.start()
                    }
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
        return String.format(Locale.US, "%d:%02d", hour12, minute)
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

    /** الساعة بنظام 12 ساعة، وجنبها ص أو م بخط أصغر. */
    private fun clockWithPeriod(periodScale: Float = 0.42f): CharSequence {
        if (store.str("clock24", "0") == "1") return TimeSource.format("HH:mm")
        val text = TimeSource.format("h:mm a")
        val space = text.indexOf(' ')
        if (space < 0) return text
        val span = android.text.SpannableString(text)
        span.setSpan(
            android.text.style.RelativeSizeSpan(periodScale), space, text.length,
            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        return span
    }

    /** يحدّث الساعة والتاريخ والصلاة القادمة، في الواجهة وفي شاشة الانتظار. */
    private fun refreshTimeViews() {
        if (!TimeSource.valid()) {
            // الراديو رجع لتاريخ المصنع وما وصل وقت الأقمار بعد
            clockText.text = "--:--"
            dateText.text = "بانتظار وقت GPS"
            nextPrayerLabel = "المواقيت بانتظار GPS"
            nextPrayerText.text = nextPrayerLabel
            for (v in prayerTimeViews) v.text = "--"
            idleClock.text = "--:--"
            idlePrayer.text = ""
            idleNowPlaying.text = music.nowPlaying
            return
        }
        val time = clockWithPeriod()
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
        checkPrayerAlert(name, remaining)
        nextPrayerLabel = if (remaining >= 60) {
            String.format(Locale.US, "%s بعد %d:%02d", name, remaining / 60, remaining % 60)
        } else {
            String.format(Locale.US, "%s بعد %d دقيقة", name, remaining)
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

        idleClock.text = clockWithPeriod(0.6f)
        idlePrayer.text = nextPrayerLabel
        idleNowPlaying.text = music.nowPlaying
    }

    // ---------- تنبيه الصلاة ----------

    private var lastNextPrayer = ""
    private var alertedFor = ""
    private val hideBanner = Runnable { findViewById<View>(R.id.alertBanner).visibility = View.GONE }

    private fun checkPrayerAlert(nextName: String, remainingMin: Int) {
        val before = store.str("prayer_alert", "10").toIntOrNull() ?: 10
        if (before <= 0) {
            lastNextPrayer = nextName
            return
        }
        // الصلاة القادمة تغيّرت واللانشر شغال: معناها دخل وقت اللي قبلها
        if (lastNextPrayer.isNotEmpty() && lastNextPrayer != nextName) {
            showBanner("حان الآن وقت صلاة $lastNextPrayer")
        } else if (remainingMin in 1..before && before - remainingMin <= 1 && alertedFor != nextName) {
            alertedFor = nextName
            showBanner(String.format(Locale.US, "باقي %d دقائق على صلاة %s", remainingMin, nextName))
        }
        if (lastNextPrayer != nextName) alertedFor = ""
        lastNextPrayer = nextName
    }

    private fun showBanner(text: String) {
        val banner = findViewById<TextView>(R.id.alertBanner)
        banner.text = text
        banner.visibility = View.VISIBLE
        handler.removeCallbacks(hideBanner)
        handler.postDelayed(hideBanner, 25_000L)
        try {
            val tone = android.media.ToneGenerator(AudioManager.STREAM_MUSIC, 70)
            tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP2, 500)
            handler.postDelayed({ tone.release() }, 1500L)
        } catch (_: Throwable) {
        }
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
        weatherTemp.text = String.format(Locale.US, "%d°", Math.round(info.temp))
        weatherDesc.text = String.format(
            Locale.US, "%s\nرياح %d كم/س", Weather.describe(info.code), Math.round(info.wind)
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
            if (Weather.serverTime > 0L) {
                syncClock(Weather.serverTime)
                Weather.serverTime = 0L
            }
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
        volText.text = String.format(Locale.US, "%d", clamped)
        carVolume.set(clamped)
    }

    private fun refreshVolume() {
        // والمستخدم يحرك الشريط، ما نرجّعه للقيمة القديمة
        if (carVolume.busy) return
        val current = carVolume.current()
        volBar.value = current
        volText.text = String.format(Locale.US, "%d", current)
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

    private fun splitList(key: String): List<String> =
        store.str(key, "").split("\n").filter { it.isNotEmpty() }

    /** يبني قائمة العرض من التطبيقات المثبتة + ترتيب المستخدم + المخفية. */
    private fun rebuildDrawerApps() {
        val hidden = splitList("hidden_apps").toSet()
        val order = splitList("app_order")
        val rank = HashMap<String, Int>()
        for (i in order.indices) rank[order[i]] = i
        val visible = AppRepo.apps.filter { it.pkg !in hidden }
        // اللي له ترتيب محفوظ يجي بترتيبه، والجديد يجي بعدهم أبجدياً
        val sorted = visible.sortedWith(
            compareBy<AppEntry> { rank[it.pkg] ?: Int.MAX_VALUE }.thenBy { it.label.lowercase() }
        )
        drawerApps.clear()
        drawerApps.addAll(sorted)
    }

    private fun saveDrawerOrder() {
        store.setQuiet("app_order", drawerApps.joinToString("\n") { it.pkg })
    }

    private fun onAppsLoaded() {
        if (dragging == null) rebuildDrawerApps()
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
            val entry = drawerApps.getOrNull(position) ?: return@setOnItemClickListener
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
        setupDrawerDrag(grid)
        findViewById<Button>(R.id.drawerClose).setOnClickListener { closeDrawer(animate = true) }
        findViewById<View>(R.id.drawerHandle).setOnClickListener { openDrawer(null) }
        findViewById<Button>(R.id.drawerDiag).setOnClickListener {
            startActivity(Intent(this, DiagActivity::class.java))
        }
    }

    // ---------- ترتيب التطبيقات بالسحب والإفلات ----------

    private fun setupDrawerDrag(grid: GridView) {
        val dropHide = findViewById<TextView>(R.id.dropHide)
        val dropTop = findViewById<TextView>(R.id.dropTop)
        val edge = (70 * resources.displayMetrics.density).toInt()

        grid.setOnItemLongClickListener { _, view, position, _ ->
            val entry = drawerApps.getOrNull(position) ?: return@setOnItemLongClickListener false
            if (pickerSlot != null) return@setOnItemLongClickListener false
            dragging = entry
            dragPos = position
            dropHide.visibility = View.VISIBLE
            dropTop.visibility = View.VISIBLE
            drawerTitle.visibility = View.GONE
            @Suppress("DEPRECATION")
            view.startDrag(null, View.DragShadowBuilder(view), null, 0)
            drawerAdapter.notifyDataSetChanged()
            true
        }

        grid.setOnDragListener { _, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_LOCATION -> {
                    val item = dragging
                    if (item != null) {
                        val x = event.x.toInt()
                        val y = event.y.toInt()
                        // تمرير تلقائي لما تقرب من الحافة
                        if (y < edge) grid.smoothScrollBy(-edge, 120)
                        else if (y > grid.height - edge) grid.smoothScrollBy(edge, 120)

                        val target = grid.pointToPosition(x, y)
                        val now = android.os.SystemClock.uptimeMillis()
                        if (target != android.widget.AdapterView.INVALID_POSITION &&
                            target != dragPos && target < drawerApps.size &&
                            now - lastMoveAt > 140L
                        ) {
                            val from = drawerApps.indexOf(item)
                            if (from >= 0) {
                                drawerApps.removeAt(from)
                                drawerApps.add(target, item)
                                dragPos = target
                                lastMoveAt = now
                                drawerAdapter.notifyDataSetChanged()
                            }
                        }
                    }
                }
                android.view.DragEvent.ACTION_DRAG_ENDED -> {
                    if (dragging != null) {
                        dragging = null
                        dragPos = -1
                        saveDrawerOrder()
                        dropHide.visibility = View.GONE
                        dropTop.visibility = View.GONE
                        dropHide.alpha = 1f
                        dropTop.alpha = 1f
                        drawerTitle.visibility = View.VISIBLE
                        drawerAdapter.notifyDataSetChanged()
                    }
                }
            }
            true
        }

        val zoneListener = View.OnDragListener { zone, event ->
            when (event.action) {
                android.view.DragEvent.ACTION_DRAG_ENTERED -> zone.alpha = 0.5f
                android.view.DragEvent.ACTION_DRAG_EXITED -> zone.alpha = 1f
                android.view.DragEvent.ACTION_DROP -> {
                    val item = dragging
                    if (item != null) {
                        drawerApps.remove(item)
                        if (zone.id == R.id.dropTop) {
                            drawerApps.add(0, item)
                        } else {
                            val hidden = splitList("hidden_apps").toMutableList()
                            hidden.add(item.pkg)
                            store.setQuiet("hidden_apps", hidden.joinToString("\n"))
                            toast("انخفى " + item.label + ". ترجعه من الإعدادات")
                        }
                    }
                }
            }
            true
        }
        dropHide.setOnDragListener(zoneListener)
        dropTop.setOnDragListener(zoneListener)
    }

    private fun isDrawerOpen(): Boolean = drawer.visibility == View.VISIBLE

    /** slot = null يفتح التطبيقات عادي. غير كذا يختار تطبيق للمربع المحدد. */
    private fun openDrawer(slot: String?) {
        pickerSlot = slot
        drawerTitle.text = if (slot == null) "اضغط مطولاً على تطبيق لترتيبه" else "اختر تطبيق للمربع"
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
        override fun getCount(): Int = drawerApps.size
        override fun getItem(position: Int): Any = drawerApps[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_app, parent, false)
            val entry = drawerApps[position]
            view.findViewById<ImageView>(R.id.appIcon).setImageDrawable(entry.icon)
            view.findViewById<TextView>(R.id.appLabel).text = entry.label
            // مكان التطبيق المسحوب يبان باهت
            view.alpha = if (entry === dragging) 0.2f else 1f
            return view
        }
    }

    // ---------- دورة الحياة ----------

    override fun onResume() {
        super.onResume()
        handlePendingWallpaper()
        if (store.rev() != appliedRev) {
            // تغيّرت الإعدادات: نعيد رسم الواجهة
            recreate()
            return
        }
        if (store.str("pending_edit_after", "") == "1") {
            store.setQuiet("pending_edit_after", "")
            setEditMode(true)
        }
        music.start()
        refreshVolume()
        val volumeFilter = IntentFilter(VOLUME_CHANGED)
        volumeFilter.addAction(CarVolume.MAIN_VOLUME_CHANGED)
        registerReceiver(volumeReceiver, volumeFilter)
        startSpeed()
        AppRepo.load(this) { onAppsLoaded() }
        photos.start()
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
        photos.stop()
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
        if (board.editMode) {
            setEditMode(false)
            return
        }
        if (isDrawerOpen()) closeDrawer(animate = true)
    }

    // ---------- الصلاحيات والسرعة ----------

    private fun granted(permission: String): Boolean {
        return Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestMissingPermissions() {
        if (Build.VERSION.SDK_INT < 23) return
        val missing = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ).filter { !granted(it) }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), PERMISSION_REQ)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQ) {
            startSpeed()
            photos.scan()
        }
    }

    private fun startSpeed() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
            startNmea(lm)
        } catch (_: Exception) {
            // ما فيه GPS أو الصلاحية مرفوضة
        }
    }

    // وقت الأقمار من رسائل NMEA: يوصل قبل تحديد الموقع، وما يعتمد على ساعة الراديو
    private var nmeaListener: Any? = null

    private fun startNmea(lm: LocationManager) {
        if (nmeaListener != null) return
        try {
            val type = Class.forName("android.location.GpsStatus\$NmeaListener")
            val proxy = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, method, args ->
                if (method.name == "onNmeaReceived" && args != null && args.size == 2) {
                    val sentence = args[1] as? String
                    if (sentence != null) handleNmea(sentence)
                }
                null
            }
            LocationManager::class.java.getMethod("addNmeaListener", type).invoke(lm, proxy)
            nmeaListener = proxy
        } catch (_: Throwable) {
        }
    }

    private fun stopNmea(lm: LocationManager) {
        val listener = nmeaListener ?: return
        try {
            val type = Class.forName("android.location.GpsStatus\$NmeaListener")
            LocationManager::class.java.getMethod("removeNmeaListener", type).invoke(lm, listener)
        } catch (_: Throwable) {
        }
        nmeaListener = null
    }

    /** يقرأ الوقت والتاريخ من رسالة RMC مثل: $GPRMC,hhmmss.ss,A,...,ddmmyy,... */
    private fun handleNmea(sentence: String) {
        try {
            val parts = sentence.trim().split(",")
            if (parts.size < 10 || !parts[0].endsWith("RMC")) return
            val time = parts[1]
            val date = parts[9]
            if (time.length < 6 || date.length != 6) return
            val utc = Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
            utc.clear()
            utc.set(
                2000 + date.substring(4, 6).toInt(),
                date.substring(2, 4).toInt() - 1,
                date.substring(0, 2).toInt(),
                time.substring(0, 2).toInt(),
                time.substring(2, 4).toInt(),
                time.substring(4, 6).toInt()
            )
            syncClock(utc.timeInMillis)
        } catch (_: Throwable) {
        }
    }

    private fun stopSpeed() {
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.removeUpdates(locationListener)
            stopNmea(lm)
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
                val onVolume = volumeColumn.visibility == View.VISIBLE &&
                    ev.rawX >= location[0] && ev.rawX <= location[0] + volumeColumn.width
                swipeArmed = !wasIdle && !isDrawerOpen() && !onVolume && !board.editMode &&
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
