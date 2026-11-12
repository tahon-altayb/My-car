package com.example.launcher

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

class AppEntry(val pkg: String, val label: String, val icon: Drawable)

/**
 * قائمة التطبيقات المثبتة مع أيقوناتها.
 * تتحمّل مرة وحدة في الخلفية وتبقى في الذاكرة، عشان الدرج يفتح فوراً.
 */
object AppRepo {

    @Volatile
    var apps: List<AppEntry> = emptyList()
        private set

    @Volatile
    private var loading = false

    fun find(pkg: String): AppEntry? = apps.firstOrNull { it.pkg == pkg }

    fun load(context: Context, onDone: () -> Unit) {
        if (loading) return
        loading = true
        val appContext = context.applicationContext
        Thread {
            val result = try {
                val pm = appContext.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                pm.queryIntentActivities(intent, 0)
                    .filter { it.activityInfo.packageName != appContext.packageName }
                    .map {
                        AppEntry(
                            it.activityInfo.packageName,
                            it.loadLabel(pm).toString(),
                            it.loadIcon(pm)
                        )
                    }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            } catch (e: Exception) {
                null
            }
            Handler(Looper.getMainLooper()).post {
                if (result != null) apps = result
                loading = false
                onDone()
            }
        }.start()
    }
}
