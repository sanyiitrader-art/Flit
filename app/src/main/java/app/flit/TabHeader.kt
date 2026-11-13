package app.flit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** Selection box of the list headers: rounded square, filled with the accent colour and a tick when on. */
class SelBox(c: Context) : View(c) {

    var checked = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val s = Ui.dpf(22)
        val x = (width - s) / 2f
        val y = (height - s) / 2f
        val r = RectF(x, y, x + s, y + s)
        p.style = Paint.Style.FILL
        p.color = if (checked) Ui.cAccent else Color.TRANSPARENT
        c.drawRoundRect(r, Ui.dpf(6), Ui.dpf(6), p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = Ui.dpf(1.5f)
        p.color = if (checked) Ui.cAccent else Ui.cText2
        c.drawRoundRect(r, Ui.dpf(6), Ui.dpf(6), p)
        if (checked) {
            p.strokeCap = Paint.Cap.ROUND
            p.strokeWidth = Ui.dpf(2.2f)
            p.color = Ui.cOnAccent
            c.drawLine(x + s * 0.27f, y + s * 0.53f, x + s * 0.44f, y + s * 0.70f, p)
            c.drawLine(x + s * 0.44f, y + s * 0.70f, x + s * 0.74f, y + s * 0.32f, p)
        }
    }
}

/**
 * Header of every list tab except History: a search box (with a sort button when [withSort]) and,
 * under it, a grey sheet with the tab's name and a select-all box ([checkLeft] puts the box at the left
 * end of the sheet, for the Internal storage tab). The whole header hides as soon as the list is scrolled
 * up and comes back as soon as it is scrolled down. Sort: New, Old, Size, Name (a tick marks the current one).
 */
class TabHeader(c: Context, withSort: Boolean, private val checkLeft: Boolean) : LinearLayout(c) {

    interface Host {
        fun onQuery(q: String)
        fun onSort(mode: Int)
        fun onSelectAll()
    }

    var host: Host? = null
    var sort = SORT_NEW
        private set

    val check = SelBox(c)
    val sheet = LinearLayout(c)
    private val title = Ui.label(c, "", 15f, Ui.cText, true, 1)
    private val box = EditText(c)
    private var hidden = false
    private var popup: PopupWindow? = null
    private val delayed = Runnable { host?.onQuery(box.text.toString()) }

    init {
        orientation = VERTICAL
        setBackgroundColor(Ui.cBg)

        val bar = LinearLayout(c)
        bar.orientation = HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setPadding(Ui.dp(12), Ui.dp(8), Ui.dp(8), Ui.dp(8))
        box.hint = c.getString(R.string.t_search)
        box.setHintTextColor(Ui.cText2)
        box.setTextColor(Ui.cText)
        box.textSize = 15f
        box.isSingleLine = true
        box.inputType = InputType.TYPE_CLASS_TEXT
        box.imeOptions = EditorInfo.IME_ACTION_SEARCH
        box.setPadding(Ui.dp(16), 0, Ui.dp(16), 0)
        box.background = Ui.shape(Ui.cSurface, 100, Ui.cBorder, 1)
        box.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, n: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, n: Int) {}
            override fun afterTextChanged(s: Editable?) {
                removeCallbacks(delayed)
                postDelayed(delayed, 180L)
            }
        })
        bar.addView(box, Ui.lin(0, Ui.dp(40), 1f))
        if (withSort) {
            val b = ImageView(c)
            b.setImageDrawable(PIcons.get(PIcons.SORT, Ui.cText))
            b.setPadding(Ui.dp(12), Ui.dp(12), Ui.dp(12), Ui.dp(12))
            b.background = Ui.ripple(null, 24)
            b.contentDescription = c.getString(R.string.t_sort)
            b.setOnClickListener { showMenu(b) }
            bar.addView(b, Ui.lin(Ui.dp(48), Ui.dp(48)).also { it.leftMargin = Ui.dp(4) })
        }
        addView(bar, Ui.lin(Ui.MATCH, Ui.dp(56)))

        sheet.orientation = HORIZONTAL
        sheet.gravity = Gravity.CENTER_VERTICAL
        sheet.setBackgroundColor(Ui.cSurface2)
        sheet.setPadding(Ui.dp(8), 0, Ui.dp(8), 0)
        val hit = FrameLayout(c)
        hit.addView(check, FrameLayout.LayoutParams(Ui.dp(44), Ui.dp(44), Gravity.CENTER))
        hit.setOnClickListener { host?.onSelectAll() }
        if (checkLeft) {
            sheet.addView(hit, Ui.lin(Ui.dp(44), Ui.dp(44)))
        } else {
            sheet.addView(title, Ui.lin(0, Ui.WRAP, 1f).also { it.leftMargin = Ui.dp(8) })
            sheet.addView(hit, Ui.lin(Ui.dp(44), Ui.dp(44)))
        }
        addView(sheet, Ui.lin(Ui.MATCH, Ui.dp(44)))
    }

    fun setTitle(t: String) {
        title.text = t
    }

    /** Makes room for the header above [rv] and hides / shows it with the scrolling. */
    fun attach(rv: RecyclerView) {
        rv.clipToPadding = false
        rv.setPadding(rv.paddingLeft, rv.paddingTop + HEIGHT(), rv.paddingRight, rv.paddingBottom)
        rv.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(r: RecyclerView, dx: Int, dy: Int) {
                if (dy > 2) slide(true) else if (dy < -2 || !r.canScrollVertically(-1)) slide(false)
            }
        })
    }

    private fun slide(hide: Boolean) {
        if (hide == hidden) return
        hidden = hide
        animate().cancel()
        animate().translationY(if (hide) -HEIGHT().toFloat() else 0f).setDuration(180L).start()
    }

    private fun showMenu(anchor: View) {
        val c = context
        val m = LinearLayout(c)
        m.orientation = VERTICAL
        m.background = Ui.shape(Ui.cSurface, 12, Ui.cBorder, 1)
        m.setPadding(Ui.dp(4), Ui.dp(6), Ui.dp(4), Ui.dp(6))
        val names = intArrayOf(R.string.t_sort_new, R.string.t_sort_old, R.string.t_sort_size, R.string.t_sort_name)
        val pw = PopupWindow(m, Ui.WRAP, Ui.WRAP, true)
        pw.elevation = Ui.dpf(8)
        for (i in names.indices) {
            val r = LinearLayout(c)
            r.orientation = HORIZONTAL
            r.gravity = Gravity.CENTER_VERTICAL
            r.setPadding(Ui.dp(8), Ui.dp(10), Ui.dp(22), Ui.dp(10))
            r.background = Ui.ripple(null, 8)
            val tick = ImageView(c)
            if (i == sort) tick.setImageDrawable(Icons.glyph(Icons.CHECK, Ui.cAccent))
            r.addView(tick, Ui.lin(Ui.dp(22), Ui.dp(22)))
            r.addView(Ui.label(c, c.getString(names[i]), 15f, Ui.cText, i == sort), Ui.lin(Ui.WRAP, Ui.WRAP).also { it.leftMargin = Ui.dp(10) })
            r.setOnClickListener {
                sort = i
                pw.dismiss()
                host?.onSort(i)
            }
            m.addView(r, Ui.lin(Ui.WRAP, Ui.WRAP))
        }
        popup = pw
        pw.showAsDropDown(anchor, 0, 0, Gravity.END)
    }

    companion object {
        const val SORT_NEW = 0
        const val SORT_OLD = 1
        const val SORT_SIZE = 2
        const val SORT_NAME = 3

        /** Search row (56 dp) + sheet row (44 dp). */
        fun HEIGHT(): Int = Ui.dp(100)
    }
}
