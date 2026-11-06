package app.flit

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.min

/**
 * Built-in audio player (portrait). A large black disk spins while playing; below it the seek bar
 * with times, then shuffle / previous / play-pause / next / playback mode. Back minimizes: audio
 * keeps playing and the in-app [MiniPlayer] takes over. Everything is driven by the shared [Audio]
 * player, so the notification and the mini-player always agree with this screen.
 */
class AudioScreen(act: MainActivity, path: String?) : Screen(act), Audio.Listener {

    private val title = Ui.label(act, "", 17f, Ui.cText, true, 1)
    private val diskFrame = FrameLayout(act)
    private val disk = DiskView(act)
    private val seek = PSlider(act)
    private val elapsed = Ui.label(act, "00:00", 12f, Ui.cText2, false, 1)
    private val total = Ui.label(act, "00:00", 12f, Ui.cText2, false, 1)
    private val shuffleBtn: ImageView
    private val playBtn: ImageView
    private val modeBtn: ImageView
    private val spin: ObjectAnimator = ObjectAnimator.ofFloat(disk, View.ROTATION, 0f, 360f)
    private var scrubbing = false
    private var alive = true
    private var playlist: PlaylistPanel? = null

    private val ticker = object : Runnable {
        override fun run() {
            tick()
            if (alive) postDelayed(this, 250L)
        }
    }

    init {
        if (path != null && Audio.current?.path != path) {
            val list = Open.siblings(path)
            Audio.start(list, list.indexOfFirst { it.path == path }.coerceAtLeast(0))
        }

        spin.duration = 8000L
        spin.repeatCount = ObjectAnimator.INFINITE
        spin.interpolator = LinearInterpolator()
        spin.start()
        spin.pause()

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        // Top bar: back, file name, playlist.
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        top.addView(iconBtn(act, Icons.BACK, act.getString(R.string.v_back)) { act.pop() }, Ui.lin(Ui.dp(48), Ui.dp(48)))
        top.addView(title, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) })
        val list = ImageView(act)
        list.setImageDrawable(PIcons.get(PIcons.LIST, Ui.cText))
        list.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
        list.background = Ui.ripple(null, 24)
        list.contentDescription = act.getString(R.string.v_playlist)
        list.setOnClickListener { openPlaylist() }
        top.addView(list, Ui.lin(Ui.dp(48), Ui.dp(48)))
        col.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))

        // The disk sits a little above the middle of the screen.
        col.addView(View(act), Ui.lin(Ui.MATCH, 0, 1f))
        diskFrame.addView(disk, FrameLayout.LayoutParams(Ui.dp(200), Ui.dp(200), Gravity.CENTER))
        col.addView(diskFrame, Ui.lin(Ui.MATCH, Ui.dp(200)))
        col.addView(View(act), Ui.lin(Ui.MATCH, 0, 1.3f))

        // Seek bar well below the disk; times under its two ends.
        col.addView(seek, Ui.lin(Ui.MATCH, Ui.dp(36)).also { it.setMargins(Ui.dp(24), 0, Ui.dp(24), 0) })
        val times = LinearLayout(act)
        times.orientation = LinearLayout.HORIZONTAL
        times.setPadding(Ui.dp(36), 0, Ui.dp(36), 0)
        times.addView(elapsed, Ui.lin(0, Ui.WRAP, 1f))
        times.addView(total, Ui.lin(Ui.WRAP, Ui.WRAP))
        col.addView(times, Ui.lin(Ui.MATCH, Ui.WRAP))

        // Controls: shuffle, previous, play/pause, next, playback mode.
        shuffleBtn = ctl(act.getString(R.string.v_shuffle), 48) { Audio.toggleShuffle() }
        val prev = ctl(act.getString(R.string.v_prev), 52) { Audio.prev() }
        prev.setImageDrawable(PIcons.get(PIcons.PREV, Ui.cText))
        playBtn = ctl(act.getString(R.string.v_play_pause), 72) { Audio.toggle() }
        val next = ctl(act.getString(R.string.v_next), 52) { Audio.next() }
        next.setImageDrawable(PIcons.get(PIcons.NEXT, Ui.cText))
        modeBtn = ctl(act.getString(R.string.v_mode), 48) { Audio.cycleMode() }
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        for (b in listOf(shuffleBtn, prev, playBtn, next, modeBtn)) {
            val box = FrameLayout(act)
            val s = if (b === playBtn) 72 else if (b === prev || b === next) 52 else 48
            box.addView(b, FrameLayout.LayoutParams(Ui.dp(s), Ui.dp(s), Gravity.CENTER))
            row.addView(box, Ui.lin(0, Ui.dp(76), 1f))
        }
        col.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(8), Ui.dp(14), Ui.dp(8), Ui.dp(44)) })
        addView(col, LayoutParams(Ui.MATCH, Ui.MATCH))

        seek.onStart = { scrubbing = true }
        seek.onChange = { f, user -> if (user) elapsed.text = clockText((f * Audio.duration).toLong()) }
        seek.onStop = {
            val d = Audio.duration
            if (d > 0L) Audio.seek((seek.progress * d).toLong())
            scrubbing = false
        }
        update()
        post(ticker)
    }

    private fun ctl(desc: String, sizeDp: Int, onClick: () -> Unit): ImageView {
        val v = ImageView(act)
        val pad = Ui.dp(if (sizeDp >= 72) 12 else 10)
        v.setPadding(pad, pad, pad, pad)
        v.contentDescription = desc
        v.background = Ui.ripple(null, sizeDp / 2)
        v.setOnClickListener { onClick() }
        return v
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val d = min(w * 0.68f, h * 0.34f).toInt().coerceAtLeast(Ui.dp(120))
        diskFrame.layoutParams = (diskFrame.layoutParams as LinearLayout.LayoutParams).also { it.height = d }
        disk.layoutParams = (disk.layoutParams as FrameLayout.LayoutParams).also {
            it.width = d
            it.height = d
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Audio.addListener(this)
        update()
    }

    override fun onDetachedFromWindow() {
        Audio.removeListener(this)
        super.onDetachedFromWindow()
    }

    override fun onDestroyed() {
        alive = false
        removeCallbacks(ticker)
        spin.cancel()
        playlist?.let { (it.parent as? android.view.ViewGroup)?.removeView(it) }
        Audio.removeListener(this)
    }

    override fun onAudio() {
        if (!alive) return
        if (!Audio.active) {
            act.pop() // stopped from the notification or after an error
            return
        }
        update()
    }

    // ---- UI state ----------------------------------------------------------------------------------

    private fun update() {
        title.text = Audio.current?.name ?: ""
        playBtn.setImageDrawable(Icons.glyph(if (Audio.wantsPlay) Icons.PAUSE else Icons.PLAY, Ui.cText))
        shuffleBtn.setImageDrawable(PIcons.get(PIcons.SHUFFLE, if (Audio.shuffle) PSlider.ACCENT else Ui.cText2))
        modeBtn.setImageDrawable(
            when (Audio.mode) {
                Audio.ONE -> PIcons.get(PIcons.REPEAT_ONE, PSlider.ACCENT)
                Audio.ALL -> PIcons.get(PIcons.REPEAT, PSlider.ACCENT)
                else -> PIcons.get(PIcons.REPEAT, Ui.cText2) // Order: plain, no repeat
            }
        )
        if (Audio.playing) {
            if (spin.isPaused) spin.resume()
        } else if (spin.isRunning && !spin.isPaused) {
            spin.pause()
        }
    }

    private fun tick() {
        if (!alive) return
        val d = Audio.duration
        total.text = clockText(d)
        if (!scrubbing) {
            elapsed.text = clockText(Audio.position)
            seek.progress = if (d > 0L) Audio.position.toFloat() / d else 0f
        }
    }

    private fun openPlaylist() {
        if (playlist != null || !Audio.active) return
        val pl = PlaylistPanel(
            act, Audio.files, Audio.index, false, false,
            { i -> Audio.jump(i) },
            { playlist = null }
        )
        playlist = pl
        pl.open(this)
    }

    // ---- The disk ----------------------------------------------------------------------------------------

    private class DiskView(c: Context) : View(c) {

        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val r = min(width, height) / 2f
            p.style = Paint.Style.FILL
            p.color = Color.BLACK
            c.drawCircle(cx, cy, r, p)
            // Grooves.
            p.style = Paint.Style.STROKE
            p.strokeWidth = Ui.dpf(1)
            p.color = Ui.alpha(Color.WHITE, 22)
            for (i in 1..5) c.drawCircle(cx, cy, r * (0.40f + 0.105f * i), p)
            // A bright arc on the rim, so the turning is visible.
            p.strokeWidth = Ui.dpf(3)
            p.color = Ui.alpha(Color.WHITE, 60)
            val inset = Ui.dpf(5)
            c.drawArc(RectF(cx - r + inset, cy - r + inset, cx + r - inset, cy + r - inset), -70f, 40f, false, p)
            // Centre label and spindle hole.
            p.style = Paint.Style.FILL
            p.color = PSlider.ACCENT
            c.drawCircle(cx, cy, r * 0.30f, p)
            p.color = Color.BLACK
            c.drawCircle(cx, cy, r * 0.05f, p)
        }
    }
}
