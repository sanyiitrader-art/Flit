package app.flit

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * One small foreground service (and the media notification with its media session) for the things that must keep running in the background:
 * audio playback and the audio of a video the user chose to keep playing (type mediaPlayback), and
 * archive extraction (type dataSync). Each has its own
 * notification (audio: Play/Pause and Close; extraction: progress, percentage and Cancel). Tapping
 * the audio notification returns to Flit; tapping the extraction one returns and reopens the
 * progress dialog. The service stops itself as soon as neither is active.
 */
/** The video player as a media source (it registers while a video is open; see VideoScreen). */
object VBg {
    @Volatile
    var active = false

    @Volatile
    var name = ""

    @Volatile
    var playing = false

    var onToggle: (() -> Unit)? = null
    var onClose: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onPrev: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var pos: () -> Long = { 0L }
    var dur: () -> Long = { 0L }

    fun clear() {
        active = false
        onToggle = null
        onClose = null
        onNext = null
        onPrev = null
        onSeek = null
    }
}

/**
 * Registers Flit with Android's media system: a MediaSession, so the notification panel, the lock screen
 * and headset buttons show and control whatever Flit plays (audio or video): previous, play / pause,
 * next, a seek bar, the title.
 */
object MediaHub {

    private var s: MediaSession? = null

    private fun toggle(wantPlay: Boolean) {
        if (Audio.active) {
            if (Audio.wantsPlay != wantPlay) Audio.toggle()
        } else if (VBg.playing != wantPlay) {
            VBg.onToggle?.invoke()
        }
    }

    private fun ensure(c: Context): MediaSession {
        s?.let { return it }
        val m = MediaSession(c.applicationContext, "Flit")
        m.setCallback(object : MediaSession.Callback() {
            override fun onPlay() = toggle(true)
            override fun onPause() = toggle(false)
            override fun onSkipToNext() {
                if (Audio.active) Audio.next() else VBg.onNext?.invoke()
            }

            override fun onSkipToPrevious() {
                if (Audio.active) Audio.prev() else VBg.onPrev?.invoke()
            }

            override fun onSeekTo(pos: Long) {
                if (Audio.active) Audio.seek(pos) else VBg.onSeek?.invoke(pos)
            }

            override fun onStop() {
                if (Audio.active) Audio.stop() else VBg.onClose?.invoke()
            }
        })
        m.isActive = true
        s = m
        return m
    }

    fun token(c: Context): MediaSession.Token = ensure(c).sessionToken

    /** Pushes the current title, state and position to the session (call after every change). */
    fun update(c: Context) {
        val m = ensure(c)
        val aud = Audio.active
        val name = (if (aud) Audio.current?.name else VBg.name) ?: ""
        val playing = if (aud) Audio.wantsPlay else VBg.playing
        val pos = if (aud) Audio.position else VBg.pos()
        val dur = if (aud) Audio.duration else VBg.dur()
        m.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, name)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, c.getString(R.string.app_name))
                .putLong(MediaMetadata.METADATA_KEY_DURATION, dur)
                .build()
        )
        m.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_STOP
                )
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, pos, 1f)
                .build()
        )
    }

    fun release() {
        s?.release()
        s = null
    }
}

class BgService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        starting = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> if (Audio.active) Audio.toggle() else VBg.onToggle?.invoke()
            ACT_CLOSE -> if (Audio.active) Audio.stop() else VBg.onClose?.invoke()
            ACT_NEXT -> if (Audio.active) Audio.next() else VBg.onNext?.invoke()
            ACT_PREV -> if (Audio.active) Audio.prev() else VBg.onPrev?.invoke()
            ACT_CANCEL -> Extractor.cancel()
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        inst = null
        starting = false
        super.onDestroy()
    }

    /** Posts the right notifications, or stops the service when nothing needs it. */
    fun refresh() {
        val aud = Audio.active || VBg.active
        val ext = Extractor.running
        val nm = getSystemService(NotificationManager::class.java)
        if (aud) MediaHub.update(this)
        if (!aud && !ext) {
            try {
                startForeground(ID_AUDIO, audioNotification()) // satisfies startForegroundService, then goes away
            } catch (e: Exception) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            MediaHub.release()
            nm?.cancel(ID_AUDIO)
            nm?.cancel(ID_EXTRACT)
            stopSelf()
            return
        }
        try {
            val mainId = if (ext) ID_EXTRACT else ID_AUDIO
            val main = if (ext) extractNotification() else audioNotification()
            if (Build.VERSION.SDK_INT >= 29) {
                var type = 0
                if (aud) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                if (ext) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(mainId, main, type)
            } else {
                startForeground(mainId, main)
            }
            if (ext && aud) nm?.notify(ID_AUDIO, audioNotification())
            if (!ext) nm?.cancel(ID_EXTRACT)
            if (!aud) nm?.cancel(ID_AUDIO)
        } catch (e: Exception) {
            stopSelf()
        }
    }

    private fun action(code: Int, act: String): PendingIntent =
        PendingIntent.getService(
            this, code, Intent(this, BgService::class.java).setAction(act),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun open(code: Int, extract: Boolean): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (extract) i.putExtra(EXTRA_EXTRACT, true)
        return PendingIntent.getActivity(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun base(): Notification.Builder {
        val b = Notification.Builder(this, FlitApp.CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_flit)
            .setColor(getColor(R.color.flit_accent))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b
    }

    private fun audioNotification(): Notification {
        val playing = if (Audio.active) Audio.wantsPlay else VBg.playing
        val name = if (Audio.active) Audio.current?.name else VBg.name
        fun act(icon: Int, label: Int, code: Int, a: String) =
            Notification.Action.Builder(Icon.createWithResource(this, icon), getString(label), action(code, a)).build()
        return base()
            .setContentTitle(name ?: getString(R.string.app_name))
            .setContentText(getString(R.string.app_name))
            .setContentIntent(open(0, false))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, R.string.v_prev, 5, ACT_PREV))
            .addAction(act(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, R.string.v_play_pause, 1, ACT_TOGGLE))
            .addAction(act(android.R.drawable.ic_media_next, R.string.v_next, 6, ACT_NEXT))
            .addAction(act(android.R.drawable.ic_menu_close_clear_cancel, R.string.v_close, 2, ACT_CLOSE))
            .setStyle(Notification.MediaStyle().setMediaSession(MediaHub.token(this)).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun extractNotification(): Notification {
        val j = Extractor.current
        val pct = ((j?.fraction() ?: 0f) * 100f).toInt()
        return base()
            .setContentTitle(getString(R.string.v_extracting, j?.src?.name ?: ""))
            .setContentText(j?.file ?: "")
            .setSubText("$pct%")
            .setProgress(100, pct, j == null || (j.total <= 0L && j.entries <= 0))
            .setContentIntent(open(3, true))
            .setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.v_cancel), action(4, ACT_CANCEL)
                ).build()
            )
            .build()
    }

    companion object {
        const val ID_AUDIO = 105
        const val ID_EXTRACT = 106
        const val ACT_TOGGLE = "app.flit.AUDIO_TOGGLE"
        const val ACT_CLOSE = "app.flit.AUDIO_CLOSE"
        const val ACT_NEXT = "app.flit.AUDIO_NEXT"
        const val ACT_PREV = "app.flit.AUDIO_PREV"
        const val ACT_CANCEL = "app.flit.EXTRACT_CANCEL"

        /** Intent extra: the notification tap should reopen the extraction progress dialog. */
        const val EXTRA_EXTRACT = "app.flit.EXTRA_EXTRACT"

        @Volatile
        private var inst: BgService? = null

        @Volatile
        private var starting = false

        /** Called whenever audio or extraction state changes (main thread): starts, updates or stops the service. */
        fun update() {
            val s = inst
            if (s != null) {
                s.refresh()
                return
            }
            if (!(Audio.active || VBg.active || Extractor.running) || starting) return
            starting = true
            val c: Context = FlitApp.app
            try {
                ContextCompat.startForegroundService(c, Intent(c, BgService::class.java))
            } catch (e: Exception) {
                starting = false
            }
        }
    }
}
