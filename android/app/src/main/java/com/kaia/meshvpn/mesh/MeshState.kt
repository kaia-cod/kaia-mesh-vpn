package com.kaia.meshvpn.mesh

/**
 * Unique source de vérité de l'UI.
 *
 * RÈGLE D'HONNÊTETÉ : aucun champ de ce modèle n'est fabriqué côté UI.
 * Tout vient soit du service (état du tunnel), soit du démon meshd
 * (événements JSON lus sur la socket locale). Si une donnée manque,
 * elle reste à zéro / liste vide et l'écran le DIT explicitement.
 */

/** État du tunnel TUN (rapporté par MeshVpnService). */
enum class TunnelState {
    DISCONNECTED,
    PREPARING,     // autorisation VPN demandée / service en démarrage
    CONNECTING,    // TUN levé, en attente du démon meshd
    CONNECTED,     // TUN levé + démon joignable + datapath actif
    ERROR,         // échec (cause dans [MeshModel.error])
    ;

    val isBusy: Boolean get() = this == PREPARING || this == CONNECTING
    val isUp: Boolean get() = this == CONNECTED
}

/** Un pair du maillage tel qu'annoncé par meshd (jamais inventé). */
data class MeshPeer(
    val virtualIp: String,
    val peerId: String,
    val shortId: String,
    val direction: String,      // "inbound" | "outbound" | "?" (rapporté par le démon)
    val lastSeenMs: Long = 0L,
)

/** Compteurs du datapath (rapportés par le démon, seconde par seconde). */
data class TrafficStats(
    val rxBytes: Long = 0L,        // mesh -> TUN (reçu du maillage)
    val txBytes: Long = 0L,        // TUN -> mesh (envoyé au maillage)
    val rxPps: Long = 0L,
    val txPps: Long = 0L,
    val droppedNoRoute: Long = 0L,
)

/** Une ligne du journal (événement réel : service, démon, système). */
data class LogLine(val timeMs: Long, val level: String, val text: String)

/** Modèle complet exposé à Compose. */
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
    /** Version courte d'un identifiant de pair (affichage compact). */
    companion object {
        fun shortPeerId(id: String): String =
            if (id.length <= 14) id else id.substring(0, 8) + "…" + id.takeLast(4)
    }
}
