package app.flit

import android.app.Activity
import android.app.Dialog
import android.graphics.Canvas
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Shared dialogs. Custom-drawn from the palette tokens (secondary surface, hairline border),
 * no system dialog styling. Every dialog dismisses itself before running its callback.
 */
object Dialogs {

    private fun frame(a: Activity, title: String, cancelable: Boolean): Pair<Dialog, LinearLayout> {
        val d = Dialog(a, R.style.Theme_Flit_Dialog)
        d.setCancelable(cancelable)
        d.setCanceledOnTouchOutside(false)
        val col = LinearLayout(a)
        col.orientation = LinearLayout.VERTICAL
        col.background = Ui.shape(Ui.cSurface2, 20, Ui.cBorder, 1)
        col.setPadding(Ui.dp(24), Ui.dp(22), Ui.dp(24), Ui.dp(18))
        col.addView(Ui.label(a, title, 18f, Ui.cText, true), Ui.lin(Ui.MATCH, Ui.WRAP))
        d.setContentView(col)
        return Pair(d, col)
    }

    private fun present(a: Activity, d: Dialog) {
        if (a.isFinishing || a.isDestroyed) return
        d.show()
        val w = minOf(a.resources.displayMetrics.widthPixels - Ui.dp(48), Ui.dp(400))
        d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun message(a: Activity, col: LinearLayout, text: CharSequence, topDp: Int = 12) {
        val t = Ui.label(a, text, 14.5f, Ui.cText2)
        t.setLineSpacing(0f, 1.15f)
        col.addView(t, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(topDp) })
    }

    private fun buttons(a: Activity, col: LinearLayout, vararg pills: Pill) {
        val row = LinearLayout(a)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.END
        for ((i, p) in pills.withIndex()) {
            row.addView(p, Ui.lin(Ui.WRAP, Ui.WRAP).also { if (i > 0) it.leftMargin = Ui.dp(8) })
        }
        col.addView(row, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(20) })
    }

    private fun pill(a: Activity, d: Dialog, label: String, filled: Boolean, cb: (() -> Unit)?): Pill {
        val p = Pill(a, label, filled)
        p.setOnClickListener {
            d.dismiss()
            cb?.invoke()
        }
        return p
    }

    /** General message dialog. [negative] is optional. Not cancelable by back/outside tap. */
    fun show(
        a: Activity,
        title: String,
        message: CharSequence?,
        positive: String,
        onPositive: (() -> Unit)?,
        negative: String? = null,
        onNegative: (() -> Unit)? = null,
        cancelable: Boolean = false
    ) {
        val (d, col) = frame(a, title, cancelable)
        if (!message.isNullOrEmpty()) message(a, col, message)
        if (negative != null) {
            buttons(a, col, pill(a, d, negative, false, onNegative), pill(a, d, positive, true, onPositive))
        } else {
            buttons(a, col, pill(a, d, positive, true, onPositive))
        }
        present(a, d)
    }

    /** Dialog with a caller-supplied body view (used by the hidden tuning panel). */
    fun custom(
        a: Activity,
        title: String,
        body: View,
        positive: String,
        onPositive: (() -> Unit)?,
        negative: String? = null,
        onNegative: (() -> Unit)? = null
    ) {
        val (d, col) = frame(a, title, true)
        d.setCanceledOnTouchOutside(true)
        col.addView(body, Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(12) })
        if (negative != null) {
            buttons(a, col, pill(a, d, negative, false, onNegative), pill(a, d, positive, true, onPositive))
        } else {
            buttons(a, col, pill(a, d, positive, true, onPositive))
        }
        present(a, d)
    }

    /** LOCATION SERVICES explanation (Android 9-12L). */
    fun location(a: Activity, onEnable: () -> Unit, onNotNow: () -> Unit) {
        show(
            a, a.getString(R.string.perm_location_title), a.getString(R.string.perm_location_msg),
            a.getString(R.string.btn_enable_location), onEnable,
            a.getString(R.string.btn_not_now), onNotNow
        )
    }

    /** Single-choice list (Theme, Language). Picking an option dismisses and reports its index. */
    fun choose(a: Activity, title: String, options: List<String>, selected: Int, onPick: (Int) -> Unit) {
        val (d, col) = frame(a, title, true)
        d.setCanceledOnTouchOutside(true)
        for ((i, o) in options.withIndex()) {
            val row = LinearLayout(a)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            row.background = Ui.ripple(null, 12)
            row.setPadding(Ui.dp(4), 0, Ui.dp(4), 0)
            row.addView(Ui.label(a, o, 16f, Ui.cText, i == selected), Ui.lin(0, Ui.WRAP, 1f))
            row.addView(RadioView(a, i == selected), Ui.lin(Ui.dp(22), Ui.dp(22)))
            row.setOnClickListener {
                d.dismiss()
                onPick(i)
            }
            col.addView(row, Ui.lin(Ui.MATCH, Ui.dp(52)).also { if (i == 0) it.topMargin = Ui.dp(8) })
        }
        present(a, d)
    }

    /**
     * INCOMING TRANSFER request. [queued] is the number of further requests waiting.
     * Returns the Dialog so the caller can dismiss it if the request is cancelled or expires.
     */
    fun incoming(
        a: Activity,
        sender: String,
        files: Int,
        bytes: Long,
        queued: Int,
        onAccept: () -> Unit,
        onDecline: () -> Unit
    ): Dialog {
        val (d, col) = frame(a, a.getString(R.string.incoming_title), false)
        message(a, col, a.getString(R.string.incoming_message, sender))
        val count = a.resources.getQuantityString(R.plurals.files_count, files, files)
        col.addView(
            Ui.label(a, a.getString(R.string.incoming_summary, count, Types.size(bytes)), 16f, Ui.cText, true),
            Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(10) }
        )
        if (queued > 0) {
            col.addView(
                Ui.label(a, a.resources.getQuantityString(R.plurals.incoming_queue, queued, queued), 12.5f, Ui.cText2),
                Ui.lin(Ui.MATCH, Ui.WRAP).also { it.topMargin = Ui.dp(8) }
            )
        }
        buttons(
            a, col,
            pill(a, d, a.getString(R.string.btn_decline), false, onDecline),
            pill(a, d, a.getString(R.string.btn_accept), true, onAccept)
        )
        present(a, d)
        return d
    }

    /** Confirmation before anything is deleted. */
    fun confirmDelete(a: Activity, count: Int, bytes: Long, onConfirm: () -> Unit) {
        show(
            a, a.getString(R.string.delete_confirm_title),
            a.resources.getQuantityString(R.plurals.delete_confirm_msg, count, count, Types.size(bytes)),
            a.getString(R.string.dialog_delete), onConfirm,
            a.getString(R.string.dialog_cancel), null, true
        )
    }

    /** Radio indicator: accent ring and dot when selected, grey ring otherwise. */
    private class RadioView(c: android.content.Context, private val on: Boolean) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val sw = Ui.dpf(2)
            val r = minOf(width, height) / 2f - sw
            p.style = Paint.Style.STROKE
            p.strokeWidth = sw
            p.color = if (on) Ui.cAccent else Ui.cText2
            c.drawCircle(cx, cy, r, p)
            if (on) {
                p.style = Paint.Style.FILL
                p.color = Ui.cAccent
                c.drawCircle(cx, cy, r * 0.5f, p)
            }
        }
    }
}