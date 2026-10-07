package com.ragnarok93.bbsremix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.ragnarok93.bbsremix.iso.IsoPatchResult
import com.ragnarok93.bbsremix.iso.IsoPatchingService
import com.ragnarok93.bbsremix.iso.IsoPreflight
import com.ragnarok93.bbsremix.iso.IsoVerificationResult
import com.ragnarok93.bbsremix.iso.IsoVerificationStatus
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

private const val REPOSITORY_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher"
private const val LICENSE_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher/blob/main/LICENSE"
private const val PLACEHOLDER_KOFI_URL = "https://ko-fi.com/"

@Composable
fun PatcherApp(
    fileGateway: FileGateway,
    patchingService: IsoPatchingService = remember { IsoPatchingService() },
) {
    PatcherTheme {
        val scope = rememberCoroutineScope()
        var page by remember { mutableStateOf(AppPage.PATCHER) }
        var source by remember { mutableStateOf<PlatformFileSelection?>(null) }
        var stagedSource by remember { mutableStateOf<Path?>(null) }
        var output by remember { mutableStateOf<PlatformOutputSelection?>(null) }
        var options by remember { mutableStateOf(PatchOptions()) }
        var preflight by remember { mutableStateOf<IsoPreflight?>(null) }
        var verification by remember { mutableStateOf<IsoVerificationResult?>(null) }
        var status by remember { mutableStateOf<PatcherStatus>(PatcherStatus.Empty) }
        var progress by remember { mutableStateOf<PatchProgress?>(null) }
        var activeJob by remember { mutableStateOf<Job?>(null) }
        var optionInputsValid by remember { mutableStateOf(true) }
        var showKoFiPrompt by remember { mutableStateOf(fileGateway.shouldShowDonationPrompt()) }

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

        fun preflightSource() {
            val sourcePath = stagedSource ?: return
            start { token ->
                val result = withContext(Dispatchers.Default) {
                    patchingService.preflight(sourcePath, options, token, ProgressReporter(::report))
                }
                preflight = result
                verification = null
                status = if (result.eboot.supported) {
                    PatcherStatus.Ready
                } else {
                    PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
                }
            }
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
                    verification = null
                    status = if (result.eboot.supported) {
                        PatcherStatus.Ready
                    } else {
                        PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
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
            start { output = fileGateway.pickOutput(suggested) }
        }

        fun verifySource() {
            if (stagedSource == null) return
            preflightSource()
        }

        fun verifyOutput() {
            start { token ->
                val selected = fileGateway.pickSource() ?: return@start
                val staged = fileGateway.createTempPath("bbs-verify", ".iso")
                try {
                    withContext(Dispatchers.Default) {
                        fileGateway.stageSource(selected, staged, token, ProgressReporter(::report))
                    }
                    val result = withContext(Dispatchers.Default) {
                        patchingService.verifyOutput(staged, options, token, ProgressReporter(::report))
                    }
                    verification = result
                    status = PatcherStatus.Verification(result)
                } finally {
                    fileGateway.deleteTemp(staged)
                }
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
                    if (stagedSource == sourcePath) {
                        fileGateway.deleteTemp(sourcePath)
                        stagedSource = null
                    }
                }
            }
        }

        val canPatch = stagedSource != null &&
            preflight?.eboot?.supported == true &&
            optionInputsValid &&
            options.validate().isEmpty()
        val busy = activeJob != null

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val compact = maxWidth < 820.dp
            if (compact) {
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            AppPage.PATCHER -> PatcherPage(
                                source = source,
                                preflight = preflight,
                                status = status,
                                progress = progress,
                                options = options,
                                output = output,
                                busy = busy,
                                canPatch = canPatch,
                                onSelectSource = ::selectSource,
                                onVerifySource = ::verifySource,
                                onOptionsChanged = { options = it },
                                onInputsValidChanged = { optionInputsValid = it },
                                onSelectOutput = ::selectOutput,
                                onPatch = ::patchIso,
                                onVerifyOutput = ::verifyOutput,
                                compact = true,
                            )

                            AppPage.INFO -> InfoPage(
                                onOpenUrl = fileGateway::openExternalUrl,
                                compact = true,
                            )
                        }
                    }
                    BottomNavigation(page = page, onPageSelected = { page = it })
                }
            } else {
                Row(Modifier.fillMaxSize()) {
                    DesktopNavigationRail(page = page, onPageSelected = { page = it })
                    Box(Modifier.weight(1f)) {
                        when (page) {
                            AppPage.PATCHER -> PatcherPage(
                                source = source,
                                preflight = preflight,
                                status = status,
                                progress = progress,
                                options = options,
                                output = output,
                                busy = busy,
                                canPatch = canPatch,
                                onSelectSource = ::selectSource,
                                onVerifySource = ::verifySource,
                                onOptionsChanged = { options = it },
                                onInputsValidChanged = { optionInputsValid = it },
                                onSelectOutput = ::selectOutput,
                                onPatch = ::patchIso,
                                onVerifyOutput = ::verifyOutput,
                                compact = false,
                            )

                            AppPage.INFO -> InfoPage(
                                onOpenUrl = fileGateway::openExternalUrl,
                                compact = false,
                            )
                        }
                    }
                }
            }

            if (showKoFiPrompt) {
                KoFiPrompt(
                    onOpen = {
                        fileGateway.openExternalUrl(PLACEHOLDER_KOFI_URL)
                        showKoFiPrompt = false
                    },
                    onDismiss = { showKoFiPrompt = false },
                    onDoNotShowAgain = {
                        fileGateway.suppressDonationPrompt()
                        showKoFiPrompt = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PatcherPage(
    source: PlatformFileSelection?,
    preflight: IsoPreflight?,
    status: PatcherStatus,
    progress: PatchProgress?,
    options: PatchOptions,
    output: PlatformOutputSelection?,
    busy: Boolean,
    canPatch: Boolean,
    onSelectSource: () -> Unit,
    onVerifySource: () -> Unit,
    onOptionsChanged: (PatchOptions) -> Unit,
    onInputsValidChanged: (Boolean) -> Unit,
    onSelectOutput: () -> Unit,
    onPatch: () -> Unit,
    onVerifyOutput: () -> Unit,
    compact: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader()
        if (compact) {
            SourceCard(source, status, preflight, progress, onSelectSource, onVerifySource, busy)
            DetectedGamePane(preflight, compact = true)
            OptionsCard(options, onOptionsChanged, busy, onInputsValidChanged)
            OutputCard(output, onSelectOutput, onPatch, busy, canPatch)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    Modifier.weight(0.9f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    SourceCard(source, status, preflight, progress, onSelectSource, onVerifySource, busy)
                    DetectedGamePane(preflight, compact = false)
                    OutputCard(output, onSelectOutput, onPatch, busy, canPatch)
                }
                Column(
                    Modifier.weight(1.1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OptionsCard(options, onOptionsChanged, busy, onInputsValidChanged)
                    StatusCard(status, progress)
                }
            }
        }
        if (compact) StatusCard(status, progress)
        VerifyOutputFooter(onVerifyOutput, busy)
    }
}

@Composable
private fun PageHeader() {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "Birth By Sleep - Final ReMix PSP ISO Patcher",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Direct ISO patching with safe validation and rebuilt output images.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = "Select a supported English-patched ISO. The app reads the embedded executable, applies only the enabled features, and never overwrites the source image.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SourceCard(
    source: PlatformFileSelection?,
    status: PatcherStatus,
    preflight: IsoPreflight?,
    progress: PatchProgress?,
    onSelect: () -> Unit,
    onVerify: () -> Unit,
    busy: Boolean,
) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Game files", style = MaterialTheme.typography.titleLarge)
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("ISO image", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        source?.displayName ?: "No ISO selected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PatcherButton("Browse…", onSelect, enabled = !busy)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(onClick = onVerify, enabled = !busy && source != null) {
                    Text("Verify source")
                }
                if (status is PatcherStatus.Busy && progress != null) {
                    Text(status.label, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (preflight != null && preflight.eboot.supported) {
                Text(
                    "Supported EBOOT verified · " + preflight.eboot.fingerprint.sha256.take(12) + "…",
                    color = PatcherSuccess,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun DetectedGamePane(
    preflight: IsoPreflight?,
    compact: Boolean,
) {
    var expanded by remember { mutableStateOf(true) }
    val cover = remember(preflight?.coverArt) {
        preflight?.coverArt?.let(::decodeCoverArt)
    }
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Detected game", style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (expanded) "ISO metadata and cover art" else "Tap to expand",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
            }
            AnimatedVisibility(expanded) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (cover != null) {
                        Image(
                            bitmap = cover,
                            contentDescription = "PSP cover art",
                            modifier = Modifier
                                .width(if (compact) 132.dp else 148.dp)
                                .height(if (compact) 88.dp else 98.dp),
                            contentScale = ContentScale.Crop,
                        )
                    } else {
                        Surface(
                            modifier = Modifier
                                .width(if (compact) 132.dp else 148.dp)
                                .height(if (compact) 88.dp else 98.dp),
                            shape = RoundedCornerShape(18.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("PSP", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(
                            if (preflight != null && preflight.eboot.supported) {
                                "Kingdom Hearts Birth by Sleep Final Mix"
                            } else {
                                "No supported game detected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text("Game ID  ULJM-05775", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (preflight?.image?.coverArt != null) {
                                "Cover art loaded from PSP_GAME/ICON0.PNG"
                            } else {
                                "Cover art is not present in this ISO"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        CompatibilityPill(preflight?.eboot?.supported == true)
                    }
                }
            }
        }
    }
}

@Composable
private fun CompatibilityPill(supported: Boolean) {
    Surface(
        color = if (supported) PatcherSuccess.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
        contentColor = if (supported) PatcherSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
        shape = RoundedCornerShape(50),
    ) {
        Text(
            if (supported) "✓ Compatible" else "Needs verification",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@Composable
private fun OptionsCard(
    options: PatchOptions,
    onOptionsChanged: (PatchOptions) -> Unit,
    busy: Boolean,
    onInputsValidChanged: (Boolean) -> Unit,
) {
    var combatExpanded by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }
    var distanceValid by remember { mutableStateOf(true) }
    var heightValid by remember { mutableStateOf(true) }

    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Patch options", style = MaterialTheme.typography.titleLarge)
            Text(
                "Enable the features you want. Each toggle maps directly to one patch capability.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FeatureToggle(
                title = "Right-stick camera control",
                description = "Adds native right-stick camera input support.",
                checked = options.rightStickCamera,
                enabled = !busy,
                onCheckedChange = { onOptionsChanged(options.copy(rightStickCamera = it)) },
            )
            FeatureToggle(
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
                onValidityChanged = {
                    distanceValid = it
                    onInputsValidChanged(distanceValid && heightValid)
                },
            )
            FeatureToggle(
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
                onValidityChanged = {
                    heightValid = it
                    onInputsValidChanged(distanceValid && heightValid)
                },
            )

            HorizontalDivider()
            ExpandableRow(
                title = "Combat Mods",
                description = "Independent combat behavior and Critical Mode toggles.",
                expanded = combatExpanded,
                enabled = !busy,
                onExpandedChange = { combatExpanded = it },
            )
            AnimatedVisibility(combatExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeatureToggle(
                        title = "Hit-aware cancels",
                        description = "Respect the player attack-target state when cancelling.",
                        checked = options.hitAwareCancels,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(hitAwareCancels = it)) },
                    )
                    FeatureToggle(
                        title = "Invincibility windows",
                        description = "Keep dodge, form-change, wind-up, and Zantetsuken windows.",
                        checked = options.invincibilityWindows,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(invincibilityWindows = it)) },
                    )
                    FeatureToggle(
                        title = "Extended defense",
                        description = "Keep extended guard and defensive cancel rules.",
                        checked = options.extendedDefense,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(extendedDefense = it)) },
                    )
                    FeatureToggle(
                        title = "Command cancels",
                        description = "Keep command-windup and command cancel rules.",
                        checked = options.commandCancels,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(commandCancels = it)) },
                    )
                    FeatureToggle(
                        title = "Telemetry",
                        description = "Keep the 64-frame runtime telemetry ring.",
                        checked = options.telemetry,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(telemetry = it)) },
                    )
                    FeatureToggle(
                        title = "Critical Mode abilities",
                        description = "Grant Reload Boost and Second Chance in Critical Mode.",
                        checked = options.criticalModeAbilities,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(criticalModeAbilities = it)) },
                    )
                    FeatureToggle(
                        title = "Critical Mode passives",
                        description = "Grant Munny Plus, Berserk, Auto-Remedy, and Double CP.",
                        checked = options.criticalModePassives,
                        enabled = !busy,
                        onCheckedChange = { onOptionsChanged(options.copy(criticalModePassives = it)) },
                    )

                    ExpandableRow(
                        title = "Advanced compatibility",
                        description = "Optional category guard behavior.",
                        expanded = advancedExpanded,
                        enabled = !busy,
                        onExpandedChange = { advancedExpanded = it },
                    )
                    AnimatedVisibility(advancedExpanded) {
                        FeatureToggle(
                            title = "Strict category exclusions",
                            description = "Use the strict category guard for excluded actions.",
                            checked = options.strictSteamExclusions,
                            enabled = !busy,
                            onCheckedChange = { onOptionsChanged(options.copy(strictSteamExclusions = it)) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureToggle(
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
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
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
private fun ExpandableRow(
    title: String,
    description: String,
    expanded: Boolean,
    enabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onExpandedChange(!expanded) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(if (expanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge)
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
            onValidityChanged(valid)
            number?.takeIf { it in range }?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        isError = enabled && invalid,
        supportingText = if (enabled && invalid) {
            { Text("Enter a value from " + range.start + " to " + range.endInclusive + ".") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OutputCard(
    output: PlatformOutputSelection?,
    onSelect: () -> Unit,
    onPatch: () -> Unit,
    busy: Boolean,
    canPatch: Boolean,
) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Patch", style = MaterialTheme.typography.titleLarge)
            Text(
                output?.displayName ?: "Choose a separate output ISO path.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PatcherButton("Choose output…", onSelect, enabled = !busy && canPatch)
                PatcherButton("Patch ISO", onPatch, enabled = !busy && canPatch && output != null)
            }
        }
    }
}

@Composable
private fun VerifyOutputFooter(
    onVerifyOutput: () -> Unit,
    busy: Boolean,
) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Verify output", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Choose any ISO to confirm that the selected Final ReMix features were applied.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PatcherButton("Verify Output", onVerifyOutput, enabled = !busy)
        }
    }
}

@Composable
private fun StatusCard(status: PatcherStatus, progress: PatchProgress?) {
    when (status) {
        PatcherStatus.Empty,
        PatcherStatus.Ready,
        -> Unit

        PatcherStatus.Cancelled -> MessageCard("Cancelled", "No completed output was committed.")
        is PatcherStatus.Busy -> if (progress == null) MessageCard("Working", status.label)
        is PatcherStatus.Failure -> MessageCard("Unable to continue", status.message)
        is PatcherStatus.Complete -> MessageCard(
            "Patched ISO ready",
            "Output: " + status.outputLocation +
                "\nSource EBOOT SHA-256: " + status.result.sourceEbootSha256 +
                "\nPatched EBOOT SHA-256: " + status.result.patchedEbootSha256,
        )

        is PatcherStatus.Verification -> {
            val title = when (status.result.status) {
                IsoVerificationStatus.VERIFIED_PATCHED -> "Output verified"
                IsoVerificationStatus.UNPATCHED -> "Source image detected"
                IsoVerificationStatus.INCOMPATIBLE -> "Output does not match"
                IsoVerificationStatus.MALFORMED -> "Invalid ISO"
            }
            val hash = status.result.embeddedEbootSha256?.let {
                "\nEmbedded EBOOT SHA-256: " + it
            } ?: ""
            MessageCard(title, status.result.message + hash)
        }
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BottomNavigation(
    page: AppPage,
    onPageSelected: (AppPage) -> Unit,
) {
    Surface(
        tonalElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            NavigationItem("Setup", page == AppPage.PATCHER) { onPageSelected(AppPage.PATCHER) }
            NavigationItem("Info", page == AppPage.INFO) { onPageSelected(AppPage.INFO) }
        }
    }
}

@Composable
private fun DesktopNavigationRail(
    page: AppPage,
    onPageSelected: (AppPage) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxHeight().width(208.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Column(
            Modifier.fillMaxHeight().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("BIRTH BY SLEEP", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text("Final ReMix", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "PSP ISO Patcher",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            NavigationItem("Setup", page == AppPage.PATCHER) { onPageSelected(AppPage.PATCHER) }
            NavigationItem("Info", page == AppPage.INFO) { onPageSelected(AppPage.INFO) }
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(label)
    }
}

@Composable
private fun InfoPage(
    onOpenUrl: (String) -> Unit,
    compact: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Info", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Birth By Sleep - Final ReMix PSP ISO Patcher", style = MaterialTheme.typography.titleLarge)
                Text(
                    "A focused cross-platform utility for safely patching and validating supported PSP ISO images.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Hyperlink("Source Code", REPOSITORY_URL, onOpenUrl)
                Hyperlink("GNU License File", LICENSE_URL, onOpenUrl)
                Hyperlink("Ko-fi donation link (placeholder)", PLACEHOLDER_KOFI_URL, onOpenUrl)
            }
        }
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Safety", style = MaterialTheme.typography.titleMedium)
                Text(
                    "The source ISO is never overwritten. The patcher validates the embedded executable before writing and reopens the rebuilt image before reporting success.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
private fun Hyperlink(
    label: String,
    url: String,
    onOpenUrl: (String) -> Unit,
) {
    TextButton(
        onClick = { onOpenUrl(url) },
        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.primary),
    ) {
        Text(label, textDecoration = TextDecoration.Underline)
    }
}

@Composable
private fun KoFiPrompt(
    onOpen: () -> Unit,
    onDismiss: () -> Unit,
    onDoNotShowAgain: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Support future updates") },
        text = {
            Text(
                "A placeholder Ko-fi link is included for later wiring. You can dismiss this message or disable it for future launches.",
            )
        },
        confirmButton = {
            TextButton(onClick = onOpen) {
                Text("Open Ko-fi")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                TextButton(onClick = onDoNotShowAgain) {
                    Text("Do not show again")
                }
            }
        },
    )
}

private enum class AppPage {
    PATCHER,
    INFO,
}

private sealed interface PatcherStatus {
    data object Empty : PatcherStatus
    data object Ready : PatcherStatus
    data object Cancelled : PatcherStatus
    data class Busy(val label: String) : PatcherStatus
    data class Failure(val message: String) : PatcherStatus
    data class Complete(val result: IsoPatchResult, val outputLocation: String) : PatcherStatus
    data class Verification(val result: IsoVerificationResult) : PatcherStatus
}

private class CoroutineCancellationToken(private val job: Job) : CancellationToken {
    override val isCancelled: Boolean
        get() = !job.isActive
}
