package com.ragnarok93.bbsremix.ui

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ragnarok93.bbsremix.iso.IsoPatchResult
import com.ragnarok93.bbsremix.iso.IsoPatchingService
import com.ragnarok93.bbsremix.iso.IsoPreflight
import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.PatchCancelledException
import com.ragnarok93.bbsremix.patch.PatchMode
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

@Composable
fun PatcherApp(
    fileGateway: FileGateway,
    patchingService: IsoPatchingService = remember { IsoPatchingService() },
) {
    PatcherTheme {
        val scope = rememberCoroutineScope()
        var source by remember { mutableStateOf<PlatformFileSelection?>(null) }
        var stagedSource by remember { mutableStateOf<Path?>(null) }
        var output by remember { mutableStateOf<PlatformOutputSelection?>(null) }
        var options by remember { mutableStateOf(PatchOptions()) }
        var preflight by remember { mutableStateOf<IsoPreflight?>(null) }
        var status by remember { mutableStateOf<PatcherStatus>(PatcherStatus.Empty) }
        var progress by remember { mutableStateOf<PatchProgress?>(null) }
        var activeJob by remember { mutableStateOf<Job?>(null) }
        var optionInputsValid by remember { mutableStateOf(true) }

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
                status = if (result.eboot.supported) {
                    PatcherStatus.Ready
                } else {
                    fileGateway.deleteTemp(sourcePath)
                    stagedSource = null
                    PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
                }
            }
        }

        fun selectSource() {
            start { token ->
                val selected = fileGateway.pickSource() ?: return@start
                val oldPath = stagedSource
                if (oldPath != null) fileGateway.deleteTemp(oldPath)
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
                    status = if (result.eboot.supported) PatcherStatus.Ready else PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
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
            val suggested = sourceName.substringBeforeLast('.', sourceName) + ".bbs-stage5.iso"
            start { output = fileGateway.pickOutput(suggested) }
        }

        fun verifyOnly() {
            if (stagedSource == null) return
            preflightSource()
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

        val canPatch = stagedSource != null && preflight?.eboot?.supported == true &&
            optionInputsValid && options.validate().isEmpty()

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val wide = maxWidth >= 760.dp
            val contentModifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = if (wide) 32.dp else 16.dp, vertical = 20.dp)

            if (wide) {
                Row(contentModifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(Modifier.weight(0.95f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Header()
                        SourceCard(source, status, preflight, progress, ::selectSource, ::verifyOnly, activeJob != null)
                        OutputCard(output, ::selectOutput, ::patchIso, activeJob != null, canPatch)
                        StatusCard(status, progress)
                    }
                    Column(Modifier.weight(1.05f), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        OptionsCard(options, { options = it }, activeJob != null) { optionInputsValid = it }
                    }
                }
            } else {
                Column(contentModifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Header()
                    SourceCard(source, status, preflight, progress, ::selectSource, ::verifyOnly, activeJob != null)
                    OptionsCard(options, { options = it }, activeJob != null) { optionInputsValid = it }
                    OutputCard(output, ::selectOutput, ::patchIso, activeJob != null, canPatch)
                    StatusCard(status, progress)
                }
            }

            if (activeJob != null) {
                // Keep cancellation reachable from every adaptive layout.
                Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.BottomEnd) {
                    PatcherButton("Cancel", { activeJob?.cancel() }, enabled = true)
                }
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text(
            text = "Birth By Sleep Final ReMix",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Better Battle System PSP ISO Patcher",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Patch a supported English-patched ISO directly. The app handles EBOOT staging, validation, rebuilding, and output verification.",
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("1. Source ISO", style = MaterialTheme.typography.titleLarge)
            Text(
                source?.displayName ?: "No ISO selected",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PatcherButton("Select ISO…", onSelect, enabled = !busy)
                PatcherButton("Verify only", onVerify, enabled = !busy && source != null)
            }
            if (preflight != null && preflight.eboot.supported) {
                Text(
                    "Supported decrypted EBOOT verified · ${preflight.eboot.fingerprint.sha256.take(12)}…",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (status is PatcherStatus.Busy && progress != null) {
                PatcherProgress(progress.fraction, Modifier.fillMaxWidth())
                Text(status.label, style = MaterialTheme.typography.bodySmall)
            }
        }
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("2. Patch options", style = MaterialTheme.typography.titleLarge)
            Text("Mode", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PatchMode.values().forEach { mode ->
                    PatcherButton(
                        label = mode.label(),
                        onClick = { onOptionsChanged(options.copy(mode = mode)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                "Right-stick camera support is included in Combined and Camera-only modes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()

            SettingToggle(
                title = "Camera distance",
                description = "Normal-player camera distance; default 4.5, range 1.0–12.0.",
                checked = options.cameraDistanceEnabled,
                enabled = !busy && options.mode != PatchMode.COMBAT_ONLY,
                onCheckedChange = { onOptionsChanged(options.copy(cameraDistanceEnabled = it)) },
            )
            NumberSetting(
                label = "Distance",
                value = options.cameraDistance,
                range = PatchOptions.CAMERA_DISTANCE_RANGE,
                enabled = !busy && options.cameraDistanceEnabled && options.mode != PatchMode.COMBAT_ONLY,
                onValueChange = { onOptionsChanged(options.copy(cameraDistance = it)) },
                onValidityChanged = {
                    distanceValid = it
                    onInputsValidChanged(distanceValid && heightValid)
                },
            )
            SettingToggle(
                title = "Camera height",
                description = "Free and lock-on camera height; default 1.0, range 0.0–4.0.",
                checked = options.cameraHeightEnabled,
                enabled = !busy && options.mode != PatchMode.COMBAT_ONLY,
                onCheckedChange = { onOptionsChanged(options.copy(cameraHeightEnabled = it)) },
            )
            NumberSetting(
                label = "Height",
                value = options.cameraHeight,
                range = PatchOptions.CAMERA_HEIGHT_RANGE,
                enabled = !busy && options.cameraHeightEnabled && options.mode != PatchMode.COMBAT_ONLY,
                onValueChange = { onOptionsChanged(options.copy(cameraHeight = it)) },
                onValidityChanged = {
                    heightValid = it
                    onInputsValidChanged(distanceValid && heightValid)
                },
            )

            if (options.combatFeatures) {
                HorizontalDivider()
                Text("Advanced combat", style = MaterialTheme.typography.titleMedium)
                OptionCheck("Strict Steam exclusions", "Use the strict Steam category guard.", options.strictSteamExclusions, !busy) {
                    onOptionsChanged(options.copy(strictSteamExclusions = it))
                }
                OptionCheck("Hit-aware cancels", "Respect the player attack-target state when cancelling.", options.hitAwareCancels, !busy) {
                    onOptionsChanged(options.copy(hitAwareCancels = it))
                }
                OptionCheck("Invincibility windows", "Keep forced dodge, form-change, wind-up, and Zantetsuken windows.", options.invincibilityWindows, !busy) {
                    onOptionsChanged(options.copy(invincibilityWindows = it))
                }
                OptionCheck("Extended defense", "Keep the extended guard and defensive cancel rules.", options.extendedDefense, !busy) {
                    onOptionsChanged(options.copy(extendedDefense = it))
                }
                OptionCheck("Command cancels", "Keep command-windup and command cancel rules.", options.commandCancels, !busy) {
                    onOptionsChanged(options.copy(commandCancels = it))
                }
                OptionCheck("Telemetry", "Keep the 64-frame runtime telemetry ring.", options.telemetry, !busy) {
                    onOptionsChanged(options.copy(telemetry = it))
                }
                OptionCheck("Critical Mode abilities", "Grant Reload Boost and Second Chance in Critical Mode.", options.criticalModeAbilities, !busy) {
                    onOptionsChanged(options.copy(criticalModeAbilities = it))
                }
                OptionCheck("Critical Mode passives", "Grant Munny Plus, Berserk, Auto-Remedy, and Double CP.", options.criticalModePassives, !busy) {
                    onOptionsChanged(options.copy(criticalModePassives = it))
                }
            }
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
            .toggleable(enabled = enabled, value = checked, role = Role.Switch, onValueChange = onCheckedChange)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            onValidityChanged(valid)
            number?.takeIf { it in range }?.let(onValueChange)
        },
        label = { Text(label) },
        enabled = enabled,
        isError = enabled && invalid,
        supportingText = if (enabled && invalid) {
            { Text("Enter a value from ${range.start} to ${range.endInclusive}.") }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun OptionCheck(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column {
        PatcherCheckbox(title, checked, onCheckedChange, enabled = enabled)
        Text(
            description,
            Modifier.padding(start = 48.dp, bottom = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
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
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("3. Output and patch", style = MaterialTheme.typography.titleLarge)
            Text(output?.displayName ?: "Choose a separate output ISO path.", style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PatcherButton("Choose output…", onSelect, enabled = !busy && canPatch)
                PatcherButton("Patch ISO", onPatch, enabled = !busy && canPatch && output != null)
            }
        }
    }
}

@Composable
private fun StatusCard(status: PatcherStatus, progress: PatchProgress?) {
    when (status) {
        PatcherStatus.Empty -> Unit
        PatcherStatus.Ready -> Unit
        PatcherStatus.Cancelled -> MessageCard("Cancelled", "No completed output was committed.")
        is PatcherStatus.Busy -> if (progress == null) MessageCard("Working", status.label)
        is PatcherStatus.Failure -> MessageCard("Unable to patch", status.message)
        is PatcherStatus.Complete -> MessageCard(
            "Patched ISO ready",
            "Output: ${status.outputLocation}\nSource EBOOT SHA-256: ${status.result.sourceEbootSha256}\nPatched EBOOT SHA-256: ${status.result.patchedEbootSha256}",
        )
    }
}

@Composable
private fun MessageCard(title: String, message: String) {
    PatcherSurface(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun PatchMode.label(): String = when (this) {
    PatchMode.COMBINED -> "Combined"
    PatchMode.CAMERA_ONLY -> "Camera only"
    PatchMode.COMBAT_ONLY -> "Combat only"
}

private sealed interface PatcherStatus {
    data object Empty : PatcherStatus
    data object Ready : PatcherStatus
    data object Cancelled : PatcherStatus
    data class Busy(val label: String) : PatcherStatus
    data class Failure(val message: String) : PatcherStatus
    data class Complete(val result: IsoPatchResult, val outputLocation: String) : PatcherStatus
}

private class CoroutineCancellationToken(private val job: Job) : CancellationToken {
    override val isCancelled: Boolean
        get() = !job.isActive
}
