package com.example.launcher

import android.app.Activity

/** يطبّق الثيم الأساسي (نهاري/ليلي) ثم ألوان المستخدم فوقه. */
object ThemeHelper {

    val ACCENTS = listOf("blue", "green", "orange", "purple", "pink", "gold")
    val ACCENT_NAMES = listOf("أزرق", "أخضر", "برتقالي", "بنفسجي", "وردي", "ذهبي")
    val NIGHT_BGS = listOf("navy", "black", "charcoal")
    val NIGHT_BG_NAMES = listOf("كحلي", "أسود", "رمادي غامق")
    val DAY_BGS = listOf("ice", "cream", "gray")
    val DAY_BG_NAMES = listOf("أبيض ثلجي", "كريمي", "رمادي فاتح")

    private fun style(activity: Activity, name: String): Int =
        activity.resources.getIdentifier(name, "style", activity.packageName)

    fun apply(activity: Activity, store: AppStore, day: Boolean) {
        activity.setTheme(if (day) R.style.LauncherDay else R.style.LauncherNight)
        val accent = store.str("accent", "blue")
        val bg = if (day) store.str("bg_day", "ice") else store.str("bg_night", "navy")
        val accentStyle = style(activity, (if (day) "AccentDay_" else "AccentNight_") + accent)
        val bgStyle = style(activity, (if (day) "BgDay_" else "BgNight_") + bg)
        if (accentStyle != 0) activity.theme.applyStyle(accentStyle, true)
        if (bgStyle != 0) activity.theme.applyStyle(bgStyle, true)
    }

    /** يلوّن شريط النظام بلون الخلفية (يستدعى بعد setContentView). */
    fun tintBars(activity: Activity) {
        val color = activity.themeColor(R.attr.lBg)
        activity.window.statusBarColor = color
        activity.window.navigationBarColor = color
        activity.window.decorView.setBackgroundColor(color)
    }
}
