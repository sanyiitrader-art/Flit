package app.flit

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.os.Build

/**
 * All notifications. Texts follow the in-app language (via [FlitApp.str]). Every notification
 * opens MainActivity with an [EXTRA_GO] hint so the right screen is shown.
 * Channels (requests: high, progress: low, results: default) are created in [FlitApp].
 */
object Notifs {

    const val ID_FG = 101
    const val ID_INCOMING = 102
    const val ID_RESULT = 103
    const val ID_ALERT = 104

    const val EXTRA_GO = "flit_go"
    const val GO_RECEIVER = 1
    const val GO_INCOMING = 2
    const val GO_TRANSFER = 3
    const val GO_SENDER = 4
    const val GO_STOPPED = 5

    private fun nm(): NotificationManager? =
        FlitApp.app.getSystemService(NotificationManager::class.java)

    private fun open(go: Int): PendingIntent {
        val i = Intent(FlitApp.app, MainActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        i.putExtra(EXTRA_GO, go)
        return PendingIntent.getActivity(
            FlitApp.app, go, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun base(channel: String, title: String, text: String, go: Int): Notification.Builder =
        Notification.Builder(FlitApp.app, channel)
            .setSmallIcon(R.drawable.ic_stat_flit)
            .setColor(FlitApp.ctx().getColor(R.color.flit_accent))
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open(go))

    private fun post(id: Int, n: Notification) {
        try {
            nm()?.notify(id, n)
        } catch (e: Exception) {
        }
    }

    fun cancel(id: Int) {
        try {
            nm()?.cancel(id)
        } catch (e: Exception) {
        }
    }

    // ---- Ongoing (foreground service) ------------------------------------------------------

    /** The service notification. [pct] < 0 means no progress bar (idle Receiver mode). */
    fun foreground(title: String, text: String, pct: Int, go: Int): Notification {
        val b = base(FlitApp.CH_PROGRESS, title, text, go)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
        if (pct >= 0) b.setProgress(100, pct.coerceIn(0, 100), false)
        if (Build.VERSION.SDK_INT >= 31) b.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return b.build()
    }

    fun receiverIdle(): Notification = foreground(
        FlitApp.str(R.string.notif_receiver_title), FlitApp.str(R.string.notif_receiver_text), -1, GO_RECEIVER
    )

    /** Updates the ongoing notification while a transfer runs. */
    fun progress(sending: Boolean, pct: Int) {
        val title = FlitApp.str(if (sending) R.string.notif_sending_title else R.string.notif_receiving_title)
        post(ID_FG, foreground(title, FlitApp.str(R.string.notif_progress_text, pct), pct, GO_TRANSFER))
    }

    // ---- Events -----------------------------------------------------------------------------

    fun incoming(sender: String) {
        val b = base(
            FlitApp.CH_REQUESTS, FlitApp.str(R.string.notif_incoming_title),
            FlitApp.str(R.string.notif_incoming_text, sender), GO_INCOMING
        ).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
        post(ID_INCOMING, b.build())
    }

    fun clearIncoming() = cancel(ID_INCOMING)

    /** Several Receivers were found while the app is in the background. */
    fun choose() {
        val b = base(
            FlitApp.CH_REQUESTS, FlitApp.str(R.string.notif_choose_title),
            FlitApp.str(R.string.notif_choose_text), GO_SENDER
        ).setAutoCancel(true).setCategory(Notification.CATEGORY_EVENT)
        post(ID_ALERT, b.build())
    }

    fun done(count: Int) {
        val text = FlitApp.ctx().resources.getQuantityString(R.plurals.notif_done_text, count, count)
        val b = base(FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_done_title), text, GO_TRANSFER)
            .setAutoCancel(true)
        post(ID_RESULT, b.build())
    }

    fun failed(count: Int) {
        val text = FlitApp.ctx().resources.getQuantityString(R.plurals.notif_failed_text, count, count)
        val b = base(FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_failed_title), text, GO_TRANSFER)
            .setAutoCancel(true)
        post(ID_ALERT, b.build())
    }

    fun interrupted() {
        val b = base(
            FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_interrupted_title),
            FlitApp.str(R.string.notif_interrupted_text), GO_TRANSFER
        ).setAutoCancel(true)
        post(ID_ALERT, b.build())
    }

    fun storage() {
        val b = base(
            FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_storage_title),
            FlitApp.str(R.string.notif_storage_text), GO_TRANSFER
        ).setAutoCancel(true)
        post(ID_ALERT, b.build())
    }

    fun resumed() {
        val b = base(
            FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_resumed_title),
            FlitApp.str(R.string.notif_resumed_text), GO_TRANSFER
        ).setAutoCancel(true)
        post(ID_ALERT, b.build())
    }

    fun stopped() {
        val b = base(
            FlitApp.CH_RESULTS, FlitApp.str(R.string.notif_stopped_title),
            FlitApp.str(R.string.notif_stopped_text), GO_STOPPED
        ).setAutoCancel(true)
        post(ID_ALERT, b.build())
    }
}