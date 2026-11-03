package app.flit

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Shared state between the engines, the service and the screens. Hooks are called on the main
 * thread; the Activity (Batch 3) installs [hook].
 */
object Xfer {

    interface Hook {
        /** The head of the incoming-request queue changed: show [ReceiveEngine.head]. */
        fun incoming()

        /** The request on screen is gone (cancelled, expired): dismiss its dialog. */
        fun incomingGone()

        /** A receiving session ended: show the completed screen (if files arrived) or go back. */
        fun receiveFinished(s: RxSession)

        /** A send run ended. The transfer screen closes itself. */
        fun sendFinished(r: TxRun)
    }

    @Volatile
    var hook: Hook? = null

    /** Set by the Activity: false while the app is in the background. */
    @Volatile
    var foreground = true

    /** Phase 3 attaches the RUN OUT OF STORAGE window here. */
    @Volatile
    var onSpace: ((RxSession) -> Unit)? = null

    @Volatile
    var focusRx: RxSession? = null

    @Volatile
    var focusTx: TxRun? = null

    private var ensured = false

    /** Registers the engines with the link layer (idempotent). */
    fun ensure() {
        if (ensured) return
        ensured = true
        Link.onSender = { p -> ReceiveEngine.onPeer(p) }
        Link.onReceiver = { p -> SendEngine.onPeer(p) }
    }

    fun busy(): Boolean = SendEngine.running || ReceiveEngine.busy()

    fun need(): Boolean = Rx.active || busy()

    /** Starts or stops the foreground service to match the current state. */
    fun sync() {
        FlitApp.main.post { TransferService.sync() }
    }

    fun tickPost() {
        FlitApp.main.post { tick() }
    }

    fun overall(): Float {
        val r = SendEngine.cur
        if (r != null && !r.finished) return r.progress()
        var proc = 0L
        var tot = 0L
        for (s in ReceiveEngine.sessionList()) {
            proc += s.processed()
            tot += s.bytesTotal
        }
        return if (tot > 0L) (proc.toDouble() / tot).toFloat().coerceIn(0f, 1f) else 0f
    }

    /** About once a second while a transfer runs: bubble, notification, wake lock. */
    fun tick() {
        val f = overall()
        Bubbles.progress(f)
        if (busy()) Notifs.progress(SendEngine.running, (f * 100f).toInt())
        TransferService.touch()
    }

    /** Send button: resumes the running send, or checks Wi-Fi / permissions and opens discovery. */
    fun startSend(act: MainActivity) {
        if (SendEngine.running) {
            restore(act)
            return
        }
        Perms.gate(act) { act.push(DiscoveryScreen(act)) }
    }

    /** Brings the transfer screen of the running transfer back (bubble tap, notification). */
    fun restore(act: MainActivity) {
        val r = SendEngine.cur
        if (r != null && !r.finished) {
            act.push(TransferScreen(act, null, r))
            return
        }
        val s = focusRx?.takeIf { !it.isEnded }
            ?: ReceiveEngine.sessionList().firstOrNull { !it.isEnded }
            ?: return
        act.push(TransferScreen(act, s, null))
    }
}

/**
 * Foreground service (type connectedDevice): keeps Receiver mode and running transfers alive in
 * the background. A partial wake lock and a Wi-Fi lock are held only while a transfer is active.
 */
class TransferService : Service() {

    private var wl: PowerManager.WakeLock? = null
    private var fl: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        inst = this
        starting = false
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Notifs.receiverIdle()
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifs.ID_FG, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(Notifs.ID_FG, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Xfer.need()) {
            stopSelf()
            return START_NOT_STICKY
        }
        refresh()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        locks(false)
        inst = null
        starting = false
        super.onDestroy()
    }

    /** Updates the notification and the locks to match the current state. */
    fun refresh() {
        val busy = Xfer.busy()
        locks(busy)
        val n = if (busy) {
            val sending = SendEngine.running
            val pct = (Xfer.overall() * 100f).toInt()
            Notifs.foreground(
                FlitApp.str(if (sending) R.string.notif_sending_title else R.string.notif_receiving_title),
                FlitApp.str(R.string.notif_progress_text, pct), pct, Notifs.GO_TRANSFER
            )
        } else {
            Notifs.receiverIdle()
        }
        try {
            getSystemService(android.app.NotificationManager::class.java)?.notify(Notifs.ID_FG, n)
        } catch (e: Exception) {
        }
    }

    private fun locks(on: Boolean) {
        try {
            if (on) {
                if (wl == null) {
                    wl = getSystemService(PowerManager::class.java)
                        ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "flit:transfer")
                        ?.also { it.setReferenceCounted(false) }
                }
                wl?.acquire(30L * 60L * 1000L)
                if (fl == null) {
                    val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                    else WifiManager.WIFI_MODE_FULL_HIGH_PERF
                    fl = applicationContext.getSystemService(WifiManager::class.java)
                        ?.createWifiLock(mode, "flit:wifi")
                        ?.also { it.setReferenceCounted(false) }
                }
                if (fl?.isHeld != true) fl?.acquire()
            } else {
                if (wl?.isHeld == true) wl?.release()
                if (fl?.isHeld == true) fl?.release()
            }
        } catch (e: Exception) {
        }
    }

    companion object {
        private var inst: TransferService? = null
        private var starting = false

        /** Main thread. */
        fun sync() {
            val need = Xfer.need()
            val i = inst
            if (need) {
                if (i != null) {
                    i.refresh()
                } else if (!starting) {
                    starting = true
                    try {
                        FlitApp.app.startForegroundService(Intent(FlitApp.app, TransferService::class.java))
                    } catch (e: Exception) {
                        starting = false
                    }
                }
            } else if (i != null) {
                FlitApp.app.stopService(Intent(FlitApp.app, TransferService::class.java))
            }
            // While 'starting', onStartCommand re-checks the need and stops itself if it is gone.
        }

        /** Keeps the wake lock alive while a transfer runs (it times out after 30 minutes otherwise). */
        fun touch() {
            val i = inst ?: return
            if (Xfer.busy()) i.locks(true)
        }
    }
}