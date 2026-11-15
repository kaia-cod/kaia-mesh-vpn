package com.kaia.meshvpn.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kaia.meshvpn.mesh.LogLine
import com.kaia.meshvpn.mesh.MeshModel
import com.kaia.meshvpn.mesh.MeshPeer
import com.kaia.meshvpn.mesh.TrafficStats
import com.kaia.meshvpn.mesh.TunnelState
import com.kaia.meshvpn.ui.theme.Amber
import com.kaia.meshvpn.ui.theme.Danger
import com.kaia.meshvpn.ui.theme.Jade
import com.kaia.meshvpn.ui.theme.Muted
import com.kaia.meshvpn.ui.theme.levelColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main screen of the mesh VPN.
 *
 * RULE: everything displayed comes from the [MeshModel] fed by the
 * service. When a datum does not exist (no peer, no byte exchanged),
 * the screen states it explicitly instead of inventing servers or countries.
 */
@Composable
fun KaiaApp(
    model: MeshModel,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 16.dp, end = 16.dp, top = 20.dp, bottom = 28.dp
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header(model) }
            item { TunnelCard(model = model, onConnect = onConnect, onDisconnect = onDisconnect) }
            item { AddressCard(model) }
            item { TrafficCard(model.stats) }
            item { SectionTitle("Mesh peers", "${model.peers.size}") }
            if (model.peers.isEmpty()) {
                item { EmptyPeersCard(tunnelUp = model.tunnel.isUp) }
            } else {
                items(model.peers) { peer -> PeerRow(peer) }
            }
            item { SectionTitle("Log", "${model.logs.size}") }
            if (model.logs.isEmpty()) {
                item {
                    Text(
                        "No events yet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            } else {
                item { LogPanel(model.logs) }
            }
            item { HonestyFooter() }
        }
    }
}

/* ------------------------------------------------------------------ header */

@Composable
private fun Header(model: MeshModel) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.linearGradient(listOf(scheme.primary, scheme.primaryContainer))
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text("K", fontWeight = FontWeight.Bold, fontSize = 22.sp, color = scheme.onPrimary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Kaia Mesh VPN", style = MaterialTheme.typography.titleLarge, color = scheme.onBackground)
            Text(
                "Peer-to-peer mesh network · encrypted (Noise)",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
        StatusPill(model.tunnel)
    }
}

@Composable
private fun StatusPill(state: TunnelState) {
    val color by animateColorAsState(
        targetValue = when (state) {
            TunnelState.CONNECTED -> Jade
            TunnelState.CONNECTING, TunnelState.PREPARING -> Amber
            TunnelState.ERROR -> Danger
            TunnelState.DISCONNECTED -> Muted
        },
        label = "pill",
    )
    val label = when (state) {
        TunnelState.CONNECTED -> "CONNECTED"
        TunnelState.CONNECTING -> "CONNECTING"
        TunnelState.PREPARING -> "PREPARING"
        TunnelState.ERROR -> "ERROR"
        TunnelState.DISCONNECTED -> "STOPPED"
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.45f), CircleShape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PulsingDot(active = state == TunnelState.CONNECTED, color = color)
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun PulsingDot(active: Boolean, color: Color) {
    val transition = rememberInfiniteTransition(label = "dot")
    val alpha by transition.animateFloat(
        initialValue = if (active) 1f else 0.65f,
        targetValue = if (active) 0.25f else 0.65f,
        animationSpec = infiniteRepeatable(tween(1100), RepeatMode.Reverse),
        label = "dotAlpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/* ------------------------------------------------------------------- tunnel */

@Composable
private fun TunnelCard(model: MeshModel, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val busy = model.tunnel.isBusy
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(
                when (model.tunnel) {
                    TunnelState.DISCONNECTED -> "The tunnel is down"
                    TunnelState.PREPARING -> "VPN permission…"
                    TunnelState.CONNECTING -> "TUN is up, contacting the daemon…"
                    TunnelState.CONNECTED -> "The tunnel is active"
                    TunnelState.ERROR -> "The tunnel could not start"
                },
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                when (model.tunnel) {
                    TunnelState.DISCONNECTED ->
                        "No traffic is going through the mesh. Your apps are using the device's normal connection."
                    TunnelState.PREPARING ->
                        "Android is asking for your permission to create a local VPN interface."
                    TunnelState.CONNECTING ->
                        "The meshd daemon is starting and opening its local socket."
                    TunnelState.CONNECTED ->
                        "All IP traffic is routed to the virtual interface and then handed to the mesh."
                    TunnelState.ERROR ->
                        model.error ?: "Unknown cause."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (model.tunnel == TunnelState.ERROR) Danger else scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            if (model.tunnel == TunnelState.DISCONNECTED || model.tunnel == TunnelState.ERROR) {
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = scheme.primary,
                        contentColor = scheme.onPrimary,
                    ),
                ) {
                    Text(
                        if (model.tunnel == TunnelState.ERROR) "Try again" else "Connect tunnel",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                    )
                }
            } else if (busy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(scheme.surfaceVariant),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = scheme.primary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Connecting…", color = scheme.onSurfaceVariant, fontSize = 14.sp)
                }
            } else {
                OutlinedButton(
                    onClick = onDisconnect,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Danger.copy(alpha = 0.6f)),
                ) {
                    Text("Disconnect", color = Danger, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ adresse */

@Composable
private fun AddressCard(model: MeshModel) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "VIRTUAL ADDRESS",
                style = MaterialTheme.typography.labelSmall,
                color = scheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                model.virtualIp.ifBlank { "—" },
                style = MaterialTheme.typography.displaySmall,
                color = if (model.virtualIp.isBlank()) Muted else scheme.primary,
                fontFamily = FontFamily.Monospace,
            )
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = scheme.outline)
            Spacer(Modifier.height(12.dp))
            InfoRow("meshd daemon", if (model.daemonPid > 0) "pid ${model.daemonPid} · alive" else "not running")
            InfoRow("MTU", if (model.mtu > 0) "${model.mtu} bytes" else "—")
            InfoRow("VPN route", model.route.ifBlank { "—" })
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurface,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/* ------------------------------------------------------------------ trafic */

@Composable
private fun TrafficCard(stats: TrafficStats) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Metric("RECEIVED", formatBytes(stats.rxBytes), "${stats.rxPps} pkt/s", Modifier.weight(1f))
            Metric("SENT", formatBytes(stats.txBytes), "${stats.txPps} pkt/s", Modifier.weight(1f))
            Metric(
                "DROPPED",
                stats.droppedNoRoute.toString(),
                "no route",
                Modifier.weight(1f),
                // null = default theme colour, resolved in Metric().
                if (stats.droppedNoRoute > 0) Danger else null,
            )
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
    sub: String,
    modifier: Modifier = Modifier,
    // null = default colour: a @Composable can NOT be read in a
    // default argument (Compose compilation error), so we resolve it here.
    valueColor: Color? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val resolvedColor = valueColor ?: scheme.onSurface
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(value, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = resolvedColor)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
    }
}

/* ------------------------------------------------------------------- pairs */

@Composable
private fun SectionTitle(title: String, badge: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        Text(
            badge,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun EmptyPeersCard(tunnelUp: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(scheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) { Text("◌", fontSize = 22.sp, color = scheme.onSurfaceVariant) }
            Spacer(Modifier.height(12.dp))
            Text(
                "No mesh peers",
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (tunnelUp)
                    "The tunnel is up but no other node of your mesh is reachable right now. " +
                        "This VPN is not a commercial service: there is no per-country exit server. " +
                        "The app only connects to the nodes you deployed and that announce " +
                        "their virtual address on the IPFS DHT."
                else
                    "The peer list fills up as soon as the tunnel is connected and other nodes of the " +
                        "mesh announce themselves. No country and no server is simulated here.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PeerRow(peer: MeshPeer) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(Jade.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) { Text("⬡", color = Jade, fontSize = 16.sp) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(peer.virtualIp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp, color = scheme.onSurface)
                Text(
                    "${peer.shortId} · ${if (peer.direction == "inbound") "inbound" else if (peer.direction == "outbound") "outbound" else "unknown direction"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
            }
            Text(
                if (peer.lastSeenMs > 0) ago(peer.lastSeenMs) else "—",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ journal */

@Composable
private fun LogPanel(logs: List<LogLine>) {
    val scheme = MaterialTheme.colorScheme
    val listState = rememberLazyListState()
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outline),
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(logs) { line ->
                Row {
                    Text(
                        time(line.timeMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = Muted,
                        fontFamily = FontFamily.Monospace,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodySmall,
                        color = levelColor(line.level),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HonestyFooter() {
    Text(
        "No traffic ever transits a commercial server: the peers listed are nodes that you " +
            "operate yourself. Nothing displayed here is simulated.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, start = 8.dp, end = 8.dp),
    )
}

/* ------------------------------------------------------------------ helpers */

private fun formatBytes(v: Long): String = when {
    v < 1024 -> "$v o"
    v < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", v / 1024.0)
    v < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f MB", v / (1024.0 * 1024))
    else -> String.format(Locale.US, "%.1f GB", v / (1024.0 * 1024 * 1024))
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private fun time(ms: Long): String = clock.format(Date(ms))
private fun ago(ms: Long): String {
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60} min ago"
        else -> "${d / 3600} h ago"
    }
}
