package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2

/** Tab underline: a hairline across the strip with an accent segment that follows the pager. */
private class Indicator(c: Context) : View(c) {
    private var l = 0f
    private var wd = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    fun set(left: Float, width: Float) {
        l = left
        wd = width
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        p.color = Ui.cBorder
        c.drawRect(0f, height - Ui.dpf(1), width.toFloat(), height.toFloat(), p)
        p.color = Ui.cAccent
        c.drawRect(l, height - Ui.dpf(3), l + wd, height.toFloat(), p)
    }
}

/**
 * Sender: storage indicator, six synchronised tabs (History, Apps, Images, Videos, Audios,
 * Internal storage; default Apps), and the floating Send N button. All tabs share one global
 * selection ([Sel]). Tap, swipe and drag all go through the ViewPager2, so content and indicator
 * cannot disagree. The storage indicator also refreshes when files or apps change elsewhere.
 */
class SenderScreen(act: MainActivity) : Screen(act), Sel.Listener, Live {

    private val history = HistoryTab(act)
    private val apps = AppsTab(act)
    private val images = MediaTab(act, Types.IMAGE)
    private val videos = MediaTab(act, Types.VIDEO)
    private val audios = MediaTab(act, Types.AUDIO)
    private val storage = StorageTab(act)
    private val pages: List<View> = listOf(history, apps, images, videos, audios, storage)

    private val summary = Ui.label(act, "", 15f, Ui.cText, true, 1)
    private val bar = BarView(act)
    private val tabViews = ArrayList<View>()
    private val indicator = Indicator(act)
    private val pager = ViewPager2(act)
    private val send = Pill(act, "", true)
    private var storageGen = 0

    init {
        Sel.clear() // every Sender session starts with an empty selection

        val col = LinearLayout(act)
        col.orientation = LinearLayout.VERTICAL

        // Top: back arrow and storage indicator.
        val top = LinearLayout(act)
        top.orientation = LinearLayout.HORIZONTAL
        top.gravity = Gravity.CENTER_VERTICAL
        top.addView(
            iconBtn(act, Icons.BACK, act.getString(R.string.cd_back)) { act.pop() },
            Ui.lin(Ui.dp(48), Ui.dp(48))
        )
        val info = LinearLayout(act)
        info.orientation = LinearLayout.VERTICAL
        info.addView(summary, Ui.lin(Ui.MATCH, Ui.WRAP))
        info.addView(bar, Ui.lin(Ui.MATCH, Ui.dp(6)).also { it.topMargin = Ui.dp(6) })
        top.addView(info, Ui.lin(0, Ui.WRAP, 1f).also {
            it.leftMargin = Ui.dp(4)
            it.rightMargin = Ui.dp(16)
        })
        col.addView(top, Ui.lin(Ui.MATCH, Ui.WRAP))

        // Tab strip.
        val strip = FrameLayout(act)
        val row = LinearLayout(act)
        row.orientation = LinearLayout.HORIZONTAL
        val names = intArrayOf(
            R.string.tab_history, R.string.tab_apps, R.string.tab_images, R.string.tab_videos, R.string.tab_audios
        )
        for (i in 0 until 5) {
            val t = TextView(act)
            t.text = act.getString(names[i])
            t.gravity = Gravity.CENTER
            t.textSize = 12.5f
            t.maxLines = 1
            t.includeFontPadding = false
            t.background = Ui.ripple(null, 0)
            t.setOnClickListener { pager.setCurrentItem(i, true) }
            row.addView(t, Ui.lin(0, Ui.MATCH, 1.2f))
            tabViews.add(t)
        }
        val phone = ImageView(act)
        phone.setPadding(Ui.dp(11), Ui.dp(11), Ui.dp(11), Ui.dp(11))
        phone.contentDescription = act.getString(R.string.cd_storage_tab)
        phone.background = Ui.ripple(null, 0)
        phone.setOnClickListener { pager.setCurrentItem(5, true) }
        row.addView(phone, Ui.lin(0, Ui.MATCH, 0.8f))
        tabViews.add(phone)

        strip.addView(row, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))
        strip.addView(indicator, FrameLayout.LayoutParams(Ui.MATCH, Ui.dp(3), Gravity.BOTTOM))
        col.addView(strip, Ui.lin(Ui.MATCH, Ui.dp(44)))
        row.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> moveIndicator(pager.currentItem, 0f) }

        // Pager.
        pager.adapter = object : RecyclerView.Adapter<VH>() {
            override fun getItemCount(): Int = 6
            override fun getItemViewType(position: Int): Int = position
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
                val v = pages[viewType]
                (v.parent as? ViewGroup)?.removeView(v)
                v.layoutParams = ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH)
                return VH(v)
            }

            override fun onBindViewHolder(holder: VH, position: Int) {}
        }
        (pager.getChildAt(0) as? RecyclerView)?.overScrollMode = View.OVER_SCROLL_NEVER
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {
                moveIndicator(position, positionOffset)
            }

            override fun onPageSelected(position: Int) {
                styleTabs(position)
                showPage(position)
            }
        })
        pager.setCurrentItem(1, false) // default tab: Apps
        styleTabs(1)
        col.addView(pager, Ui.lin(Ui.MATCH, 0, 1f))
        addView(col, FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH))

        // Floating Send button, visible on every tab.
        send.textSize = 15f
        send.setPadding(Ui.dp(28), Ui.dp(15), Ui.dp(28), Ui.dp(15))
        send.setOnClickListener {
            // Phase 2 starts discovery here. For now only the permission/Wi-Fi gate runs.
            Perms.gate(act) { }
        }
        addView(
            send,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).also {
                it.setMargins(0, 0, Ui.dp(16), Ui.dp(16))
            }
        )
        showStorage(lastUsed, lastTotal) // last known value right away, refreshed below in the background
        updateSend()
    }

    private fun styleTabs(sel: Int) {
        for ((i, v) in tabViews.withIndex()) {
            val on = i == sel
            val color = if (on) Ui.cText else Ui.cText2
            if (v is TextView) {
                v.setTextColor(color)
                v.typeface = if (on) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            } else if (v is ImageView) {
                v.setImageDrawable(Icons.glyph(Icons.PHONE, color))
            }
        }
    }

    private fun moveIndicator(position: Int, offset: Float) {
        if (position < 0 || position >= tabViews.size) return
        val a = tabViews[position]
        val b = tabViews[minOf(position + 1, tabViews.size - 1)]
        if (a.width == 0) return
        indicator.set(
            a.left + (b.left - a.left) * offset,
            a.width + (b.width - a.width) * offset
        )
    }

    private fun showPage(i: Int) {
        when (val v = pages[i]) {
            is HistoryTab -> v.onShow()
            is AppsTab -> v.onShow()
            is MediaTab -> v.onShow()
            is StorageTab -> v.onShow()
        }
    }

    private fun showStorage(used: Long, total: Long) {
        if (total <= 0L) return
        val pr = Types.storagePair(used, total)
        summary.text = act.getString(R.string.storage_summary, pr.first, pr.second)
        bar.progress = used.toFloat() / total.toFloat()
    }

    /** Reads the storage figures off the UI thread; stale results are dropped. */
    private fun updateStorage() {
        val g = ++storageGen
        FlitApp.io.execute {
            val (used, total) = Files.storage()
            FlitApp.main.post {
                if (g == storageGen) {
                    lastUsed = used
                    lastTotal = total
                    showStorage(used, total)
                }
            }
        }
    }

    private fun updateSend() {
        val n = Sel.count
        send.text = act.getString(R.string.send_count, n)
        send.isEnabled = n > 0
    }

    override fun onChanged() {
        updateSend()
    }

    /** Files or apps changed elsewhere: refresh the storage figures while this screen is visible. */
    override fun onLive(kind: Int) {
        if (isShown) updateStorage()
    }

    override fun onShow() {
        updateStorage()
        showPage(pager.currentItem)
    }

    override fun onBack(): Boolean = pager.currentItem == 5 && storage.up()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Sel.addListener(this)
        Files.addLive(this)
        updateSend()
    }

    override fun onDetachedFromWindow() {
        Sel.removeListener(this)
        Files.removeLive(this)
        super.onDetachedFromWindow()
    }

    private companion object {
        var lastUsed = 0L
        var lastTotal = 0L
    }
}