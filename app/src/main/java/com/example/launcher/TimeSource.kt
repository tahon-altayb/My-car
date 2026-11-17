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
    /** يستدعى لما يتغير التصحيح، عشان ينحفظ ويبقى نفسه بعد إعادة التشغيل. */
    var onOffsetChanged: ((Long) -> Unit)? = null

    private fun setOffset(value: Long) {
        if (value != offsetMs) {
            offsetMs = value
            onOffsetChanged?.invoke(value)
        }
    }

    private fun yearOf(millis: Long): Int {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = millis
        return c.get(Calendar.YEAR)
    }

    /**
     * الوقت موثوق؟ ويصلّح التصحيح لو صار قديم.
     * ما يرجع نتيجتين مختلفتين ورا بعض لنفس الساعة (كان هذا سبب وميض الشاشة).
     */
    fun valid(): Boolean {
        val system = System.currentTimeMillis()
        if (yearOf(system + offsetMs) in 2025..2045) return true
        // ساعة الراديو نفسها صحيحة والتصحيح المحفوظ قديم: نلغيه
        if (yearOf(system) in 2025..2045) {
            setOffset(0L)
            return true
        }
        // ساعة الراديو رجعت 1024 أسبوع (خلل الـGPS، مثلاً أبريل 2007): نرجّع الأسابيع
        val rollover = 1024L * 7 * 24 * 3600 * 1000
        var fixed = system
        var guard = 0
        while (yearOf(fixed) < 2019 && guard < 3) {
            fixed += rollover
            guard++
        }
        if (yearOf(fixed) in 2025..2045) {
            setOffset(fixed - system)
            return true
        }
        return false
    }
}
