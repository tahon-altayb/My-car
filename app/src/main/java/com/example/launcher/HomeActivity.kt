package com.example.launcher

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {

    companion object {
        private const val IDLE_TIMEOUT_MS = 120_000L  // دقيقتين بدون لمس
        private const val SWIPE_TRIGGER_PX = 50f      // مسافة السحب للأعلى اللي تفتح الدرج
        private const val PERMISSION_REQ = 1
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val arabic = Locale("ar")

    private lateinit var store: AppStore
    private lateinit var idle: IdleController
    private lateinit var camera: CameraTile
    private lateinit var music: MusicTile
    private lateinit var audio: AudioManager

    private lateinit var root: View
    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var speedText: TextView
    private lateinit var idleOverlay: FrameLayout
    private lateinit var idleClock: TextView
    private lateinit var idleNowPlaying: TextView
    private lateinit var volBar: VolumeBar
    private lateinit var volText: TextView
    private lateinit var volumeColumn: View
    private lateinit var drawer: View
    private lateinit var drawerTitle: TextView

    private val drawerAdapter = DrawerAdapter()
    private var pickerSlot: String? = null

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

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
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
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
        }

        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        store = AppStore(this)
        setTheme(if (store.isDay()) R.style.LauncherDay else R.style.LauncherNight)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        root = findViewById(R.id.root)
        dateText = findViewById(R.id.dateText)
        clockText = findViewById(R.id.clockText)
        speedText = findViewById(R.id.speedText)
        idleOverlay = findViewById(R.id.idleOverlay)
        idleClock = findViewById(R.id.idleClock)
        idleNowPlaying = findViewById(R.id.idleNowPlaying)
        volBar = findViewById(R.id.volBar)
        volText = findViewById(R.id.volText)
        volumeColumn = findViewById(R.id.volumeColumn)
        drawer = findViewById(R.id.drawer)
        drawerTitle = findViewById(R.id.drawerTitle)

        camera = CameraTile(
            this,
            findViewById<TextureView>(R.id.cameraTexture),
            findViewById(R.id.cameraStatus)
        )
        music = MusicTile(this, handler)

        setupThemeToggle()
        setupVolume()
        setupTiles()
        setupDrawer()

        findViewById<View>(R.id.cameraTile).setOnClickListener {
            requestMissingPermissions()
            camera.open()
        }

        idle = IdleController(
            handler,
            IDLE_TIMEOUT_MS,
            onIdle = {
                idleClock.text = currentTime()
                idleNowPlaying.text = music.nowPlaying
                idleOverlay.visibility = View.VISIBLE
            },
            onWake = { idleOverlay.visibility = View.GONE }
        )

        requestMissingPermissions()
    }

    // ---------- الوضع النهاري / الليلي ----------

    private fun setupThemeToggle() {
        val toggle = findViewById<Button>(R.id.themeToggle)
        // الزر يعرض الوضع اللي بتنتقل له
        toggle.text = if (store.isDay()) "ليلي" else "نهاري"
        toggle.setOnClickListener {
            store.setDay(!store.isDay())
            recreate()
        }
    }

    // ---------- الصوت ----------

    private fun setupVolume() {
        volBar.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volBar.onChange = { setVolume(it) }
        findViewById<Button>(R.id.volUp).setOnClickListener { setVolume(volBar.value + 1) }
        findViewById<Button>(R.id.volDown).setOnClickListener { setVolume(volBar.value - 1) }
        refreshVolume()
    }

    private fun setVolume(level: Int) {
        val clamped = level.coerceIn(0, volBar.max)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        } catch (_: Exception) {
        }
        refreshVolume()
    }

    private fun refreshVolume() {
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
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
                if (!launchPackage(entry.pkg)) {
                    Toast.makeText(this, "تعذر فتح التطبيق", Toast.LENGTH_SHORT).show()
                }
                closeDrawer(animate = false)
            }
        }
        findViewById<Button>(R.id.drawerClose).setOnClickListener { closeDrawer(animate = true) }
        findViewById<View>(R.id.drawerHandle).setOnClickListener { openDrawer(null) }
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
        updateClock()
        handler.post(clockTick)
        music.start()
        camera.open()
        refreshVolume()
        registerReceiver(volumeReceiver, IntentFilter(VOLUME_CHANGED))
        startSpeed()
        AppRepo.load(this) { onAppsLoaded() }
        idle.poke()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        idle.stop()
        music.stop()
        camera.close()
        stopSpeed()
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

    /** زر الرجوع ما يطلع من اللانشر (كان يرجّع للواجهة القديمة). */
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
        val missing = listOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            .filter { !granted(it) }
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
            camera.open()
            startSpeed()
        }
    }

    private fun startSpeed() {
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) return
        try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener)
        } catch (_: Exception) {
            // ما فيه GPS أو الصلاحية مرفوضة: تبقى السرعة "--"
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

    // ---------- الساعة ----------

    private fun updateClock() {
        clockText.text = currentTime()
        dateText.text = SimpleDateFormat("EEEE d MMMM", arabic).format(Date())
    }

    private fun currentTime(): String = SimpleDateFormat("HH:mm", arabic).format(Date())

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
