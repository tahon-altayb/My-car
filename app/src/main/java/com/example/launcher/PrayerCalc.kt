package com.example.launcher

/**
 * حساب مواقيت الصلاة فلكياً بدون إنترنت، بطريقة أم القرى:
 * الفجر عند زاوية 18.5 درجة، والعشاء بعد المغرب بـ 90 دقيقة، والعصر على المذهب الشافعي.
 * النتيجة بالساعات (مثلاً 4.78 = 4:47): الفجر، الشروق، الظهر، العصر، المغرب، العشاء.
 */
object PrayerCalc {

    val NAMES = arrayOf("الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء")

    private fun dsin(d: Double) = Math.sin(Math.toRadians(d))
    private fun dcos(d: Double) = Math.cos(Math.toRadians(d))
    private fun dtan(d: Double) = Math.tan(Math.toRadians(d))

    private fun fix(a: Double, b: Double): Double {
        val r = a - b * Math.floor(a / b)
        return if (r < 0) r + b else r
    }

    private fun julian(yearIn: Int, monthIn: Int, day: Int): Double {
        var year = yearIn
        var month = monthIn
        if (month <= 2) {
            year -= 1
            month += 12
        }
        val a = Math.floor(year / 100.0)
        val b = 2 - a + Math.floor(a / 4.0)
        return Math.floor(365.25 * (year + 4716)) + Math.floor(30.6001 * (month + 1)) + day + b - 1524.5
    }

    /** يرجع [ميل الشمس، معادلة الزمن]. */
    private fun sun(jd: Double): DoubleArray {
        val d = jd - 2451545.0
        val g = fix(357.529 + 0.98560028 * d, 360.0)
        val q = fix(280.459 + 0.98564736 * d, 360.0)
        val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
        val e = 23.439 - 0.00000036 * d
        val ra = Math.toDegrees(Math.atan2(dcos(e) * dsin(l), dcos(l))) / 15.0
        val decl = Math.toDegrees(Math.asin(dsin(e) * dsin(l)))
        return doubleArrayOf(decl, q / 15.0 - fix(ra, 24.0))
    }

    fun compute(year: Int, month: Int, day: Int, lat: Double, lng: Double, tzHours: Double): DoubleArray {
        val jd = julian(year, month, day) - lng / (15.0 * 24.0)

        fun midDay(t: Double): Double = fix(12.0 - sun(jd + t)[1], 24.0)

        fun angleTime(angle: Double, t: Double, beforeNoon: Boolean): Double {
            val decl = sun(jd + t)[0]
            val x = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            val hours = Math.toDegrees(Math.acos(x.coerceIn(-1.0, 1.0))) / 15.0
            return midDay(t) + if (beforeNoon) -hours else hours
        }

        fun asr(t: Double): Double {
            val decl = sun(jd + t)[0]
            val angle = -Math.toDegrees(Math.atan(1.0 / (1.0 + dtan(Math.abs(lat - decl)))))
            return angleTime(angle, t, false)
        }

        val adjust = tzHours - lng / 15.0
        val fajr = angleTime(18.5, 5.0 / 24.0, true) + adjust
        val sunrise = angleTime(0.833, 6.0 / 24.0, true) + adjust
        val dhuhr = midDay(12.0 / 24.0) + adjust
        val asrTime = asr(13.0 / 24.0) + adjust
        val maghrib = angleTime(0.833, 18.0 / 24.0, false) + adjust
        val isha = maghrib + 1.5
        return doubleArrayOf(fajr, sunrise, dhuhr, asrTime, maghrib, isha)
    }
}
