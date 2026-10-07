package com.ragnarok93.bbsremix.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ragnarok93.bbsremix.iso.IsoPatchResult
import com.ragnarok93.bbsremix.iso.IsoPatchingService
import com.ragnarok93.bbsremix.iso.IsoPreflight
import com.ragnarok93.bbsremix.iso.IsoVerificationResult
import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.PatchCancelledException
import com.ragnarok93.bbsremix.patch.PatchOptions
import com.ragnarok93.bbsremix.patch.PatchProgress
import com.ragnarok93.bbsremix.patch.ProgressReporter
import com.ragnarok93.bbsremix.platform.FileGateway
import com.ragnarok93.bbsremix.platform.PlatformFileSelection
import com.ragnarok93.bbsremix.platform.PlatformOutputSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path

private const val SOURCE_CODE_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher"
private const val LICENSE_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher/blob/main/LICENSE"
private const val KOFI_PLACEHOLDER_URL = "https://ko-fi.com/PLACEHOLDER"

@Composable
fun PatcherApp(
    fileGateway: FileGateway,
    patchingService: IsoPatchingService = remember { IsoPatchingService() },
) {
    PatcherTheme {
        val scope = rememberCoroutineScope()
        val uriHandler = LocalUriHandler.current
        var source by remember { mutableStateOf<PlatformFileSelection?>(null) }
        var stagedSource by remember { mutableStateOf<Path?>(null) }
        var output by remember { mutableStateOf<PlatformOutputSelection?>(null) }
        var options by remember { mutableStateOf(PatchOptions()) }
        var preflight by remember { mutableStateOf<IsoPreflight?>(null) }
        var status by remember { mutableStateOf<PatcherStatus>(PatcherStatus.Empty) }
        var progress by remember { mutableStateOf<PatchProgress?>(null) }
        var activeJob by remember { mutableStateOf<Job?>(null) }
        var optionInputsValid by remember { mutableStateOf(true) }
        var showInfoPage by remember { mutableStateOf(false) }
        var showDonationPrompt by remember {
            mutableStateOf(!fileGateway.isDonationPromptDisabled())
        }

        fun start(operation: suspend (CancellationToken) -> Unit) {
            activeJob?.cancel()
            val job = scope.launch {
                val token = CoroutineCancellationToken(coroutineContext[Job]!!)
                try {
                    operation(token)
                } catch (_: PatchCancelledException) {
                    status = PatcherStatus.Cancelled
                } catch (_: CancellationException) {
                    status = PatcherStatus.Cancelled
                } catch (error: Exception) {
                    status = PatcherStatus.Failure(error.message ?: "The operation failed.")
                } finally {
                    if (activeJob == coroutineContext[Job]) activeJob = null
                }
            }
            activeJob = job
        }

        fun report(value: PatchProgress) {
            progress = value
            status = PatcherStatus.Busy(value.detail ?: value.phase.name)
        }

        fun selectSource() {
            start { token ->
                val selected = fileGateway.pickSource() ?: return@start
                stagedSource?.let { fileGateway.deleteTemp(it) }
                val staged = fileGateway.createTempPath("bbs-source", ".iso")
                try {
                    withContext(Dispatchers.Default) {
                        fileGateway.stageSource(selected, staged, token, ProgressReporter(::report))
                    }
                    val result = withContext(Dispatchers.Default) {
                        patchingService.preflight(staged, options, token, ProgressReporter(::report))
                    }
                    source = selected
                    stagedSource = staged
                    output = null
                    preflight = result
                    status = if (result.eboot.supported) {
                        PatcherStatus.Ready
                    } else {
                        PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
                    }
                    if (!result.eboot.supported) {
                        fileGateway.deleteTemp(staged)
                        stagedSource = null
                    }
                } catch (error: Throwable) {
                    fileGateway.deleteTemp(staged)
                    throw error
                }
            }
        }

        fun selectOutput() {
            val sourceName = source?.displayName ?: "Birth-By-Sleep-Final-ReMix"
            val suggested = sourceName.substringBeforeLast('.', sourceName) + ".final-remix.iso"
            start {
                output = fileGateway.pickOutput(suggested)
                if (output != null) status = PatcherStatus.Ready
            }
        }

        fun patchIso() {
            val sourcePath = stagedSource ?: return
            val sourceSelection = source ?: return
            val outputSelection = output ?: return
            if (fileGateway.isSameSourceAndOutput(sourceSelection, outputSelection)) {
                status = PatcherStatus.Failure("The output must be a separate ISO and must not overwrite the source image.")
                return
            }
            if (!outputSelection.displayName.endsWith(".iso", ignoreCase = true)) {
                status = PatcherStatus.Failure("Choose an output filename ending in .iso.")
                return
            }
            start { token ->
                val temporary = fileGateway.createTempPath("bbs-output", ".iso")
                try {
                    val result = withContext(Dispatchers.Default) {
                        patchingService.patchTo(sourcePath, temporary, options, token, ProgressReporter(::report))
                    }
                    withContext(Dispatchers.Default) {
                        fileGateway.commitOutput(temporary, outputSelection, token, ProgressReporter(::report))
                    }
                    status = PatcherStatus.Complete(result, outputSelection.location)
                } finally {
                    fileGateway.deleteTemp(temporary)
                }
            }
        }

        fun verifyOutput() {
            val sourcePath = stagedSource
            if (sourcePath == null || preflight?.eboot?.supported != true) {
                status = PatcherStatus.Failure("Select and validate the source ISO before verifying a patched output.")
                return
            }
            start { token ->
                val selected = fileGateway.pickVerificationTarget() ?: return@start
                val temporary = fileGateway.createTempPath("bbs-verify", ".iso")
                try {
                    withContext(Dispatchers.Default) {
                        fileGateway.stageSource(selected, temporary, token, ProgressReporter(::report))
                    }
                    val result = withContext(Dispatchers.Default) {
                        patchingService.verifyPatchedOutput(
                            source = sourcePath,
                            candidate = temporary,
                            options = options,
                            cancellation = token,
                            progress = ProgressReporter(::report),
                        )
                    }
                    status = PatcherStatus.Verified(result, selected.displayName)
                } finally {
                    fileGateway.deleteTemp(temporary)
                }
            }
        }

        val canPatch = stagedSource != null &&
            preflight?.eboot?.supported == true &&
            optionInputsValid &&
            options.validate().isEmpty()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF0A2B55),
                            MaterialTheme.colorScheme.background,
                            MaterialTheme.colorScheme.background,
                        )
                    )
                )
        ) {
            if (showInfoPage) {
                InfoPage(
                    onBack = { showInfoPage = false },
                    onSourceCode = { uriHandler.openUri(SOURCE_CODE_URL) },
                    onLicense = { uriHandler.openUri(LICENSE_URL) },
                    onDonate = { uriHandler.openUri(KOFI_PLACEHOLDER_URL) },
                )
            } else {
                PatcherHome(
                    source = source,
                    preflight = preflight,
                    output = output,
                    options = options,
                    status = status,
                    progress = progress,
                    busy = activeJob != null,
                    canPatch = canPatch,
                    onSelectSource = ::selectSource,
                    onSelectOutput = ::selectOutput,
                    onPatch = ::patchIso,
                    onVerifyOutput = ::verifyOutput,
                    onOptionsChanged = { options = it },
                    onInputsValidChanged = { optionInputsValid = it },
                    onInfo = { showInfoPage = true },
                )
            }

            if (activeJob != null) {
                Box(
                    Modifier.fillMaxSize().padding(20.dp),
                    contentAlignment = Alignment.BottomEnd,
                ) {
                    PatcherButton("Cancel", { activeJob?.cancel() }, enabled = true)
                }
            }
        }

        if (showDonationPrompt) {
            DonationPrompt(
                onOpen = { uriHandler.openUri(KOFI_PLACEHOLDER_URL) },
                onDismiss = { showDonationPrompt = false },
                onDoNotShowAgain = {
                    fileGateway.setDonationPromptDisabled(true)
                    showDonationPrompt = false
                },
            )
        }
    }
}

@Composable
private fun PatcherHome(
    source: PlatformFileSelection?,
    preflight: IsoPreflight?,
    output: PlatformOutputSelection?,
    options: PatchOptions,
    status: PatcherStatus,
    progress: PatchProgress?,
    busy: Boolean,
    canPatch: Boolean,
    onSelectSource: () -> Unit,
    onSelectOutput: () -> Unit,
    onPatch: () -> Unit,
    onVerifyOutput: () -> Unit,
    onOptionsChanged: (PatchOptions) -> Unit,
    onInputsValidChanged: (Boolean) -> Unit,
    onInfo: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        val wide = maxWidth >= 900.dp
        val horizontalPadding = if (wide) 32.dp else 16.dp
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 1440.dp)
                .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(onInfo)
            if (wide) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SourceCard(source, busy, onSelectSource, Modifier.weight(0.9f))
                    DetectedGameCard(preflight, Modifier.weight(1.1f))
                }
            } else {
                SourceCard(source, busy, onSelectSource, Modifier.fillMaxWidth())
                DetectedGameCard(preflight, Modifier.fillMaxWidth())
            }
            OptionsCard(
                options = options,
                onOptionsChanged = onOptionsChanged,
                busy = busy,
                onInputsValidChanged = onInputsValidChanged,
            )
            OutputCard(
                output = output,
                status = status,
                progress = progress,
                onSelect = onSelectOutput,
                onPatch = onPatch,
                onVerifyOutput = onVerifyOutput,
                busy = busy,
                canPatch = canPatch,
            )
        }
    }
}

@Composable
private fun Header(onInfo: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp
        if (compact) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Birth By Sleep - Final ReMix",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "PSP ISO Patcher",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Text(
                    text = "Patch a supported English-patched ISO directly, with independent camera and combat options.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onInfo) { Text("Info") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "Birth By Sleep - Final ReMix",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "PSP ISO Patcher",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "Direct ISO patching, validation, cover-art detection, and output verification.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onInfo) { Text("Info") }
            }
        }
    }
}

@Composable
private fun SourceCard(
    source: PlatformFileSelection?,
    busy: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier,
) {
    PatcherSurface(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Source ISO", style = MaterialTheme.typography.titleLarge)
            Text(
                source?.displayName ?: "No ISO selected",
                style = MaterialTheme.typography.bodyLarge,
                color = if (source == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "The source is validated automatically after selection.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PatcherButton(
                label = "Select ISO…",
                onClick = onSelect,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        }
    }
}

@Composable
private fun DetectedGameCard(
    preflight: IsoPreflight?,
    modifier: Modifier,
) {
    var expanded by remember { mutableStateOf(true) }
    PatcherSurface(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Detected game", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (preflight?.eboot?.supported == true) "Compatible source detected" else "Select an ISO to detect game information",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (preflight?.eboot?.supported == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleLarge)
            }
            if (expanded) {
                HorizontalDivider()
                if (preflight == null) {
                    Text(
                        "PSP cover art and metadata will be read directly from the selected ISO.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        GameCoverArt(
                            pngBytes = preflight.game.coverArtPng,
                            modifier = Modifier.size(116.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Text(
                                preflight.game.title ?: "Kingdom Hearts Birth by Sleep Final Mix",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            preflight.game.discId?.let { MetadataLine("Game ID", it) }
                            preflight.game.version?.let { MetadataLine("Version", it) }
                            MetadataLine("EBOOT", if (preflight.eboot.supported) "Supported" else "Unsupported")
                            Text(
                                "SHA-256 ${preflight.eboot.fingerprint.sha256.take(16)}…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetadataLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "$label:",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun OptionsCard(
    options: PatchOptions,
    onOptionsChanged: (PatchOptions) -> Unit,
    busy: Boolean,
    onInputsValidChanged: (Boolean) -> Unit,
) {
    var distanceValid by remember { mutableStateOf(true) }
    var heightValid by remember { mutableStateOf(true) }
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Patch options", style = MaterialTheme.typography.titleLarge)
            Text(
                "Every patch component is independent. Expand a category to configure only what you want applied.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val split = maxWidth >= 820.dp
                if (split) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(20.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        CameraOptions(
                            options = options,
                            onOptionsChanged = onOptionsChanged,
                            busy = busy,
                            modifier = Modifier.weight(1f),
                            onDistanceValidityChanged = {
                                distanceValid = it
                                onInputsValidChanged(distanceValid && heightValid)
                            },
                            onHeightValidityChanged = {
                                heightValid = it
                                onInputsValidChanged(distanceValid && heightValid)
                            },
                        )
                        CombatOptions(
                            options = options,
                            onOptionsChanged = onOptionsChanged,
                            busy = busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CameraOptions(
                            options = options,
                            onOptionsChanged = onOptionsChanged,
                            busy = busy,
                            modifier = Modifier.fillMaxWidth(),
                            onDistanceValidityChanged = {
                                distanceValid = it
                                onInputsValidChanged(distanceValid && heightValid)
                            },
                            onHeightValidityChanged = {
                                heightValid = it
                                onInputsValidChanged(distanceValid && heightValid)
                            },
                        )
                        CombatOptions(
                            options = options,
                            onOptionsChanged = onOptionsChanged,
                            busy = busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CameraOptions(
    options: PatchOptions,
    onOptionsChanged: (PatchOptions) -> Unit,
    busy: Boolean,
    modifier: Modifier,
    onDistanceValidityChanged: (Boolean) -> Unit,
    onHeightValidityChanged: (Boolean) -> Unit,
) {
    ExpandableSection(
        title = "Camera & controls",
        subtitle = "Right-stick input, camera distance, and camera height",
        modifier = modifier,
        initiallyExpanded = true,
    ) {
        SettingToggle(
            title = "Right-stick camera control",
            description = "Adds modern right-stick X/Y camera input.",
            checked = options.rightStickCameraEnabled,
            enabled = !busy,
            onCheckedChange = { onOptionsChanged(options.copy(rightStickCameraEnabled = it)) },
        )
        HorizontalDivider()
        SettingToggle(
            title = "Camera distance",
            description = "Normal-player camera distance; default 4.5, range 1.0–12.0.",
            checked = options.cameraDistanceEnabled,
            enabled = !busy,
            onCheckedChange = { onOptionsChanged(options.copy(cameraDistanceEnabled = it)) },
        )
        NumberSetting(
            label = "Distance",
            value = options.cameraDistance,
            range = PatchOptions.CAMERA_DISTANCE_RANGE,
            enabled = !busy && options.cameraDistanceEnabled,
            onValueChange = { onOptionsChanged(options.copy(cameraDistance = it)) },
            onValidityChanged = onDistanceValidityChanged,
        )
        SettingToggle(
            title = "Camera height",
            description = "Free and lock-on camera height; default 1.0, range 0.0–4.0.",
            checked = options.cameraHeightEnabled,
            enabled = !busy,
            onCheckedChange = { onOptionsChanged(options.copy(cameraHeightEnabled = it)) },
        )
        NumberSetting(
            label = "Height",
            value = options.cameraHeight,
            range = PatchOptions.CAMERA_HEIGHT_RANGE,
            enabled = !busy && options.cameraHeightEnabled,
            onValueChange = { onOptionsChanged(options.copy(cameraHeight = it)) },
            onValidityChanged = onHeightValidityChanged,
        )
    }
}

@Composable
private fun CombatOptions(
    options: PatchOptions,
    onOptionsChanged: (PatchOptions) -> Unit,
    busy: Boolean,
    modifier: Modifier,
) {
    ExpandableSection(
        title = "Combat modifications",
        subtitle = "Individual combat, Critical Mode, and diagnostics toggles",
        modifier = modifier,
        initiallyExpanded = true,
    ) {
        ExpandableSection(
            title = "Core combat",
            subtitle = "Cancel, defense, and invincibility behavior",
            initiallyExpanded = true,
            compact = true,
        ) {
            SettingToggle("Hit-aware cancels", "Respect the player attack-target state when cancelling.", options.hitAwareCancels, !busy) {
                onOptionsChanged(options.copy(hitAwareCancels = it))
            }
            SettingToggle("Invincibility windows", "Keep forced dodge, form-change, wind-up, and Zantetsuken windows.", options.invincibilityWindows, !busy) {
                onOptionsChanged(options.copy(invincibilityWindows = it))
            }
            SettingToggle("Extended defense", "Keep extended guard and defensive cancel rules.", options.extendedDefense, !busy) {
                onOptionsChanged(options.copy(extendedDefense = it))
            }
            SettingToggle("Command cancels", "Keep command-windup and command cancel rules.", options.commandCancels, !busy) {
                onOptionsChanged(options.copy(commandCancels = it))
            }
            SettingToggle("Strict category exclusions", "Use the stricter original category guard behavior.", options.strictSteamExclusions, !busy) {
                onOptionsChanged(options.copy(strictSteamExclusions = it))
            }
        }
        ExpandableSection(
            title = "Critical Mode",
            subtitle = "Abilities and passive runtime grants",
            initiallyExpanded = false,
            compact = true,
        ) {
            SettingToggle("Critical Mode abilities", "Grant Reload Boost and Second Chance in Critical Mode.", options.criticalModeAbilities, !busy) {
                onOptionsChanged(options.copy(criticalModeAbilities = it))
            }
            SettingToggle("Critical Mode passives", "Grant Munny Plus, Berserk, Auto-Remedy, and Double CP.", options.criticalModePassives, !busy) {
                onOptionsChanged(options.copy(criticalModePassives = it))
            }
        }
        ExpandableSection(
            title = "Diagnostics",
            subtitle = "Runtime patch telemetry",
            initiallyExpanded = false,
            compact = true,
        ) {
            SettingToggle("Telemetry", "Keep the 64-frame runtime telemetry ring.", options.telemetry, !busy) {
                onOptionsChanged(options.copy(telemetry = it))
            }
        }
    }
}

@Composable
private fun ExpandableSection(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (compact) 0.34f else 0.52f),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(if (compact) 18.dp else 22.dp),
            )
            .padding(if (compact) 12.dp else 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(if (expanded) "▾" else "▸")
        }
        if (expanded) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f))
            content()
        }
    }
}

@Composable
private fun SettingToggle(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(
                enabled = enabled,
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun NumberSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
    onValidityChanged: (Boolean) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toFloatOrNull()
    val invalid = parsed == null || parsed !in range
    LaunchedEffect(enabled, invalid) {
        onValidityChanged(!enabled || !invalid)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { next ->
            text = next
            val number = next.toFloatOrNull()
            val valid = number != null && number in range
            onValidityChanged(!enabled || valid)
            number?.takeIf { it in range }?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        isError = enabled && invalid,
        supportingText = if (enabled && invalid) {
            { Text("Enter a value from ${range.start} to ${range.endInclusive}.") }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OutputCard(
    output: PlatformOutputSelection?,
    status: PatcherStatus,
    progress: PatchProgress?,
    onSelect: () -> Unit,
    onPatch: () -> Unit,
    onVerifyOutput: () -> Unit,
    busy: Boolean,
    canPatch: Boolean,
) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Output & patch", style = MaterialTheme.typography.titleLarge)
            Text(
                output?.displayName ?: "Choose a separate output ISO path.",
                style = MaterialTheme.typography.bodyLarge,
                color = if (output == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 650.dp) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PatcherButton("Choose output…", onSelect, Modifier.weight(1f), enabled = !busy && canPatch)
                        PatcherButton("Patch ISO", onPatch, Modifier.weight(1f), enabled = !busy && canPatch && output != null)
                        PatcherButton("Verify Output…", onVerifyOutput, Modifier.weight(1f), enabled = !busy && canPatch)
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PatcherButton("Choose output…", onSelect, Modifier.fillMaxWidth(), enabled = !busy && canPatch)
                        PatcherButton("Patch ISO", onPatch, Modifier.fillMaxWidth(), enabled = !busy && canPatch && output != null)
                        PatcherButton("Verify Output…", onVerifyOutput, Modifier.fillMaxWidth(), enabled = !busy && canPatch)
                    }
                }
            }
            if (status is PatcherStatus.Busy && progress != null) {
                PatcherProgress(progress.fraction, Modifier.fillMaxWidth())
                Text(
                    status.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatusSummary(status)
            }
        }
    }
}

@Composable
private fun StatusSummary(status: PatcherStatus) {
    when (status) {
        PatcherStatus.Empty -> Text(
            "Select a source ISO to begin.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PatcherStatus.Ready -> Text(
            "Ready to patch.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
        PatcherStatus.Cancelled -> Text(
            "Operation cancelled. No completed output was committed.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        is PatcherStatus.Busy -> Text(status.label, style = MaterialTheme.typography.bodySmall)
        is PatcherStatus.Failure -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Unable to complete operation", fontWeight = FontWeight.SemiBold)
            Text(status.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is PatcherStatus.Complete -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Patched ISO ready", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(
                "Output: ${status.outputLocation}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Patched EBOOT SHA-256: ${status.result.patchedEbootSha256}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        is PatcherStatus.Verified -> Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Output verified successfully", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Text(
                "${status.displayName} matches the currently selected patch configuration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "EBOOT SHA-256: ${status.result.actualEbootSha256}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoPage(
    onBack: () -> Unit,
    onSourceCode: () -> Unit,
    onLicense: () -> Unit,
    onDonate: () -> Unit,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .widthIn(max = 900.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text(
                "About Birth By Sleep - Final ReMix",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            PatcherSurface(Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "A cross-platform PSP ISO patcher that applies selected gameplay and camera modifications directly to a supported user-provided ISO.",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    TextButton(onClick = onSourceCode) { Text("Source Code") }
                    TextButton(onClick = onLicense) { Text("GNU GPL-3.0 License") }
                    TextButton(onClick = onDonate) { Text("Ko-fi donation link (placeholder)") }
                    Text(
                        "The donation destination is intentionally a placeholder until the project link is wired.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DonationPrompt(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onDoNotShowAgain: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support the project") },
        text = {
            Text("A Ko-fi link can be wired here later. The current button opens a placeholder destination.")
        },
        confirmButton = {
            TextButton(onClick = onOpen) { Text("Open Ko-fi") }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) { Text("Dismiss") }
                TextButton(onClick = onDoNotShowAgain) { Text("Do not show again") }
            }
        },
    )
}

private sealed interface PatcherStatus {
    data object Empty : PatcherStatus
    data object Ready : PatcherStatus
    data object Cancelled : PatcherStatus
    data class Busy(val label: String) : PatcherStatus
    data class Failure(val message: String) : PatcherStatus
    data class Complete(val result: IsoPatchResult, val outputLocation: String) : PatcherStatus
    data class Verified(val result: IsoVerificationResult, val displayName: String) : PatcherStatus
}

private class CoroutineCancellationToken(private val job: Job) : CancellationToken {
    override val isCancelled: Boolean
        get() = !job.isActive
}
