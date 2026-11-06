package app.flit

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import android.util.Log

/**
 * Wi-Fi Direct (Wi-Fi P2P) wrapper. Everything runs on the main thread.
 *
 *  - Receiver: [advertise] publishes a DNS-SD service "_flit._tcp" whose TXT record carries the
 *    profile name, device id and avatar revision.
 *  - Sender: [discover] looks for that service for a fixed window and reports the devices found.
 *    [connect] invites one Receiver with Group Owner intent 15 (Receivers keep the default), so the
 *    Sender normally becomes the Group Owner and can serve several Receivers in one group.
 *  - Group events go to [Link]: the P2P client dials the Group Owner over TCP; the Group Owner
 *    starts its server. The group's network name and addresses are never assumed.
 *
 * Callers pair [acquire] / [release]. Permissions are checked by [Perms] beforehand; a missing
 * permission here is reported instead of crashing. Diagnostics use the log tag "FlitP2p".
 */
@SuppressLint("MissingPermission")
object P2p {

    const val ERR_UNSUPPORTED = 1
    const val ERR_PERMISSION = 2
    const val ERR_FAILED = 3

    private const val TAG = "FlitP2p"
    private const val SERVICE = "_flit._tcp"
    private const val READVERTISE_MS = 75_000L
    private const val LOSS_CONFIRM_MS = 2500L
    private const val PEER_WAIT_POLLS = 14

    class Found(val address: String, val id: String, val name: String, val rev: Int)

    interface Discovery {
        fun onFound(list: List<Found>)
        fun onDone(list: List<Found>)
        fun onError(code: Int)
    }

    private val h = FlitApp.main
    private var mgr: WifiP2pManager? = null
    private var ch: WifiP2pManager.Channel? = null
    private var users = 0
    private var registered = false
    private var adv = false
    private var groupUp = false

    @Volatile
    var enabled = true
        private set

    /** Why the last [connect] failed (for diagnostics; empty if it succeeded). */
    @Volatile
    var lastFail = ""
        private set

    private var disc: Discovery? = null
    private val found = LinkedHashMap<String, Found>()
    private val miss = HashMap<String, Int>()
    private val timers = ArrayList<Runnable>()
    private val lossCheck = Runnable { verifyLoss() }

    private val noop = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {}
        override fun onFailure(reason: Int) {}
    }

    val supported: Boolean
        get() = FlitApp.app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)

    fun why(r: Int): String = when (r) {
        WifiP2pManager.ERROR -> "ERROR"
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.NO_SERVICE_REQUESTS -> "NO_SERVICE_REQUESTS"
        else -> "code $r"
    }

    /** Device addresses are logged shortened. */
    fun mask(a: String?): String = if (a == null) "null" else if (a.length > 5) "..." + a.takeLast(5) else a

    // ---- Lifecycle -----------------------------------------------------------------------

    /** Returns false if Wi-Fi Direct cannot be used on this device. */
    fun acquire(): Boolean {
        if (!supported) {
            Log.w(TAG, "acquire: Wi-Fi Direct not supported on this device")
            return false
        }
        users++
        if (users == 1) {
            mgr = FlitApp.app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            newChannel()
            val f = IntentFilter()
            f.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            f.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            f.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    FlitApp.app.registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    FlitApp.app.registerReceiver(rx, f)
                }
                registered = true
            } catch (e: Exception) {
                Log.w(TAG, "acquire: registerReceiver failed: ${e.message}")
            }
            Log.d(TAG, "acquire: manager=${mgr != null} channel=${ch != null}")
        }
        return mgr != null && ch != null
    }

    fun release() {
        if (users <= 0) return
        users--
        if (users > 0) return
        Log.d(TAG, "release: closing channel")
        stopDiscovery()
        adv = false
        clearTimers()
        h.removeCallbacks(lossCheck)
        if (registered) {
            try {
                FlitApp.app.unregisterReceiver(rx)
            } catch (e: Exception) {
            }
            registered = false
        }
        try {
            ch?.close()
        } catch (e: Exception) {
        }
        ch = null
        mgr = null
        groupUp = false
    }

    private fun newChannel() {
        val m = mgr ?: return
        ch = m.initialize(FlitApp.app, Looper.getMainLooper()) {
            // Channel lost (the Wi-Fi stack restarted): open a new one shortly.
            Log.w(TAG, "channel lost, reopening")
            ch = null
            h.postDelayed({
                if (users > 0) {
                    newChannel()
                    if (adv) startAdvertising()
                }
            }, 1000L)
        }
    }

    private fun clearTimers() {
        for (t in timers) h.removeCallbacks(t)
        timers.clear()
    }

    private fun later(ms: Long, r: Runnable) {
        timers.add(r)
        h.postDelayed(r, ms)
    }

    private fun step(f: () -> Unit) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = f()
        override fun onFailure(reason: Int) {
            Log.d(TAG, "setup step returned ${why(reason)}, continuing")
            f()
        }
    }

    // ---- Broadcasts ----------------------------------------------------------------------

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val on = i.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    val was = enabled
                    enabled = on
                    Log.d(TAG, "p2p state: enabled=$on")
                    if (on && !was && adv) startAdvertising()
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> onPeers()
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> onConnection()
            }
        }
    }

    private fun onConnection() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestConnectionInfo(c) { info ->
                if (info != null && info.groupFormed) {
                    h.removeCallbacks(lossCheck)
                    if (!groupUp) Log.d(TAG, "group formed")
                    groupUp = true
                    Log.d(
                        TAG,
                        "connection info: owner=${info.isGroupOwner} go=${info.groupOwnerAddress?.hostAddress}"
                    )
                    logGroup()
                    Link.onGroup(info)
                } else {
                    Log.d(TAG, "connection changed: no group (groupUp=$groupUp)")
                    // A single "no group" broadcast can be transient while a group forms: confirm first.
                    if (groupUp) {
                        h.removeCallbacks(lossCheck)
                        h.postDelayed(lossCheck, LOSS_CONFIRM_MS)
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "requestConnectionInfo: permission denied")
        }
    }

    private fun verifyLoss() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestConnectionInfo(c) { info ->
                if (info != null && info.groupFormed) {
                    Log.d(TAG, "loss check: group is still up")
                } else if (groupUp) {
                    Log.w(TAG, "group lost (confirmed)")
                    groupUp = false
                    Link.onGroupLost()
                    // Some devices drop their local service while in a group: publish it again.
                    if (adv) later(1500L, Runnable { if (adv) startAdvertising() })
                }
            }
        } catch (e: SecurityException) {
        }
    }

    private fun logGroup() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null) {
                    Log.d(TAG, "group: name=${g.networkName} owner=${g.isGroupOwner} clients=${g.clientList.size}")
                } else {
                    Log.d(TAG, "group: none")
                }
            }
        } catch (e: SecurityException) {
        }
    }

    private fun onPeers() {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.requestPeers(c) { list ->
                val l = disc
                if (l != null && list != null) {
                    val addrs = HashSet<String>()
                    for (d in list.deviceList) addrs.add(d.deviceAddress)
                    var changed = false
                    val it = found.keys.iterator()
                    while (it.hasNext()) {
                        val a = it.next()
                        if (addrs.contains(a)) {
                            miss.remove(a)
                        } else {
                            // Gone from the peer list twice in a row: it stopped advertising.
                            val k = (miss[a] ?: 0) + 1
                            if (k >= 2) {
                                it.remove()
                                miss.remove(a)
                                changed = true
                            } else {
                                miss[a] = k
                            }
                        }
                    }
                    if (changed) l.onFound(found.values.toList())
                }
            }
        } catch (e: SecurityException) {
        }
    }

    // ---- Receiver: advertise ---------------------------------------------------------------

    /** Starts or stops publishing this device as a Receiver. Returns false if it could not start. */
    fun advertise(on: Boolean): Boolean {
        if (on) {
            if (adv) return true
            if (!acquire()) return false
            adv = true
            Log.d(TAG, "advertise: on")
            cleanStaleGroup()
            startAdvertising()
            scheduleReadvertise()
            return true
        }
        if (!adv) return true
        adv = false
        Log.d(TAG, "advertise: off")
        clearTimers()
        try {
            val m = mgr
            val c = ch
            if (m != null && c != null) {
                m.clearLocalServices(c, noop)
                m.stopPeerDiscovery(c, noop)
            }
        } catch (e: SecurityException) {
        }
        release()
        return true
    }

    /** The name or avatar changed: publish the new TXT record. */
    fun refreshAdvertising() {
        if (adv) startAdvertising()
    }

    private fun cleanStaleGroup() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.requestGroupInfo(c) { g ->
                if (g != null && !Link.hasPeers()) {
                    Log.d(TAG, "advertise: removing a stale group")
                    m.removeGroup(c, noop)
                }
            }
        } catch (e: SecurityException) {
        }
    }

    private fun startAdvertising() {
        val m = mgr ?: return
        val c = ch ?: return
        try {
            m.clearLocalServices(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = register()
                override fun onFailure(reason: Int) = register()
            })
        } catch (e: SecurityException) {
        }
    }

    private fun register() {
        val m = mgr ?: return
        val c = ch ?: return
        if (!adv) return
        val txt = HashMap<String, String>()
        txt["v"] = Proto.VERSION.toString()
        txt["i"] = Prefs.deviceId
        txt["n"] = Prefs.name.take(24)
        txt["r"] = Prefs.avatarRev.toString()
        val info = WifiP2pDnsSdServiceInfo.newInstance("flit" + Prefs.deviceId.take(8), SERVICE, txt)
        try {
            m.addLocalService(c, info, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "advertise: service published")
                    visible()
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "advertise: addLocalService failed: ${why(reason)}")
                    visible()
                }
            })
        } catch (e: SecurityException) {
        }
    }

    /** Peer discovery makes this device visible to others' service discovery. */
    private fun visible() {
        val m = mgr ?: return
        val c = ch ?: return
        if (!adv) return
        try {
            m.discoverPeers(c, noop)
        } catch (e: SecurityException) {
        }
    }

    /** Android stops P2P discovery on its own after a while: re-trigger it. */
    private fun scheduleReadvertise() {
        later(READVERTISE_MS, Runnable {
            if (adv) {
                if (!groupUp) visible()
                scheduleReadvertise()
            }
        })
    }

    // ---- Sender: discover ----------------------------------------------------------------------

    /** Looks for Receivers for [ms] milliseconds. Requires a prior [acquire]. */
    fun discover(ms: Long, l: Discovery) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            l.onError(ERR_UNSUPPORTED)
            return
        }
        stopDiscovery()
        disc = l
        found.clear()
        miss.clear()
        Log.d(TAG, "discover: start (${ms} ms)")
        try {
            m.setDnsSdResponseListeners(
                c,
                WifiP2pManager.DnsSdServiceResponseListener { _, _, _ -> },
                WifiP2pManager.DnsSdTxtRecordListener { full, map, dev -> onTxt(full, map, dev) }
            )
            m.clearServiceRequests(c, noop)
            m.addServiceRequest(c, WifiP2pDnsSdServiceRequest.newInstance(), noop)
        } catch (e: SecurityException) {
            disc = null
            l.onError(ERR_PERMISSION)
            return
        }
        kick(3)
        later(3000L, Runnable { services() })
        later(6000L, Runnable { services() })
        later(ms, Runnable { finishDiscovery() })
    }

    private fun kick(tries: Int) {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.discoverPeers(c, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    later(300L, Runnable { services() })
                }

                override fun onFailure(reason: Int) {
                    Log.w(TAG, "discoverPeers failed: ${why(reason)}")
                    when {
                        reason == WifiP2pManager.P2P_UNSUPPORTED -> fail(ERR_UNSUPPORTED)
                        tries > 1 -> later(800L, Runnable { kick(tries - 1) })
                        else -> services()
                    }
                }
            })
        } catch (e: SecurityException) {
            fail(ERR_PERMISSION)
        }
    }

    private fun services() {
        val m = mgr ?: return
        val c = ch ?: return
        if (disc == null) return
        try {
            m.discoverServices(c, noop)
        } catch (e: SecurityException) {
            fail(ERR_PERMISSION)
        }
    }

    private fun fail(code: Int) {
        val l = disc
        stopDiscovery()
        l?.onError(code)
    }

    private fun onTxt(full: String?, map: Map<String, String>?, dev: WifiP2pDevice?) {
        val l = disc ?: return
        if (map == null || dev == null || full == null || !full.contains("_flit")) return
        val id = map["i"] ?: return
        if (id == Prefs.deviceId) return
        val name = map["n"]?.takeIf { it.isNotBlank() } ?: dev.deviceName ?: "?"
        found[dev.deviceAddress] = Found(dev.deviceAddress, id, name, map["r"]?.toIntOrNull() ?: 0)
        miss.remove(dev.deviceAddress)
        Log.d(TAG, "discover: found '$name' at ${mask(dev.deviceAddress)} (${found.size} total)")
        l.onFound(found.values.toList())
    }

    private fun finishDiscovery() {
        val l = disc ?: return
        val list = found.values.toList()
        Log.d(TAG, "discover: window ended, ${list.size} found")
        stopDiscovery()
        l.onDone(list)
    }

    /** Stops searching (the list found so far is discarded). Safe to call at any time. */
    fun stopDiscovery() {
        if (disc == null && timers.isEmpty()) return
        disc = null
        if (!adv) clearTimers()
        val m = mgr
        val c = ch
        if (m != null && c != null) {
            try {
                m.stopPeerDiscovery(c, noop)
                m.clearServiceRequests(c, noop)
            } catch (e: SecurityException) {
            }
        }
    }

    // ---- Sender: connect / leave ----------------------------------------------------------------

    private fun config(addr: String): WifiP2pConfig {
        val c = WifiP2pConfig()
        c.deviceAddress = addr
        c.wps.setup = WpsInfo.PBC
        c.groupOwnerIntent = 15 // the Sender should be the Group Owner (can serve several Receivers)
        return c
    }

    /**
     * Invites the device [address]. [done] reports whether the invitation was started (not whether
     * the Receiver accepted: that arrives as a group event, then a TCP connection).
     *
     * Android rejects an invitation (reason ERROR) when the target is not in its current peer list,
     * and some chipsets empty that list when peer discovery is stopped. So before inviting, peer
     * discovery is (re)started and we wait until the target shows up in the list. Discovery is NOT
     * stopped before the invitation. A half-started connection and a stale group (with no live
     * peers) are cleared first. BUSY / ERROR are retried; the reason is kept in [lastFail].
     */
    fun connect(address: String, tries: Int = 8, done: (Boolean) -> Unit) {
        if (mgr == null || ch == null) {
            lastFail = "no channel"
            Log.w(TAG, "connect: no channel")
            done(false)
            return
        }
        lastFail = ""
        Log.d(TAG, "connect: preparing invitation to ${mask(address)}")
        prepare { ensurePeer(address) { attempt(address, tries, 1, done) } }
    }

    private fun prepare(next: () -> Unit) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            next()
            return
        }
        // A group is already running (an earlier Receiver is connected or being set up): do not
        // cancel or remove anything, that could drop the Receiver that is already joined.
        if (groupUp || Link.hasPeers()) {
            Log.d(TAG, "connect: a group is up, inviting without touching it")
            next()
            return
        }
        var fired = false
        lateinit var guard: Runnable
        val go: () -> Unit = {
            if (!fired) {
                fired = true
                h.removeCallbacks(guard)
                timers.remove(guard)
                next()
            }
        }
        guard = Runnable {
            Log.w(TAG, "connect: a setup call did not answer, continuing")
            go()
        }
        later(3000L, guard)
        try {
            m.cancelConnect(c, step {
                m.requestGroupInfo(c) { g ->
                    if (g != null && !Link.hasPeers()) {
                        Log.d(TAG, "connect: removing a stale group first")
                        m.removeGroup(c, step { go() })
                    } else {
                        go()
                    }
                }
            })
        } catch (e: SecurityException) {
            go()
        }
    }

    /** Restarts peer discovery and waits (up to ~10 s) until [address] is in the framework's peer list. */
    private fun ensurePeer(address: String, next: () -> Unit) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            next()
            return
        }
        try {
            m.discoverPeers(c, noop)
        } catch (e: SecurityException) {
            next()
            return
        }
        pollPeer(address, PEER_WAIT_POLLS, next)
    }

    private fun pollPeer(address: String, left: Int, next: () -> Unit) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            next()
            return
        }
        try {
            m.requestPeers(c) { list ->
                val present = list != null && list.deviceList.any { it.deviceAddress == address }
                if (present) {
                    Log.d(TAG, "connect: target is in the peer list")
                    next()
                } else if (left <= 0) {
                    Log.w(TAG, "connect: target never appeared in the peer list, trying anyway")
                    next()
                } else {
                    if (left % 4 == 0) {
                        try {
                            mgr?.discoverPeers(ch, noop)
                        } catch (e: SecurityException) {
                        }
                    }
                    later(700L, Runnable { pollPeer(address, left - 1, next) })
                }
            }
        } catch (e: SecurityException) {
            next()
        }
    }

    private fun attempt(address: String, tries: Int, n: Int, done: (Boolean) -> Unit) {
        val m = mgr
        val c = ch
        if (m == null || c == null) {
            lastFail = "no channel"
            done(false)
            return
        }
        Log.d(TAG, "connect: sending invitation to ${mask(address)} (attempt $n)")
        try {
            m.connect(c, config(address), object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    Log.d(TAG, "connect: invitation accepted by the framework, waiting for the group")
                    lastFail = ""
                    done(true)
                }

                override fun onFailure(reason: Int) {
                    lastFail = why(reason)
                    Log.w(TAG, "connect: attempt $n failed: ${why(reason)}")
                    if (reason != WifiP2pManager.P2P_UNSUPPORTED && tries > 1) {
                        later(1500L, Runnable {
                            val mm = mgr
                            val cc = ch
                            if (mm == null || cc == null) {
                                done(false)
                            } else {
                                try {
                                    mm.cancelConnect(cc, step {
                                        ensurePeer(address) { attempt(address, tries - 1, n + 1, done) }
                                    })
                                } catch (e: SecurityException) {
                                    lastFail = "permission"
                                    done(false)
                                }
                            }
                        })
                    } else {
                        done(false)
                    }
                }
            })
        } catch (e: SecurityException) {
            lastFail = "permission"
            Log.w(TAG, "connect: permission denied")
            done(false)
        }
    }

    /** Cancels a pending invitation and dissolves the group. */
    fun leave() {
        Log.d(TAG, "leave: cancel pending connection and remove group")
        val m = mgr
        val c = ch
        if (m != null && c != null) {
            try {
                m.cancelConnect(c, noop)
                m.removeGroup(c, noop)
            } catch (e: SecurityException) {
            }
        }
        h.removeCallbacks(lossCheck)
        if (groupUp) {
            groupUp = false
            Link.onGroupLost()
        }
    }
}