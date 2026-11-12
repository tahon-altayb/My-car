package com.example.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Handler
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import java.io.File

/**
 * مربع صور: يعرض الصور الموجودة في مجلد اسمه LauncherPhotos
 * (على الفلاشة أو ذاكرة الراديو) ويتنقل بينها كل كم ثانية.
 */
class PhotoTile(
    private val handler: Handler,
    private val image: ImageView,
    private val hint: TextView
) {
    companion object {
        const val FOLDER = "LauncherPhotos"
        private const val INTERVAL_MS = 7000L
        private const val TARGET_WIDTH = 640
    }

    private var files: List<File> = emptyList()
    private var index = -1
    private var running = false
    private var loading = false

    private val advance = Runnable { next() }

    fun start() {
        running = true
        scan()
    }

    fun stop() {
        running = false
        handler.removeCallbacks(advance)
    }

    /** يعيد البحث عن الصور (مثلاً بعد منح صلاحية التخزين أو تركيب الفلاشة). */
    fun scan() {
        Thread {
            val found = ArrayList<File>()
            val roots = ArrayList<File>()
            try {
                roots.add(Environment.getExternalStorageDirectory())
            } catch (_: Throwable) {
            }
            try {
                File("/storage").listFiles()?.let { roots.addAll(it) }
            } catch (_: Throwable) {
            }
            for (root in roots) {
                try {
                    val dir = File(root, FOLDER)
                    val list = dir.listFiles() ?: continue
                    for (f in list) {
                        val name = f.name.lowercase()
                        if (f.isFile && (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                                name.endsWith(".png") || name.endsWith(".webp"))
                        ) found.add(f)
                    }
                } catch (_: Throwable) {
                }
            }
            val sorted = found.distinctBy { it.absolutePath }.sortedBy { it.name }
            handler.post {
                val changed = sorted.map { it.absolutePath } != files.map { it.absolutePath }
                files = sorted
                if (files.isEmpty()) {
                    image.setImageDrawable(null)
                    hint.visibility = View.VISIBLE
                } else if (changed || index < 0) {
                    index = -1
                    next()
                }
            }
        }.start()
    }

    /** الصورة التالية (تستدعى تلقائياً أو بضغطة). */
    fun next() {
        handler.removeCallbacks(advance)
        if (files.isEmpty()) {
            scan()
            return
        }
        if (loading) return
        loading = true
        index = (index + 1) % files.size
        val file = files[index]
        Thread {
            val bitmap = decode(file)
            handler.post {
                loading = false
                if (bitmap != null) {
                    hint.visibility = View.GONE
                    image.animate().cancel()
                    image.alpha = 0.3f
                    image.setImageBitmap(bitmap)
                    image.animate().alpha(1f).setDuration(350L).start()
                }
                if (running && files.size > 1) handler.postDelayed(advance, INTERVAL_MS)
            }
        }.start()
    }

    private fun decode(file: File): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options()
            bounds.inJustDecodeBounds = true
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= TARGET_WIDTH) sample *= 2
            val options = BitmapFactory.Options()
            options.inSampleSize = sample
            options.inPreferredConfig = Bitmap.Config.RGB_565
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Throwable) {
            null
        }
    }
}
