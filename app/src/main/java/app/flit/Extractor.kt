package app.flit

import android.media.MediaScannerConnection
import android.widget.Toast
import java.io.File

/**
 * Archive extraction flow (main thread). Tapping Extract on a ZIP / 7z / RAR file shows the
 * "Extraction" confirmation; Extract starts the work on its own thread and shows the progress dialog.
 * Hide closes only the dialog (a notification with Cancel keeps showing the progress, and tapping it
 * or tapping the archive again reopens the dialog); Cancel stops the extraction and removes what was
 * written. One extraction runs at a time. A RAR5, encrypted or otherwise unsupported archive is offered
 * to other apps ("Open with").
 */
object Extractor {

    @Volatile
    private var job: ExtractJob? = null

    private var dialog: ExtractDialog? = null
    private var ticks = 0

    val running: Boolean
        get() = job?.result == ExtractJob.R_RUNNING

    val current: ExtractJob?
        get() = job

    private val tick = object : Runnable {
        override fun run() {
            val j = job ?: return
            if (j.result != ExtractJob.R_RUNNING) return
            dialog?.let { if (it.showing) it.update(j.file, j.fraction()) }
            if (++ticks % 3 == 0) BgService.update() // about once a second
            FlitApp.main.postDelayed(this, 300L)
        }
    }

    /** Extract button / file tap on an archive. */
    fun ask(a: MainActivity, path: String) {
        if (running) {
            reopen(a)
            return
        }
        if (!File(path).isFile) {
            Ui.toast(a, R.string.v_open_missing)
            return
        }
        Dialogs.extractConfirm(a) { start(a, path) }
    }

    /** Shows the progress dialog of the running extraction again (notification tap, or tapping the archive). */
    fun reopen(a: MainActivity) {
        val j = job ?: return
        if (j.result != ExtractJob.R_RUNNING) return
        if (dialog?.showing == true) return
        showDialog(a, j)
    }

    fun cancel() {
        job?.cancel?.set(true)
        dialog?.dismiss()
        dialog = null
    }

    private fun start(a: MainActivity, path: String) {
        val src = File(path)
        val j = ExtractJob(src, uniqueDir(src))
        job = j
        ticks = 0
        showDialog(a, j)
        BgService.update()
        Thread({
            Unpack.run(j)
            FlitApp.main.post { finished(j) }
        }, "flit-extract").start()
        FlitApp.main.postDelayed(tick, 300L)
    }

    private fun showDialog(a: MainActivity, j: ExtractJob) {
        dialog?.dismiss()
        val d = Dialogs.extractProgress(a, j.src.name, { cancel() }, {
            dialog?.dismiss()
            dialog = null
        })
        d.update(j.file, j.fraction())
        dialog = d
    }

    private fun finished(j: ExtractJob) {
        if (job !== j) return
        job = null
        dialog?.dismiss()
        dialog = null
        BgService.update() // removes the notification
        val c = FlitApp.ctx()
        when (j.result) {
            ExtractJob.R_OK -> {
                Toast.makeText(c, c.getString(R.string.v_extract_done, j.dest.name), Toast.LENGTH_LONG).show()
                if (j.written.isNotEmpty()) {
                    try {
                        MediaScannerConnection.scanFile(FlitApp.app, j.written.toTypedArray(), null, null)
                    } catch (e: Exception) {
                    }
                }
            }
            ExtractJob.R_CANCELLED -> Ui.toast(c, R.string.v_extract_cancelled)
            ExtractJob.R_NOSPACE -> Ui.toast(c, R.string.v_extract_nospace)
            ExtractJob.R_UNSUPPORTED -> {
                Ui.toast(c, R.string.v_extract_unsupported)
                Open.external(c, j.src.path)
            }
            else -> Ui.toast(c, R.string.v_extract_failed)
        }
    }

    /** A folder named after the archive without its extension; "(1)", "(2)" ... if that name is taken. */
    private fun uniqueDir(src: File): File {
        val dot = src.name.lastIndexOf('.')
        val base = (if (dot > 0) src.name.substring(0, dot) else src.name).trim().ifEmpty { "Extracted" }
        val parent = src.parentFile ?: File(Files.root, "Download")
        var f = File(parent, base)
        var i = 1
        while (f.exists()) {
            f = File(parent, "$base ($i)")
            i++
        }
        return f
    }
}
