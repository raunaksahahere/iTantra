package com.itantra.ui.languages

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.itantra.models.LanguageModelSpec
import com.itantra.models.ModelCatalog
import com.itantra.models.ModelManager
import com.itantra.schema.VoicePreferences
import com.itantra.ui.theme.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LanguagePacksScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val modelManager = remember { ModelManager(context) }
    val voicePrefs = remember { VoicePreferences.getInstance(context) }
    val packs = remember { ModelCatalog.languages(context) }
    val progress by modelManager.progress.collectAsState()
    val enabledLanguages by voicePrefs.enabledLanguages.collectAsState()
    val scope = rememberCoroutineScope()

    // Recomputed after each install so status badges reflect what is actually on disk.
    var refreshToken by remember { mutableStateOf(0) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(progress) {
        when (val p = progress) {
            is ModelManager.Progress.Done -> {
                refreshToken++
                snackbarHostState.showSnackbar("${p.lang.uppercase()} pack installed")
            }
            is ModelManager.Progress.Failed -> {
                refreshToken++
                snackbarHostState.showSnackbar(p.reason)
            }
            else -> Unit
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Language Packs (${packs.size})",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = TextPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SurfaceCard)
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = SurfaceBg
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { OfflineBanner() }

            items(packs, key = { it.lang }) { pack ->
                val status = remember(pack.lang, refreshToken) { modelManager.status(pack.lang) }
                val busy = progress.let {
                    (it is ModelManager.Progress.Downloading && it.lang == pack.lang) ||
                        (it is ModelManager.Progress.Verifying && it.lang == pack.lang)
                }
                LanguagePackItem(
                    pack = pack,
                    status = status,
                    enabled = pack.lang in enabledLanguages,
                    progress = progress.takeIf { busy },
                    onInstall = { scope.launch { modelManager.install(pack.lang) } },
                    onUninstall = {
                        modelManager.uninstall(pack.lang)
                        refreshToken++
                    },
                    onToggleEnabled = { checked ->
                        if (checked) voicePrefs.enable(pack.lang) else voicePrefs.disable(pack.lang)
                    }
                )
            }
        }
    }
}

@Composable
private fun OfflineBanner() {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = SurfaceVariantBg,
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                Icons.Default.CloudOff,
                contentDescription = null,
                tint = AccentEmerald,
                modifier = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "One-time download, then fully offline",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
                Text(
                    text = "Downloading a pack is the only time iTantra uses the network. " +
                        "Every file is SHA-256 verified before install. Once a language is " +
                        "present, speech recognition and synthesis run entirely on-device.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun LanguagePackItem(
    pack: LanguageModelSpec,
    status: ModelManager.PackStatus,
    enabled: Boolean,
    progress: ModelManager.Progress?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceCard),
        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = pack.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                        Text(
                            text = "(${pack.nativeName})",
                            style = MaterialTheme.typography.bodyMedium,
                            color = AccentSaffronDeep
                        )
                    }

                    Text(
                        text = buildString {
                            append("STT: IndicConformer • TTS: FastPitch+HiFi-GAN")
                            if (pack.totalBytes > 0) append(" • ~${pack.totalBytes / 1_000_000} MB")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 11.sp
                    )
                }

                StatusAction(status, progress, onInstall, onUninstall)
            }

            if (progress is ModelManager.Progress.Downloading) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    LinearProgressIndicator(
                        progress = { progress.fraction },
                        modifier = Modifier.fillMaxWidth(),
                        color = AccentSaffron,
                        trackColor = SurfaceVariantBg
                    )
                    Text(
                        text = "${progress.fileName} — ${(progress.fraction * 100).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        fontSize = 10.sp
                    )
                }
            } else if (progress is ModelManager.Progress.Verifying) {
                Text(
                    text = "Verifying checksum of ${progress.fileName}…",
                    style = MaterialTheme.typography.labelSmall,
                    color = AccentCyan,
                    fontSize = 10.sp
                )
            }

            if (status == ModelManager.PackStatus.UNPUBLISHED) {
                Text(
                    text = "No verified download published yet. Export this language to ONNX, " +
                        "then set url + sha256 in assets/models/manifest.json — or sideload the " +
                        "files during development.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    fontSize = 10.sp
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Checkbox(
                    checked = enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = CheckboxDefaults.colors(checkedColor = AccentSaffron)
                )
                Text(
                    text = "Available for speaking and listening",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (enabled) TextSecondary else TextMuted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun StatusAction(
    status: ModelManager.PackStatus,
    progress: ModelManager.Progress?,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    if (progress != null) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = AccentSaffron)
        return
    }

    when (status) {
        ModelManager.PackStatus.INSTALLED -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = AccentEmerald.copy(alpha = 0.2f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, AccentEmerald)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = AccentEmerald,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Ready",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = AccentEmerald
                        )
                    }
                }
                IconButton(onClick = onUninstall) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove pack",
                        tint = TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }

        ModelManager.PackStatus.UNPUBLISHED -> {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceVariantBg,
                border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle)
            ) {
                Text(
                    text = "Not published",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = TextMuted
                )
            }
        }

        else -> {
            OutlinedButton(
                onClick = onInstall,
                shape = RoundedCornerShape(8.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, AccentSaffron),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = AccentSaffron),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = if (status == ModelManager.PackStatus.PARTIAL) "Resume" else "Download",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
