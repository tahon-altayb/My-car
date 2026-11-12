package com.example.launcher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.widget.TextView

/**
 * يقرأ الموسيقى الشغالة حالياً من أي تطبيق (Spotify, YouTube Music...)
 * ويعرض العنوان والفنان، ويرسل أوامر التشغيل والتالي والسابق.
 *
 * يحتاج المستخدم يفعّل صلاحية "الوصول للإشعارات" للتطبيق مرة وحدة.
 */
class MusicTile(
    private val context: Context,
    private val titleView: TextView,
    private val artistView: TextView
) {
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, NowPlayingService::class.java)

    private var controller: MediaController? = null

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            render(metadata)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list)
    }

    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            bind(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            titleView.text = "فعّل صلاحية الإشعارات للتطبيق"
            artistView.text = ""
        }
    }

    fun stop() {
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) {
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun bind(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        controller = list?.firstOrNull()
        controller?.registerCallback(controllerCallback)
        render(controller?.metadata)
    }

    private fun render(metadata: MediaMetadata?) {
        titleView.text = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "لا يوجد تشغيل"
        artistView.text = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "اختر أغنية للبدء"
    }

    fun playPause() {
        val state = controller?.playbackState?.state
        if (state == PlaybackState.STATE_PLAYING) {
            controller?.transportControls?.pause()
        } else {
            controller?.transportControls?.play()
        }
    }

    fun next() {
        controller?.transportControls?.skipToNext()
    }

    fun prev() {
        controller?.transportControls?.skipToPrevious()
    }
}
