package com.example.launcher

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * ساعة اللانشر. ساعة الراديو نفسها قد تكون غلط (تاريخ أو منطقة زمنية)،
 * فنصححها من وقت الـGPS ونثبّت المنطقة على توقيت السعودية.
 */
object TimeSource {

    val zone: TimeZone = TimeZone.getTimeZone("Asia/Riyadh")
    private val arabic = Locale("ar")

    /** الفرق بين الوقت الصحيح وساعة الراديو بالملّي ثانية. */
    @Volatile
    var offsetMs: Long = 0L

    fun now(): Long = System.currentTimeMillis() + offsetMs

    fun calendar(): Calendar = Calendar.getInstance(zone).apply { timeInMillis = now() }

    /** يحوّل الأرقام العربية الشرقية (١٢٣) إلى الأرقام العادية (123). */
    fun latin(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(
                when (ch) {
                    in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')
                    in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    fun format(pattern: String): String {
        val format = SimpleDateFormat(pattern, arabic)
        format.timeZone = zone
        return latin(format.format(Date(now())))
    }

    /**
     * الوقت موثوق؟ لو الراديو رجع لتاريخ المصنع (2007) وما وصل وقت GPS بعد، لا.
     */
    fun valid(): Boolean {
        val year = calendar().get(Calendar.YEAR)
        if (year in 2025..2045) return true
        // ساعة الراديو قفزت بعد آخر تصحيح (مثلاً رجعت 2007): نصحح الدوران من ساعته نفسها
        val rollover = 1024L * 7 * 24 * 3600 * 1000
        val raw = Calendar.getInstance(zone)
        raw.timeInMillis = System.currentTimeMillis()
        var fixed = System.currentTimeMillis()
        var guard = 0
        while (raw.get(Calendar.YEAR) < 2019 && guard < 3) {
            fixed += rollover
            raw.timeInMillis = fixed
            guard++
        }
        if (guard > 0 && raw.get(Calendar.YEAR) in 2025..2045) {
            offsetMs = fixed - System.currentTimeMillis()
            return true
        }
        offsetMs = 0L
        return false
    }
}
