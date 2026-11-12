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
class BtRemote(private val context: Context) {

    companion object {
        private const val PROFILE_AVRCP_CONTROLLER = 12
        const val KEY_PLAY = 0x44
        const val KEY_PAUSE = 0x46
        const val KEY_NEXT = 0x4B
        const val KEY_PREV = 0x4C
        private const val A2DP_PLAYING_CHANGED =
            "android.bluetooth.a2dp-sink.profile.action.PLAYING_STATE_CHANGED"
        private const val VENDOR_PREFIX = "com.autochips.bluetooth.avrcpct.profile.action."
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
    var playing = false
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
                playing = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, 11) == 10
                onChange?.invoke()
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

    fun start() {
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

    fun stop() {
        try {
            context.unregisterReceiver(receiver)
        } catch (_: Throwable) {
        }
        try {
            val p = proxy as? BluetoothProfile
            if (p != null) {
                BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(PROFILE_AVRCP_CONTROLLER, p)
            }
        } catch (_: Throwable) {
        }
        proxy = null
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

    fun playPause(): Boolean = send(if (playing) KEY_PAUSE else KEY_PLAY)
}
