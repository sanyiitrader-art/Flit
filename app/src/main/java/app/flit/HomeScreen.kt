package app.flit

import android.content.Context
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout

/** Round icon button (48dp touch area is set by the caller's layout params). */
fun iconBtn(c: Context, kind: Int, desc: String, onClick: () -> Unit): ImageView {
    val v = ImageView(c)
    v.setImageDrawable(Icons.glyph(kind, Ui.cText))
    v.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
    v.background = Ui.ripple(null, 24)
    v.contentDescription = desc
    v.setOnClickListener { onClick() }
    return v
}

/** Title bar with a back arrow, shared by Settings and Profile. */
fun topBar(c: Context, title: String, onBack: () -> Unit): LinearLayout {
    val bar = LinearLayout(c)
    bar.orientation = LinearLayout.HORIZONTAL
    bar.gravity = Gravity.CENTER_VERTICAL
    bar.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(12), Ui.dp(4))
    bar.addView(iconBtn(c, Icons.BACK, c.getString(R.string.cd_back), onBack), Ui.lin(Ui.dp(48), Ui.dp(48)))
    bar.addView(
        Ui.label(c, title, 20f, Ui.cText, true),
        Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) }
    )
    return bar
}

/** Large rounded home button. Filled = accent (primary action). */
private class BigButton(c: Context, kind: Int, text: String, filled: Boolean) : LinearLayout(c) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        val fg = if (filled) Ui.cOnAccent else Ui.cText
        background = if (filled) Ui.ripple(Ui.shape(Ui.cAccent, 24), 24)
        else Ui.ripple(Ui.shape(Ui.cSurface, 24, Ui.cBorder, 1), 24)
        addView(Icons.view(c, kind, 36, fg))
        val t = Ui.label(c, text, 20f, fg, true)
        t.letterSpacing = 0.04f
        addView(t, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.topMargin = Ui.dp(10) })
    }
}

/** Initial screen: Sender, Receiver and the Settings icon. Nothing else. */
class HomeScreen(act: MainActivity) : Screen(act) {

    init {
        val w = minOf(resources.displayMetrics.widthPixels - Ui.dp(64), Ui.dp(360))

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER

        val sender = BigButton(act, Icons.UP, act.getString(R.string.home_sender), true)
        sender.setOnClickListener { act.push(SenderScreen(act)) }
        col.addView(sender, Ui.lin(w, Ui.dp(128)))

        val receiver = BigButton(act, Icons.DOWN, act.getString(R.string.home_receiver), false)
        receiver.setOnClickListener { act.openReceiver() }
        col.addView(receiver, Ui.lin(w, Ui.dp(128)).also { it.topMargin = Ui.dp(16) })

        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        val settings = iconBtn(act, Icons.SETTINGS, act.getString(R.string.cd_settings)) {
            act.push(SettingsScreen(act))
        }
        addView(
            settings,
            FrameLayout.LayoutParams(Ui.dp(48), Ui.dp(48), Gravity.TOP or Gravity.END).also {
                it.setMargins(0, Ui.dp(4), Ui.dp(4), 0)
            }
        )
    }
}