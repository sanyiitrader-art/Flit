package app.flit

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.Drawable
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Hands an APK to Android's package installer, asking for "Install unknown apps" first if needed.
 * A main APK with split parts beside it is installed together with them ([Files.install]).
 */
fun installApk(a: MainActivity, path: String) {
    if (Files.canInstall(a)) {
        if (!Files.install(a, path)) Ui.toast(a, R.string.apk_install_failed)
        return
    }
    try {
        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
    } catch (e: Exception) {
        Ui.toast(a, R.string.apk_install_failed)
    }
}

/** Label of the action button of a received file. */
fun openLabel(type: Int): Int = when (type) {
    Types.VIDEO, Types.AUDIO -> R.string.btn_play
    Types.APK -> R.string.btn_install
    Types.ARCHIVE -> R.string.btn_extract
    else -> R.string.btn_open
}

/** File card: type icon, name, size / progress, status, and a horizontal progress fill behind the content. */
class FileCard(c: Context) : LinearLayout(c) {

    val icon = ImageView(c)
    val name = Ui.label(c, "", 14f, Ui.cText, false, 1)
    val sub = Ui.label(c, "", 12f, Ui.cText2, false, 1)
    val status = Ui.label(c, "", 11f, Ui.cText2, true, 1)
    val tick = ImageView(c)
    val action = Pill(c, "", true)

    /** The item currently shown (static parts are only rebuilt when it changes). */
    var bound: Any? = null

    private var frac = 0f
    private var failed = false
    private val clip = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val normal: Drawable = Ui.card(14)
    private val bad: Drawable = Ui.ripple(Ui.shape(Ui.cSurface, 14, Ui.cText2, 1), 14)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setWillNotDraw(false)
        layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also {
            it.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
        }
        background = normal
        setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(12), Ui.dp(10))

        icon.background = Ui.shape(Ui.cSurface2, 10)
        icon.setPadding(Ui.dp(10), Ui.dp(10), Ui.dp(10), Ui.dp(10))
        addView(icon, Ui.lin(Ui.dp(44), Ui.dp(44)))

        val col = LinearLayout(c)
        col.orientation = VERTICAL
        col.addView(name, Ui.lin(Ui.MATCH, Ui.WRAP))
        col.addView(sub, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(3) })
        addView(col, Ui.lin(0, Ui.WRAP, 1f).also { it.setMargins(Ui.dp(12), 0, Ui.dp(8), 0) })

        addView(status, Ui.lin(Ui.WRAP, Ui.WRAP))
        tick.setImageDrawable(Icons.glyph(Icons.CHECK, Ui.cAccent))
        tick.visibility = GONE
        addView(tick, Ui.lin(Ui.dp(20), Ui.dp(20)).also { it.leftMargin = Ui.dp(6) })
        action.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
        action.textSize = 12f
        action.visibility = GONE
        addView(action, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(8) })
    }

    fun setFrac(f: Float) {
        if (abs(f - frac) > 0.002f) {
            frac = f
            invalidate()
        }
    }

    /** Failed look: neutral outline (no extra colour). */
    fun setFailedLook(on: Boolean) {
        if (on != failed) {
            failed = on
            background = if (on) bad else normal
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        clip.reset()
        clip.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), Ui.dpf(14), Ui.dpf(14), Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        if (frac > 0f) {
            c.save()
            c.clipPath(clip)
            fill.color = Ui.alpha(Ui.cAccent, 46)
            c.drawRect(0f, 0f, width * frac, height.toFloat(), fill)
            c.restore()
        }
    }
}

/**
 * Transfer screen for the Receiver ([rx]) or the Sender ([tx]). The X button closes the screen
 * but not the transfer (a draggable progress bubble takes over). Updates run 4 times a second
 * while the screen is visible. A split-APK app is ONE card (combined size, progress and status).
 * Receiver: Pause, or Stop + Resume while paused, Retry on failed entries, an action on received
 * ones. Sender: one control set per Receiver (chips switch between Receivers when there are several).
 */
class TransferScreen(act: MainActivity, val rx: RxSession?, val tx: TxRun?) : Screen(act) {

    private val title = Ui.label(act, "", 17f, Ui.cText, true, 1)
    private val pct = Ui.label(act, "", 13f, Ui.cText2, true)
    private val bar = BarView(act)
    private val state = Ui.label(act, "", 13f, Ui.cText2, true, 1)
    private val btnPause = Pill(act, "", true)
    private val btnStop = Pill(act, "", false)
    private val chips = LinearLayout(act)
    private val rv = RecyclerView(act)
    private val adapter = Ad()
    private val chipViews = ArrayList<TextView>()

    private var sel: TxSession? = null
    private var lastCount = -1
    private var alive = true

    private val sWait = act.getString(R.string.status_waiting)
    private val sSending = act.getString(R.string.status_sending)
    private val sSent = act.getString(R.string.status_sent)
    private val sReceiving = act.getString(R.string.status_receiving)
    private val sReceived = act.getString(R.string.status_received)
    private val sFailed = act.getString(R.string.status_failed)
    private val sExists = act.getString(R.string.status_exists)
    private val sPaused = act.getString(R.string.status_paused)
    private val sStopped = act.getString(R.string.status_stopped)
    private val sDeclined = act.getString(R.string.status_declined)

    private val ticker = object : Runnable {
        override fun run() {
            refresh()
            if (alive && isAttachedToWindow) postDelayed(this, 250L)
        }
    }

    init {
        Bubbles.transfer(false)
        if (tx != null) sel = tx.sessions.firstOrNull()

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(16), Ui.dp(4))
        top.addView(
            iconBtn(act, Icons.CLOSE, act.getString(R.string.cd_close)) { act.pop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        top.addView(title, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(4) })
        top.addView(pct, Ui.lin(Ui.WRAP, Ui.WRAP))
        col.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))
        col.addView(bar, Ui.lin(Ui.MATCH, Ui.dp(6)).also { it.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(10)) })

        if (tx != null && tx.sessions.size > 1) {
            chips.orientation = LinearLayout.HORIZONTAL
            for (s in tx.sessions) {
                val t = Ui.label(act, s.name, 13f, Ui.cText, true, 1)
                t.setPadding(Ui.dp(14), Ui.dp(8), Ui.dp(14), Ui.dp(8))
                t.setOnClickListener {
                    sel = s
                    lastCount = -1
                    refresh()
                }
                chips.addView(t, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(8) })
                chipViews.add(t)
            }
            val hs = HorizontalScrollView(act)
            hs.isHorizontalScrollBarEnabled = false
            hs.addView(chips)
            col.addView(hs, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(8)) })
        }

        val ctl = LinearLayout(act)
        ctl.orientation = LinearLayout.HORIZONTAL
        ctl.gravity = Gravity.CENTER_VERTICAL
        ctl.addView(state, Ui.lin(0, Ui.WRAP, 1f))
        btnStop.text = act.getString(R.string.btn_stop)
        btnStop.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9))
        btnStop.setOnClickListener {
            if (rx != null) rx.stop() else sel?.let { SendEngine.stop(it) }
            refresh()
        }
        btnPause.setPadding(Ui.dp(16), Ui.dp(9), Ui.dp(16), Ui.dp(9))
        btnPause.setOnClickListener {
            if (rx != null) {
                if (rx.paused) rx.resume() else rx.pause()
            } else {
                sel?.let { if (it.paused) SendEngine.resume(it) else SendEngine.pause(it) }
            }
            refresh()
        }
        ctl.addView(btnStop, Ui.lin(Ui.WRAP, Ui.WRAP).also { it.rightMargin = Ui.dp(8) })
        ctl.addView(btnPause, Ui.lin(Ui.WRAP, Ui.WRAP))
        col.addView(ctl, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(16), 0, Ui.dp(16), Ui.dp(8)) })

        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, Ui.dp(4), 0, Ui.dp(24))
        col.addView(rv, Ui.lin(Ui.MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        refresh()
    }

    private fun running(): Boolean = if (rx != null) !rx.isEnded else tx != null && !tx.finished

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alive = true
        post(ticker)
    }

    override fun onDetachedFromWindow() {
        alive = false
        removeCallbacks(ticker)
        super.onDetachedFromWindow()
    }

    /** Popped (X button, back, or the transfer ended): leave a bubble behind if the transfer goes on. */
    override fun onDestroyed() {
        if (running()) {
            Bubbles.transfer(true)
            Bubbles.progress(Xfer.overall())
        }
    }

    // ---- Refresh ------------------------------------------------------------------------------

    private fun refresh() {
        if (tx != null && tx.finished) {
            alive = false
            if (isShown) act.pop()
            return
        }
        if (rx != null && rx.isEnded) {
            alive = false
            return
        }
        val f: Float
        val paused: Boolean
        val canCtl: Boolean
        val st: String
        if (rx != null) {
            f = rx.fraction()
            paused = rx.paused
            canCtl = rx.state == RxSession.RUN
            title.text = act.getString(R.string.transfer_receiving_from, rx.name)
            st = when {
                rx.state == RxSession.WAIT -> sWait
                rx.space -> act.getString(R.string.storage_full_title)
                paused -> sPaused
                else -> ""
            }
        } else {
            val t = tx!!
            val s = sel
            f = t.progress()
            paused = s?.paused ?: false
            canCtl = s != null && s.state == TxSession.ACTIVE
            title.text = act.getString(R.string.transfer_sending_to, s?.name ?: "")
            st = when {
                s == null -> ""
                s.state == TxSession.CONNECTING -> act.getString(R.string.discovery_connecting)
                s.state == TxSession.REQUESTED -> act.getString(R.string.discovery_waiting_accept, s.name)
                s.state == TxSession.DECLINED -> sDeclined
                s.state == TxSession.STOPPED -> sStopped
                s.state == TxSession.FAILED || s.state == TxSession.LOST -> sFailed
                paused -> sPaused
                else -> ""
            }
            for ((i, v) in chipViews.withIndex()) {
                val cs = t.sessions[i]
                val on = cs === s
                v.text = cs.name + "  " + (cs.fraction() * 100f).toInt() + "%"
                v.setTextColor(if (on) Ui.cOnAccent else Ui.cText)
                v.background = if (on) Ui.shape(Ui.cAccent, 100) else Ui.shape(Ui.cSurface, 100, Ui.cBorder, 1)
            }
        }
        pct.text = act.getString(R.string.transfer_percent, (f * 100f).toInt())
        bar.progress = f
        state.text = st
        btnPause.visibility = if (canCtl) VISIBLE else GONE
        btnPause.text = act.getString(if (paused) R.string.btn_resume else R.string.btn_pause)
        btnStop.visibility = if (canCtl && paused) VISIBLE else GONE

        if (!isShown) return
        val n = adapter.itemCount
        if (n != lastCount) {
            lastCount = n
            adapter.notifyDataSetChanged()
        } else {
            for (i in 0 until rv.childCount) {
                val h = rv.getChildViewHolder(rv.getChildAt(i))
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) bindCard(h.itemView as FileCard, p)
            }
        }
    }

    // ---- Cards --------------------------------------------------------------------------------

    private fun bindStatic(c: FileCard, item: Any, fileName: String) {
        if (c.bound === item) return
        c.bound = item
        c.name.text = fileName
        val type = Types.typeOf(fileName)
        c.icon.setImageDrawable(Icons.forFile(type, Types.ext(fileName)))
    }

    private fun bindCard(c: FileCard, pos: Int) {
        if (rx != null) {
            val rows = rx.rows
            if (pos >= rows.size) return
            val row = rows[pos]
            val f = row.head
            bindStatic(c, row, f.name)
            c.tick.visibility = GONE
            c.action.visibility = GONE
            c.setFailedLook(false)
            val size = row.size()
            var frac = 0f
            when (row.state()) {
                RecvFile.ACTIVE -> {
                    val b = row.bytes()
                    c.status.text = if (rx.paused) sPaused else sReceiving
                    c.status.setTextColor(Ui.cText)
                    c.sub.text = act.getString(R.string.progress_of, Types.size(b), Types.size(size))
                    frac = if (size > 0L) b.toFloat() / size else 0f
                }
                RecvFile.DONE -> {
                    c.status.text = sReceived
                    c.status.setTextColor(Ui.cText2)
                    c.sub.text = Types.size(size)
                    c.action.text = act.getString(openLabel(Types.typeOf(f.name)))
                    c.action.visibility = VISIBLE
                }
                RecvFile.FAILED -> {
                    c.status.text = "✕ $sFailed"
                    c.status.setTextColor(Ui.cText)
                    c.sub.text = Types.size(size)
                    c.setFailedLook(true)
                    if (rx.state == RxSession.RUN) {
                        c.action.text = act.getString(R.string.btn_retry)
                        c.action.visibility = VISIBLE
                    }
                }
                RecvFile.EXISTS -> {
                    c.status.text = sExists
                    c.status.setTextColor(Ui.cText2)
                    c.sub.text = Types.size(size)
                }
                RecvFile.STOPPED -> {
                    c.status.text = sStopped
                    c.status.setTextColor(Ui.cText2)
                    c.sub.text = Types.size(size)
                }
                else -> {
                    c.status.text = sWait
                    c.status.setTextColor(Ui.cText2)
                    c.sub.text = Types.size(size)
                }
            }
            c.setFrac(frac)
            return
        }

        val s = sel ?: return
        val t = tx!!
        val files = t.files
        val rows = t.rows
        if (!s.prepared || pos >= rows.size) return
        val row = rows[pos]
        val f = files[row.start]
        bindStatic(c, row, f.name)
        c.action.visibility = GONE
        c.tick.visibility = GONE
        c.setFailedLook(false)

        var size = 0L
        var sentB = 0L
        var anyFail = false
        var anySend = false
        var allSent = true
        var allExists = true
        for (i in row.start until row.end) {
            size += files[i].size
            sentB += s.sent.get(i)
            when (s.status.get(i)) {
                TxSession.ST_FAIL -> {
                    anyFail = true
                    allSent = false
                    allExists = false
                }
                TxSession.ST_SENT -> allExists = false
                TxSession.ST_EXISTS -> {}
                TxSession.ST_SEND -> {
                    anySend = true
                    allSent = false
                    allExists = false
                }
                else -> {
                    allSent = false
                    allExists = false
                }
            }
        }
        val combined = when {
            anyFail -> TxSession.ST_FAIL
            allSent && allExists -> TxSession.ST_EXISTS
            allSent -> TxSession.ST_SENT
            anySend || sentB > 0L -> TxSession.ST_SEND
            else -> TxSession.ST_WAIT
        }
        var frac = 0f
        when (combined) {
            TxSession.ST_SEND -> {
                c.status.text = if (s.paused) sPaused else sSending
                c.status.setTextColor(Ui.cText)
                c.sub.text = act.getString(R.string.progress_of, Types.size(sentB), Types.size(size))
                frac = if (size > 0L) sentB.toFloat() / size else 0f
            }
            TxSession.ST_SENT -> {
                c.status.text = sSent
                c.status.setTextColor(Ui.cText2)
                c.sub.text = Types.size(size)
                c.tick.visibility = VISIBLE
            }
            TxSession.ST_FAIL -> {
                c.status.text = "✕ $sFailed"
                c.status.setTextColor(Ui.cText)
                c.sub.text = Types.size(size)
                c.setFailedLook(true)
            }
            TxSession.ST_EXISTS -> {
                c.status.text = sExists
                c.status.setTextColor(Ui.cText2)
                c.sub.text = Types.size(size)
            }
            else -> {
                c.status.text = sWait
                c.status.setTextColor(Ui.cText2)
                c.sub.text = Types.size(size)
            }
        }
        c.setFrac(frac)
    }

    private fun onAction(pos: Int) {
        val r = rx ?: return
        val rows = r.rows
        if (pos >= rows.size) return
        val row = rows[pos]
        when (row.state()) {
            RecvFile.FAILED -> for (m in row.members) if (m.state == RecvFile.FAILED) r.retry(m)
            RecvFile.DONE -> {
                val path = row.head.dest.path
                if (Types.typeOf(row.head.name) == Types.APK) installApk(act, path) else Files.open(act, path)
            }
        }
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int {
            if (rx != null) return rx.rows.size
            val s = sel
            val t = tx
            return if (s != null && t != null && s.prepared) t.rows.size else 0
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = FileCard(act)
            val h = VH(card)
            card.action.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION) onAction(p)
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val c = h.v as FileCard
            c.bound = null
            bindCard(c, position)
        }
    }
}