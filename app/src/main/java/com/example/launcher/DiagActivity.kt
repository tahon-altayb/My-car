package com.example.launcher

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
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

        val probeButton = Button(this)
        probeButton.text = "فحص البلوتوث والصوت"
        probeButton.setOnClickListener { probe() }

        val exportButton = Button(this)
        exportButton.text = "نسخ برامج المصنع"
        exportButton.setOnClickListener { exportApks() }

        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.addView(closeButton)
        bar.addView(exportButton)
        bar.addView(probeButton)
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

    // ---------- فحص البلوتوث والصوت ----------

    private fun listMethods(sb: StringBuilder, cls: Class<*>, filter: Regex?) {
        sb.append("CLASS ").append(cls.name).append(" extends ").append(cls.superclass?.name).append('\n')
        val methods = try { cls.declaredMethods } catch (e: Throwable) { emptyArray() }
        for (m in methods.sortedBy { it.name }) {
            if (filter != null && !filter.containsMatchIn(m.name)) continue
            sb.append("   ").append(m.name).append('(')
                .append(m.parameterTypes.joinToString(",") { it.simpleName })
                .append("): ").append(m.returnType.simpleName).append('\n')
        }
        try {
            for (f in cls.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers) &&
                    (f.type == Int::class.javaPrimitiveType || f.type == String::class.java)) {
                    f.isAccessible = true
                    sb.append("   F ").append(f.name).append(" = ").append(f.get(null)).append('\n')
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun probe() {
        output.text = "..."
        Thread {
            val sb = StringBuilder("== PROBE ==\n")
            // الصوت
            val vol = CarVolume(this)
            sb.append("vendor volume api: ").append(vol.usesVendor)
                .append("  McuMainVolume=").append(vol.current()).append('\n')
            val before = vol.current()
            val ok = vol.set(before)
            sb.append("set same value ok=").append(ok).append(" err=").append(vol.lastError).append('\n')
            listMethods(sb, android.media.AudioManager::class.java, Regex("(?i)master|mcu|xy"))

            // كلاسات البلوتوث في النظام
            for (name in listOf(
                "android.bluetooth.BluetoothAvrcpController",
                "android.bluetooth.BluetoothA2dpSink",
                "android.bluetooth.BluetoothAvrcpPlayerSettings"
            )) {
                try {
                    listMethods(sb, Class.forName(name), null)
                } catch (e: Throwable) {
                    sb.append("missing ").append(name).append('\n')
                }
            }
            // كلاسات برنامج البلوتوث حق المصنع
            try {
                val ctx = createPackageContext(
                    "com.autochips.bluetooth",
                    Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY
                )
                for (name in listOf(
                    "com.autochips.bluetooth.AvrcpControllerProfile",
                    "com.autochips.bluetooth.AvrcpControllerProfile\$MetadataCallback",
                    "com.autochips.bluetooth.BtUtils"
                )) {
                    try {
                        listMethods(sb, ctx.classLoader.loadClass(name), null)
                    } catch (e: Throwable) {
                        sb.append("missing ").append(name).append(": ").append(e.javaClass.simpleName).append('\n')
                    }
                }
            } catch (e: Throwable) {
                sb.append("vendor bt context: ").append(e).append('\n')
            }
            // مكتبات النظام المشتركة
            for (dir in listOf("/system/framework", "/system/etc/permissions")) {
                sb.append("DIR ").append(dir).append(": ")
                    .append(File(dir).list()?.sorted()?.joinToString(" ") ?: "?").append('\n')
            }
            val text = sb.toString() + "\n" + report
            handler.post {
                report = text
                output.text = text
            }
        }.start()
    }

    // ---------- نسخ برامج المصنع ----------

    private val vendorPackages = listOf(
        "com.xyauto.Settings",
        "com.autochips.bluetooth",
        "com.autochips.android.backcar",
        "com.acloud.stub.localmusic",
        "com.xygala.canbus",
        "com.pve.apsbridge"
    )

    /** كل الأماكن اللي نقدر نكتب فيها: الفلاشة أولاً، ثم مجلد التنزيلات. */
    private fun exportTargets(): List<File> {
        val targets = ArrayList<File>()
        try {
            // المجلدات الخاصة بالتطبيق على الفلاشة ما تحتاج صلاحية
            val dirs = getExternalFilesDirs(null)
            for (i in 1 until dirs.size) {
                val d = dirs[i] ?: continue
                targets.add(File(d, "radio-apks"))
            }
        } catch (e: Exception) {
        }
        if (Build.VERSION.SDK_INT < 23 ||
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            targets.add(
                File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    "radio-apks"
                )
            )
        }
        return targets
    }

    private fun exportApks() {
        if (Build.VERSION.SDK_INT >= 23 &&
            checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE), 7)
        }
        output.text = "جاري النسخ... انتظر"
        Thread {
            val log = StringBuilder("== EXPORT ==\n")
            val targets = exportTargets()
            if (targets.isEmpty()) {
                log.append("ما لقيت مكان للحفظ. ركّب الفلاشة واضغط مرة ثانية.\n")
            }
            for (pkg in vendorPackages) {
                try {
                    val info = packageManager.getApplicationInfo(pkg, 0)
                    val apk = File(info.sourceDir)
                    val appDir = apk.parentFile
                    log.append(pkg).append(" <- ").append(info.sourceDir).append('\n')
                    for (target in targets) {
                        val dest = File(target, pkg)
                        val copied = copyTree(appDir!!, dest, 0)
                        log.append("   ").append(dest.absolutePath)
                            .append("  (").append(copied).append(" ملف)\n")
                    }
                } catch (e: Exception) {
                    log.append(pkg).append(": ").append(e.javaClass.simpleName)
                        .append(' ').append(e.message).append('\n')
                }
            }
            val text = log.toString() + "\n" + report
            handler.post {
                report = text
                output.text = text
            }
        }.start()
    }

    /** ينسخ مجلد البرنامج كامل (الـapk ومجلد oat إن وجد). */
    private fun copyTree(src: File, dest: File, depth: Int): Int {
        if (depth > 4) return 0
        if (src.isDirectory) {
            dest.mkdirs()
            var count = 0
            for (child in src.listFiles() ?: emptyArray()) {
                count += copyTree(child, File(dest, child.name), depth + 1)
            }
            return count
        }
        if (!src.canRead()) return 0
        src.inputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        return 1
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
