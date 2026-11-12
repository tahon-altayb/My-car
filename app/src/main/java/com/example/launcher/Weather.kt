package com.example.launcher

import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import org.json.JSONObject

class WeatherInfo(val temp: Double, val code: Int, val wind: Double)

/** الطقس الحالي من خدمة Open-Meteo المجانية (تحتاج إنترنت، بدون مفتاح). */
object Weather {

    /** آخر وقت استلمناه من الخادم (UTC بالملّي ثانية)، صفر لو ما وصل. */
    @Volatile
    var serverTime: Long = 0L

    fun fetch(lat: Double, lng: Double, onResult: (WeatherInfo?) -> Unit) {
        Thread {
            val path = String.format(
                Locale.US,
                "api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current_weather=true",
                lat, lng
            )
            // أنظمة أندرويد القديمة قد ترفض شهادة https، فنجرّب http بعدها
            val result = request("https://$path") ?: request("http://$path")
            Handler(Looper.getMainLooper()).post { onResult(result) }
        }.start()
    }

    private fun request(url: String): WeatherInfo? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 8000
            connection.readTimeout = 8000
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            // وقت الخادم: نستخدمه لتصحيح الساعة لما يكون فيه نت
            val date = connection.getHeaderFieldDate("Date", 0L)
            if (date > 0L) serverTime = date
            val current = JSONObject(body).getJSONObject("current_weather")
            WeatherInfo(
                current.getDouble("temperature"),
                current.getInt("weathercode"),
                current.getDouble("windspeed")
            )
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    fun describe(code: Int): String = when (code) {
        0 -> "صافي"
        1, 2 -> "غائم جزئياً"
        3 -> "غائم"
        45, 48 -> "ضباب"
        in 51..57 -> "رذاذ"
        in 61..67 -> "مطر"
        in 71..77 -> "ثلج"
        in 80..82 -> "زخات مطر"
        in 95..99 -> "عواصف رعدية"
        else -> "غير معروف"
    }
}
