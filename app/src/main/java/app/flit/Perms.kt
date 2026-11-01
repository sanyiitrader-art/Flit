package app.flit

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * Version-aware permission and system-state logic.
 *
 *  - Storage: API 28-29 legacy runtime permissions; API 30+ all-files access (a Settings screen).
 *  - Wi-Fi Direct: API 28-32 ACCESS_FINE_LOCATION (+ Location services ON); API 33+ NEARBY_WIFI_DEVICES.
 *    BLUETOOTH_* permissions are not involved on any version.
 *  - Notifications: runtime permission on API 33+ only.
 *
 * Two flows share one small state machine, driven by the Activity forwarding [onResult] and [onResume]:
 *  - [startup]: runs once per launch at the first-launch steps. Each step is attempted at most once
 *    per installation, so a denied permission is never nagged again at launch.
 *  - [gate]: runs before every discovery start and only asks for what is actually missing.
 */
object Perms {

    const val REQ_STORAGE = 71
    const val REQ_WIFI = 72
    const val REQ_NOTIF = 73

    private const val MODE_NONE = 0
    private const val MODE_STARTUP = 1
    private const val MODE_GATE = 2

    private const val W_NONE = 0
    private const val W_LOCSVC = 1
    private const val W_WIFI = 2
    private const val W_STORAGE = 3

    private var mode = MODE_NONE
    private var step = 0
    private var waiting = W_NONE
    private var gateReady: (() -> Unit)? = null

    // ---- State queries -------------------------------------------------------------

    private fun granted(p: String): Boolean =
        FlitApp.app.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun hasStorage(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(Manifest.permission.READ_EXTERNAL_STORAGE) &&
            granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    /** The runtime permission Wi-Fi Direct needs on this Android version. */
    fun wifiPerm(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.NEARBY_WIFI_DEVICES
        else Manifest.permission.ACCESS_FINE_LOCATION

    fun hasWifiPerm(): Boolean = granted(wifiPerm())

    /** True when discovery may start from the permission point of view (feature gating). */
    fun canDiscover(): Boolean = hasWifiPerm()

    /** Location services must be on for Wi-Fi Direct discovery on Android 9-12L. */
    fun needsLocationServices(): Boolean = Build.VERSION.SDK_INT <= 32

    fun locationOn(): Boolean {
        val lm = FlitApp.app.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isLocationEnabled
    }

    fun wifiOn(): Boolean {
        val wm = FlitApp.app.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return false
        return wm.isWifiEnabled
    }

    fun hasNotif(): Boolean =
        Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS)

    // ---- Standalone actions -----------------------------------------------------------

    /** Asks for storage access in the way the Android version requires. No-op when granted. */
    fun requestStorage(a: Activity) {
        if (hasStorage()) return
        if (Build.VERSION.SDK_INT >= 30) {
            askAllFiles(a, null)
        } else {
            a.requestPermissions(
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                REQ_STORAGE
            )
        }
    }

    fun openAppSettings(a: Activity) {
        launch(a, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + a.packageName)))
    }

    // ---- Startup flow --------------------------------------------------------------------

    /** First-launch sequence: storage, Location services, Wi-Fi Direct permission, notifications. */
    fun startup(a: Activity) {
        if (mode != MODE_NONE) return
        mode = MODE_STARTUP
        step = 0
        advance(a)
    }

    private fun once(k: String): Boolean {
        if (Prefs.flag("asked_$k")) return false
        Prefs.setFlag("asked_$k", true)
        return true
    }

    private fun advance(a: Activity) {
        while (mode == MODE_STARTUP) {
            when (step++) {
                0 -> if (!hasStorage() && once("storage")) {
                    if (Build.VERSION.SDK_INT >= 30) {
                        askAllFiles(a) { advance(a) }
                    } else {
                        a.requestPermissions(
                            arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ),
                            REQ_STORAGE
                        )
                    }
                    return
                }
                1 -> if (needsLocationServices() && !locationOn() && once("locsvc")) {
                    Dialogs.location(a, {
                        waiting = W_LOCSVC
                        launch(a, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                    }, { advance(a) })
                    return
                }
                2 -> if (!hasWifiPerm() && once("wifi")) {
                    a.requestPermissions(arrayOf(wifiPerm()), REQ_WIFI)
                    return
                }
                3 -> if (Build.VERSION.SDK_INT >= 33 && !hasNotif() && once("notif")) {
                    a.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
                    return
                }
                else -> mode = MODE_NONE
            }
        }
    }

    // ---- Discovery gate ------------------------------------------------------------------

    /**
     * Runs [ready] once Wi-Fi Direct can be used: permission granted, Location services on
     * (Android 9-12L) and Wi-Fi enabled. Only missing items are requested; if the user declines
     * any step the gate is cancelled and [ready] is never called.
     */
    fun gate(a: Activity, ready: () -> Unit) {
        mode = MODE_GATE
        gateReady = ready
        waiting = W_NONE
        gateStep(a)
    }

    private fun gateStep(a: Activity) {
        if (!hasWifiPerm()) {
            a.requestPermissions(arrayOf(wifiPerm()), REQ_WIFI)
            return
        }
        if (needsLocationServices() && !locationOn()) {
            Dialogs.location(a, {
                waiting = W_LOCSVC
                launch(a, Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            }, { cancel() })
            return
        }
        if (!wifiOn()) {
            Dialogs.show(
                a, a.getString(R.string.wifi_off_title), a.getString(R.string.wifi_off_msg),
                a.getString(R.string.btn_turn_on), {
                    waiting = W_WIFI
                    val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY)
                    else Intent(Settings.ACTION_WIFI_SETTINGS)
                    launch(a, i)
                },
                a.getString(R.string.dialog_cancel), { cancel() }
            )
            return
        }
        val r = gateReady
        cancel()
        r?.invoke()
    }

    private fun cancel() {
        mode = MODE_NONE
        gateReady = null
        waiting = W_NONE
    }

    // ---- Activity callbacks --------------------------------------------------------------

    /** Forward from Activity.onRequestPermissionsResult. */
    fun onResult(a: Activity, req: Int, results: IntArray) {
        val ok = results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED }
        when (req) {
            REQ_WIFI -> {
                if (mode == MODE_GATE) {
                    if (ok) {
                        gateStep(a)
                    } else {
                        cancel()
                        if (!a.shouldShowRequestPermissionRationale(wifiPerm())) deniedForever(a)
                    }
                } else if (mode == MODE_STARTUP) {
                    advance(a)
                }
            }
            REQ_STORAGE -> {
                if (mode == MODE_STARTUP) {
                    advance(a)
                } else if (!ok &&
                    !a.shouldShowRequestPermissionRationale(Manifest.permission.READ_EXTERNAL_STORAGE)
                ) {
                    deniedForever(a)
                }
            }
            REQ_NOTIF -> if (mode == MODE_STARTUP) advance(a)
        }
    }

    /** Forward from Activity.onResume: continues a flow after the user came back from Settings. */
    fun onResume(a: Activity) {
        val w = waiting
        waiting = W_NONE
        if (w == W_NONE) return
        when (mode) {
            MODE_STARTUP -> advance(a)
            MODE_GATE -> {
                val satisfied = when (w) {
                    W_LOCSVC -> locationOn()
                    W_WIFI -> wifiOn()
                    else -> true
                }
                if (satisfied) gateStep(a) else cancel()
            }
        }
    }

    // ---- Helpers -------------------------------------------------------------------------

    private fun askAllFiles(a: Activity, onNotNow: (() -> Unit)?) {
        Dialogs.show(
            a, a.getString(R.string.perm_storage_title), a.getString(R.string.perm_storage_msg_all),
            a.getString(R.string.btn_grant), {
                if (mode == MODE_STARTUP) waiting = W_STORAGE
                var i = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + a.packageName)
                )
                if (i.resolveActivity(a.packageManager) == null) {
                    i = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                }
                launch(a, i)
            },
            a.getString(R.string.btn_not_now), { onNotNow?.invoke() }
        )
    }

    private fun deniedForever(a: Activity) {
        Dialogs.show(
            a, a.getString(R.string.perm_required_title), a.getString(R.string.perm_required_msg),
            a.getString(R.string.btn_open_settings), { openAppSettings(a) },
            a.getString(R.string.btn_not_now), null
        )
    }

    private fun launch(a: Activity, i: Intent) {
        try {
            a.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            waiting = W_NONE
        }
    }
}