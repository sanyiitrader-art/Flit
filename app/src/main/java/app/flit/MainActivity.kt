package app.flit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout

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
 * forwarding, and the layer that holds the floating bubble. Language and theme are applied in
 * attachBaseContext, so changing either simply recreates this Activity.
 */
class MainActivity : Activity() {

    private lateinit var container: FrameLayout
    private lateinit var layer: FrameLayout
    private val stack = ArrayList<Screen>()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Ui.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ui.load(this)
        edgeToEdge()

        val root = FrameLayout(this)
        root.setBackgroundColor(Ui.cBg)
        container = FrameLayout(this)
        layer = FrameLayout(this)
        root.addView(container, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        root.addView(layer, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        Ui.fitSystem(root)
        setContentView(root)

        Bubbles.attach(layer, { push(ReceiverScreen(this)) }, { })

        push(HomeScreen(this))
        if (Prefs.flag("reopen_settings")) {
            Prefs.setFlag("reopen_settings", false)
            push(SettingsScreen(this))
        }
        if (savedInstanceState == null) root.post { Perms.startup(this) }
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
            push(ReceiverScreen(this))
            Rx.set(true)
        }
    }

    // ---- Lifecycle and forwarding ------------------------------------------------------

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
        stack.lastOrNull()?.onResult(requestCode, resultCode == RESULT_OK, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        val top = stack.lastOrNull()
        if (top != null && top.onBack()) return
        if (stack.size > 1) pop() else finish()
    }

    override fun onDestroy() {
        Bubbles.detach(layer)
        super.onDestroy()
    }
}