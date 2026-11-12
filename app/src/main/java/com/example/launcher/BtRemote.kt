package com.example.launcher

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * التحكم بموسيقى الجوال على البلوتوث (تشغيل/إيقاف/التالي/السابق)
 * عن طريق واجهة AVRCP المخفية في نظام الراديو.
 * ويسجّل رسائل برنامج البلوتوث حق المصنع عشان نعرف شكل بيانات اسم الأغنية.
 */
class BtRemote private constructor(private val context: Context) {

    companion object {
        const val KEY_FAST_FORWARD = 0x49
        const val KEY_REWIND = 0x48
        private var instance: BtRemote? = null

        /** نسخة وحدة تعيش مع التطبيق، عشان السجل ما ينقطع لما تطلع من اللانشر. */
        fun get(context: Context): BtRemote {
            var current = instance
            if (current == null) {
                current = BtRemote(context.applicationContext)
                instance = current
                current.start()
            }
            return current
        }

        private const val PROFILE_AVRCP_CONTROLLER = 12
        const val KEY_PLAY = 0x44
        const val KEY_PAUSE = 0x46
        const val KEY_NEXT = 0x4B
        const val KEY_PREV = 0x4C
        private const val A2DP_PLAYING_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        private const val VENDOR_PREFIX = "com.autochips.bluetooth.avrcpct.profile.action."
        private const val VENDOR_EXTRA = "com.autochips.bluetooth.avrcpct.profile.extra."
        private val VENDOR_ACTIONS = listOf(
            "ACTION_EVENT_NOTIFICATION_CHANGED", "GET_CAPABILITY_CNF", "GET_ELEMENT_ATTRIBUTES",
            "GET_PLAYER_STATUS", "PASSTHROUGH_COMMAND_CNF", "REMOTE_RC_FEATURES"
        )

        /** سجل آخر الرسائل، يظهر في صفحة التشخيص. */
        val log = StringBuilder()

        fun note(line: String) {
            synchronized(log) {
                if (log.length > 6000) log.delete(0, 2000)
                log.append(line).append('\n')
            }
        }
    }

    private var proxy: Any? = null
    private var a2dpPlaying = false
    private var lastPositionEvent = 0L

    /** موضع الأغنية بالملّي ثانية، من رسائل برنامج البلوتوث. */
    var positionMs = 0L
        private set

    /** الجوال يشغّل الآن؟ رسالة الموضع تجي كل ثانية طول ما الأغنية شغالة. */
    val playing: Boolean
        get() {
            val now = android.os.SystemClock.elapsedRealtime()
            // بعد ضغطة المستخدم نعرض الحالة المتوقعة فوراً، لين توصل الحالة الحقيقية
            if (now < expectUntil) return expected
            return a2dpPlaying || now - lastPositionEvent < 2500L
        }

    private var expected = false
    private var expectUntil = 0L

    /** شفنا نشاط موسيقى بلوتوث في هذي الجلسة؟ */
    var seen = false
        private set
    var onChange: (() -> Unit)? = null

    private val listener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, p: BluetoothProfile?) {
            proxy = p
            note("avrcp proxy connected: " + p?.javaClass?.name)
            onChange?.invoke()
        }

        override fun onServiceDisconnected(profile: Int) {
            proxy = null
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            if (intent.action == A2DP_PLAYING_CHANGED) {
                a2dpPlaying = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 11) == 10
                seen = true
                onChange?.invoke()
                return
            }
            if (intent.action == VENDOR_PREFIX + "ACTION_EVENT_NOTIFICATION_CHANGED") {
                val event = intent.getByteExtra(VENDOR_EXTRA + "EVENTID", 0).toInt()
                if (event == 5) {
                    // موضع التشغيل: تجي كل ثانية، فما نسجلها عشان ما تغرق السجل
                    positionMs = intent.getLongExtra(VENDOR_EXTRA + "PARAM", 0L)
                    lastPositionEvent = android.os.SystemClock.elapsedRealtime()
                    seen = true
                    onChange?.invoke()
                    return
                }
            }
            val sb = StringBuilder("RX ").append(intent.action?.substringAfterLast('.'))
            try {
                val extras = intent.extras
                if (extras != null) {
                    for (key in extras.keySet()) {
                        val v = extras.get(key)
                        val text = when (v) {
                            is ByteArray -> "bytes" + v.joinToString(",", "[", "]")
                            is IntArray -> "ints" + v.joinToString(",", "[", "]")
                            is LongArray -> "longs" + v.joinToString(",", "[", "]")
                            is Array<*> -> "arr" + v.joinToString("|", "[", "]")
                            else -> v.toString() + ":" + v?.javaClass?.simpleName
                        }
                        sb.append("\n     ").append(key.substringAfterLast('.')).append(" = ").append(text)
                    }
                }
            } catch (e: Throwable) {
                sb.append(" extras error ").append(e)
            }
            note(sb.toString())
        }
    }

    private fun start() {
        try {
            val filter = IntentFilter(A2DP_PLAYING_CHANGED)
            for (a in VENDOR_ACTIONS) filter.addAction(VENDOR_PREFIX + a)
            context.registerReceiver(receiver, filter)
        } catch (e: Throwable) {
            note("register failed: $e")
        }
        try {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val ok = adapter?.getProfileProxy(context, listener, PROFILE_AVRCP_CONTROLLER)
            note("getProfileProxy(avrcp ct) = $ok")
        } catch (e: Throwable) {
            note("getProfileProxy failed: $e")
        }
    }

    private fun device(): BluetoothDevice? {
        val p = proxy as? BluetoothProfile ?: return null
        return try {
            p.connectedDevices.firstOrNull()
        } catch (e: Throwable) {
            null
        }
    }

    /** فيه جوال متصل نقدر نرسل له أوامر؟ */
    fun isConnected(): Boolean = device() != null

    fun send(key: Int): Boolean {
        val p = proxy ?: return false
        val dev = device() ?: return false
        return try {
            val m = p.javaClass.getMethod(
                "sendPassThroughCmd", BluetoothDevice::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            m.invoke(p, dev, key, 0)   // ضغط
            m.invoke(p, dev, key, 1)   // رفع
            note("TX key 0x" + Integer.toHexString(key) + " ok")
            true
        } catch (e: Throwable) {
            note("TX key failed: " + (e.cause ?: e))
            false
        }
    }

    /** أوامر برنامج البلوتوث حق المصنع (نفس إشارات أزرار الدركسون). */
    private fun vendor(action: String) {
        context.sendBroadcast(Intent(action))
        note("TX broadcast $action")
    }

    fun playPause() {
        val wasPlaying = playing
        if (!send(if (wasPlaying) KEY_PAUSE else KEY_PLAY)) vendor("xy.android.playpause")
        expected = !wasPlaying
        expectUntil = android.os.SystemClock.elapsedRealtime() + 3500L
        if (wasPlaying) a2dpPlaying = false
        onChange?.invoke()
    }

    /** تقديم أو ترجيع سريع: نضغط الزر مدة ثم نرفعه. يرجع false لو الراديو ما يدعمه. */
    fun hold(key: Int, ms: Long): Boolean {
        val p = proxy ?: return false
        val dev = device() ?: return false
        return try {
            val m = p.javaClass.getMethod(
                "sendPassThroughCmd", BluetoothDevice::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
            m.invoke(p, dev, key, 0)
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    m.invoke(p, dev, key, 1)
                } catch (_: Throwable) {
                }
            }, ms)
            note("TX hold 0x" + Integer.toHexString(key) + " " + ms + "ms")
            true
        } catch (e: Throwable) {
            note("TX hold failed: " + (e.cause ?: e))
            false
        }
    }

    fun next() {
        if (!send(KEY_NEXT)) vendor("xy.android.nextmedia")
    }

    fun prev() {
        if (!send(KEY_PREV)) vendor("xy.android.previousmedia")
    }

    /** للتجربة من صفحة التشخيص: طريقة واحدة محددة. */
    fun test(method: Int, what: Int): String = when (method) {
        1 -> "passthrough ok=" + send(intArrayOf(if (playing) KEY_PAUSE else KEY_PLAY, KEY_NEXT, KEY_PREV)[what]) +
            " proxy=" + (proxy != null) + " dev=" + (device() != null)
        else -> {
            vendor(arrayOf("xy.android.playpause", "xy.android.nextmedia", "xy.android.previousmedia")[what])
            "broadcast sent"
        }
    }
}
