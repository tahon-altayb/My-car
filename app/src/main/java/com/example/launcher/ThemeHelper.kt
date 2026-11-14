package com.example.launcher

import android.app.Activity

/** يطبّق الثيم الأساسي (نهاري/ليلي) ثم ألوان المستخدم فوقه. */
object ThemeHelper {

    val ACCENTS = listOf("blue", "sky", "teal", "green", "lime", "gold", "orange", "white", "silver", "purple", "pink")
    val ACCENT_NAMES = listOf(
        "أزرق", "سماوي", "فيروزي", "أخضر", "ليموني", "ذهبي", "برتقالي",
        "أبيض (أسود بالنهاري)", "فضي", "بنفسجي", "وردي"
    )
    val NIGHT_BGS = listOf("navy", "black", "charcoal", "ocean", "forest")
    val NIGHT_BG_NAMES = listOf("كحلي", "أسود", "رمادي غامق", "أزرق محيطي", "أخضر غامق")
    val DAY_BGS = listOf("ice", "cream", "gray", "skyl", "mint")
    val DAY_BG_NAMES = listOf("أبيض ثلجي", "كريمي", "رمادي فاتح", "سماوي فاتح", "نعناعي")
    val TEXT_TONES = listOf("white", "soft", "accent")
    val TEXT_TONE_NAMES = listOf("ناصع (أبيض بالليل، أسود بالنهار)", "هادي", "بلون اللون الرئيسي")
    val ICON_TONES = listOf("text", "accent")
    val ICON_TONE_NAMES = listOf("بلون الخط", "بلون اللون الرئيسي")

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
        val textStyle = style(activity, (if (day) "TextDay_" else "TextNight_") + store.str("text_tone", "white"))
        if (textStyle != 0) activity.theme.applyStyle(textStyle, true)
        val iconStyle = style(activity, "Icon_" + store.str("icon_tone", "text"))
        if (iconStyle != 0) activity.theme.applyStyle(iconStyle, true)
    }

    /** يلوّن شريط النظام بلون الخلفية (يستدعى بعد setContentView). */
    fun tintBars(activity: Activity) {
        val color = activity.themeColor(R.attr.lBg)
        activity.window.statusBarColor = color
        activity.window.navigationBarColor = color
        activity.window.decorView.setBackgroundColor(color)
    }
}
