package com.itantra.ui.transceiver

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import com.itantra.identity.IdentityManager
import com.itantra.mesh.ITantraMeshManager
import com.itantra.models.ModelCatalog
import com.itantra.schema.ITantraMessage
import com.itantra.schema.MessageType
import com.itantra.schema.Peer
import com.itantra.schema.VoicePreferences
import com.itantra.stt.SttManager
import com.itantra.stt.SttUnavailable
import com.itantra.tts.TtsManager
import com.itantra.ui.theme.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
    meshManager: ITantraMeshManager,
    identityManager: IdentityManager,
    onOpenLanguages: () -> Unit
) {
    val context = LocalContext.current
    val identity = remember { identityManager.getCurrentIdentity() }
    val connectedPeers by meshManager.connectedPeers.collectAsState()
    val isMeshRunning by meshManager.isMeshRunning.collectAsState()

    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val sttManager = remember { SttManager(context) }
    val ttsManager = remember { TtsManager(context) }

    val enabledLanguages by voicePrefs.enabledLanguages.collectAsState()
    val selectedLanguage by voicePrefs.activeLanguage.collectAsState()
    val secureSend by voicePrefs.secureSend.collectAsState()
    val autoSpeak by voicePrefs.autoSpeak.collectAsState()
    val sttState by sttManager.state.collectAsState()

    var messages by remember { mutableStateOf(listOf<ITantraMessage>()) }
    var typedText by remember { mutableStateOf("") }
    var isAlertMode by remember { mutableStateOf(false) }
    var isBypassMode by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    // Release model memory when the screen goes away (Rules §9).
    DisposableEffect(Unit) {
        onDispose {
            sttManager.release()
            ttsManager.release()
        }
    }

    // Listen to incoming messages over the mesh
    LaunchedEffect(Unit) {
        meshManager.incomingMessages.collect { msg ->
            messages = messages + msg
            coroutineScope.launch {
                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
            }
            // Read incoming traffic aloud. Alerts always speak; ordinary messages only
            // when auto-speak is on. A missing voice is logged, never fatal (Rules §7).
            val isAlert = msg.isAlert || msg.type == MessageType.ALERT
            if (isAlert || autoSpeak) {
                coroutineScope.launch {
                    ttsManager.speak(msg.text, selectedLanguage, alert = isAlert)
                }
            }
        }
    }

    // PTT pulse animation
    val infiniteTransition = rememberInfiniteTransition(label = "pttPulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isPttPressed) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "iTantra",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            // Mesh Status Pill
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isMeshRunning) AccentEmerald.copy(alpha = 0.2f) else AccentAlert.copy(alpha = 0.2f),
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isMeshRunning) AccentEmerald else AccentAlert
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(6.dp)
                                            .clip(CircleShape)
                                            .background(if (isMeshRunning) AccentEmerald else AccentAlert)
                                    )
                                    Text(
                                        text = if (isMeshRunning) "Mesh Online" else "Offline",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isMeshRunning) AccentEmerald else AccentAlert,
                                        fontSize = 10.sp
                                    )
                                }
                            }
                        }
                        Text(
                            text = "${identity?.displayName.orEmpty()} (${identity?.deviceModel.orEmpty()})",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentSaffronBright
                        )
                    }
                },
                actions = {
                    // Language Chip
                    FilledTonalButton(
                        onClick = { showLangPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = DarkSurfaceVariant)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Translate,
                            contentDescription = "Language",
                            tint = AccentSaffron,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = selectedLanguage.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }

                    // Alert Mode Toggle
                    IconButton(onClick = { isAlertMode = !isAlertMode }) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Alert Mode",
                            tint = if (isAlertMode) AccentAlert else TextMuted
                        )
                    }

                    // Secure send: Noise-encrypted unicast to each peer, versus the
                    // signed-but-public broadcast that reaches further over multi-hop.
                    IconButton(onClick = { voicePrefs.setSecureSend(!secureSend) }) {
                        Icon(
                            imageVector = if (secureSend) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = if (secureSend) {
                                "Encrypted send (Noise) — tap for broadcast"
                            } else {
                                "Broadcast send — tap for Noise encryption"
                            },
                            tint = if (secureSend) AccentEmerald else TextMuted
                        )
                    }

                    // Language Packs Screen Button
                    IconButton(onClick = onOpenLanguages) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Language Packs",
                            tint = TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = DarkSurface)
            )
        },
        containerColor = DarkBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Connected Peers Strip
            ConnectedPeersHeader(peers = connectedPeers)

            BatteryOptimizationNotice()

            // Alert Mode Banner
            AnimatedVisibility(visible = isAlertMode) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = AccentAlert.copy(alpha = 0.15f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentAlert.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Emergency,
                            contentDescription = "Alert Active",
                            tint = AccentAlert,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "ALERT MODE ACTIVE — Transmissions broadcast at max volume",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentAlert
                        )
                    }
                }
            }

            // Message Transcript List
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 48.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Hearing,
                                    contentDescription = null,
                                    tint = TextMuted,
                                    modifier = Modifier.size(40.dp)
                                )
                                Text(
                                    text = "Ready to Transceive",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = TextSecondary
                                )
                                Text(
                                    text = "Hold PTT to speak, or type a text message below.\nOnly text crosses the mesh link.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = TextMuted,
                                    fontSize = 12.sp,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }
                }

                items(messages, key = { it.msgId }) { msg ->
                    MessageBubble(
                        message = msg,
                        isMine = msg.senderId == identity?.peerId,
                        onReadAloud = {
                            coroutineScope.launch {
                                ttsManager.speak(
                                    text = msg.text,
                                    lang = selectedLanguage,
                                    alert = msg.isAlert || msg.type == MessageType.ALERT
                                )
                            }
                        }
                    )
                }
            }

            // Speech pipeline status — mirrors what STT is actually doing, so a failure
            // is visible rather than silent.
            SpeechStatusBar(state = sttState, onOpenLanguages = onOpenLanguages)

            // Bottom Input & PTT Controls
            Surface(
                color = DarkSurface,
                border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Typed Text Input Row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = typedText,
                            onValueChange = { typedText = it },
                            placeholder = { Text("Type a message...", color = TextMuted) },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = AccentSaffron,
                                unfocusedBorderColor = DarkBorder,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = DarkSurfaceVariant,
                                unfocusedContainerColor = DarkSurfaceVariant
                            )
                        )

                        IconButton(
                            onClick = {
                                if (typedText.isNotBlank()) {
                                    val sent = meshManager.sendTypedText(
                                        text = typedText.trim(),
                                        srcLang = selectedLanguage,
                                        isAlert = isAlertMode,
                                        secure = secureSend
                                    )
                                    if (sent != null) {
                                        messages = messages + sent
                                        coroutineScope.launch {
                                            listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                                        }
                                    }
                                    typedText = ""
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(AccentSaffron)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Send,
                                contentDescription = "Send",
                                tint = DarkBg
                            )
                        }
                    }

                    // Large Push-to-Talk (PTT) Button
                    Box(
                        modifier = Modifier
                            .scale(pulseScale)
                            .size(76.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    if (isAlertMode) listOf(AccentAlert, Color(0xFFB91C1C))
                                    else if (isPttPressed) listOf(AccentEmerald, AccentCyan)
                                    else listOf(AccentSaffron, Color(0xFFEA580C))
                                )
                            )
                            .pointerInput(selectedLanguage, isAlertMode, secureSend, hasMicPermission) {
                                detectTapGestures(
                                    onPress = {
                                        if (!hasMicPermission) {
                                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@detectTapGestures
                                        }

                                        // Mic opens on press and closes on release: the
                                        // captured audio is the utterance, nothing else.
                                        isPttPressed = true
                                        sttManager.startListening(selectedLanguage)

                                        tryAwaitRelease()
                                        isPttPressed = false

                                        coroutineScope.launch {
                                            val spoken = sttManager.stopAndTranscribe(selectedLanguage)
                                            if (spoken.isNullOrBlank()) return@launch

                                            val sent = meshManager.sendVoiceText(
                                                text = spoken,
                                                srcLang = selectedLanguage,
                                                isAlert = isAlertMode,
                                                secure = secureSend
                                            )
                                            if (sent != null) {
                                                messages = messages + sent
                                                listState.animateScrollToItem(
                                                    (messages.size - 1).coerceAtLeast(0)
                                                )
                                            }
                                        }
                                    }
                                )
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isPttPressed) Icons.Default.GraphicEq else Icons.Default.Mic,
                            contentDescription = "Push to Talk",
                            tint = DarkBg,
                            modifier = Modifier.size(36.dp)
                        )
                    }

                    Text(
                        text = when {
                            !hasMicPermission -> "TAP PTT TO GRANT MICROPHONE ACCESS"
                            sttState is SttManager.State.Transcribing -> "TRANSCRIBING SPEECH..."
                            isPttPressed -> "LISTENING — RELEASE TO SEND"
                            else -> "HOLD PTT TO TRANSMIT SPEECH"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isPttPressed) AccentEmerald else TextSecondary,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }

    // Language picker — multi-select, with live switching between enabled languages.
    // Nothing here is forced on launch; it opens only when the user asks for it.
    if (showLangPicker) {
        val catalog = remember { ModelCatalog.languages(context) }
        AlertDialog(
            onDismissRequest = { showLangPicker = false },
            title = {
                Column {
                    Text("Voice Languages", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Tap to speak in a language. Use the checkbox to keep it enabled.",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    catalog.forEach { spec ->
                        val isEnabled = spec.lang in enabledLanguages
                        val isActive = spec.lang == selectedLanguage
                        val sttReady = remember(spec.lang, showLangPicker) {
                            sttManager.isAvailable(spec.lang)
                        }
                        val ttsReady = remember(spec.lang, showLangPicker) {
                            ttsManager.isAvailable(spec.lang)
                        }

                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    // Switching is live — no restart, no reload prompt.
                                    voicePrefs.setActive(spec.lang)
                                    showLangPicker = false
                                },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isActive) AccentSaffron.copy(alpha = 0.2f) else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Checkbox(
                                    checked = isEnabled,
                                    onCheckedChange = { checked ->
                                        if (checked) voicePrefs.enable(spec.lang)
                                        else voicePrefs.disable(spec.lang)
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = AccentSaffron)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${spec.nativeName} (${spec.displayName})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isActive) AccentSaffron else TextPrimary
                                    )
                                    Text(
                                        text = buildString {
                                            append(if (sttReady) "STT ready" else "STT missing")
                                            append(" • ")
                                            append(if (ttsReady) "TTS ready" else "TTS missing")
                                            if (spec.bundled) append(" • bundled")
                                        },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (sttReady && ttsReady) AccentEmerald else TextMuted,
                                        fontSize = 10.sp
                                    )
                                }
                                if (isActive) {
                                    Icon(
                                        Icons.Default.RecordVoiceOver,
                                        contentDescription = "Active",
                                        tint = AccentSaffron,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showLangPicker = false
                    onOpenLanguages()
                }) {
                    Text("Manage packs", color = AccentCyan)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLangPicker = false }) {
                    Text("Close", color = AccentSaffron)
                }
            },
            containerColor = DarkSurface
        )
    }
}

@Composable
private fun SpeechStatusBar(state: SttManager.State, onOpenLanguages: () -> Unit) {
    val visible = state !is SttManager.State.Idle
    AnimatedVisibility(visible = visible) {
        val (accent, icon, label, detail) = when (state) {
            is SttManager.State.Listening -> Quad(
                if (state.speech) AccentEmerald else AccentSaffron,
                Icons.Default.Mic,
                if (state.speech) "SPEECH DETECTED" else "LISTENING",
                "Release PTT to transcribe and send"
            )
            is SttManager.State.Transcribing -> Quad(
                AccentCyan,
                Icons.Default.GraphicEq,
                "TRANSCRIBING",
                "Running IndicConformer on-device"
            )
            is SttManager.State.Unavailable -> when (val reason = state.reason) {
                is SttUnavailable.ModelMissing -> Quad(
                    AccentAlert,
                    Icons.Default.CloudDownload,
                    "SPEECH MODEL MISSING (${reason.lang.uppercase()})",
                    "Install the language pack to enable voice input"
                )
                is SttUnavailable.LoadFailed -> Quad(
                    AccentAlert, Icons.Default.ErrorOutline, "SPEECH ENGINE FAILED", reason.reason
                )
                SttUnavailable.PermissionDenied -> Quad(
                    AccentAlert,
                    Icons.Default.MicOff,
                    "MICROPHONE BLOCKED",
                    "Grant microphone access to use push-to-talk"
                )
            }
            SttManager.State.Idle -> Quad(TextMuted, Icons.Default.Mic, "", "")
        }

        Surface(
            color = accent.copy(alpha = 0.15f),
            border = androidx.compose.foundation.BorderStroke(1.dp, accent.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    if (detail.isNotEmpty()) {
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 10.sp
                        )
                    }
                }
                if (state is SttManager.State.Unavailable &&
                    state.reason is SttUnavailable.ModelMissing
                ) {
                    TextButton(onClick = onOpenLanguages) {
                        Text("Install", color = accent)
                    }
                }
            }
        }
    }
}

/** Small holder so the status branches above can destructure in one expression. */
private data class Quad(
    val accent: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val detail: String
)

@Composable
private fun ConnectedPeersHeader(peers: List<Peer>) {
    var query by remember { mutableStateOf("") }
    val filteredPeers = remember(peers, query) {
        peers.filter { it.name.contains(query.trim(), ignoreCase = true) }
    }
    Surface(
        color = DarkSurface,
        border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Hub,
                        contentDescription = "Peers",
                        tint = AccentCyan,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "NEARBY MESH PEERS (${peers.size})",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = TextSecondary
                    )
                }
            }

            if (peers.isEmpty()) {
                Text(
                    text = "Scanning for nearby iTantra nodes over BLE mesh. No peers nearby to search yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                enabled = peers.isNotEmpty(),
                singleLine = true,
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = null, tint = AccentCyan)
                },
                placeholder = {
                    Text(if (peers.isEmpty()) "No peers nearby to search" else "Filter nearby peers by name")
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = AccentCyan,
                    unfocusedBorderColor = DarkBorder,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary
                )
            )

            if (peers.isNotEmpty() && filteredPeers.isEmpty()) {
                Text(
                    text = "No nearby peer matches \"${query.trim()}\" — they must be nearby and broadcasting.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else if (filteredPeers.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 6.dp)
                ) {
                    items(filteredPeers) { peer ->
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = DarkSurfaceVariant,
                            border = androidx.compose.foundation.BorderStroke(1.dp, DarkBorder)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(AccentEmerald)
                                )
                                Column {
                                    Text(
                                        text = peer.name,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                    Text(
                                        text = peer.deviceModel.ifEmpty { "Node ${peer.peerId.take(4)}" },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BatteryOptimizationNotice() {
    val context = LocalContext.current
    val manufacturer = Build.MANUFACTURER.orEmpty()
    val isXiaomiFamily = manufacturer.contains("xiaomi", ignoreCase = true) ||
        manufacturer.contains("redmi", ignoreCase = true) ||
        manufacturer.contains("poco", ignoreCase = true)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val ignoringOptimizations = powerManager?.isIgnoringBatteryOptimizations(context.packageName) == true

    if (isXiaomiFamily && !ignoringOptimizations) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = AccentAlert.copy(alpha = 0.12f),
            border = androidx.compose.foundation.BorderStroke(1.dp, AccentAlert.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Default.BatteryAlert, contentDescription = null, tint = AccentAlert)
                Column(modifier = Modifier.weight(1f)) {
                    Text("Allow unrestricted battery use", color = TextPrimary, fontWeight = FontWeight.Bold)
                    Text(
                        "Xiaomi/MIUI can stop BLE discovery. Disable battery optimization and enable Autostart for iTantra.",
                        color = TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                TextButton(onClick = {
                    try {
                        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:${context.packageName}")
                        }
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        android.util.Log.e("BatteryOptimization", "BATTERY_SETTINGS_OPEN_FAILED: ${e.message}", e)
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }
                }) {
                    Text("Fix", color = AccentAlert)
                }
            }
        }
    }
}

@Composable
private fun MessageBubble(
    message: ITantraMessage,
    isMine: Boolean,
    onReadAloud: () -> Unit
) {
    val isAlert = message.isAlert || message.type == MessageType.ALERT
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(message.ts) { timeFormatter.format(Date(message.ts)) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
    ) {
        // Sender Name & Device Model Header
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Text(
                text = if (isMine) "You" else message.senderName,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (isMine) AccentSaffron else AccentCyan
            )
            Text(
                text = "• ${message.deviceModel}",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 10.sp
            )
            Text(
                text = "• $formattedTime",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                fontSize = 10.sp
            )
        }

        // Bubble Surface
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMine) 16.dp else 4.dp,
                bottomEnd = if (isMine) 4.dp else 16.dp
            ),
            color = if (isAlert) AccentAlert.copy(alpha = 0.25f)
            else if (isMine) DarkSurfaceVariant
            else DarkSurface,
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isAlert) AccentAlert
                else if (isMine) AccentSaffron.copy(alpha = 0.5f)
                else DarkBorder
            )
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Type & Lang Tag
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = when (message.type) {
                            MessageType.VOICE_TEXT -> Icons.Default.RecordVoiceOver
                            MessageType.ALERT -> Icons.Default.Emergency
                            MessageType.TYPED_TEXT -> Icons.Default.ChatBubbleOutline
                            MessageType.SYSTEM -> Icons.Default.Info
                        },
                        contentDescription = null,
                        tint = if (isAlert) AccentAlert else AccentSaffron,
                        modifier = Modifier.size(14.dp)
                    )

                    Text(
                        text = when (message.type) {
                            MessageType.VOICE_TEXT -> "SPEECH"
                            MessageType.ALERT -> "ALERT"
                            MessageType.TYPED_TEXT -> "TEXT"
                            MessageType.SYSTEM -> "SYSTEM"
                        } + " • ${message.srcLang.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (isAlert) AccentAlert else TextSecondary,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )

                    // Read-aloud on demand: every message exists as both text and speech.
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "Read aloud",
                        tint = if (isAlert) AccentAlert else AccentCyan,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable { onReadAloud() }
                    )
                }

                // Message Text Content
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )
            }
        }
    }
}
