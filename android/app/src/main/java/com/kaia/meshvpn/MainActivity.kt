package com.kaia.meshvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaia.meshvpn.mesh.MeshViewModel
import com.kaia.meshvpn.mesh.TunnelState
import com.kaia.meshvpn.ui.KaiaApp
import com.kaia.meshvpn.ui.theme.KaiaMeshTheme

/**
 * Main screen — Jetpack Compose.
 *
 * The screen content (tunnel state, virtual address, peers, counters,
 * log) comes EXCLUSIVELY from [MeshViewModel], fed by the real broadcasts
 * of MeshVpnService and of the meshd daemon.
 *
 * Cold-start safety: no heavy initialisation inside
 * onCreate; the Compose content is wrapped in a try/catch with a text fallback
 * so that a launch never crashes immediately.
 */
class MainActivity : ComponentActivity() {

    companion object {
        private const val TAG = "KaiaMain"
        private const val REQ_NOTIF = 42
    }

    private val vm: MeshViewModel by viewModels()

    private val vpnPermission = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            vm.noteLocal("INFO", "VPN consent granted · starting")
            vm.setTunnel(TunnelState.PREPARING)
            MeshVpnService.start(this)
        } else {
            vm.setTunnel(TunnelState.DISCONNECTED, "VPN permission denied")
            vm.noteLocal("WARN", "VPN permission denied by the user")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        try {
            super.onCreate(savedInstanceState)
        } catch (e: Exception) {
            Log.e(TAG, "super.onCreate failed", e)
        }

        askNotificationPermission()

        try {
            setContent {
                KaiaMeshTheme {
                    val model by vm.state.collectAsStateWithLifecycle()
                    Surface(modifier = Modifier.fillMaxSize()) {
                        KaiaApp(
                            model = model,
                            onConnect = ::connectTunnel,
                            onDisconnect = ::disconnectTunnel,
                        )
                    }
                }
            }
            Log.i(TAG, "Compose UI displayed")
        } catch (e: Exception) {
            Log.e(TAG, "Compose init failed, falling back to text", e)
            setContent {
                KaiaMeshTheme {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Kaia Mesh VPN\n\nUI initialization error: ${e.message}",
                            color = MaterialTheme.colorScheme.onBackground,
                            modifier = Modifier.padding(24.dp),
                            fontSize = 16.sp,
                        )
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        vm.onStart(this)
    }

    override fun onStop() {
        vm.onStop(this)
        super.onStop()
    }

    /** Requests the VPN permission, then starts the service. */
    private fun connectTunnel() {
        try {
            vm.setTunnel(TunnelState.PREPARING)
            val consent = VpnService.prepare(this)
            if (consent != null) {
                vm.noteLocal("INFO", "VPN permission requested by Android")
                vpnPermission.launch(consent)
            } else {
                vm.noteLocal("INFO", "VPN consent already granted · starting")
                MeshVpnService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectTunnel failed", e)
            vm.setTunnel(TunnelState.ERROR, "Could not start the tunnel: ${e.message}")
        }
    }

    private fun disconnectTunnel() {
        runCatching { MeshVpnService.stop(this) }
            .onFailure { vm.setTunnel(TunnelState.ERROR, "Could not stop the tunnel: ${it.message}") }
        vm.noteLocal("INFO", "Disconnect requested")
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            == PackageManager.PERMISSION_GRANTED
        ) return
        runCatching {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
        }
    }

    @Deprecated("Kept for compat < AndroidX 1.9")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "onActivityResult failed", e)
        }
    }
}
