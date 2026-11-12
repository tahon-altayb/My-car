package com.example.launcher

import android.content.Context

/**
 * يحفظ اختيارات المستخدم: التطبيق في كل مربع، والوضع النهاري أو الليلي.
 * null في المربع = فاضي ويعرض "+ اختر تطبيق".
 */
class AppStore(context: Context) {

    companion object {
        const val MAPS = "maps"
        const val SLOT_1 = "slot1"
        const val SLOT_2 = "slot2"
        const val SLOT_3 = "slot3"

        private const val PREFS = "launcher_prefs"
        private const val KEY_DAY = "day_mode"
        private const val DEFAULT_MAPS = "com.google.android.apps.maps"
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? {
        return if (key == MAPS) prefs.getString(key, DEFAULT_MAPS) else prefs.getString(key, null)
    }

    fun set(key: String, packageName: String?) {
        prefs.edit().putString(key, packageName).apply()
    }

    fun isDay(): Boolean = prefs.getBoolean(KEY_DAY, false)

    fun setDay(day: Boolean) {
        prefs.edit().putBoolean(KEY_DAY, day).apply()
    }
}
