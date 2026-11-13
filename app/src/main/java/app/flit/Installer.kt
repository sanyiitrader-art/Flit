package app.flit

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import java.io.File

/**
 * "Install unknown apps" handling. When an install needs that permission, the file is remembered, the
 * permission screen opens, and when the user comes back (even if Android restarted Flit meanwhile) the
 * install simply carries on.
 */
object Installer {

    private const val KEY = "pending_install"

    fun askPermission(c: Context, path: String): Boolean {
        Prefs.putStr(KEY, path)
        return try {
            val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + c.packageName))
            if (c !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            c.startActivity(i)
            true
        } catch (e: Exception) {
            Prefs.putStr(KEY, "")
            false
        }
    }

    /** Called when the app comes to the front: carries on with an install that was waiting for the permission. */
    fun resume(a: Activity) {
        val p = Prefs.str(KEY)
        if (p.isEmpty()) return
        Prefs.putStr(KEY, "")
        if (a.packageManager.canRequestPackageInstalls() && File(p).isFile) {
            if (!Files.install(a, p)) Ui.toast(a, R.string.apk_install_failed)
        }
    }
}
