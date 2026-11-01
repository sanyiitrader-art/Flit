package app.flit

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File

/**
 * History tab: a tap-only Received / Sent switch (default Received) above a card list.
 * Entries are independent of the physical file: a missing file is greyed out and gets a Clear
 * button that removes only that entry. Entries cannot be selected or deleted otherwise.
 */
class HistoryTab(c: Context) : LinearLayout(c) {

    private class Row(val h: Hist, val exists: Boolean, val installed: Boolean)

    private var dir = Db.RECEIVED
    private var rows: MutableList<Row> = ArrayList()
    private var gen = 0

    private val rv = RecyclerView(c)
    private val empty = Notice(c)
    private val adapter = Ad()
    private val segReceived: TextView
    private val segSent: TextView
    private val px: Int by lazy { Ui.dp(96) }

    init {
        orientation = VERTICAL

        val bar = LinearLayout(c)
        bar.orientation = HORIZONTAL
        bar.background = Ui.shape(Ui.cSurface, 100, Ui.cBorder, 1)
        bar.setPadding(Ui.dp(3), Ui.dp(3), Ui.dp(3), Ui.dp(3))
        segReceived = seg(c.getString(R.string.hist_received), Db.RECEIVED)
        segSent = seg(c.getString(R.string.hist_sent), Db.SENT)
        bar.addView(segReceived, Ui.lin(0, Ui.dp(36), 1f))
        bar.addView(segSent, Ui.lin(0, Ui.dp(36), 1f))
        addView(bar, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(12), Ui.dp(8), Ui.dp(12), Ui.dp(4)) })

        val body = FrameLayout(c)
        rv.layoutManager = LinearLayoutManager(c)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, Ui.dp(4), 0, Ui.dp(96))
        body.addView(rv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        body.addView(empty, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        addView(body, Ui.lin(Ui.MATCH, 0, 1f))

        styleSegments()
    }

    private fun seg(label: String, d: Int): TextView {
        val t = TextView(context)
        t.text = label
        t.gravity = Gravity.CENTER
        t.textSize = 13f
        t.includeFontPadding = false
        t.setOnClickListener {
            if (dir != d) {
                dir = d
                styleSegments()
                load()
            }
        }
        return t
    }

    private fun styleSegments() {
        for ((t, d) in listOf(Pair(segReceived, Db.RECEIVED), Pair(segSent, Db.SENT))) {
            val on = dir == d
            t.setTextColor(if (on) Ui.cOnAccent else Ui.cText2)
            t.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            t.background = if (on) Ui.shape(Ui.cAccent, 100) else null
        }
    }

    /** Called by the Sender screen whenever this tab becomes visible: history changes after transfers. */
    fun onShow() {
        load()
    }

    private fun load() {
        val d = dir
        val g = ++gen
        Db.async({
            val list = Db.history(d)
            val out = ArrayList<Row>(list.size)
            for (h in list) {
                val ok = File(h.path).exists()
                var installed = false
                if (h.type == Types.APK) {
                    var pkg = h.pkg
                    var vc = h.vcode
                    if (pkg.isEmpty() && ok) {
                        val m = Files.apkMeta(h.path)
                        if (m != null) {
                            pkg = m.first
                            vc = m.second
                        }
                    }
                    if (pkg.isNotEmpty()) installed = Files.installedVersion(pkg) >= vc
                }
                out.add(Row(h, ok, installed))
            }
            out
        }, { list ->
            if (g == gen && d == dir) {
                rows = list
                adapter.notifyDataSetChanged()
                updateEmpty()
            }
        })
    }

    private fun updateEmpty() {
        if (rows.isEmpty()) empty.set(context.getString(R.string.hist_empty)) else empty.hide()
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = rows.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(RowView(context, Icons.NOTE))

        override fun onBindViewHolder(h: VH, position: Int) {
            val r = h.v as RowView
            val row = rows[position]
            val e = row.h
            r.reset()
            r.title.text = e.name
            r.sub.text = Types.size(e.size)

            val ext = Types.ext(e.name)
            val exists = row.exists

            when {
                !exists -> Thumbs.icon(r.thumb, Icons.forFile(e.type, ext), 16)

                e.type == Types.IMAGE -> {
                    r.ph = Icons.glyph(Icons.IMAGE, Ui.cText2)
                    Thumbs.load(r.thumb, "i:${e.path}:$px", r.ph, 18) { Thumbs.image(e.path, null, px) }
                }

                e.type == Types.VIDEO -> {
                    r.ph = Icons.glyph(Icons.FILM, Ui.cText2)
                    Thumbs.load(r.thumb, "v:${e.path}:$px", r.ph, 18) { Thumbs.video(e.path, null, px) }
                    r.chip.tag = e.path
                    Thumbs.duration(e.path) { d ->
                        if (r.chip.tag == e.path && d > 0) {
                            r.chip.text = Types.duration(d)
                            r.chip.visibility = View.VISIBLE
                        }
                    }
                }

                e.type == Types.AUDIO -> {
                    r.ph = Icons.glyph(Icons.NOTE, Ui.cText2)
                    Thumbs.load(r.thumb, "a:${e.path}:$px", r.ph, 18) { Thumbs.audio(e.path, null, px) }
                    r.sub.tag = e.path
                    Thumbs.duration(e.path) { d ->
                        if (r.sub.tag == e.path && d > 0) {
                            r.sub.text = Types.size(e.size) + " · " + Types.duration(d)
                        }
                    }
                }

                e.type == Types.APK -> {
                    r.ph = DocGlyph("APK", Ui.cText2)
                    Thumbs.load(r.thumb, "k:${e.path}:${e.size}", r.ph, 14) { Thumbs.apkIcon(e.path, px) }
                }

                else -> Thumbs.icon(r.thumb, Icons.forFile(e.type, ext), 16)
            }

            if (!exists) {
                r.thumbBox.alpha = 0.45f
                r.col.alpha = 0.45f
                r.action.text = context.getString(R.string.btn_clear)
                r.action.filled = false
                r.action.restyle()
                r.action.visibility = View.VISIBLE
                r.action.setOnClickListener {
                    val p = h.bindingAdapterPosition
                    if (p != RecyclerView.NO_POSITION) {
                        Db.post { Db.deleteHistory(e.id) }
                        rows.removeAt(p)
                        notifyItemRemoved(p)
                        updateEmpty()
                    }
                }
                return
            }

            if (e.type == Types.APK && row.installed) {
                r.badge.text = context.getString(R.string.status_installed)
                r.badge.visibility = View.VISIBLE
                return
            }

            val label = when (e.type) {
                Types.VIDEO, Types.AUDIO -> R.string.btn_play
                Types.APK -> R.string.btn_install
                else -> R.string.btn_open
            }
            r.action.text = context.getString(label)
            r.action.filled = true
            r.action.restyle()
            r.action.visibility = View.VISIBLE
            r.action.setOnClickListener { Files.open(context, e.path) }
        }
    }
}