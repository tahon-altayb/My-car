package com.example.launcher

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HomeActivity : Activity() {

    companion object {
        private const val IDLE_TIMEOUT_MS = 120_000L   // دقيقتين بدون لمس
        private const val SWIPE_UP_MIN_PX = 150f         // أقل مسافة للسحب للأعلى
        private const val CAMERA_PERMISSION_REQ = 1
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: AppStore
    private lateinit var idle: IdleController
    private lateinit var camera: CameraTile
    private lateinit var music: MusicTile

    private lateinit var dateText: TextView
    private lateinit var clockText: TextView
    private lateinit var idleOverlay: FrameLayout
    private lateinit var idleClock: TextView
    private lateinit var idleNowPlaying: TextView
    private lateinit var musicTitle: TextView

    private var downY = 0f
    private var downInBottom = false

    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 30_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        store = AppStore(this)

        dateText = findViewById(R.id.dateText)
        clockText = findViewById(R.id.clockText)
        idleOverlay = findViewById(R.id.idleOverlay)
        idleClock = findViewById(R.id.idleClock)
        idleNowPlaying = findViewById(R.id.idleNowPlaying)
        musicTitle = findViewById(R.id.musicTitle)

        camera = CameraTile(
            this,
            findViewById<TextureView>(R.id.cameraTexture),
            findViewById(R.id.cameraStatus)
        )
        music = MusicTile(this, musicTitle, findViewById(R.id.musicArtist))

        findViewById<Button>(R.id.musicPrev).setOnClickListener { music.prev() }
        findViewById<Button>(R.id.musicPlay).setOnClickListener { music.playPause() }
        findViewById<Button>(R.id.musicNext).setOnClickListener { music.next() }

        // الخرائط: مربع ثابت، والضغط يفتح التطبيق
        findViewById<Button>(R.id.mapsTile).setOnClickListener {
            if (!launchPackage(store.get(AppStore.MAPS))) {
                toast("تطبيق الخرائط غير موجود، اختره من قائمة التطبيقات")
            }
        }

        // المربعات الثلاثة: تفتح التطبيق المختار، والضغط المطول يغيّره
        val slots = listOf(
            R.id.slot1 to AppStore.SLOT_1,
            R.id.slot2 to AppStore.SLOT_2,
            R.id.slot3 to AppStore.SLOT_3
        )
        for ((viewId, key) in slots) {
            val button = findViewById<Button>(viewId)
            button.setOnClickListener {
                val pkg = store.get(key)
                if (pkg == null) {
                    openPicker(key)
                } else if (!launchPackage(pkg)) {
                    store.set(key, null)
                    toast("التطبيق لم يعد موجوداً، اختر تطبيق ثاني")
                    openPicker(key)
                }
            }
            button.setOnLongClickListener {
                openPicker(key)
                true
            }
        }

        idle = IdleController(
            handler,
            IDLE_TIMEOUT_MS,
            onIdle = {
                idleClock.text = currentTime()
                idleNowPlaying.text = musicTitle.text
                idleOverlay.visibility = View.VISIBLE
            },
            onWake = { idleOverlay.visibility = View.GONE }
        )

        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_REQ)
        }
    }

    override fun onResume() {
        super.onResume()
        updateClock()
        handler.post(clockTick)
        refreshSlots()
        music.start()
        camera.open()
        idle.poke()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(clockTick)
        idle.stop()
        music.stop()
        camera.close()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_REQ) {
            camera.open()
        }
    }

    /** أي لمسة ترجع الشاشة وتعيد العد. ولو كانت شاشة الانتظار ظاهرة، اللمسة ما تضغط على شي تحتها. */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val wasIdle = idle.isIdle
        idle.poke()

        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                downInBottom = ev.y > resources.displayMetrics.heightPixels * 0.8f
            }
            MotionEvent.ACTION_UP -> {
                if (downInBottom && downY - ev.y > SWIPE_UP_MIN_PX) {
                    startActivity(Intent(this, AppDrawerActivity::class.java))
                }
            }
        }

        if (wasIdle) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun refreshSlots() {
        renderSlot(R.id.slot1, AppStore.SLOT_1)
        renderSlot(R.id.slot2, AppStore.SLOT_2)
        renderSlot(R.id.slot3, AppStore.SLOT_3)
    }

    private fun renderSlot(viewId: Int, key: String) {
        val button = findViewById<Button>(viewId)
        val pkg = store.get(key)
        button.text = if (pkg == null) "+ اختر تطبيق" else appLabel(pkg)
    }

    private fun openPicker(slotKey: String) {
        startActivity(
            Intent(this, AppDrawerActivity::class.java)
                .putExtra(AppDrawerActivity.EXTRA_SLOT, slotKey)
        )
    }

    private fun launchPackage(pkg: String?): Boolean {
        if (pkg == null) return false
        val intent = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        startActivity(intent)
        return true
    }

    private fun appLabel(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }

    private fun updateClock() {
        clockText.text = currentTime()
        dateText.text = SimpleDateFormat("EEEE d MMMM", Locale("ar")).format(Date())
    }

    private fun currentTime(): String = SimpleDateFormat("HH:mm", Locale("ar")).format(Date())

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
