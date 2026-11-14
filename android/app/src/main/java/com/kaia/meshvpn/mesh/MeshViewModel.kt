package com.kaia.meshvpn.mesh

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.kaia.meshvpn.MeshVpnService
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel of the single screen.
 *
 * It computes NO business data: it aggregates what the service publishes
 * (MESH_STATE / MESH_PEERS / MESH_STATS / MESH_LOG) and turns it into
 * [MeshModel]. The peer list stays empty as long as the daemon announces
 * nobody — and the UI then shows "No peer node" with its explanation.
 */
class MeshViewModel : ViewModel() {

    private val _state = MutableStateFlow(MeshModel())
    val state: StateFlow<MeshModel> = _state.asStateFlow()

    private var receiver: BroadcastReceiver? = null

    private val eventReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                MeshVpnService.ACTION_STATE -> {
                    val tunnel = intent.getStringExtra(MeshVpnService.EXTRA_TUNNEL)
                        ?.let { raw -> runCatching { TunnelState.valueOf(raw) }.getOrNull() }
                        ?: TunnelState.DISCONNECTED
                    val vip = intent.getStringExtra(MeshVpnService.EXTRA_VIP).orEmpty()
                    val pid = intent.getIntExtra(MeshVpnService.EXTRA_DAEMON_PID, 0)
                    val alive = intent.getBooleanExtra(MeshVpnService.EXTRA_DAEMON_ALIVE, false)
                    val mtu = intent.getIntExtra(MeshVpnService.EXTRA_MTU, 0)
                    val route = intent.getStringExtra(MeshVpnService.EXTRA_ROUTE).orEmpty()
                    val err = intent.getStringExtra(MeshVpnService.EXTRA_ERROR)
                    _state.update {
                        it.copy(
                            tunnel = tunnel,
                            virtualIp = vip,
                            daemonPid = pid,
                            daemonAlive = alive,
                            mtu = mtu,
                            route = route,
                            error = err,
                        )
                    }
                    appendLog(
                        when {
                            err != null -> "ERROR"
                            else -> "INFO"
                        },
                        when (tunnel) {
                            TunnelState.CONNECTED -> "Tunnel connected · meshd pid=$pid · VIP=$vip"
                            TunnelState.CONNECTING -> "TUN up (VIP=$vip) · waiting for meshd daemon"
                            TunnelState.PREPARING -> "Preparing: VPN authorization / service start"
                            TunnelState.DISCONNECTED -> "Tunnel stopped"
                            TunnelState.ERROR -> "Tunnel failed: ${err ?: "unknown cause"}"
                        }
                    )
                }

                MeshVpnService.ACTION_PEERS -> {
                    val raw = intent.getStringArrayExtra(MeshVpnService.EXTRA_PEERS) ?: emptyArray()
                    val peers = raw.mapNotNull { line ->
                        // format : vip|peerId|direction|lastSeenMs
                        val p = line.split('|')
                        if (p.size < 2 || p[0].isBlank()) return@mapNotNull null
                        MeshPeer(
                            virtualIp = p[0],
                            peerId = p[1],
                            shortId = MeshModel.shortPeerId(p[1]),
                            direction = p.getOrNull(2)?.takeIf { it.isNotBlank() } ?: "?",
                            lastSeenMs = p.getOrNull(3)?.toLongOrNull() ?: 0L,
                        )
                    }
                    val previous = _state.value.peers
                    _state.update { it.copy(peers = peers) }
                    if (peers.size != previous.size) {
                        appendLog("INFO", "Maillage : ${peers.size} pair(s) connu(s)")
                    }
                }

                MeshVpnService.ACTION_STATS -> {
                    val stats = TrafficStats(
                        rxBytes = intent.getLongExtra(MeshVpnService.EXTRA_RX_BYTES, 0L),
                        txBytes = intent.getLongExtra(MeshVpnService.EXTRA_TX_BYTES, 0L),
                        rxPps = intent.getLongExtra(MeshVpnService.EXTRA_RX_PPS, 0L),
                        txPps = intent.getLongExtra(MeshVpnService.EXTRA_TX_PPS, 0L),
                        droppedNoRoute = intent.getLongExtra(MeshVpnService.EXTRA_DROPPED, 0L),
                    )
                    _state.update { it.copy(stats = stats) }
                }

                MeshVpnService.ACTION_LOG -> {
                    val text = intent.getStringExtra(MeshVpnService.EXTRA_LOG).orEmpty()
                    val level = intent.getStringExtra(MeshVpnService.EXTRA_LEVEL) ?: "INFO"
                    appendLog(level, text)
                }
            }
        }
    }

    fun onStart(ctx: Context) {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(MeshVpnService.ACTION_STATE)
            addAction(MeshVpnService.ACTION_PEERS)
            addAction(MeshVpnService.ACTION_STATS)
            addAction(MeshVpnService.ACTION_LOG)
        }
        receiver = eventReceiver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ctx.applicationContext.registerReceiver(eventReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.applicationContext.registerReceiver(eventReceiver, filter)
        }
        requestSnapshot(ctx)
    }

    fun onStop(ctx: Context) {
        receiver?.let { runCatching { ctx.applicationContext.unregisterReceiver(it) } }
        receiver = null
    }

    /** Updates the model with a locally produced event (start requested, ...). */
    fun noteLocal(level: String, text: String) = appendLog(level, text)

    fun setTunnel(state: TunnelState, error: String? = null) {
        _state.update { it.copy(tunnel = state, error = error) }
    }

    /** Asks the service for its current state (screen returning to the foreground). */
    fun requestSnapshot(ctx: Context) {
        viewModelScope.launch {
            runCatching {
                ctx.applicationContext.sendBroadcast(
                    Intent(MeshVpnService.ACTION_REQUEST_SNAPSHOT)
                        .setPackage(ctx.applicationContext.packageName)
                )
            }
        }
    }

    private fun appendLog(level: String, text: String) {
        if (text.isBlank()) return
        val line = LogLine(System.currentTimeMillis(), level, text)
        _state.update { model ->
            val merged = (model.logs + line)
            model.copy(logs = if (merged.size > MAX_LOG) merged.takeLast(MAX_LOG) else merged)
        }
    }

    companion object {
        private const val MAX_LOG = 300
    }
}
