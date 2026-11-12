package com.example.launcher

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings

/**
 * صوت السيارة الحقيقي في رواديو XY/Autochips.
 * الطريقة اللي ثبت إنها تشتغل: إرسال نفس إشارة أزرار الصوت اللي على حافة الشاشة
 * (xy.android.gtpkey.voladd / voldec)، كل إشارة تحرك الصوت درجة.
 * والمستوى الحالي نقرأه من إعداد النظام McuMainVolume (من 0 إلى 30).
 */
class CarVolume(private val context: Context) {

    companion object {
        const val MAIN_VOLUME_CHANGED = "xy.android.main.volume.changed"
        private const val KEY_UP = "xy.android.gtpkey.voladd"
        private const val KEY_DOWN = "xy.android.gtpkey.voldec"
        private const val SETTING_KEY = "McuMainVolume"
        private const val STEP_MS = 130L
        private const val MAX_STEPS = 45
    }

    private val handler = Handler(Looper.getMainLooper())
    private var target = -1
    private var stepsLeft = 0

    val max: Int = 30

    /** صحيح طول ما الشريط يتحرك نحو قيمة طلبها المستخدم. */
    val busy: Boolean get() = target >= 0

    fun current(): Int {
        return try {
            Settings.System.getInt(context.contentResolver, SETTING_KEY, 10)
        } catch (e: Throwable) {
            10
        }
    }

    private val stepper = object : Runnable {
        override fun run() {
            val now = current()
            if (target < 0 || now == target || stepsLeft <= 0) {
                target = -1
                return
            }
            stepsLeft--
            context.sendBroadcast(Intent(if (now < target) KEY_UP else KEY_DOWN))
            handler.postDelayed(this, STEP_MS)
        }
    }

    /** يحرك صوت السيارة درجة درجة لين يوصل للمستوى المطلوب. */
    fun set(level: Int) {
        target = level.coerceIn(0, max)
        stepsLeft = MAX_STEPS
        handler.removeCallbacks(stepper)
        handler.post(stepper)
    }

    fun stop() {
        handler.removeCallbacks(stepper)
        target = -1
    }
}
