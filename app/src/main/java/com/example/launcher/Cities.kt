package com.example.launcher

/** مدن المملكة لمواقيت الصلاة، لما المستخدم يختار مدينة بدل الـGPS. */
object Cities {

    class City(val key: String, val name: String, val lat: Double, val lng: Double)

    val ALL = listOf(
        City("taif", "الطائف", 21.2703, 40.4158),
        City("riyadh", "الرياض", 24.7136, 46.6753),
        City("makkah", "مكة المكرمة", 21.4225, 39.8262),
        City("jeddah", "جدة", 21.4858, 39.1925),
        City("madinah", "المدينة المنورة", 24.4672, 39.6024),
        City("dammam", "الدمام", 26.4207, 50.0888),
        City("khobar", "الخبر", 26.2172, 50.1971),
        City("ahsa", "الأحساء", 25.3830, 49.5866),
        City("qassim", "بريدة", 26.3260, 43.9750),
        City("hail", "حائل", 27.5114, 41.7208),
        City("tabuk", "تبوك", 28.3835, 36.5662),
        City("abha", "أبها", 18.2164, 42.5053),
        City("baha", "الباحة", 20.0129, 41.4677),
        City("jazan", "جازان", 16.8892, 42.5511),
        City("najran", "نجران", 17.4924, 44.1277),
        City("yanbu", "ينبع", 24.0895, 38.0618),
        City("jouf", "سكاكا", 29.9697, 40.2064),
        City("arar", "عرعر", 30.9753, 41.0381)
    )

    fun byKey(key: String): City? = ALL.firstOrNull { it.key == key }

    /** أقرب مدينة لموقع الـGPS، لو كانت ضمن 60 كم. */
    fun nearest(lat: Double, lng: Double): City? {
        var best: City? = null
        var bestKm = Double.MAX_VALUE
        for (c in ALL) {
            val dLat = Math.toRadians(c.lat - lat)
            val dLng = Math.toRadians(c.lng - lng)
            val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat)) * Math.cos(Math.toRadians(c.lat)) *
                Math.sin(dLng / 2) * Math.sin(dLng / 2)
            val km = 6371.0 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
            if (km < bestKm) {
                bestKm = km
                best = c
            }
        }
        return if (bestKm <= 60.0) best else null
    }
}
