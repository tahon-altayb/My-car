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

    fun format(pattern: String): String {
        val format = SimpleDateFormat(pattern, arabic)
        format.timeZone = zone
        return format.format(Date(now()))
    }
}
