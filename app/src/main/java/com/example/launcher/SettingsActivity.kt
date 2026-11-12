package com.example.launcher

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * إعدادات اللانشر. كل سطر: اسم الإعداد وزر يعرض القيمة الحالية، والضغط ينقل للخيار التالي.
 * الألوان مجموعات جاهزة متناسقة: لون النص يتبع الوضع (نهاري/ليلي) دائماً فيبقى واضح.
 */
class SettingsActivity : Activity() {

    private lateinit var store: AppStore
    private lateinit var list: LinearLayout
    private var dp = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        store = AppStore(this)
        ThemeHelper.apply(this, store, store.lastDay())
        super.onCreate(savedInstanceState)
        dp = resources.displayMetrics.density

        list = LinearLayout(this)
        list.orientation = LinearLayout.VERTICAL
        val pad = (20 * dp).toInt()
        list.setPadding(pad, pad, pad, pad)

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(themeColor(R.attr.lBg))
        scroll.addView(list)
        setContentView(scroll)
        ThemeHelper.tintBars(this)
        build()
    }

    private fun build() {
        list.removeAllViews()

        val close = Button(this)
        close.text = "رجوع"
        close.setOnClickListener { finish() }
        style(close)
        list.addView(close, LinearLayout.LayoutParams((140 * dp).toInt(), (52 * dp).toInt()))

        header("المظهر")
        choice("الوضع", "theme_mode", listOf("auto", "day", "night"), listOf("تلقائي", "نهاري", "ليلي"), "auto")
        choice("اللون الرئيسي", "accent", ThemeHelper.ACCENTS, ThemeHelper.ACCENT_NAMES, "blue")
        choice("خلفية الوضع الليلي", "bg_night", ThemeHelper.NIGHT_BGS, ThemeHelper.NIGHT_BG_NAMES, "navy")
        choice("خلفية الوضع النهاري", "bg_day", ThemeHelper.DAY_BGS, ThemeHelper.DAY_BG_NAMES, "ice")
        action("صورة الخلفية", "اختيار صورة") { requestWallpaper("pick") }
        action("", "إزالة صورة الخلفية") { requestWallpaper("remove") }

        header("ترتيب الواجهة")
        choice("اتجاه الواجهة", "mirror", listOf("0", "1"), listOf("الساعة يسار والصوت يمين", "الساعة يمين والصوت يسار"), "0")
        choice("الصف العلوي", "swap_top", listOf("0", "1"), listOf("الطقس ثم الموسيقى", "الموسيقى ثم الطقس"), "0")
        choice("الصف السفلي", "swap_apps", listOf("0", "1"), listOf("التطبيقات ثم الصور", "الصور ثم التطبيقات"), "0")
        choice("مربع السرعة", "show_speed", listOf("1", "0"), listOf("ظاهر", "مخفي"), "1")
        choice("مربع الصور", "show_photo", listOf("1", "0"), listOf("ظاهر", "مخفي"), "1")
        choice("شريط الصوت", "show_volume", listOf("1", "0"), listOf("ظاهر", "مخفي"), "1")
        choice("أعمدة قائمة التطبيقات", "drawer_cols", listOf("5", "6", "7", "8"), listOf("5", "6", "7", "8"), "6")

        header("الوقت والشاشة")
        choice("نظام الساعة", "clock24", listOf("0", "1"), listOf("12 ساعة", "24 ساعة"), "0")
        choice(
            "شاشة الانتظار بعد", "idle_sec", listOf("60", "120", "300", "600", "0"),
            listOf("دقيقة", "دقيقتين", "5 دقائق", "10 دقائق", "لا تظهر"), "120"
        )
        choice(
            "تبديل الصور كل", "photo_sec", listOf("5", "7", "15", "30", "60"),
            listOf("5 ثواني", "7 ثواني", "15 ثانية", "30 ثانية", "دقيقة"), "7"
        )

        header("قائمة التطبيقات")
        action("التطبيقات المخفية", "إظهار الكل") {
            store.setQuiet("hidden_apps", "")
            store.setStr("apps_reset", System.currentTimeMillis().toString())
            android.widget.Toast.makeText(this, "رجعت كل التطبيقات", android.widget.Toast.LENGTH_SHORT).show()
        }
        action("ترتيب القائمة", "رجوع للترتيب الأبجدي") {
            store.setQuiet("app_order", "")
            store.setStr("apps_reset", System.currentTimeMillis().toString())
            android.widget.Toast.makeText(this, "رجع الترتيب أبجدي", android.widget.Toast.LENGTH_SHORT).show()
        }

        header("الصلاة")
        choice(
            "تنبيه قبل الصلاة", "prayer_alert", listOf("10", "15", "5", "0"),
            listOf("قبل 10 دقائق", "قبل 15 دقيقة", "قبل 5 دقائق", "بدون تنبيه"), "10"
        )

        header("أدوات")
        action("صفحة التشخيص", "فتح") { startActivity(Intent(this, DiagActivity::class.java)) }
    }

    private fun requestWallpaper(what: String) {
        store.setQuiet("pending_wallpaper", what)
        finish()
    }

    private fun style(button: Button) {
        button.setBackgroundResource(R.drawable.bg_tile)
        button.setTextColor(themeColor(R.attr.lText))
        button.textSize = 18f
        button.stateListAnimator = null
    }

    private fun header(text: String) {
        val view = TextView(this)
        view.text = text
        view.textSize = 22f
        view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        view.setTextColor(themeColor(R.attr.lAccent))
        view.gravity = Gravity.RIGHT
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = (22 * dp).toInt()
        params.bottomMargin = (6 * dp).toInt()
        list.addView(view, params)
    }

    private fun row(label: String, button: Button) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        style(button)
        row.addView(button, LinearLayout.LayoutParams((320 * dp).toInt(), (56 * dp).toInt()))
        val text = TextView(this)
        text.text = label
        text.textSize = 19f
        text.setTextColor(themeColor(R.attr.lText))
        text.gravity = Gravity.RIGHT
        row.addView(text, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = (8 * dp).toInt()
        list.addView(row, params)
    }

    private fun choice(label: String, key: String, values: List<String>, names: List<String>, default: String) {
        val button = Button(this)
        val current = values.indexOf(store.str(key, default)).coerceAtLeast(0)
        button.text = names[current]
        button.setOnClickListener {
            val next = (values.indexOf(store.str(key, default)).coerceAtLeast(0) + 1) % values.size
            store.setStr(key, values[next])
            // الألوان والوضع تغيّر شكل هذي الصفحة نفسها، فنعيد رسمها
            if (key == "accent" || key == "bg_night" || key == "bg_day") recreate()
            else button.text = names[next]
        }
        row(label, button)
    }

    private fun action(label: String, buttonText: String, run: () -> Unit) {
        val button = Button(this)
        button.text = buttonText
        button.setOnClickListener { run() }
        row(label, button)
    }
}
