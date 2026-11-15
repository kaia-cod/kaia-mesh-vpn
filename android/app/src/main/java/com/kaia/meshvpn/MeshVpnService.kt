package com.kaia.meshvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * S3 FIX:
 * - Cause #2 crash FGS: Notification without smallIcon => Invalid notification (no valid small icon)
 *   Fixed: setSmallIcon(R.drawable.ic_notification) always.
 * - Daemon socket connect without try/catch => crash if meshd.sock missing.
 *   Fixed: lazy connect with retry loop + try/catch + graceful fallback (keep TUN but no forwarding if daemon absent).
 * - Heavy packet forwarding moved to background thread lazily, wrapped in try/catch.
 * - Notification uses string resources (R.string) and correct channel handling.
 */
class MeshVpnService : VpnService() {

    companion object {
        private const val TAG = "MeshVpnService"
        private const val CHANNEL_ID = "mesh_vpn"

        private const val ACTION_START = "com.kaia.meshvpn.START"
        private const val ACTION_STOP = "com.kaia.meshvpn.STOP"

        // --- Broadcast contract towards the UI (MeshViewModel) ---
        const val ACTION_STATE = "com.kaia.meshvpn.STATE"
        const val ACTION_PEERS = "com.kaia.meshvpn.PEERS"
        const val ACTION_STATS = "com.kaia.meshvpn.STATS"
        const val ACTION_LOG = "com.kaia.meshvpn.LOG"
        const val ACTION_REQUEST_SNAPSHOT = "com.kaia.meshvpn.REQUEST_SNAPSHOT"

        const val EXTRA_TUNNEL = "tunnel"
        const val EXTRA_VIP = "vip"
        const val EXTRA_DAEMON_PID = "daemon_pid"
        const val EXTRA_DAEMON_ALIVE = "daemon_alive"
        const val EXTRA_MTU = "mtu"
        const val EXTRA_ROUTE = "route"
        const val EXTRA_ERROR = "error"
        const val EXTRA_PEERS = "peers"
        const val EXTRA_RX_BYTES = "rx_bytes"
        const val EXTRA_TX_BYTES = "tx_bytes"
        const val EXTRA_RX_PPS = "rx_pps"
        const val EXTRA_TX_PPS = "tx_pps"
        const val EXTRA_DROPPED = "dropped"
        const val EXTRA_LOG = "log"
        const val EXTRA_LEVEL = "level"

        /** Starts the tunnel (VPN consent must already be granted). */
        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, MeshVpnService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        /** Stops the tunnel and the daemon. */
        fun stop(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, MeshVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    private val LOCAL_TUN_ADDRESS: String get() = "10.42.0.1"
    private val DEFAULT_MTU = 1400
    private val LOCAL_TUN_PREFIX = 32

    // Last published state (used as a snapshot when the UI comes back to the foreground)
    @Volatile private var lastTunnel = "DISCONNECTED"
    @Volatile private var lastVip: String? = null
    @Volatile private var lastPid = 0
    @Volatile private var lastMtu = 0
    @Volatile private var lastRoute = ""
    @Volatile private var lastError: String? = null

    private fun remember(tunnel: String, vip: String, pid: Int, mtu: Int, route: String, error: String?) {
        lastTunnel = tunnel; lastVip = vip; lastPid = pid; lastMtu = mtu; lastRoute = route; lastError = error
        publishState(tunnel, vip, pid, pid > 0, mtu, route, error)
    }

    private fun stopVpn() {
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        remember("DISCONNECTED", "", 0, 0, "", null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Publishes the current state to the UI (never made-up data). */
    private fun publishState(tunnel: String, vip: String, pid: Int, alive: Boolean,
                             mtu: Int, route: String, error: String?) {
        val i = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_TUNNEL, tunnel)
            .putExtra(EXTRA_VIP, vip)
            .putExtra(EXTRA_DAEMON_PID, pid)
            .putExtra(EXTRA_DAEMON_ALIVE, alive)
            .putExtra(EXTRA_MTU, mtu)
            .putExtra(EXTRA_ROUTE, route)
            .putExtra(EXTRA_ERROR, error)
        sendBroadcast(i)
    }

    /** Real log (service/daemon) shown on screen. */
    private fun publishLog(level: String, text: String) {
        Log.println(
            when (level) { "ERROR" -> Log.ERROR; "WARN" -> Log.WARN; else -> Log.INFO },
            TAG, text
        )
        sendBroadcast(
            Intent(ACTION_LOG).setPackage(packageName)
                .putExtra(EXTRA_LOG, text)
                .putExtra(EXTRA_LEVEL, level)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            publishLog("INFO", "Stop requested by the user")
            running = false
            stopVpn()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_REQUEST_SNAPSHOT) {
            lastVip?.let { publishState(lastTunnel, it, lastPid, lastPid > 0, lastMtu, lastRoute, lastError) }
            return START_STICKY
        }
        try {
            startForegroundSafe()
        } catch (e: Exception) {
            Log.e(TAG, "startForegroundSafe failed", e)
            // Try minimal notification to avoid crash loop
            try {
                val fallback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle(getString(R.string.vpn_notif_title))
                        .setContentText("Starting…")
                        .setSmallIcon(R.drawable.ic_notification)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    Notification.Builder(this)
                        .setContentTitle(getString(R.string.vpn_notif_title))
                        .setContentText("Starting…")
                        .setSmallIcon(R.drawable.ic_notification)
                        .build()
                }
                startForeground(1, fallback)
            } catch (e2: Exception) {
                Log.e(TAG, "fallback notification failed", e2)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (!running) {
            running = true
            remember("PREPARING", "", 0, 0, "", null)
            publishLog("INFO", "Preparing tunnel")
            Thread { runTunnelSafe() }.start()
        }
        return START_STICKY
    }

    private fun startForegroundSafe() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.vpn_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.vpn_channel_desc)
            }
            nm?.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.vpn_notif_title))
                .setContentText(getString(R.string.vpn_notif_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle(getString(R.string.vpn_notif_title))
                .setContentText(getString(R.string.vpn_notif_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .build()
        }
        startForeground(1, notification)
        Log.i(TAG, "foreground started with smallIcon")
    }

    private fun runTunnelSafe() {
        try {
            runTunnel()
        } catch (e: Exception) {
            Log.e(TAG, "runTunnel crashed", e)
        } finally {
            running = false
        }
    }

    private fun runTunnel() {
        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(DEFAULT_MTU)
            .addAddress(LOCAL_TUN_ADDRESS, 32)
            .addRoute("0.0.0.0", 0)          // all traffic goes through the mesh
            .addRoute("::", 0)
        // Split tunnel: the daemon's own traffic (DHT/libp2p) must not loop back into the TUN.
        builder.addDisallowedApplication(packageName)
        try {
            tun = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "TUN establish failed", e)
            remember("ERROR", "", 0, 0, "", "TUN establishment failed: ${e.message}")
            publishLog("ERROR", "TUN establish failed: ${e.message}")
            return
        }
        if (tun == null) {
            remember("ERROR", "", 0, 0, "", "The system refused the TUN (VPN consent revoked?)")
            publishLog("ERROR", "TUN establish returned null")
            return
        }
        publishLog("OK", "TUN up · VIP=$LOCAL_TUN_ADDRESS/$LOCAL_TUN_PREFIX · MTU=$DEFAULT_MTU · route 0.0.0.0/0")

        val input = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)

        // --- Supervision of the meshd daemon (extracted from libmeshd.so) ---
        val daemon = MeshDaemon(this)
        val started = daemon.ensureRunning()
        if (!started.ok) {
            remember("ERROR", LOCAL_TUN_ADDRESS, 0, DEFAULT_MTU, "0.0.0.0/0", started.error)
            publishLog("ERROR", started.error ?: MeshDaemon.ERR_BINARY_MISSING)
            // The TUN stays up but the screen TELLS the truth: error, no traffic.
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (input.available() > 0) { runCatching { input.read(ByteArray(1500)) } }
            }
            return
        }
        publishLog("OK", "meshd daemon started · pid=${started.pid} · exec=${started.method} · socket=${daemon.socketPath}")

        // --- Connection to the daemon's local socket ---
        var socket: LocalSocket? = null
        var connected = false
        repeat(10) { attempt ->
            if (connected || !running) return@repeat
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(daemon.socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                connected = true
                publishLog("OK", "meshd local socket connected (attempt ${attempt + 1})")
            } catch (e: Exception) {
                publishLog("WARN", "meshd socket connect failed: attempt ${attempt + 1} (${e.message})")
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }
        if (!connected) {
            remember("ERROR", LOCAL_TUN_ADDRESS, started.pid, DEFAULT_MTU, "0.0.0.0/0",
                "Daemon started (pid=${started.pid}) but socket unreachable: no datapath")
            publishLog("ERROR", "meshd socket unreachable after 10 attempts")
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (input.available() > 0) { runCatching { input.read(ByteArray(1500)) } }
            }
            return
        }
        remember("CONNECTED", LOCAL_TUN_ADDRESS, started.pid, DEFAULT_MTU, "0.0.0.0/0", null)
        publishLog("OK", "Tunnel active · bidirectional datapath TUN <-> mesh")

        val sockOut = socket!!.outputStream
        val sockIn = socket!!.inputStream
        val writeLock = Any()
        // D4 FIX: @Volatile only applies to properties, not to local variables.
        // Cross-thread visibility is provided here by atomics (java.util.concurrent.atomic).
        val rxBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val txBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val rxPps = java.util.concurrent.atomic.AtomicLong(0L)
        val txPps = java.util.concurrent.atomic.AtomicLong(0L)
        val dropped = java.util.concurrent.atomic.AtomicLong(0L)
        val linkUp = java.util.concurrent.atomic.AtomicBoolean(true)

        // --- Direction TUN -> mesh (dedicated thread) ---
        val upThread = Thread {
            val buf = ByteArray(65535)
            while (running && linkUp.get()) {
                try {
                    val n = input.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(writeLock) {
                        sockOut.write((n ushr 24) and 0xFF); sockOut.write((n ushr 16) and 0xFF)
                        sockOut.write((n ushr 8) and 0xFF); sockOut.write(n and 0xFF)
                        sockOut.write(buf, 0, n)
                        sockOut.flush()
                    }
                    txBytes.addAndGet(n.toLong())
                } catch (e: Exception) {
                    if (!running) break
                    dropped.incrementAndGet()
                    publishLog("WARN", "Mesh write interrupted: ${e.message}")
                    linkUp.set(false)
                }
            }
        }.apply { name = "tun-to-mesh"; isDaemon = true; start() }

        // --- Direction mesh -> TUN (main thread of this runTunnel) + stats heartbeat ---
        var lastStatsAt = System.currentTimeMillis()
        var lastRx = 0L
        var lastTx = 0L
        while (running) {
            try {
                val h = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val r = sockIn.read(h, read, 4 - read)
                    if (r <= 0) throw java.io.IOException("socket closed")
                    read += r
                }
                val len = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
                        ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (len !in 1..65535) { dropped.incrementAndGet(); continue }
                val pkt = ByteArray(len)
                var pr = 0
                while (pr < len) {
                    val r = sockIn.read(pkt, pr, len - pr)
                    if (r <= 0) throw java.io.IOException("socket closed")
                    pr += r
                }
                output.write(pkt)
                output.flush()
                rxBytes.addAndGet(len.toLong())
            } catch (e: Exception) {
                if (!running) break
                dropped.incrementAndGet()
                publishLog("WARN", "Mesh read interrupted: ${e.message}")
                break
            }
            val now = System.currentTimeMillis()
            if (now - lastStatsAt >= 1000) {
                val dt = ((now - lastStatsAt).coerceAtLeast(1)) / 1000.0
                val rxNow = rxBytes.get()
                val txNow = txBytes.get()
                rxPps.set(((rxNow - lastRx) / dt).toLong())
                txPps.set(((txNow - lastTx) / dt).toLong())
                lastRx = rxNow; lastTx = txNow; lastStatsAt = now
                sendBroadcast(
                    Intent(ACTION_STATS).setPackage(packageName)
                        .putExtra(EXTRA_RX_BYTES, rxNow)
                        .putExtra(EXTRA_TX_BYTES, txNow)
                        .putExtra(EXTRA_RX_PPS, rxPps.get())
                        .putExtra(EXTRA_TX_PPS, txPps.get())
                        .putExtra(EXTRA_DROPPED, dropped.get())
                )
                if (!daemon.isAlive()) {
                    publishLog("ERROR", "meshd daemon exited (pid=${started.pid})")
                    remember("ERROR", LOCAL_TUN_ADDRESS, 0, DEFAULT_MTU, "0.0.0.0/0",
                        "meshd daemon terminated: tunnel without datapath")
                    break
                }
            }
        }
        linkUp.set(false)
        try { socket?.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
        daemon.stop()
        if (running) remember("DISCONNECTED", "", 0, 0, "", null)
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
