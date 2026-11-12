package com.example.launcher

import android.content.Context
import android.media.AudioManager
import android.provider.Settings

/**
 * صوت السيارة الحقيقي في رواديو XY/Autochips.
 * برنامج المصنع (اعدادات السيارة) يغيّره بدالة مخفية في النظام اسمها setMasterVolume،
 * ويحفظ القيمة الحالية في إعداد اسمه McuMainVolume.
 * لو الدالة مو موجودة أو مرفوضة، نرجع لصوت الأندرويد العادي.
 */
class CarVolume(private val context: Context) {

    companion object {
        const val MAIN_VOLUME_CHANGED = "xy.android.main.volume.changed"
        private const val SETTING_KEY = "McuMainVolume"
        private const val VENDOR_MAX = 30
    }

    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val setMaster = try {
        AudioManager::class.java.getMethod("setMasterVolume", Int::class.javaPrimitiveType)
    } catch (e: Throwable) {
        null
    }

    /** آخر خطأ صار، يظهر في صفحة التشخيص. */
    var lastError: String = ""
        private set

    val usesVendor: Boolean get() = setMaster != null

    val max: Int
        get() = if (usesVendor) VENDOR_MAX else audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

    fun current(): Int {
        if (usesVendor) {
            return try {
                Settings.System.getInt(context.contentResolver, SETTING_KEY, 10)
            } catch (e: Throwable) {
                10
            }
        }
        return audio.getStreamVolume(AudioManager.STREAM_MUSIC)
    }

    /** يرجع true إذا انطبق على صوت السيارة. */
    fun set(level: Int): Boolean {
        val value = level.coerceIn(0, max)
        val method = setMaster
        if (method != null) {
            try {
                method.invoke(audio, value)
                lastError = ""
                return true
            } catch (e: Throwable) {
                val cause = e.cause ?: e
                lastError = cause.javaClass.simpleName + ": " + cause.message
            }
        }
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, value.coerceAtMost(
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), 0)
        } catch (_: Throwable) {
        }
        return false
    }
}
