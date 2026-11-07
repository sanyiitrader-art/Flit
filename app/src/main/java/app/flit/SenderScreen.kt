package app.flit

import android.content.Context
import android.graphics.Color
import android.media.MediaScannerConnection
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
import java.io.File

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

/** "Delete N": grey and inactive with nothing selected, red and tappable otherwise (same shape as Send). */
private class DeleteButton(c: Context) : TextView(c) {

    init {
        gravity = Gravity.CENTER
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        includeFontPadding = false
        letterSpacing = 0.04f
        setPadding(Ui.dp(28), Ui.dp(15), Ui.dp(28), Ui.dp(15))
        isClickable = true
        isFocusable = true
        restyle()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        restyle()
    }

    private fun restyle() {
        val on = isEnabled
        setTextColor(if (on) Color.WHITE else Ui.cText2)
        background = Ui.ripple(Ui.shape(if (on) Dialogs.RED else Ui.cSurface3, 100), 100)
    }
}

/**
 * Sender: storage indicator, six synchronised tabs (History, Apps, Images, Videos, Audios,
 * Internal storage; default Apps), and the floating Send N button. All tabs share one global
 * selection ([Sel]). Tap, swipe and drag all go through the ViewPager2, so content and indicator
 * cannot disagree. The storage indicator also refreshes when files or apps change elsewhere.
 * Send checks Wi-Fi / permissions, then opens receiver discovery (or returns to a running send).
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
    private val del = DeleteButton(act)
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
        send.setOnClickListener { Xfer.startSend(act) }
        addView(
            send,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.END).also {
                it.setMargins(0, 0, Ui.dp(16), Ui.dp(16))
            }
        )
        // Delete button on the same row, at the left.
        del.setOnClickListener { confirmDelete() }
        addView(
            del,
            FrameLayout.LayoutParams(Ui.WRAP, Ui.WRAP, Gravity.BOTTOM or Gravity.START).also {
                it.setMargins(Ui.dp(16), 0, 0, Ui.dp(16))
            }
        )
        showStorage(lastUsed, lastTotal) // last known value right away, refreshed below in the background
        updateSend()
    }

    // ---- Delete ----------------------------------------------------------------------------------

    /** Asks first. Installed apps in the selection cannot be deleted here, only files and folders. */
    private fun confirmDelete() {
        val files = Sel.snapshot().filter { it.key.startsWith("f:") }
        if (files.isEmpty()) {
            if (Sel.count > 0) Ui.toast(act, R.string.v_delete_apps)
            return
        }
        var size = 0L
        for (f in files) size += f.size
        Dialogs.confirmDelete(act, files.size, Types.size(size)) { deleteSelected(files) }
    }

    private fun deleteSelected(files: List<Item>) {
        val folders = Sel.selectedFolders()
        val tops = folders.filter { f -> folders.none { o -> o != f && f.startsWith("$o/") } }
        Files.pool.execute {
            var failed = 0
            val gone = ArrayList<String>()
            for (d in tops) {
                val dir = File(d)
                if (dir.path == Files.root.path || Files.isRestricted(dir)) continue
                Unpack.deleteTree(dir)
                if (dir.exists()) failed++
            }
            for (it in files) {
                val f = File(it.path)
                if (!f.exists() || f.delete()) {
                    if (gone.size < 2000) gone.add(f.path)
                } else {
                    failed++
                }
            }
            FlitApp.main.post {
                for (it in files) if (!File(it.path).exists()) Sel.remove(it.key)
                for (d in tops) if (!File(d).exists()) Sel.removeFolder(d)
                if (gone.isNotEmpty()) {
                    try {
                        MediaScannerConnection.scanFile(FlitApp.app, gone.toTypedArray(), null, null)
                    } catch (e: Exception) {
                    }
                }
                Ui.toast(act, if (failed > 0) R.string.v_delete_failed else R.string.v_deleted)
                updateStorage()
            }
        }
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
        del.text = act.getString(R.string.btn_delete) + " " + n
        del.isEnabled = n > 0
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