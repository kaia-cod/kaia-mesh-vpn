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
    }

    private var tun: ParcelFileDescriptor? = null
    @Volatile private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
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
            .addAddress("10.42.0.1", 24)
            .addRoute("10.42.0.0", 16)
        try {
            tun = builder.establish()
        } catch (e: Exception) {
            Log.e(TAG, "TUN establish failed", e)
            return
        }
        if (tun == null) {
            Log.e(TAG, "TUN establish returned null")
            return
        }

        val input = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)

        // Lazy connect to daemon socket with retry and fallback
        var socket: LocalSocket? = null
        try {
            socket = LocalSocket()
            val sockPath = "/data/data/${packageName}/files/meshd.sock"
            var connected = false
            // Retry up to 5 times, 500ms apart (daemon may be starting)
            repeat(5) { attempt ->
                try {
                    socket!!.connect(LocalSocketAddress(sockPath))
                    connected = true
                    Log.i(TAG, "connected to meshd.sock at $sockPath attempt=$attempt")
                    return@repeat
                } catch (e: Exception) {
                    Log.w(TAG, "socket connect attempt $attempt failed: ${e.message}")
                    Thread.sleep(500)
                }
            }
            if (!connected) {
                // Fallback: try legacy path and no-socket mode (keep TUN alive but not forwarding)
                Log.w(TAG, "meshd.sock not reachable, running in idle TUN mode")
                // Keep service alive, just sleep and loop checking running flag
                while (running) {
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    // Optional: read from TUN and drop to avoid buffer overflow
                    try {
                        if (input.available() > 0) {
                            val tmp = ByteArray(1500)
                            input.read(tmp)
                        }
                    } catch (_: Exception) {}
                }
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "socket init failed, idle mode", e)
            while (running) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
            return
        }

        val buf = ByteArray(65535)
        while (running) {
            try {
                val n = input.read(buf, 0, buf.size)
                if (n <= 0) continue
                // Length-prefixed frame: 4 bytes big-endian + packet.
                val header = ByteArray(4)
                header[0] = (n ushr 24).toByte()
                header[1] = (n ushr 16).toByte()
                header[2] = (n ushr 8).toByte()
                header[3] = n.toByte()
                try {
                    socket?.outputStream?.write(header)
                    socket?.outputStream?.write(buf, 0, n)
                    socket?.outputStream?.flush()
                } catch (e: Exception) {
                    Log.w(TAG, "write to meshd.sock failed: ${e.message}, trying reconnect")
                    try { socket?.close() } catch (_: Exception) {}
                    // Attempt reconnect once
                    try {
                        val newSock = LocalSocket()
                        newSock.connect(LocalSocketAddress("/data/data/${packageName}/files/meshd.sock"))
                        socket = newSock
                    } catch (e2: Exception) {
                        Log.w(TAG, "reconnect failed: ${e2.message}")
                        Thread.sleep(1000)
                    }
                }

                // Try to read return path non-blocking? Implementation keeps one-way for now,
                // response reading would be in another thread. We add optional read if available:
                // (daemon may write back packets)
                try {
                    val sockIn = socket?.inputStream
                    if (sockIn != null && sockIn.available() >= 4) {
                        val h = ByteArray(4)
                        var read = 0
                        while (read < 4) {
                            val r = sockIn.read(h, read, 4 - read)
                            if (r <= 0) break
                            read += r
                        }
                        if (read == 4) {
                            val len = ((h[0].toInt() and 0xFF) shl 24) or ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
                            if (len in 1..65535 && sockIn.available() >= 0) {
                                val pkt = ByteArray(len)
                                var pr = 0
                                while (pr < len) {
                                    val r = sockIn.read(pkt, pr, len - pr)
                                    if (r <= 0) break
                                    pr += r
                                }
                                if (pr == len) {
                                    try { output.write(pkt) } catch (_: Exception) {}
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}

            } catch (e: Exception) {
                if (!running) break
                Log.w(TAG, "tunnel loop exception: ${e.message}")
                try { Thread.sleep(200) } catch (_: Exception) {}
            }
        }
        try { socket?.close() } catch (_: Exception) {}
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        try { tun?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
}
