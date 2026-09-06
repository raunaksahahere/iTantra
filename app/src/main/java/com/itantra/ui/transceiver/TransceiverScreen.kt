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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import android.util.Log
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.os.Build
import com.itantra.identity.IdentityManager
import com.itantra.mesh.ITantraMeshManager
import com.itantra.mesh.transport.BleBlocker
import com.itantra.mesh.transport.BleReadiness
import com.itantra.models.ModelCatalog
import com.itantra.schema.ITantraMessage
import com.itantra.schema.MessageType
import com.itantra.schema.Peer
import com.itantra.schema.VoicePreferences
import com.itantra.stt.SttManager
import com.itantra.stt.SttUnavailable
import com.itantra.translate.TranslationManager
import com.itantra.tts.TtsManager
import com.itantra.ui.theme.*
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransceiverScreen(
    meshManager: ITantraMeshManager,
    identityManager: IdentityManager,
    peer: Peer,
    onBack: () -> Unit,
    onOpenLanguages: () -> Unit
) {
    val context = LocalContext.current
    val identity = remember { identityManager.getCurrentIdentity() }
    val connectedPeers by meshManager.connectedPeers.collectAsState()
    val isMeshRunning by meshManager.isMeshRunning.collectAsState()

    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val sttManager = remember { SttManager(context) }
    val ttsManager = remember { TtsManager(context) }
    val translationManager = remember { TranslationManager(context) }

    val enabledLanguages by voicePrefs.enabledLanguages.collectAsState()
    val selectedLanguage by voicePrefs.activeLanguage.collectAsState()
    val secureSend by voicePrefs.secureSend.collectAsState()
    val autoSpeak by voicePrefs.autoSpeak.collectAsState()
    val sttState by sttManager.state.collectAsState()
    val sosAnnouncements by meshManager.sos.announcements.collectAsState()

    var messages by remember { mutableStateOf(listOf<ITantraMessage>()) }

    // Translation outcome per message id. Kept beside the list rather than inside
    // ITantraMessage because it is this reader's view of a packet, not part of the
    // packet: the same broadcast resolves differently on every phone that hears it.
    var translations by remember {
        mutableStateOf(mapOf<String, TranslationManager.Outcome>())
    }
    var typedText by remember { mutableStateOf("") }
    var isAlertMode by remember { mutableStateOf(false) }
    var isBypassMode by remember { mutableStateOf(false) }
    var isPttPressed by remember { mutableStateOf(false) }
    var showLangPicker by remember { mutableStateOf(false) }

    // When set, messages are addressed to this peer and routed hop-by-hop instead of
    // being broadcast to the whole mesh (§4.3 targeted ranged messaging).
    // This screen is a conversation *with one peer*, so the target is fixed by
    // navigation rather than chosen here. It is never cleared to null: null means
    // "broadcast", and silently turning a private message into a broadcast because the
    // recipient walked out of range is exactly the wrong failure.
    val selectedPeerId = peer.peerId
    val peerPresent = connectedPeers.any { it.peerId == peer.peerId }
    var showSosDialog by remember { mutableStateOf(false) }

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
            translationManager.release()
        }
    }

    // Listen to incoming messages over the mesh
    LaunchedEffect(Unit) {
        meshManager.incomingMessages.collect { msg ->
            // This screen is one conversation, so it shows that conversation: traffic
            // from this peer, plus our own. Distress is the deliberate exception — it is
            // addressed to everyone in range, and hiding it because the reader happens to
            // have a chat open is not a trade worth making in an emergency.
            val emergency = msg.isAlert ||
                msg.type == MessageType.ALERT ||
                msg.type == MessageType.SOS ||
                msg.type == MessageType.SOS_RESOLVED
            val mine = msg.senderId == identity?.peerId
            if (!emergency && !mine && msg.senderId != peer.peerId) {
                return@collect
            }
            messages = messages + msg
            coroutineScope.launch {
                listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
            }
            // Read incoming traffic aloud. Alerts always speak; ordinary messages only
            // when auto-speak is on. A missing voice is logged, never fatal (Rules §7).
            val isAlert = msg.isAlert || msg.type == MessageType.ALERT
            coroutineScope.launch {
                // Translate into *this* phone's language before speaking. The sender
                // transmitted in its own language and deliberately did not translate, so
                // that one broadcast can reach speakers of several languages at once —
                // each phone resolves it locally. Same-language traffic short-circuits
                // inside the manager and never touches a model.
                val outcome = translationManager.translate(
                    text = msg.text,
                    source = msg.srcLang,
                    target = selectedLanguage
                )
                translations = translations + (msg.msgId to outcome)

                if (isAlert || autoSpeak) {
                    // Speak whatever the reader is actually shown. Speaking the original
                    // with the local voice is how Hindi text ends up read aloud by an
                    // English voice, which is worse than staying silent.
                    val spoken = when (outcome) {
                        is TranslationManager.Outcome.Translated -> outcome.text
                        is TranslationManager.Outcome.NotNeeded -> outcome.text
                        is TranslationManager.Outcome.Failed -> null
                    }
                    if (spoken != null) {
                        ttsManager.speak(spoken, selectedLanguage, alert = isAlert)
                    } else {
                        Log.w(
                            "Transceiver",
                            "not speaking ${msg.msgId}: no ${msg.srcLang}->$selectedLanguage " +
                                "translation, and the local voice cannot read ${msg.srcLang}"
                        )
                    }
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
                            color = AccentSaffronDeep
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to people",
                            tint = TextSecondary
                        )
                    }
                },
                actions = {
                    // Language Chip
                    FilledTonalButton(
                        onClick = { showLangPicker = true },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(containerColor = SurfaceVariantBg)
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

                    // Raise a distress announcement.
                    IconButton(onClick = { showSosDialog = true }) {
                        Icon(
                            imageVector = Icons.Default.Sos,
                            contentDescription = "Raise distress announcement",
                            tint = AccentAlert
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
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        containerColor = SurfaceBg
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Who this conversation is with, and whether they are still reachable.
            // Losing the peer does not silently widen the audience — it says so.
            PeerPresenceStrip(
                peer = connectedPeers.firstOrNull { it.peerId == peer.peerId } ?: peer,
                present = peerPresent
            )

            // Why discovery cannot work, if it cannot. A revoked permission or an off Location
            // toggle stops the mesh outright, while the MIUI warning is about staying alive once
            // it is already running — so only one of the two is ever worth showing, and it is
            // this one. Stacking both put two full-width warning bars above the transcript and
            // pointed the user at the less important fix.
            val meshBlocked = MeshReadinessNotice()

            if (!meshBlocked) {
                BatteryOptimizationNotice()
            }

            // Live distress announcements this device is holding and relaying.
            if (sosAnnouncements.isNotEmpty()) {
                SosBanner(
                    announcements = sosAnnouncements,
                    locationProvider = remember { com.itantra.mesh.LocationProvider(context) },
                    onResolve = { meshManager.sos.resolve(it) }
                )
            }


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
                        translation = translations[msg.msgId],
                        onReadAloud = {
                            coroutineScope.launch {
                                ttsManager.speak(
                                    text = (translations[msg.msgId] as?
                                        TranslationManager.Outcome.Translated)?.text
                                        ?: msg.text,
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
                color = SurfaceCard,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                unfocusedBorderColor = BorderSubtle,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                focusedContainerColor = SurfaceVariantBg,
                                unfocusedContainerColor = SurfaceVariantBg
                            )
                        )

                        IconButton(
                            onClick = {
                                if (typedText.isNotBlank()) {
                                    val sent = meshManager.sendTypedText(
                                        text = typedText.trim(),
                                        srcLang = selectedLanguage,
                                        isAlert = isAlertMode,
                                        recipientPeerId = selectedPeerId,
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
                                tint = SurfaceBg
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
                            .pointerInput(selectedLanguage, isAlertMode, secureSend, hasMicPermission, selectedPeerId) {
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
                                                recipientPeerId = selectedPeerId,
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
                            tint = SurfaceBg,
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

    if (showSosDialog) {
        SosConfirmDialog(
            onDismiss = { showSosDialog = false },
            onConfirm = { note ->
                showSosDialog = false
                coroutineScope.launch {
                    val raised = meshManager.sos.raise(
                        text = note.ifBlank { "Distress signal — assistance needed" },
                        srcLang = selectedLanguage
                    )
                    if (raised != null) {
                        messages = messages + raised
                        listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0))
                    }
                }
            }
        )
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
            containerColor = SurfaceCard
        )
    }
}

/** Small holder so the status branches below can destructure in one expression. */
private data class Quad(
    val accent: Color,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val detail: String
)

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

@Composable
private fun SosBanner(
    announcements: List<com.itantra.mesh.SosManager.Active>,
    locationProvider: com.itantra.mesh.LocationProvider,
    onResolve: (String) -> Unit
) {
    var expanded by remember { mutableStateOf<String?>(null) }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AccentAlert.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, AccentAlert.copy(alpha = 0.45f))
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(Icons.Default.Sos, contentDescription = null, tint = AccentAlert, modifier = Modifier.size(18.dp))
                Text(
                    text = "${announcements.size} ACTIVE DISTRESS ${if (announcements.size == 1) "CALL" else "CALLS"}",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = AccentAlert
                )
            }

            announcements.forEach { entry ->
                val msg = entry.message
                val minsLeft = msg.remainingMillis() / 60000

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { expanded = if (expanded == msg.msgId) null else msg.msgId }
                        .padding(vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (entry.isMine) "You" else msg.senderName,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = msg.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                fontSize = 12.sp
                            )
                            Text(
                                text = buildString {
                                    append("${minsLeft} min left")
                                    append(if (msg.hasLocation) "  •  location attached" else "  •  no location")
                                    append("  •  tap for details")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                fontSize = 10.sp
                            )
                        }
                        if (entry.isMine) {
                            TextButton(onClick = { onResolve(msg.msgId) }) {
                                Text("Resolve", color = AccentEmerald, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (expanded == msg.msgId) {
                        val here = remember(msg.msgId) { locationProvider.lastKnown() }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = SurfaceCard,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = if (msg.lat != null && msg.lon != null) {
                                        "Origin: %.5f, %.5f".format(msg.lat, msg.lon) +
                                            (msg.gpsAccuracyM?.let { " (±${it.toInt()} m)" } ?: "")
                                    } else {
                                        "Origin: no position — the sender had no GPS fix"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextPrimary,
                                    fontSize = 11.sp
                                )
                                Text(
                                    text = if (here != null) {
                                        "You: %.5f, %.5f".format(here.latitude, here.longitude)
                                    } else {
                                        "You: no position available"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary,
                                    fontSize = 11.sp
                                )
                                if (msg.lat != null && msg.lon != null && here != null) {
                                    val metres = com.itantra.mesh.RangePolicy.distanceMeters(
                                        msg.lat, msg.lon, here.latitude, here.longitude
                                    )
                                    Text(
                                        text = "Approximately ${com.itantra.mesh.RangePolicy.formatDistance(metres)} away",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = AccentAlert,
                                        fontSize = 11.sp
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

/** Confirmation before broadcasting a distress call, with an optional note. */
@Composable
private fun SosConfirmDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var note by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Default.Sos, contentDescription = null, tint = AccentAlert) },
        title = { Text("Broadcast distress call?", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "This goes to every phone in range and keeps being passed on for one hour, " +
                        "including to people who arrive later. Your location is attached if " +
                        "available. You can cancel it at any time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    placeholder = { Text("Add a short note (optional)", color = TextMuted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentAlert,
                        unfocusedBorderColor = BorderSubtle,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary
                    )
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(note) },
                colors = ButtonDefaults.buttonColors(containerColor = AccentAlert)
            ) {
                Text("Broadcast SOS", fontWeight = FontWeight.Bold, color = SurfaceCard)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        },
        containerColor = SurfaceCard
    )
}

/**
 * Says who this conversation is with and whether they are still reachable.
 *
 * There is no "clear target" affordance any more: with a peer-first home screen, a
 * conversation without a peer has no meaning, and clearing the target used to mean
 * "broadcast to everyone" — far too easy to hit by accident.
 */
@Composable
private fun PeerPresenceStrip(peer: Peer, present: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (present) BubbleMine else SurfaceVariantBg,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (present) AccentSaffron.copy(alpha = 0.5f) else BorderSubtle
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.AlternateEmail,
                contentDescription = null,
                tint = if (present) AccentSaffronDeep else TextMuted,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = if (present) {
                    "Sending to ${peer.name}" +
                        if (peer.hops <= 1) " (direct)" else " (${peer.hops} hops away)"
                } else {
                    "${peer.name} is out of range — messages will not be delivered"
                },
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = if (present) AccentSaffronDeep else TextSecondary,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Says out loud why the peer list is empty when the cause is on this phone.
 *
 * Android withholds BLE scan results entirely when location permission is missing or the system
 * Location toggle is off — no callback, no error, just nothing — so without this the screen shows
 * "Scanning for nearby iTantra nodes" forever and blames the mesh for a settings problem.
 *
 * Polled rather than observed: the user fixes these in system settings and comes back, and there
 * is no single broadcast covering permission grants, the Location toggle and the Bluetooth
 * adapter together.
 */
@Composable
private fun MeshReadinessNotice(): Boolean {
    val context = LocalContext.current
    var report by remember { mutableStateOf(BleReadiness.check(context)) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            report = BleReadiness.check(context)
        }
    }

    val blocker = report.blockers.firstOrNull() ?: return false

    val action: (() -> Unit)? = when (blocker) {
        BleBlocker.NO_BLE_HARDWARE -> null
        BleBlocker.BLUETOOTH_OFF -> {
            { context.openSettings(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        BleBlocker.LOCATION_SERVICES_OFF -> {
            { context.openSettings(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        }
        else -> {
            {
                context.openSettings(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                )
            }
        }
    }

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
            Icon(
                imageVector = when (blocker) {
                    BleBlocker.LOCATION_SERVICES_OFF, BleBlocker.MISSING_LOCATION_PERMISSION ->
                        Icons.Default.LocationOff
                    BleBlocker.BLUETOOTH_OFF -> Icons.Default.BluetoothDisabled
                    else -> Icons.Default.ErrorOutline
                },
                contentDescription = null,
                tint = AccentAlert,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = blocker.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = blocker.detail,
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
                if (report.blockers.size > 1) {
                    Text(
                        text = "+${report.blockers.size - 1} more to fix",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted
                    )
                }
            }
            if (action != null) {
                TextButton(onClick = action) {
                    Text("Fix", color = AccentAlert, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    return true
}

/** Opens a settings screen, tolerating devices that do not expose the exact activity. */
private fun android.content.Context.openSettings(intent: Intent) {
    try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        android.util.Log.e("MeshReadiness", "SETTINGS_OPEN_FAILED: ${e.message}", e)
        try {
            startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { }
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
    translation: TranslationManager.Outcome?,
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
            color = when {
                isAlert -> AccentAlert.copy(alpha = 0.10f)
                isMine -> BubbleMine
                else -> BubbleTheirs
            },
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isAlert) AccentAlert
                else if (isMine) AccentSaffron.copy(alpha = 0.5f)
                else BorderSubtle
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
                            MessageType.SOS -> Icons.Default.Sos
                            MessageType.SOS_RESOLVED -> Icons.Default.CheckCircle
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
                            MessageType.SOS -> "DISTRESS"
                            MessageType.SOS_RESOLVED -> "RESOLVED"
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

                // Message Text Content. When a translation exists the reader sees it
                // first, with the original kept underneath — and when one was needed but
                // could not be produced, that is stated outright. Silently showing
                // untranslated text as though it were translated is the failure that
                // actually matters in a distress message.
                val shown = when (translation) {
                    is TranslationManager.Outcome.Translated -> translation.text
                    else -> message.text
                }
                Text(
                    text = shown,
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextPrimary
                )

                when (translation) {
                    is TranslationManager.Outcome.Translated -> {
                        Text(
                            text = "translated from ${message.srcLang.uppercase()} · " +
                                translation.original,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    is TranslationManager.Outcome.Failed -> {
                        Text(
                            text = "shown untranslated — no " +
                                "${message.srcLang.uppercase()} translation available",
                            style = MaterialTheme.typography.labelSmall,
                            color = AccentAlert,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }

                    // NotNeeded and "not translated yet" both render as plain text.
                    else -> Unit
                }
            }
        }
    }
}
