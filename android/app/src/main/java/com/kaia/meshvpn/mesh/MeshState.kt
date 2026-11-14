package com.kaia.meshvpn.mesh

/**
 * Single source of truth for the UI.
 *
 * HONESTY RULE: no field of this model is fabricated on the UI side.
 * Everything comes either from the service (tunnel state) or from the meshd
 * daemon (JSON events read on the local socket). If a value is missing it
 * stays at zero / empty and the screen SAYS so explicitly.
 */

/** TUN tunnel state (reported by MeshVpnService). */
enum class TunnelState {
    DISCONNECTED,
    PREPARING,     // VPN consent requested / service starting
    CONNECTING,    // TUN up, waiting for the meshd daemon
    CONNECTED,     // TUN up + daemon reachable + datapath active
    ERROR,         // failure (cause in [MeshModel.error])
    ;

    val isBusy: Boolean get() = this == PREPARING || this == CONNECTING
    val isUp: Boolean get() = this == CONNECTED
}

/** A mesh peer as announced by meshd (never invented). */
data class MeshPeer(
    val virtualIp: String,
    val peerId: String,
    val shortId: String,
    val direction: String,      // "inbound" | "outbound" | "?" (reported by the daemon)
    val lastSeenMs: Long = 0L,
)

/** Datapath counters (reported by the daemon, second by second). */
data class TrafficStats(
    val rxBytes: Long = 0L,        // mesh -> TUN (received from the mesh)
    val txBytes: Long = 0L,        // TUN -> mesh (sent to the mesh)
    val rxPps: Long = 0L,
    val txPps: Long = 0L,
    val droppedNoRoute: Long = 0L,
)

/** One log line (real event: service, daemon, system). */
data class LogLine(val timeMs: Long, val level: String, val text: String)

/** Full model exposed to Compose. */
data class MeshModel(
    val tunnel: TunnelState = TunnelState.DISCONNECTED,
    val virtualIp: String = "",
    val daemonPid: Int = 0,
    val daemonAlive: Boolean = false,
    val mtu: Int = 0,
    val route: String = "",
    val peers: List<MeshPeer> = emptyList(),
    val stats: TrafficStats = TrafficStats(),
    val logs: List<LogLine> = emptyList(),
    val error: String? = null,
) {
    /** Short form of a peer id (compact display). */
    companion object {
        fun shortPeerId(id: String): String =
            if (id.length <= 14) id else id.substring(0, 8) + "…" + id.takeLast(4)
    }
}
