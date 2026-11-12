package com.example.launcher

import android.os.Handler

/**
 * يعد الوقت بدون لمس، وإذا خلص يستدعي onIdle (شاشة الانتظار).
 * أي لمسة جديدة تستدعي poke() فترجع الشاشة وتعيد العد.
 */
class IdleController(
    private val handler: Handler,
    private val timeoutMs: Long,
    private val onIdle: () -> Unit,
    private val onWake: () -> Unit
) {
    var isIdle: Boolean = false
        private set

    private val sleepRunnable = Runnable {
        isIdle = true
        onIdle()
    }

    fun poke() {
        if (isIdle) {
            isIdle = false
            onWake()
        }
        handler.removeCallbacks(sleepRunnable)
        handler.postDelayed(sleepRunnable, timeoutMs)
    }

    fun stop() {
        handler.removeCallbacks(sleepRunnable)
    }
}
