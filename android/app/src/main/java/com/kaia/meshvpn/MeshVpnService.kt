package com.kaia.meshvpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.LocalSocket
import java.net.LocalSocketAddress

/**
 * Bridge between Android's VpnService TUN interface and the meshd daemon
 * (Go/libp2p) over a Unix socket. Packets read from the TUN fd are forwarded
 * to the daemon, which routes them through the IPFS mesh to the destination
 * virtual IP. Packets coming back are written to the TUN fd.
 */
class MeshVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null
    private var running = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundService()
        if (!running) {
            running = true
            Thread { runTunnel() }.start()
        }
        return START_STICKY
    }

    private fun startForegroundService() {
        val channelId = "mesh_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "Kaia Mesh VPN",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("Kaia Mesh VPN actif")
                .setContentText("Tunnel running — peer-to-peer over IPFS")
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("Kaia Mesh VPN actif")
                .setContentText("Tunnel running — peer-to-peer over IPFS")
                .build()
        }
        startForeground(1, notification)
    }

    private fun runTunnel() {
        val builder = Builder()
            .setSession("Kaia Mesh VPN")
            .addAddress("10.42.0.1", 24)
            .addRoute("10.42.0.0", 16)
        tun = builder.establish() ?: return

        val input = FileInputStream(tun!!.fileDescriptor)
        val output = FileOutputStream(tun!!.fileDescriptor)

        val socket = LocalSocket()
        socket.connect(LocalSocketAddress("/data/data/com.kaia.meshvpn/files/meshd.sock"))

        val buf = ByteArray(65535)
        while (running) {
            val n = input.read(buf, 0, buf.size)
            if (n <= 0) continue
            // Length-prefixed frame: 4 bytes big-endian + packet.
            val header = ByteArray(4)
            header[0] = (n ushr 24).toByte()
            header[1] = (n ushr 16).toByte()
            header[2] = (n ushr 8).toByte()
            header[3] = n.toByte()
            socket.outputStream.write(header)
            socket.outputStream.write(buf, 0, n)
            socket.outputStream.flush()
        }
        try { socket.close() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        running = false
        tun?.close()
        super.onDestroy()
    }
}
