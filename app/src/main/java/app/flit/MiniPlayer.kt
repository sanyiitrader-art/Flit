package app.flit

import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * Small floating bar at the bottom-left of the app for audio that is playing while another screen
 * is open: restore (open the full player), close (stops the audio), next, and play/pause. It is an
 * in-app view, not a system overlay. Hidden while the full player or a full-screen viewer is open.
 */
class MiniPlayer(private val act: MainActivity) : LinearLayout(act), Audio.Listener {

    private val play = ImageView(act)
    private var covered = false

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Ui.shape(Ui.cSurface, 16, Ui.cBorder, 1)
        elevation = Ui.dpf(6)
        setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        visibility = GONE

        addView(
            button(Icons.glyph(Icons.UP, Ui.cText), 52, 14, act.getString(R.string.v_restore)) {
                if (act.top() !is AudioScreen) act.push(AudioScreen(act, null))
            },
            Ui.lin(Ui.dp(52), Ui.dp(52))
        )
        addView(
            button(Icons.glyph(Icons.CLOSE, Ui.cText), 40, 9, act.getString(R.string.v_close)) { Audio.stop() },
            Ui.lin(Ui.dp(40), Ui.dp(40)).also { it.leftMargin = Ui.dp(2) }
        )
        addView(
            button(PIcons.get(PIcons.NEXT, Ui.cText), 40, 9, act.getString(R.string.v_next)) { Audio.next() },
            Ui.lin(Ui.dp(40), Ui.dp(40))
        )
        play.setPadding(Ui.dp(9), Ui.dp(9), Ui.dp(9), Ui.dp(9))
        play.background = Ui.ripple(null, 20)
        play.contentDescription = act.getString(R.string.v_play_pause)
        play.setOnClickListener { Audio.toggle() }
        addView(play, Ui.lin(Ui.dp(40), Ui.dp(40)))
    }

    private fun button(d: android.graphics.drawable.Drawable, sizeDp: Int, padDp: Int, desc: String, onClick: () -> Unit): ImageView {
        val v = ImageView(act)
        v.setImageDrawable(d)
        v.setPadding(Ui.dp(padDp), Ui.dp(padDp), Ui.dp(padDp), Ui.dp(padDp))
        v.background = Ui.ripple(null, sizeDp / 2)
        v.contentDescription = desc
        v.setOnClickListener { onClick() }
        return v
    }

    /** Called by the Activity whenever the top screen changes. */
    fun sync(top: Screen?) {
        covered = top is AudioScreen || top is VideoScreen || top is ImageScreen || top is CropScreen
        refresh()
    }

    private fun refresh() {
        visibility = if (Audio.active && !covered) VISIBLE else GONE
        play.setImageDrawable(Icons.glyph(if (Audio.wantsPlay) Icons.PAUSE else Icons.PLAY, Ui.cText))
    }

    override fun onAudio() = refresh()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Audio.addListener(this)
        refresh()
    }

    override fun onDetachedFromWindow() {
        Audio.removeListener(this)
        super.onDetachedFromWindow()
    }
}
