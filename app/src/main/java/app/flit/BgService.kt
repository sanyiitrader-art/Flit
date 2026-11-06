package app.flit

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * One small foreground service for the two things that must keep running in the background:
 * audio playback (type mediaPlayback) and archive extraction (type dataSync). Each has its own
 * notification (audio: Play/Pause and Close; extraction: progress, percentage and Cancel). Tapping
 * the audio notification returns to Flit; tapping the extraction one returns and reopens the
 * progress dialog. The service stops itself as soon as neither is active.
 */
class BgService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        starting = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_TOGGLE -> Audio.toggle()
            ACT_CLOSE -> Audio.stop()
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
        val aud = Audio.active
        val ext = Extractor.running
        val nm = getSystemService(NotificationManager::class.java)
        if (!aud && !ext) {
            try {
                startForeground(ID_AUDIO, audioNotification()) // satisfies startForegroundService, then goes away
            } catch (e: Exception) {
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
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
        val playing = Audio.wantsPlay
        return base()
            .setContentTitle(Audio.current?.name ?: getString(R.string.app_name))
            .setContentText(getString(R.string.app_name))
            .setContentIntent(open(0, false))
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    getString(R.string.v_play_pause), action(1, ACT_TOGGLE)
                ).build()
            )
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                    getString(R.string.v_close), action(2, ACT_CLOSE)
                ).build()
            )
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
            if (!(Audio.active || Extractor.running) || starting) return
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