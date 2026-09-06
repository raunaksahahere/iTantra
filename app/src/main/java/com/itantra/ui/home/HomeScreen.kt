package com.itantra.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.mesh.ITantraMeshManager
import com.itantra.schema.Peer
import com.itantra.ui.theme.*
import com.itantra.schema.VoicePreferences
import kotlinx.coroutines.launch

/**
 * Peer-first home screen: search, then the people this phone can actually reach.
 *
 * There is deliberately **no broadcast button here**. Every conversation starts by
 * choosing who it is with, which is what makes the peer list the primary object on the
 * screen rather than a status readout above a transcript.
 *
 * Distress is the one exception and sits outside the list, because it is not addressed to
 * a peer — it goes to everyone in range and must stay reachable without first picking
 * someone.
 */
@Composable
fun HomeScreen(
    meshManager: ITantraMeshManager,
    onOpenPeer: (Peer) -> Unit,
    onOpenLanguages: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val peers by meshManager.connectedPeers.collectAsState()
    val sosAnnouncements by meshManager.sos.announcements.collectAsState()
    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val selectedLanguage by voicePrefs.activeLanguage.collectAsState()
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var showSosDialog by remember { mutableStateOf(false) }

    // Match on name, device model and peer id: in a crowd the display name is often the
    // least distinctive thing about a node.
    val filtered = remember(peers, query) {
        val q = query.trim()
        if (q.isEmpty()) peers
        else peers.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.deviceModel.contains(q, ignoreCase = true) ||
                it.peerId.contains(q, ignoreCase = true)
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(SurfaceBg)) {

        // ---- title bar -------------------------------------------------------------
        Surface(color = SurfaceCard, shadowElevation = 1.dp) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "iTantra",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = AccentSaffron,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onOpenLanguages) {
                    Icon(
                        Icons.Default.Language,
                        contentDescription = "Language packs",
                        tint = TextSecondary
                    )
                }
            }
        }

        // ---- search ----------------------------------------------------------------
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text("Search people nearby", color = TextMuted) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = TextMuted) },
            shape = RoundedCornerShape(24.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = SurfaceVariantBg,
                unfocusedContainerColor = SurfaceVariantBg,
                focusedBorderColor = AccentSaffron,
                unfocusedBorderColor = BorderSubtle
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        )

        // ---- distress, kept out of the peer list ------------------------------------
        SosSection(
            activeCount = sosAnnouncements.size,
            onRaise = { showSosDialog = true }
        )

        // ---- peers -----------------------------------------------------------------
        if (filtered.isEmpty()) {
            EmptyPeers(hasPeers = peers.isNotEmpty(), query = query)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(filtered, key = { it.peerId }) { peer ->
                    PeerRow(peer = peer, onClick = { onOpenPeer(peer) })
                    HorizontalDivider(color = BorderSubtle, thickness = 0.5.dp)
                }
            }
        }
    }

    if (showSosDialog) {
        SosDialog(
            onDismiss = { showSosDialog = false },
            onConfirm = { note ->
                showSosDialog = false
                scope.launch {
                    meshManager.sos.raise(
                        text = note.ifBlank { "Distress signal — assistance needed" },
                        srcLang = selectedLanguage
                    )
                }
            }
        )
    }
}

@Composable
private fun SosSection(activeCount: Int, onRaise: () -> Unit) {
    Surface(
        color = if (activeCount > 0) AccentAlert.copy(alpha = 0.10f) else SurfaceCard,
        border = BorderStroke(1.dp, if (activeCount > 0) AccentAlert else BorderSubtle),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 10.dp)
            .clickable { onRaise() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(AccentAlert),
                contentAlignment = Alignment.Center
            ) {
                Text("SOS", color = SurfaceCard, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Send distress signal",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = if (activeCount > 0) {
                        "$activeCount active nearby — tap to raise your own"
                    } else {
                        "Reaches everyone in range, not one person"
                    },
                    color = if (activeCount > 0) AccentAlert else TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun PeerRow(peer: Peer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(SurfaceVariantBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = peer.name.trim().take(1).uppercase().ifBlank { "?" },
                color = AccentCyan,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = peer.name,
                fontWeight = FontWeight.SemiBold,
                color = TextPrimary,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1
            )
            Text(
                text = peer.deviceModel.ifBlank { "Mesh node" },
                color = TextSecondary,
                fontSize = 12.sp,
                maxLines = 1
            )
        }

        HopBadge(hops = peer.hops)
    }
}

/**
 * Direct peers and relayed peers are both reachable, but they are not the same thing —
 * a 4-hop peer can vanish because a phone in the middle walked away, so the distance is
 * worth showing rather than flattening into "online".
 */
@Composable
private fun HopBadge(hops: Int) {
    val direct = hops <= 1
    Surface(
        color = if (direct) AccentEmerald.copy(alpha = 0.12f) else PeerBadgeBg,
        border = BorderStroke(1.dp, if (direct) AccentEmerald else PeerBadgeBorder),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(
            text = if (direct) "direct" else "$hops hops",
            color = if (direct) AccentEmerald else TextSecondary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
private fun EmptyPeers(hasPeers: Boolean, query: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (hasPeers) "No one matches \"$query\"" else "No one nearby yet",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = TextSecondary
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (hasPeers) {
                "Clear the search to see everyone in range."
            } else {
                "Phones running iTantra appear here automatically once " +
                    "they are in Bluetooth range."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SosDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceCard,
        title = {
            Text("Send distress signal?", fontWeight = FontWeight.Bold, color = AccentAlert)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This reaches every phone in range and keeps propagating for one hour. " +
                        "Your location is attached but only revealed when someone taps it.",
                    color = TextSecondary,
                    fontSize = 13.sp
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("What is wrong? (optional)", color = TextMuted) },
                    singleLine = false,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(note) }) {
                Text("Send SOS", color = AccentAlert, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}
