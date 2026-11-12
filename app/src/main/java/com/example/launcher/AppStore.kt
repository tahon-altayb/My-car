package com.example.launcher

import android.content.Context

/**
 * يحفظ اختيارات المستخدم وآخر بيانات معروفة:
 * تطبيق كل مربع، وضع العرض، آخر موقع، آخر طقس، وتصحيح الساعة.
 */
class AppStore(context: Context) {

    companion object {
        const val MAPS = "maps"
        const val SLOT_1 = "slot1"
        const val SLOT_2 = "slot2"
        const val SLOT_3 = "slot3"

        const val MODE_AUTO = "auto"
        const val MODE_DAY = "day"
        const val MODE_NIGHT = "night"

        private const val PREFS = "launcher_prefs"
        private const val DEFAULT_MAPS = "com.google.android.apps.maps"

        // الرياض، تستخدم لين يلقط الـGPS الموقع الفعلي
        private const val DEFAULT_LAT = 24.7136f
        private const val DEFAULT_LNG = 46.6753f
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? {
        return if (key == MAPS) prefs.getString(key, DEFAULT_MAPS) else prefs.getString(key, null)
    }

    fun set(key: String, packageName: String?) {
        prefs.edit().putString(key, packageName).apply()
    }

    fun themeMode(): String = prefs.getString("theme_mode", MODE_AUTO) ?: MODE_AUTO

    fun setThemeMode(mode: String) {
        prefs.edit().putString("theme_mode", mode).apply()
    }

    fun lat(): Double = prefs.getFloat("lat", DEFAULT_LAT).toDouble()
    fun lng(): Double = prefs.getFloat("lng", DEFAULT_LNG).toDouble()

    fun setLocation(lat: Double, lng: Double) {
        prefs.edit().putFloat("lat", lat.toFloat()).putFloat("lng", lng.toFloat()).apply()
    }

    fun clockOffset(): Long = prefs.getLong("clock_offset", 0L)

    fun setClockOffset(offsetMs: Long) {
        prefs.edit().putLong("clock_offset", offsetMs).apply()
    }

    /** وقت آخر تحديث ناجح للطقس. صفر = ما عندنا طقس محفوظ. */
    fun weatherTime(): Long = prefs.getLong("weather_time", 0L)

    fun weather(): WeatherInfo = WeatherInfo(
        prefs.getFloat("weather_temp", 0f).toDouble(),
        prefs.getInt("weather_code", 0),
        prefs.getFloat("weather_wind", 0f).toDouble()
    )

    fun setWeather(info: WeatherInfo, time: Long) {
        prefs.edit()
            .putFloat("weather_temp", info.temp.toFloat())
            .putInt("weather_code", info.code)
            .putFloat("weather_wind", info.wind.toFloat())
            .putLong("weather_time", time)
            .apply()
    }
}
