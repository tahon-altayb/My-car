package com.example.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * صفحة تشخيص: تعرض معلومات الراديو الفعلية (نسخة النظام، برامج المصنع، حالة الصوت)
 * عشان نعرف كيف نتحكم بالأشياء الخاصة بهذا الجهاز. ما تغيّر شي إلا إذا ضغطت زر ADB.
 */
class DiagActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var output: TextView
    private var report = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()

        output = TextView(this)
        output.textSize = 15f
        output.typeface = Typeface.MONOSPACE
        output.setTextIsSelectable(true)
        output.setPadding(pad, pad, pad, pad)
        output.gravity = Gravity.LEFT
        output.textDirection = View.TEXT_DIRECTION_LTR
        output.text = "..."

        val adbButton = Button(this)
        adbButton.text = "تفعيل ADB على الواي فاي"
        adbButton.setOnClickListener { enableAdb() }

        val saveButton = Button(this)
        saveButton.text = "حفظ في ملف"
        saveButton.setOnClickListener { save() }

        val closeButton = Button(this)
        closeButton.text = "إغلاق"
        closeButton.setOnClickListener { finish() }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(closeButton)
        bar.addView(saveButton)
        bar.addView(adbButton)

        val scroll = ScrollView(this)
        scroll.addView(output)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.addView(bar)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        setContentView(root)

        Thread {
            val text = collect()
            handler.post {
                report = text
                output.text = text
            }
        }.start()
    }

    private fun collect(): String {
        val sb = StringBuilder()
        sb.append("== SYSTEM ==\n")
        sb.append("android: ").append(Build.VERSION.RELEASE).append("  sdk: ").append(Build.VERSION.SDK_INT).append('\n')
        sb.append("model: ").append(Build.MANUFACTURER).append(" / ").append(Build.MODEL).append('\n')
        sb.append("display: ").append(Build.DISPLAY).append('\n')
        sb.append("hardware: ").append(Build.HARDWARE).append("  board: ").append(Build.BOARD).append('\n')

        sb.append("\n== AUDIO ==\n")
        try {
            val audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            sb.append("fixedVolume: ").append(audio.isVolumeFixed).append('\n')
            sb.append("music: ").append(audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                .append(" / ").append(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).append('\n')
        } catch (e: Exception) {
            sb.append("error: ").append(e.message).append('\n')
        }

        sb.append("\n== CAMERA ==\n")
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            sb.append("camera2 ids: ").append(cm.cameraIdList.joinToString(",")).append('\n')
        } catch (e: Exception) {
            sb.append("error: ").append(e.message).append('\n')
        }

        sb.append("\n== ROOT / ADB ==\n")
        sb.append("su: ").append(run(arrayOf("su", "-c", "id"))).append('\n')
        sb.append("adb tcp port: ").append(run(arrayOf("getprop", "service.adb.tcp.port"))).append('\n')

        sb.append("\n== VENDOR PACKAGES ==\n")
        try {
            val pm = packageManager
            val names = pm.getInstalledPackages(0).map { it.packageName }
                .filter {
                    it != "android" && !it.startsWith("com.android.") &&
                        !it.startsWith("com.google.") && it != packageName
                }
                .sorted()
            for (name in names) {
                val label = try {
                    pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
                } catch (e: Exception) {
                    ""
                }
                sb.append(name).append("  [").append(label).append("]\n")
                try {
                    val info = pm.getPackageInfo(
                        name, PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS
                    )
                    info.services?.take(12)?.forEach { sb.append("    S ").append(it.name).append('\n') }
                    info.receivers?.take(12)?.forEach { sb.append("    R ").append(it.name).append('\n') }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) {
            sb.append("error: ").append(e.message).append('\n')
        }
        return sb.toString()
    }

    /** يشغّل أمر ويرجع ناتجه. يقطعه بعد 6 ثواني لو علّق. */
    private fun run(command: Array<String>): String {
        return try {
            val process = Runtime.getRuntime().exec(command)
            handler.postDelayed({ process.destroy() }, 6000L)
            val out = process.inputStream.bufferedReader().readText().trim()
            val err = process.errorStream.bufferedReader().readText().trim()
            val code = process.waitFor()
            "exit=" + code + " " + out + (if (err.isEmpty()) "" else " | " + err)
        } catch (e: Exception) {
            "failed: " + e.message
        }
    }

    private fun enableAdb() {
        output.text = "..."
        Thread {
            val script = "setprop service.adb.tcp.port 5555; stop adbd; start adbd"
            val plain = run(arrayOf("sh", "-c", script))
            val root = run(arrayOf("su", "-c", script))
            val port = run(arrayOf("getprop", "service.adb.tcp.port"))
            val text = "== ADB ATTEMPT ==\nsh: " + plain + "\nsu: " + root +
                "\nport now: " + port + "\n\n" + report
            handler.post {
                report = text
                output.text = text
            }
        }.start()
    }

    private fun save() {
        try {
            val dir = getExternalFilesDir(null) ?: filesDir
            val file = File(dir, "diag.txt")
            file.writeText(report)
            Toast.makeText(this, file.absolutePath, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "تعذر الحفظ", Toast.LENGTH_SHORT).show()
        }
    }
}
