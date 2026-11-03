package app.flit

import android.content.Context
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Receiver discovery (10 s window). Cards: checkbox at the far left, avatar, device name. Exactly
 * one Receiver found: the request is sent automatically when the window ends. Several: select any
 * number and tap SEND TO N. Discovery avatars are generated placeholders; the real picture appears
 * on the transfer screen once connected.
 */
class DiscoveryScreen(act: MainActivity) : Screen(act), P2p.Discovery {

    private var list: List<P2p.Found> = emptyList()
    private val chosen = LinkedHashSet<String>()
    private var searching = true
    private var proceeded = false
    private var run: TxRun? = null

    private val rv = RecyclerView(act)
    private val notice = Notice(act)
    private val status = Ui.label(act, "", 13f, Ui.cText2)
    private val send = Pill(act, "", true)
    private val adapter = Ad()

    private class Row(c: Context) : LinearLayout(c) {
        val check = CheckView(c)
        val avatar = AvatarView(c)
        val name = Ui.label(c, "", 16f, Ui.cText, true, 1)

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RecyclerView.LayoutParams(Ui.MATCH, Ui.WRAP).also {
                it.setMargins(Ui.dp(12), Ui.dp(4), Ui.dp(12), Ui.dp(4))
            }
            background = Ui.card(16)
            setPadding(Ui.dp(14), Ui.dp(12), Ui.dp(14), Ui.dp(12))
            addView(check, Ui.lin(Ui.dp(24), Ui.dp(24)))
            addView(avatar, Ui.lin(Ui.dp(44), Ui.dp(44)).also { it.leftMargin = Ui.dp(14) })
            addView(name, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(14) })
        }
    }

    init {
        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL
        col.addView(topBar(act, act.getString(R.string.discovery_title)) { act.pop() }, Ui.lin(Ui.MATCH, Ui.WRAP))
        col.addView(status, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.setMargins(Ui.dp(20), 0, Ui.dp(20), Ui.dp(8)) })

        val body = FrameLayout(act)
        rv.layoutManager = LinearLayoutManager(act)
        rv.adapter = adapter
        rv.itemAnimator = null
        rv.clipToPadding = false
        rv.setPadding(0, Ui.dp(4), 0, Ui.dp(96))
        body.addView(rv, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        body.addView(notice, FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.CENTER))
        col.addView(body, Ui.lin(Ui.MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        send.textSize = 15f
        send.setPadding(Ui.dp(28), Ui.dp(15), Ui.dp(28), Ui.dp(15))
        send.visibility = GONE
        send.setOnClickListener {
            val t = list.filter { chosen.contains(it.address) }
            if (t.isNotEmpty()) proceed(t)
        }
        addView(
            send,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
                it.setMargins(0, 0, 0, Ui.dp(16))
            }
        )

        val r = SendEngine.begin(Sel.snapshot())
        if (r == null) {
            proceeded = true // nothing to cancel
            Ui.toast(act, R.string.err_p2p_unsupported)
            FlitApp.main.post { act.pop() }
        } else {
            run = r
            startSearch()
        }
    }

    private fun startSearch() {
        searching = true
        list = emptyList()
        chosen.clear()
        notice.hide()
        refresh()
        SendEngine.search(this)
    }

    private fun refresh() {
        status.text = if (searching) act.getString(R.string.discovery_searching) else ""
        adapter.notifyDataSetChanged()
        if (chosen.isNotEmpty()) {
            send.text = act.getString(R.string.send_to_count, chosen.size)
            send.visibility = VISIBLE
        } else {
            send.visibility = GONE
        }
        if (!searching && list.isEmpty()) {
            notice.set(act.getString(R.string.discovery_none), act.getString(R.string.btn_search_again)) { startSearch() }
        } else {
            notice.hide()
        }
    }

    private fun proceed(targets: List<P2p.Found>) {
        val r = run ?: return
        if (proceeded) return
        proceeded = true
        SendEngine.send(targets)
        act.pop()
        act.push(TransferScreen(act, null, r))
    }

    // ---- P2p.Discovery (main thread) --------------------------------------------------------

    override fun onFound(list: List<P2p.Found>) {
        if (proceeded) return
        this.list = list
        chosen.retainAll(list.map { it.address }.toSet())
        refresh()
    }

    override fun onDone(list: List<P2p.Found>) {
        if (proceeded) return
        searching = false
        this.list = list
        chosen.retainAll(list.map { it.address }.toSet())
        if (list.size == 1) {
            proceed(list)
            return
        }
        if (list.size > 1 && !Xfer.foreground) Notifs.choose()
        refresh()
    }

    override fun onError(code: Int) {
        if (proceeded) return
        searching = false
        Ui.toast(act, if (code == P2p.ERR_UNSUPPORTED) R.string.err_p2p_unsupported else R.string.err_connection_failed)
        refresh()
    }

    override fun onDestroyed() {
        if (!proceeded) SendEngine.cancel()
    }

    private inner class Ad : RecyclerView.Adapter<VH>() {

        override fun getItemCount(): Int = list.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val row = Row(act)
            val h = VH(row)
            row.setOnClickListener {
                val p = h.bindingAdapterPosition
                if (p != RecyclerView.NO_POSITION && p < list.size) {
                    val a = list[p].address
                    if (!chosen.remove(a)) chosen.add(a)
                    refresh()
                }
            }
            return h
        }

        override fun onBindViewHolder(h: VH, position: Int) {
            val f = list[position]
            val r = h.v as Row
            r.name.text = f.name
            r.avatar.initial = f.name
            r.check.checked = chosen.contains(f.address)
        }
    }
}