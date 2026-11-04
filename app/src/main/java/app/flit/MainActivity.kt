package app.flit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.FrameLayout
import java.util.Locale

/** Base class of every full screen. Screens are plain Views swapped inside [MainActivity]. */
abstract class Screen(val act: MainActivity) : FrameLayout(act) {
    /** Visible (again): refresh data. Also called when the app resumes. */
    open fun onShow() {}

    /** Return true if the back press was handled inside the screen. */
    open fun onBack(): Boolean = false

    open fun onResult(req: Int, ok: Boolean, data: Intent?) {}

    /** The screen was popped. */
    open fun onDestroyed() {}
}

/**
 * The only Activity. Owns the screen stack, edge-to-edge window setup, permission and result
 * forwarding, the layer that holds the floating bubble, and the transfer hooks of [Xfer]
 * (transfer screen on a new incoming transfer, completed screen). Language and theme are applied
 * in attachBaseContext, so changing either simply recreates this Activity.
 */
class MainActivity : Activity(), Xfer.Hook {

    private lateinit var container: FrameLayout
    private lateinit var layer: FrameLayout
    private val stack = ArrayList<Screen>()

    private val rxStart = HashMap<RxSession, Long>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Ui.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)
        edgeToEdge()
        Xfer.ensure()
        Xfer.hook = this

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.cBg)
        container = FrameLayout(this)
        layer = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(layer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        Ui.fitSystem(root)
        setContentView(root)

        Bubbles.attach(layer, { push(ReceiverScreen(this)) }, { Xfer.restore(this) })

        push(HomeScreen(this))
        if (Prefs.flag("reopen_settings")) {
            Prefs.setFlag("reopen_settings", false)
            push(SettingsScreen(this))
        }
        if (savedInstanceState == null) {
            root.post { Perms.startup(this) }
            root.post { handleGo(intent) }
        }

        root.post {
            Files.watch()
            Files.prefetchApps()
        }
    }

    @Suppress("DEPRECATION")
    private fun edgeToEdge() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            var f = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            if (!dark) {
                f = f or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                if (Build.VERSION.SDK_INT >= 26) f = f or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
            window.decorView.systemUiVisibility = f
        }
    }

    // ---- Navigation ----------------------------------------------------------------

    fun top(): Screen? = stack.lastOrNull()

    fun push(s: Screen) {
        stack.lastOrNull()?.visibility = View.GONE
        stack.add(s)
        container.addView(s, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        syncBubble()
        s.onShow()
    }

    fun pop() {
        if (stack.size <= 1) return
        val s = stack.removeAt(stack.size - 1)
        container.removeView(s)
        s.onDestroyed()
        val top = stack[stack.size - 1]
        top.visibility = View.VISIBLE
        syncBubble()
        top.onShow()
    }

    private fun removeScreen(s: Screen) {
        val i = stack.indexOf(s)
        if (i < 0) return
        if (i == stack.size - 1) {
            pop()
            return
        }
        stack.removeAt(i)
        container.removeView(s)
        s.onDestroyed()
    }

    private fun syncBubble() {
        Bubbles.suppress(stack.lastOrNull() is ReceiverScreen)
    }

    /** Receiver button: checks Wi-Fi, Location and permissions first (only what is missing). */
    fun openReceiver() {
        if (Rx.active) {
            push(ReceiverScreen(this))
            return
        }
        Perms.gate(this) {
            if (Rx.set(true)) push(ReceiverScreen(this))
        }
    }

    // ---- Notification taps -------------------------------------------------------------

    private fun handleGo(i: Intent?) {
        when (i?.getIntExtra(Notifs.EXTRA_GO, 0)) {
            Notifs.GO_RECEIVER ->
                if (Rx.active && stack.none { it is ReceiverScreen }) push(ReceiverScreen(this))
            Notifs.GO_INCOMING -> incoming()
            Notifs.GO_TRANSFER ->
                if (Xfer.busy() && stack.none { it is TransferScreen }) Xfer.restore(this)
        }
        i?.removeExtra(Notifs.EXTRA_GO)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleGo(intent)
    }

    // ---- Xfer.Hook (main thread) -------------------------------------------------------

    /**
     * A transfer from a Sender just started (the Android Wi-Fi Direct invitation was the user's
     * confirmation): show its transfer screen.
     */
    override fun incoming() {
        val s = Xfer.focusRx ?: return
        if (isFinishing || isDestroyed || s.isEnded) return
        if (stack.any { it is TransferScreen && it.rx === s }) return
        if (!rxStart.containsKey(s)) rxStart[s] = SystemClock.elapsedRealtime()
        push(TransferScreen(this, s, null))
    }

    override fun incomingGone() {}

    /** The receiving session ended: completed screen if files arrived, otherwise just back. */
    override fun receiveFinished(s: RxSession) {
        report(s)
        for (sc in stack.toList()) if (sc is TransferScreen && sc.rx === s) removeScreen(sc)
        if (isFinishing || isDestroyed) return
        if (s.rows.any { it.state() == RecvFile.DONE }) push(CompletedScreen(this, s))
    }

    /** The send run ended: its transfer screen closes, leaving the Sender interface. */
    override fun sendFinished(r: TxRun) {
        for (sc in stack.toList()) if (sc is TransferScreen && sc.tx === r) removeScreen(sc)
    }

    /** Average speed of a finished receive (tuning panel). Includes connection set-up and pauses. */
    private fun report(s: RxSession) {
        val t0 = rxStart.remove(s) ?: return
        val ms = SystemClock.elapsedRealtime() - t0
        var bytes = 0L
        var n = 0
        for (f in s.files) {
            if (f.state == RecvFile.DONE) {
                bytes += f.size
                n++
            }
        }
        if (bytes <= 0L || ms < 500L) return
        val mbs = bytes / 1_000_000.0 / (ms / 1000.0)
        val streams = Prefs.int("t_streams", 0)
        Tuning.report(
            String.format(
                Locale.US,
                "%d files, %s in %.1f s = %.1f MB/s | chunk %d KiB, buf %d KiB, out %d, streams %s, " +
                    "sockbuf %d KiB, 5GHz %b, simultaneous %b",
                n, Types.size(bytes), ms / 1000.0, mbs,
                Tuning.chunkSize / 1024, Tuning.bufSize / 1024, Tuning.outstanding,
                if (streams == 0) "auto" else streams.toString(),
                Tuning.sockBuf / 1024, Tuning.band5, Prefs.simultaneous
            )
        )
    }

    // ---- Lifecycle and forwarding ------------------------------------------------------

    override fun onStart() {
        super.onStart()
        Xfer.foreground = true
    }

    override fun onStop() {
        Xfer.foreground = false
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        Perms.onResume(this)
        stack.lastOrNull()?.onShow()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        Perms.onResult(this, requestCode, grantResults)
        stack.lastOrNull()?.onShow()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (Perms.onActivityResult(this, requestCode, resultCode)) return
        stack.lastOrNull()?.onResult(requestCode, resultCode == RESULT_OK, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val top = stack.lastOrNull()
        if (top != null && top.onBack()) return
        if (stack.size > 1) pop() else finish()
    }

    override fun onDestroy() {
        if (Xfer.hook === this) Xfer.hook = null
        Bubbles.detach(layer)
        super.onDestroy()
    }
}