package com.example.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * مربع الموسيقى: صورة الألبوم، العنوان، شريط التقديم والترجيع، وأزرار التحكم.
 *
 * - الأزرار (تشغيل/التالي/السابق) تشتغل دائماً، لأنها ترسل أزرار الوسائط للنظام.
 * - اسم الأغنية والصورة وشريط التقديم تحتاج صلاحية "الوصول للإشعارات"،
 *   والضغط على المربع يفتح صفحة تفعيلها.
 */
class MusicTile(private val activity: Activity, private val handler: Handler) {

    private val titleView: TextView = activity.findViewById(R.id.musicTitle)
    private val artistView: TextView = activity.findViewById(R.id.musicArtist)
    private val artView: ImageView = activity.findViewById(R.id.musicArt)
    private val seek: SeekBar = activity.findViewById(R.id.musicSeek)
    private val elapsedView: TextView = activity.findViewById(R.id.musicElapsed)
    private val totalView: TextView = activity.findViewById(R.id.musicTotal)
    private val playButton: ImageButton = activity.findViewById(R.id.musicPlay)

    private val sessionManager =
        activity.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val listenerComponent = ComponentName(activity, NowPlayingService::class.java)

    private var controller: MediaController? = null
    val bt = BtRemote.get(activity)
    private var listening = false
    private var userSeeking = false
    private var durationMs = 0L

    /** النص اللي يظهر في شاشة الانتظار. فاضي إذا ما فيه أغنية معروفة. */
    var nowPlaying: String = ""
        private set

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            renderMetadata(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            renderState(state)
        }
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        bind(list)
    }

    private val tick = object : Runnable {
        override fun run() {
            renderPosition()
            handler.postDelayed(this, 1000L)
        }
    }

    init {
        bt.onChange = { handler.post { renderBluetooth() } }
        activity.findViewById<ImageButton>(R.id.musicPrev).setOnClickListener {
            val c = controller
            if (c != null) c.transportControls.skipToPrevious()
            else if (bt.seen) bt.prev()
            else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        }
        activity.findViewById<ImageButton>(R.id.musicNext).setOnClickListener {
            val c = controller
            if (c != null) c.transportControls.skipToNext()
            else if (bt.seen) bt.next()
            else sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
        }
        playButton.setOnClickListener {
            val c = controller
            if (c == null) {
                if (bt.seen) bt.playPause() else sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            } else if (c.playbackState?.state == PlaybackState.STATE_PLAYING) {
                c.transportControls.pause()
            } else {
                c.transportControls.play()
            }
        }
        activity.findViewById<View>(R.id.musicBack10).setOnClickListener { jump(-10_000L) }
        activity.findViewById<View>(R.id.musicFwd10).setOnClickListener { jump(10_000L) }
        activity.findViewById<View>(R.id.musicInfo).setOnClickListener {
            if (!hasAccess()) openAccessSettings()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) elapsedView.text = formatTime(progress * 1000L)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                userSeeking = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                userSeeking = false
                controller?.transportControls?.seekTo(bar.progress * 1000L)
            }
        })
    }

    /** تقديم أو ترجيع بالملّي ثانية. */
    private fun jump(deltaMs: Long) {
        val c = controller
        if (c != null) {
            val state = c.playbackState ?: return
            var position = state.position
            if (state.state == PlaybackState.STATE_PLAYING) {
                position += ((SystemClock.elapsedRealtime() - state.lastPositionUpdateTime) *
                    state.playbackSpeed).toLong()
            }
            var target = position + deltaMs
            if (target < 0) target = 0
            if (durationMs > 0 && target > durationMs) target = durationMs
            c.transportControls.seekTo(target)
            return
        }
        if (bt.seen) {
            // البلوتوث ما فيه "اذهب للثانية كذا"، فيه تقديم سريع بالضغط المستمر فقط
            val key = if (deltaMs > 0) BtRemote.KEY_FAST_FORWARD else BtRemote.KEY_REWIND
            if (!bt.hold(key, 1200L)) {
                Toast.makeText(activity, "الجوال ما يدعم التقديم عن طريق البلوتوث", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val btRecheck = Runnable { renderBluetooth() }

    /** لما ما فيه مشغّل داخل الراديو والموسيقى جاية من الجوال بالبلوتوث. */
    private fun renderBluetooth() {
        if (controller != null || !bt.seen) return
        val playing = bt.playing
        val hasTitle = bt.title.isNotEmpty()
        nowPlaying = if (hasTitle) bt.title else if (playing) "موسيقى البلوتوث" else ""
        titleView.text = if (hasTitle) bt.title else "موسيقى البلوتوث"
        artistView.text = when {
            hasTitle && bt.artist.isNotEmpty() -> bt.artist
            playing -> "يشتغل من الجوال"
            else -> "متوقف"
        }
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)

        // الشريط يمشي مع الأغنية. السحب مقفول لأن البلوتوث ما يدعم القفز لوقت محدد
        seek.isEnabled = false
        val length = bt.durationMs
        if (length > 0) {
            seek.max = (length / 1000L).toInt()
            seek.progress = (bt.positionMs.coerceIn(0L, length) / 1000L).toInt()
            totalView.text = formatTime(length)
        } else {
            seek.progress = 0
            totalView.text = ""
        }
        elapsedView.text = if (bt.positionMs > 0) formatTime(bt.positionMs) else ""

        // رسالة الموضع تجي كل ثانية؛ لو وقفت نعرف إنه توقف
        handler.removeCallbacks(btRecheck)
        if (playing) handler.postDelayed(btRecheck, 2800L)
    }

    fun start() {
        if (!hasAccess()) {
            showNoAccess()
            return
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent)
            listening = true
            bind(sessionManager.getActiveSessions(listenerComponent))
        } catch (e: SecurityException) {
            showNoAccess()
        }
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(btRecheck)
        handler.removeCallbacks(tick)
        if (listening) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
            } catch (_: Exception) {
            }
            listening = false
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    private fun hasAccess(): Boolean {
        val enabled = Settings.Secure.getString(
            activity.contentResolver, "enabled_notification_listeners"
        ) ?: return false
        return enabled.contains(activity.packageName)
    }

    private fun openAccessSettings() {
        try {
            activity.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        } catch (e: Exception) {
            Toast.makeText(
                activity,
                "الراديو ما فيه صفحة الوصول للإشعارات. الأزرار تشتغل بدونها.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showNoAccess() {
        nowPlaying = ""
        titleView.text = "اضغط هنا لعرض اسم الأغنية"
        artistView.text = "أزرار التحكم تشتغل بدون تفعيل"
        showPlaceholderArt()
        setSeekEnabled(false)
    }

    private fun bind(list: List<MediaController>?) {
        controller?.unregisterCallback(controllerCallback)
        controller = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: list?.firstOrNull()
        controller?.registerCallback(controllerCallback, handler)
        renderMetadata(controller?.metadata)
        renderState(controller?.playbackState)
        renderBluetooth()
    }

    private fun renderMetadata(metadata: MediaMetadata?) {
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)
        val artist = metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
        nowPlaying = title ?: ""
        titleView.text = title ?: "لا يوجد تشغيل"
        artistView.text = artist ?: if (title == null) "شغّل أغنية من أي تطبيق" else ""

        val art = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        if (art != null) {
            artView.imageTintList = null
            artView.setImageBitmap(art)
        } else {
            showPlaceholderArt()
        }

        durationMs = metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (durationMs > 0) {
            setSeekEnabled(true)
            seek.max = (durationMs / 1000L).toInt()
            totalView.text = formatTime(durationMs)
        } else {
            setSeekEnabled(false)
        }
        renderPosition()
    }

    private fun renderState(state: PlaybackState?) {
        val playing = state?.state == PlaybackState.STATE_PLAYING
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        renderPosition()
    }

    private fun renderPosition() {
        if (durationMs <= 0 || userSeeking) return
        val state = controller?.playbackState ?: return
        var position = state.position
        if (state.state == PlaybackState.STATE_PLAYING) {
            val delta = SystemClock.elapsedRealtime() - state.lastPositionUpdateTime
            position += (delta * state.playbackSpeed).toLong()
        }
        position = position.coerceIn(0L, durationMs)
        seek.progress = (position / 1000L).toInt()
        elapsedView.text = formatTime(position)
    }

    private fun setSeekEnabled(enabled: Boolean) {
        seek.isEnabled = enabled
        if (!enabled) {
            durationMs = 0L
            seek.progress = 0
            elapsedView.text = ""
            totalView.text = ""
        }
    }

    private fun showPlaceholderArt() {
        artView.setImageResource(R.drawable.ic_note)
        artView.imageTintList = ColorStateList.valueOf(activity.themeColor(R.attr.lTextDim))
    }

    private fun sendMediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun formatTime(ms: Long): String {
        val totalSeconds = ms / 1000L
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
    }
}
