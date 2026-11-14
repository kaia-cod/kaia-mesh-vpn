package com.kaia.meshvpn

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kaia.meshvpn.mesh.MeshViewModel
import com.kaia.meshvpn.mesh.TunnelState
import com.kaia.meshvpn.ui.KaiaApp
import com.kaia.meshvpn.ui.theme.KaiaMeshTheme

/**
 * Écran principal — Jetpack Compose.
 *
 * Le contenu de l'écran (état du tunnel, adresse virtuelle, pairs, compteurs,
 * journal) vient EXCLUSIVEMENT de [MeshViewModel], alimenté par les diffusions
 * réelles de MeshVpnService et du démon meshd.
 *
 * Sécurité cold-start (acquis S3) : aucune initialisation lourde dans
 * onCreate ; le contenu Compose est enveloppé d'un try/catch avec repli texte
 * afin de ne jamais provoquer de crash immédiat au lancement.
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
            vm.noteLocal("INFO", "Consentement VPN accordé · démarrage")
            vm.setTunnel(TunnelState.PREPARING)
            MeshVpnService.start(this)
        } else {
            vm.setTunnel(TunnelState.DISCONNECTED, "Autorisation VPN refusée")
            vm.noteLocal("WARN", "Autorisation VPN refusée par l'utilisateur")
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
            Log.i(TAG, "Compose UI affichée")
        } catch (e: Exception) {
            Log.e(TAG, "Compose init failed, repli texte", e)
            setContentView(
                TextView(this).apply {
                    text = "Kaia Mesh VPN\n\nErreur d'initialisation de l'interface : ${e.message}"
                    setPadding(48, 96, 48, 48)
                    setTextColor(Color.WHITE)
                }
            )
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

    /** Demande l'autorisation VPN puis démarre le service. */
    private fun connectTunnel() {
        try {
            vm.setTunnel(TunnelState.PREPARING)
            val consent = VpnService.prepare(this)
            if (consent != null) {
                vm.noteLocal("INFO", "Autorisation VPN demandée par Android")
                vpnPermission.launch(consent)
            } else {
                vm.noteLocal("INFO", "Consentement VPN déjà accordé · démarrage")
                MeshVpnService.start(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "connectTunnel failed", e)
            vm.setTunnel(TunnelState.ERROR, "Démarrage impossible : ${e.message}")
        }
    }

    private fun disconnectTunnel() {
        runCatching { MeshVpnService.stop(this) }
            .onFailure { vm.setTunnel(TunnelState.ERROR, "Arrêt impossible : ${it.message}") }
        vm.noteLocal("INFO", "Déconnexion demandée")
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

    @Deprecated("Conservé pour compat < AndroidX 1.9")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        try {
            @Suppress("DEPRECATION")
            super.onActivityResult(requestCode, resultCode, data)
        } catch (e: Exception) {
            Log.e(TAG, "onActivityResult failed", e)
        }
    }
}
