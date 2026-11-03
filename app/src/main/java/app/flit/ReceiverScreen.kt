package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Receiver mode. Turning it on publishes this device over Wi-Fi Direct and lets it accept the
 * Group Owner's connection; turning it off stops that. Running transfers continue until they end.
 */
object Rx {

    var active = false
        private set

    private var profile = ""

    /** Returns false (and shows a message) if Wi-Fi Direct cannot be used. */
    fun set(on: Boolean): Boolean {
        if (on == active) return true
        if (on) {
            Xfer.ensure()
            if (!P2p.advertise(true)) {
                Ui.toast(FlitApp.ctx(), R.string.err_p2p_unsupported)
                return false
            }
            Link.use(true)
            active = true
            profile = sig()
        } else {
            active = false
            P2p.advertise(false)
            Link.use(false)
            ReceiveEngine.declineAll()
        }
        Bubbles.receiver(on)
        Xfer.sync()
        return true
    }

    private fun sig(): String = Prefs.name + "|" + Prefs.avatarRev

    /** Publishes the new name / avatar revision if the profile changed while discoverable. */
    fun refreshProfile() {
        if (active && sig() != profile) {
            profile = sig()
            P2p.refreshAdvertising()
        }
    }
}

/** Two-state toggle drawn from palette tokens: accent track when on. */
class ToggleView(c: Context) : View(c) {

    var on: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(Ui.dp(46), Ui.dp(28))
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat()
        p.style = Paint.Style.FILL
        p.color = if (on) Ui.cAccent else Ui.cSurface3
        r.set(0f, 0f, width.toFloat(), h)
        c.drawRoundRect(r, h / 2f, h / 2f, p)
        p.color = if (on) Ui.cOnAccent else Ui.cText2
        val rad = h / 2f - Ui.dpf(4)
        val cx = if (on) width - h / 2f else h / 2f
        c.drawCircle(cx, h / 2f, rad, p)
    }
}

/**
 * Receiver: back arrow (stops Receiver mode), minimize (keeps it active, shows the in-app bubble),
 * centred avatar with radiating waves, DISCOVERABLE, and the simultaneous-transfers toggle.
 */
class ReceiverScreen(act: MainActivity) : Screen(act) {

    private val wave: WaveView

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        top.addView(
            iconBtn(act, Icons.BACK, act.getString(R.string.cd_back)) { stop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        top.addView(View(act), Ui.lin(0, 1, 1f))
        top.addView(
            iconBtn(act, Icons.MINIMIZE, act.getString(R.string.cd_minimize)) { act.pop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        col.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))

        val middle = LinearLayout(act)
        middle.orientation = LinearLayout.VERTICAL
        middle.gravity = Gravity.CENTER
        val side = minOf(resources.displayMetrics.widthPixels - Ui.dp(48), Ui.dp(320))
        wave = WaveView(act, 0.46f)
        middle.addView(wave, Ui.lin(side, side))
        val label = Ui.label(act, act.getString(R.string.receiver_discoverable), 20f, Ui.cText, true)
        label.letterSpacing = 0.12f
        middle.addView(label, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(8) })
        col.addView(middle, Ui.lin(Ui.MATCH, 0, 1f))

        val toggle = ToggleView(act)
        toggle.on = Prefs.simultaneous
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.background = Ui.card(18)
        row.setPadding(Ui.dp(18), Ui.dp(14), Ui.dp(18), Ui.dp(14))
        row.isClickable = true
        row.isFocusable = true
        row.setOnClickListener {
            toggle.on = !toggle.on
            Prefs.simultaneous = toggle.on
        }
        row.addView(
            Ui.label(act, act.getString(R.string.receiver_simultaneous), 15f, Ui.cText, true),
            Ui.lin(0, Ui.WRAP, 1f)
        )
        row.addView(toggle, Ui.lin(Ui.WRAP, Ui.WRAP))
        col.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(16)) })

        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    /** Back arrow and hardware back: leave and stop Receiver mode. */
    private fun stop() {
        Rx.set(false)
        act.pop()
    }

    override fun onBack(): Boolean {
        stop()
        return true
    }

    override fun onShow() {
        wave.refresh()
        Rx.refreshProfile()
    }
}