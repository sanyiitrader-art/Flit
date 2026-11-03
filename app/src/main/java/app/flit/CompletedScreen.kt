package app.flit

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Receiver's completed-transfer screen: X at the top-left, the total received size in the centre,
 * and every file of this transaction with its icon, name, size and action (Open / Play / Extract /
 * Install). Only files that arrived complete are listed.
 */
class CompletedScreen(act: MainActivity, s: RxSession) : Screen(act) {

    private val items: List<RxFile> = s.files.filter { it.state == RecvFile.DONE }
    private val px: Int by lazy { Ui.dp(64) }

    init {
        var sum = 0L
        for (f in items) sum += f.size

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.setPadding(Ui.dp(4), Ui.dp(4), Ui.dp(4), Ui.dp(4))
        top.addView(
            iconBtn(act, Icons.CLOSE, act.getString(R.string.cd_close)) { act.pop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        val total = Ui.label(act, Types.size(sum), 18f, Ui.cText, true)
        total.gravity = Gravity.CENTER
        top.addView(total, Ui.lin(0, Ui.WRAP, 1f))
        top.addView(View(act), Ui.lin(Ui.dp(48), Ui.dp(48)))
        col.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))

        val rv = RecyclerView(act)
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = Ad()
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, Ui.dp(4), 0, Ui.dp(24))
        col.addView(rv, Ui.lin(Ui.MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH = VH(RowView(act, Icons.NOTE))

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = items[position]
            val r = h.v as RowView
            r.reset()
            r.title.text = f.name
            r.sub.text = Types.size(f.size)
            val type = Types.typeOf(f.name)
            val path = f.dest.path
            when (type) {
                Types.IMAGE -> {
                    r.ph = Icons.glyph(Icons.IMAGE, Ui.cText2)
                    Thumbs.load(r.thumb, "i:$path:$px", r.ph, 18) { Thumbs.image(path, null, px) }
                }
                Types.VIDEO -> {
                    r.ph = Icons.glyph(Icons.FILM, Ui.cText2)
                    Thumbs.load(r.thumb, "v:$path:$px", r.ph, 18) { Thumbs.video(path, null, px) }
                }
                Types.APK -> {
                    r.ph = DocGlyph("APK", Ui.cText2)
                    Thumbs.load(r.thumb, "k:$path:${f.size}", r.ph, 14) { Thumbs.apkIcon(path, px) }
                }
                else -> Thumbs.icon(r.thumb, Icons.forFile(type, Types.ext(f.name)), 16)
            }
            r.action.text = act.getString(openLabel(type))
            r.action.filled = true
            r.action.restyle()
            r.action.visibility = View.VISIBLE
            r.action.setOnClickListener {
                if (type == Types.APK) installApk(act, path) else Files.open(act, path)
            }
        }
    }
}