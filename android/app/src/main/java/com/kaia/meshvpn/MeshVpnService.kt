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

        // --- Contrat de diffusion vers l'UI (MeshViewModel) ---
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

        /** Démarre le tunnel (le consentement VPN doit avoir été obtenu). */
        fun start(ctx: android.content.Context) {
            val i = Intent(ctx, MeshVpnService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(ctx, i)
        }

        /** Arrête le tunnel et le démon. */
        fun stop(ctx: android.content.Context) {
            ctx.startService(Intent(ctx, MeshVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    private val LOCAL_TUN_ADDRESS: String get() = "10.42.0.1"
    private val DEFAULT_MTU = 1400
    private val LOCAL_TUN_PREFIX = 32

    // Dernier état publié (sert au snapshot quand l'UI revient au premier plan)
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

    /** Publie l'état courant vers l'UI (jamais de données inventées). */
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

    /** Journal réel (service/démon) affiché dans l'écran. */
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
            publishLog("INFO", "Arrêt demandé par l'utilisateur")
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
            publishLog("INFO", "Préparation du tunnel")
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
            .addRoute("0.0.0.0", 0)          // tout le trafic passe par le maillage
            .addRoute("::", 0)
        // Split tunnel : le trafic du démon lui-même (DHT/libp2p) ne doit pas repasser dans le TUN.
        builder.addDisallowedApplication(packageName)
        try {
            tun = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "TUN establish failed", e)
            remember("ERROR", "", 0, 0, "", "Établissement du TUN impossible : ${e.message}")
            publishLog("ERROR", "TUN establish failed: ${e.message}")
            return
        }
        if (tun == null) {
            remember("ERROR", "", 0, 0, "", "Le système a refusé le TUN (consentement VPN révoqué ?)")
            publishLog("ERROR", "TUN establish returned null")
            return
        }
        publishLog("OK", "TUN levé · VIP=$LOCAL_TUN_ADDRESS/$LOCAL_TUN_PREFIX · MTU=$DEFAULT_MTU · route 0.0.0.0/0")

        val input = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)

        // --- Supervision du démon meshd (extrait depuis libmeshd.so) ---
        val daemon = MeshDaemon(this)
        val started = daemon.ensureRunning()
        if (!started.ok) {
            remember("ERROR", LOCAL_TUN_ADDRESS, 0, DEFAULT_MTU, "0.0.0.0/0", started.error)
            publishLog("ERROR", "meshd introuvable : ${started.error}")
            // Le TUN reste levé mais l'écran DIT la vérité : erreur, aucun trafic.
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (input.available() > 0) { runCatching { input.read(ByteArray(1500)) } }
            }
            return
        }
        publishLog("OK", "Démon meshd démarré · pid=${started.pid} · socket=${daemon.socketPath}")

        // --- Connexion à la socket locale du démon ---
        var socket: LocalSocket? = null
        var connected = false
        repeat(10) { attempt ->
            if (connected || !running) return@repeat
            try {
                val s = LocalSocket()
                s.connect(LocalSocketAddress(daemon.socketPath, LocalSocketAddress.Namespace.FILESYSTEM))
                socket = s
                connected = true
                publishLog("OK", "Socket locale meshd connectée (essai ${attempt + 1})")
            } catch (e: Exception) {
                publishLog("WARN", "Connexion socket meshd : essai ${attempt + 1} échoué (${e.message})")
                try { Thread.sleep(500) } catch (_: InterruptedException) {}
            }
        }
        if (!connected) {
            remember("ERROR", LOCAL_TUN_ADDRESS, started.pid, DEFAULT_MTU, "0.0.0.0/0",
                "Démon lancé (pid=${started.pid}) mais socket injoignable : aucun datapath")
            publishLog("ERROR", "Socket meshd injoignable après 10 essais")
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                if (input.available() > 0) { runCatching { input.read(ByteArray(1500)) } }
            }
            return
        }
        remember("CONNECTED", LOCAL_TUN_ADDRESS, started.pid, DEFAULT_MTU, "0.0.0.0/0", null)
        publishLog("OK", "Tunnel actif · datapath bidirectionnel TUN <-> maillage")

        val sockOut = socket!!.outputStream
        val sockIn = socket!!.inputStream
        val writeLock = Any()
        @Volatile var rxBytes = 0L
        @Volatile var txBytes = 0L
        @Volatile var rxPps = 0L
        @Volatile var txPps = 0L
        @Volatile var dropped = 0L
        @Volatile var linkUp = true

        // --- Sens TUN -> maillage (thread dédié) ---
        val upThread = Thread {
            val buf = ByteArray(65535)
            while (running && linkUp) {
                try {
                    val n = input.read(buf, 0, buf.size)
                    if (n <= 0) continue
                    synchronized(writeLock) {
                        sockOut.write((n ushr 24) and 0xFF); sockOut.write((n ushr 16) and 0xFF)
                        sockOut.write((n ushr 8) and 0xFF); sockOut.write(n and 0xFF)
                        sockOut.write(buf, 0, n)
                        sockOut.flush()
                    }
                    txBytes += n
                } catch (e: Exception) {
                    if (!running) break
                    dropped++
                    publishLog("WARN", "Écriture maillage interrompue : ${e.message}")
                    linkUp = false
                }
            }
        }.apply { name = "tun-to-mesh"; isDaemon = true; start() }

        // --- Sens maillage -> TUN (thread principal de ce runTunnel) + heartbeat statistiques ---
        var lastStatsAt = System.currentTimeMillis()
        var lastRx = 0L
        var lastTx = 0L
        while (running) {
            try {
                val h = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val r = sockIn.read(h, read, 4 - read)
                    if (r <= 0) throw java.io.IOException("socket fermée")
                    read += r
                }
                val len = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or
                        ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                if (len !in 1..65535) { dropped++; continue }
                val pkt = ByteArray(len)
                var pr = 0
                while (pr < len) {
                    val r = sockIn.read(pkt, pr, len - pr)
                    if (r <= 0) throw java.io.IOException("socket fermée")
                    pr += r
                }
                output.write(pkt)
                output.flush()
                rxBytes += len
            } catch (e: Exception) {
                if (!running) break
                dropped++
                publishLog("WARN", "Lecture maillage interrompue : ${e.message}")
                break
            }
            val now = System.currentTimeMillis()
            if (now - lastStatsAt >= 1000) {
                val dt = ((now - lastStatsAt).coerceAtLeast(1)) / 1000.0
                rxPps = ((rxBytes - lastRx) / dt).toLong()
                txPps = ((txBytes - lastTx) / dt).toLong()
                lastRx = rxBytes; lastTx = txBytes; lastStatsAt = now
                sendBroadcast(
                    Intent(ACTION_STATS).setPackage(packageName)
                        .putExtra(EXTRA_RX_BYTES, rxBytes)
                        .putExtra(EXTRA_TX_BYTES, txBytes)
                        .putExtra(EXTRA_RX_PPS, rxPps)
                        .putExtra(EXTRA_TX_PPS, txPps)
                        .putExtra(EXTRA_DROPPED, dropped)
                )
                if (!daemon.isAlive()) {
                    publishLog("ERROR", "Le démon meshd s'est arrêté (pid=${started.pid})")
                    remember("ERROR", LOCAL_TUN_ADDRESS, 0, DEFAULT_MTU, "0.0.0.0/0",
                        "Démon meshd terminé : tunnel sans datapath")
                    break
                }
            }
        }
        linkUp = false
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
