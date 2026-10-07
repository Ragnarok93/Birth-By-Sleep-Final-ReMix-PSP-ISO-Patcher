package com.ragnarok93.bbsremix.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
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
import com.ragnarok93.bbsremix.platform.PlatformDirectorySelection
import com.ragnarok93.bbsremix.texture.TextureAssetManifest
import com.ragnarok93.bbsremix.texture.TextureCoverage
import com.ragnarok93.bbsremix.texture.TextureInstallOptions
import com.ragnarok93.bbsremix.texture.TextureInstallProgress
import com.ragnarok93.bbsremix.texture.TextureProfile
import com.ragnarok93.bbsremix.texture.TextureProfileCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path
import okio.FileSystem
import okio.buffer
import com.ragnarok93.bbsremix.resources.Res
import com.ragnarok93.bbsremix.resources.wallpaper
import org.jetbrains.compose.resources.painterResource

private const val REPOSITORY_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher"
private const val LICENSE_URL =
    "https://github.com/Ragnarok93/Birth-By-Sleep-Final-ReMix-PSP-ISO-Patcher/blob/main/LICENSE"
private const val PLACEHOLDER_KOFI_URL = "https://ko-fi.com/"
private val PatcherNestedCornerShape = RoundedCornerShape(22.dp)

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
        var textureDestination by remember { mutableStateOf<PlatformDirectorySelection?>(null) }
        var includeRegionalButtonSwaps by remember { mutableStateOf(false) }
        var includeExtraHdPortraits by remember { mutableStateOf(false) }
        var textureStatus by remember { mutableStateOf("Select an ISO to identify its texture profile.") }
        var textureProgress by remember { mutableStateOf<TextureInstallProgress?>(null) }
        var status by remember { mutableStateOf<PatcherStatus>(PatcherStatus.Empty) }
        var progress by remember { mutableStateOf<PatchProgress?>(null) }
        var activeJob by remember { mutableStateOf<Job?>(null) }
        var activeOperation by remember { mutableStateOf<String?>(null) }
        var showKoFiPrompt by remember { mutableStateOf(fileGateway.shouldShowDonationPrompt()) }
        val logEntries = remember { mutableStateListOf<String>() }
        var lastProgressLogKey by remember { mutableStateOf("") }

        fun appendLog(message: String) {
            logEntries += "[${fileGateway.localTimeStamp()}] $message"
            if (logEntries.size > 2000) logEntries.removeAt(0)
        }

        fun start(label: String, operation: suspend (CancellationToken) -> Unit) {
            if (activeJob != null) return
            activeOperation = label
            progress = null
            lastProgressLogKey = ""
            status = PatcherStatus.Busy(label)
            appendLog("$label started.")
            val job = scope.launch {
                val token = CoroutineCancellationToken(coroutineContext[Job]!!)
                try {
                    operation(token)
                    appendLog("$label finished.")
                } catch (_: PatchCancelledException) {
                    status = PatcherStatus.Cancelled
                    appendLog("$label cancelled.")
                } catch (_: CancellationException) {
                    status = PatcherStatus.Cancelled
                    appendLog("$label cancelled.")
                } catch (error: Exception) {
                    status = PatcherStatus.Failure(error.message ?: "The operation failed.")
                    appendLog("$label failed: ${error.message ?: "The operation failed."}")
                } finally {
                    if (activeJob == coroutineContext[Job]) {
                        activeJob = null
                        activeOperation = null
                        if (status is PatcherStatus.Busy) {
                            status = if (preflight?.eboot?.supported == true) PatcherStatus.Ready else PatcherStatus.Empty
                        }
                    }
                }
            }
            activeJob = job
        }

        fun report(value: PatchProgress) {
            scope.launch {
                if (activeOperation == null) return@launch
                progress = value
                val label = value.detail ?: value.phase.name
                status = PatcherStatus.Busy(label)
                val bucket = if (value.total > 0L) ((value.completed * 100L / value.total) / 10L) * 10L else value.completed
                val key = "${value.phase}|$label|$bucket"
                if (key != lastProgressLogKey) {
                    lastProgressLogKey = key
                    val amount = if (value.total > 0L) " ($bucket%)" else ""
                    appendLog("${value.phase}: $label$amount")
                }
            }
        }

        fun preflightSource() {
            val sourcePath = stagedSource ?: return
            start("Verify source ISO") { token ->
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
                appendLog("Source ISO identified as ${result.image.discSerial ?: "disc serial unavailable"}.")
            }
        }

        fun selectSource() {
            start("Select ISO") { token ->
                val selected = fileGateway.pickSource() ?: run {
                    appendLog("ISO selection cancelled.")
                    return@start
                }
                val previousStagedSource = stagedSource
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
                    previousStagedSource?.let { fileGateway.deleteTemp(it) }
                    output = null
                    preflight = result
                    verification = null
                    status = if (result.eboot.supported) {
                        PatcherStatus.Ready
                    } else {
                        PatcherStatus.Failure(result.eboot.problems.joinToString(" "))
                    }
                    appendLog("Selected ${selected.displayName}; game ID ${result.image.discSerial ?: "unavailable"}.")
                } catch (error: Throwable) {
                    fileGateway.deleteTemp(staged)
                    throw error
                }
            }
        }

        fun selectOutput() {
            val sourceName = source?.displayName ?: "Birth-By-Sleep-Final-ReMix"
            val suggested = sourceName.substringBeforeLast('.', sourceName) + ".final-remix.iso"
            start("Choose ISO output") { output = fileGateway.pickOutput(suggested) }
        }

        fun verifySource() {
            if (stagedSource == null) return
            preflightSource()
        }

        fun verifyOutput() {
            start("Verify Output") { token ->
                val selected = fileGateway.pickSource() ?: run {
                    appendLog("Output verification picker cancelled.")
                    return@start
                }
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
                    appendLog("ISO verification: ${result.status} — ${result.message}")
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
                appendLog("Patch ISO refused: output points to the source image.")
                return
            }
            if (!outputSelection.displayName.endsWith(".iso", ignoreCase = true)) {
                status = PatcherStatus.Failure("Choose an output filename ending in .iso.")
                appendLog("Patch ISO refused: output filename must end in .iso.")
                return
            }
            start("Patch ISO") { token ->
                val temporary = fileGateway.createTempPath("bbs-output", ".iso")
                var outputCommitted = false
                try {
                    val result = withContext(Dispatchers.Default) {
                        patchingService.patchTo(sourcePath, temporary, options, token, ProgressReporter(::report))
                    }
                    withContext(Dispatchers.Default) {
                        fileGateway.commitOutput(temporary, outputSelection, token, ProgressReporter(::report))
                    }
                    outputCommitted = true
                    status = PatcherStatus.Complete(result, outputSelection.location)
                    appendLog("Patched ISO committed to ${outputSelection.location}.")
                } finally {
                    fileGateway.deleteTemp(temporary)
                    if (outputCommitted && stagedSource == sourcePath) {
                        fileGateway.deleteTemp(sourcePath)
                        stagedSource = null
                    }
                }
            }
        }

        fun cancelCurrentOperation() {
            val label = activeOperation ?: return
            appendLog("Cancellation requested for $label.")
            activeJob?.cancel()
        }

        fun startTextureOperation(label: String, operation: suspend (CancellationToken) -> Unit) {
            if (activeJob != null) return
            activeOperation = label
            textureProgress = null
            textureStatus = "$label started."
            appendLog("$label started.")
            val job = scope.launch {
                val operationJob = coroutineContext[Job]!!
                val token = CoroutineCancellationToken(operationJob)
                try {
                    operation(token)
                    appendLog("$label finished.")
                } catch (_: PatchCancelledException) {
                    textureStatus = "Texture operation cancelled."
                    appendLog("$label cancelled.")
                } catch (_: CancellationException) {
                    textureStatus = "Texture operation cancelled."
                    appendLog("$label cancelled.")
                } catch (error: Exception) {
                    textureStatus = "Texture operation failed: ${error.message ?: "The operation failed."}"
                    appendLog("$label failed: ${error.message ?: "The operation failed."}")
                } finally {
                    if (activeJob == coroutineContext[Job]) {
                        activeJob = null
                        activeOperation = null
                    }
                }
            }
            activeJob = job
        }

        fun selectTextureDestination() {
            startTextureOperation("Choose Texture Destination") {
                val selected = fileGateway.pickTextureDestination() ?: run {
                    textureStatus = "Texture destination selection cancelled."
                    appendLog("Texture destination selection cancelled.")
                    return@startTextureOperation
                }
                textureDestination = selected
                textureStatus = "Destination selected: ${selected.displayName}"
                appendLog("Texture destination selected: ${selected.displayName}.")
            }
        }

        fun installTextures() {
            val profile = TextureProfileCatalog.find(preflight?.image?.discSerial)
            if (profile == null) {
                textureStatus = "Select an ISO with a supported game ID before installing textures."
                return
            }
            val destination = textureDestination
            if (destination == null) {
                textureStatus = "Choose the PPSSPP PSP/TEXTURES folder first."
                return
            }
            val installOptions = TextureInstallOptions(
                includeRegionalButtonSwaps = includeRegionalButtonSwaps && profile.regionalButtonSwapAvailable,
                includeExtraHdPortraits = includeExtraHdPortraits && profile.extraHdPortraitsAvailable,
            )
            startTextureOperation("Install HD Textures") { token ->
                textureStatus = "Loading the pinned texture manifest."
                val manifest = TextureAssetManifest.load()
                val plan = TextureAssetManifest.buildPlan(profile, manifest, installOptions)
                val result = withContext(Dispatchers.IO) {
                    fileGateway.installTexturePack(destination, plan, token) { value ->
                        scope.launch { textureProgress = value }
                    }
                }
                textureProgress = null
                textureStatus = "Installed ${result.filesInstalled} verified files to ${result.installedLocation}."
                appendLog("Installed ${result.filesInstalled} verified texture files to ${result.installedLocation}.")
            }
        }

        fun exportLog() {
            val serial = preflight?.image?.discSerial
            val fileName = logExportFileName(serial, fileGateway.localDateStamp())
            start("Export Log") { token ->
                if (serial == null) appendLog("Disc serial unavailable; exporting with an explicit unavailable-serial filename.")
                val destination = fileGateway.pickOutput(fileName) ?: run {
                    appendLog("Log export destination picker cancelled.")
                    return@start
                }
                val temporary = fileGateway.createTempPath("bbs-log", ".log")
                try {
                    val content = logEntries.joinToString(separator = "\n", postfix = "\n")
                    FileSystem.SYSTEM.sink(temporary, mustCreate = true).buffer().use { it.writeUtf8(content) }
                    fileGateway.commitOutput(temporary, destination, token, ProgressReporter(::report))
                    appendLog("Log exported to ${destination.location}.")
                } finally {
                    fileGateway.deleteTemp(temporary)
                }
            }
        }

        val canPatch = stagedSource != null &&
            preflight?.eboot?.supported == true &&
            options.validate().isEmpty()
        val busy = activeJob != null
        val textureProfile = TextureProfileCatalog.find(preflight?.image?.discSerial)
        val textureOperationBusy = activeOperation == "Choose Texture Destination" || activeOperation == "Install HD Textures"

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            PatcherBackground(Modifier.fillMaxSize())
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
                                onSelectOutput = ::selectOutput,
                                onPatch = ::patchIso,
                                onCancel = ::cancelCurrentOperation,
                                isPatching = activeOperation == "Patch ISO",
                                compact = true,
                            )

                            AppPage.HD_TEXTURES -> HdTexturesPage(
                                profile = textureProfile,
                                destination = textureDestination,
                                includeRegionalButtonSwaps = includeRegionalButtonSwaps,
                                includeExtraHdPortraits = includeExtraHdPortraits,
                                statusText = textureStatus,
                                progress = textureProgress,
                                busy = busy,
                                textureOperationBusy = textureOperationBusy,
                                onPickDestination = ::selectTextureDestination,
                                onRegionalButtonSwapsChanged = { includeRegionalButtonSwaps = it },
                                onExtraHdPortraitsChanged = { includeExtraHdPortraits = it },
                                onInstall = ::installTextures,
                                onCancel = ::cancelCurrentOperation,
                                compact = true,
                            )
                            AppPage.LOGS -> LogsPage(
                                entries = logEntries,
                                status = status,
                                progress = progress,
                                busy = busy,
                                onVerifyOutput = ::verifyOutput,
                                onExportLog = ::exportLog,
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
                                onSelectOutput = ::selectOutput,
                                onPatch = ::patchIso,
                                onCancel = ::cancelCurrentOperation,
                                isPatching = activeOperation == "Patch ISO",
                                compact = false,
                            )

                            AppPage.HD_TEXTURES -> HdTexturesPage(
                                profile = textureProfile,
                                destination = textureDestination,
                                includeRegionalButtonSwaps = includeRegionalButtonSwaps,
                                includeExtraHdPortraits = includeExtraHdPortraits,
                                statusText = textureStatus,
                                progress = textureProgress,
                                busy = busy,
                                textureOperationBusy = textureOperationBusy,
                                onPickDestination = ::selectTextureDestination,
                                onRegionalButtonSwapsChanged = { includeRegionalButtonSwaps = it },
                                onExtraHdPortraitsChanged = { includeExtraHdPortraits = it },
                                onInstall = ::installTextures,
                                onCancel = ::cancelCurrentOperation,
                                compact = false,
                            )
                            AppPage.LOGS -> LogsPage(
                                entries = logEntries,
                                status = status,
                                progress = progress,
                                busy = busy,
                                onVerifyOutput = ::verifyOutput,
                                onExportLog = ::exportLog,
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
    onSelectOutput: () -> Unit,
    onPatch: () -> Unit,
    onCancel: () -> Unit,
    isPatching: Boolean,
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
            OptionsCard(options, onOptionsChanged, busy)
            OutputCard(output, onSelectOutput, onPatch, onCancel, busy, canPatch, isPatching, progress)
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Box(Modifier.weight(1f)) {
                    SourceCard(source, status, preflight, progress, onSelectSource, onVerifySource, busy)
                }
                Box(Modifier.weight(1f)) { DetectedGamePane(preflight, compact = false) }
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OptionsCard(options, onOptionsChanged, busy, Modifier.fillMaxWidth(0.88f))
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutputCard(output, onSelectOutput, onPatch, onCancel, busy, canPatch, isPatching, progress, Modifier.fillMaxWidth(0.72f))
            }
            StatusCard(status, progress)
        }
        if (compact) StatusCard(status, progress)
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
private fun PatcherBackground(modifier: Modifier = Modifier) {
    Box(modifier) {
        Image(
            painter = painterResource(Res.drawable.wallpaper),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        Box(Modifier.fillMaxSize().background(Color(0xD9071425)))
    }
}

@Composable
private fun HdTexturesPage(
    profile: TextureProfile?,
    destination: PlatformDirectorySelection?,
    includeRegionalButtonSwaps: Boolean,
    includeExtraHdPortraits: Boolean,
    statusText: String,
    progress: TextureInstallProgress?,
    busy: Boolean,
    textureOperationBusy: Boolean,
    onPickDestination: () -> Unit,
    onRegionalButtonSwapsChanged: (Boolean) -> Unit,
    onExtraHdPortraitsChanged: (Boolean) -> Unit,
    onInstall: () -> Unit,
    onCancel: () -> Unit,
    compact: Boolean,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = if (compact) 18.dp else 28.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("HD Textures", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("PPSSPP texture profiles", style = MaterialTheme.typography.titleLarge)
                if (profile == null) {
                    Text(
                        "Select an ISO in Setup to detect its game ID and match a texture profile. Texture installation has its own destination and status, separate from ISO patching.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(profile.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Game ID: ${profile.serial} · " + when (profile.coverage) {
                            TextureCoverage.PRIMARY -> "Primary upstream profile"
                            TextureCoverage.PARTIAL -> "Partial regional profile"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Pinned source: HD ReMix+ ${TextureProfileCatalog.UPSTREAM_VERSION} · ${TextureProfileCatalog.CORE_PNG_ASSET_COUNT} base images · about 532 MiB expanded.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    PatcherCheckbox(
                        label = "Install extra HD character portraits",
                        checked = includeExtraHdPortraits,
                        onCheckedChange = onExtraHdPortraitsChanged,
                        enabled = !busy && profile.extraHdPortraitsAvailable,
                    )
                    if (profile.regionalButtonSwapAvailable) {
                        PatcherCheckbox(
                            label = "Install regional button swaps (rebind X/O in PPSSPP if needed)",
                            checked = includeRegionalButtonSwaps,
                            onCheckedChange = onRegionalButtonSwapsChanged,
                            enabled = !busy,
                        )
                    } else {
                        Text(
                            "Regional button swaps are available for the European and North American profiles.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "Aqua model delta: unavailable until the complete source ISO can be matched to a verified fingerprint.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Install destination", style = MaterialTheme.typography.titleLarge)
                Text(
                    destination?.displayName ?: "Choose the PPSSPP PSP/TEXTURES folder. The app installs into a new game-ID folder and will not overwrite an existing profile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PatcherButton(
                    label = if (destination == null) "Choose PPSSPP TEXTURES folder" else "Change destination folder",
                    onClick = onPickDestination,
                    enabled = !busy,
                )
            }
        }
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Texture install", style = MaterialTheme.typography.titleLarge)
                Text(statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (progress != null) {
                    if (progress.totalBytes > 0L) {
                        PatcherProgress(
                            progress = (progress.completedBytes.toDouble() / progress.totalBytes.toDouble()).toFloat().coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        PatcherIndeterminateProgress(Modifier.fillMaxWidth())
                    }
                    Text(
                        "${progress.phase.name.replace('_', ' ')} · ${progress.completedFiles} / ${progress.totalFiles} files · ${formatTextureSize(progress.completedBytes)} / ${formatTextureSize(progress.totalBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (textureOperationBusy) {
                    PatcherIndeterminateProgress(Modifier.fillMaxWidth())
                }
                PatcherButton(
                    label = "Install verified textures",
                    onClick = onInstall,
                    enabled = profile != null && destination != null && !busy,
                )
                if (textureOperationBusy) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
            }
        }
    }
}

private fun formatTextureSize(bytes: Long): String =
    if (bytes <= 0L) "—" else "${bytes / (1024L * 1024L)} MiB"

@Composable
private fun LogsPage(
    entries: List<String>,
    status: PatcherStatus,
    progress: PatchProgress?,
    busy: Boolean,
    onVerifyOutput: () -> Unit,
    onExportLog: () -> Unit,
) {
    val logScrollState = rememberScrollState()
    LaunchedEffect(entries.size) {
        logScrollState.animateScrollTo(logScrollState.maxValue)
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Logs", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        StatusCard(status, progress)
        PatcherSurface(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp, max = 500.dp)
                    .verticalScroll(logScrollState),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (entries.isEmpty()) {
                    Text("Operation details will appear here as work progresses.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    entries.forEach { entry ->
                        Text(entry, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
        VerifyOutputFooter(onVerifyOutput, busy)
        PatcherSurface(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Export Log", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Save the operation log using the detected game ID and local date.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PatcherButton("Export Log", onExportLog, enabled = !busy)
            }
        }
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
            Text("1. Game Files", style = MaterialTheme.typography.titleLarge)
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
                    .clip(PatcherNestedCornerShape)
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("2. Detected Game", style = MaterialTheme.typography.titleLarge)
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
                        Text("Game ID  ${preflight?.image?.discSerial ?: "Not available"}", style = MaterialTheme.typography.bodyMedium)
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
    modifier: Modifier = Modifier,
) {
    var combatExpanded by remember { mutableStateOf(true) }
    var advancedExpanded by remember { mutableStateOf(false) }

    PatcherSurface(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("3. Patch Options", style = MaterialTheme.typography.titleLarge)
            Text(
                "PSP-native revalidation is in progress. Only the right-stick candidate is currently patchable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            FeatureToggle(
                title = "Right-stick camera control",
                description = "PSP-native candidate using direct SceCtrlData right-analog reads; gameplay validation pending.",
                checked = options.rightStickCamera,
                enabled = !busy,
                onCheckedChange = { onOptionsChanged(options.copy(rightStickCamera = it)) },
            )
            FeatureToggle(
                title = "Camera distance",
                description = "Unavailable · PC-derived mapping requires PSP-native re-derivation.",
                checked = options.cameraDistanceEnabled,
                enabled = false,
                onCheckedChange = { onOptionsChanged(options.copy(cameraDistanceEnabled = it)) },
            )
            SliderSetting(
                label = "Camera Distance",
                value = options.cameraDistance,
                range = PatchOptions.CAMERA_DISTANCE_RANGE,
                steps = 109,
                enabled = !busy && options.cameraDistanceEnabled,
                onValueChange = { onOptionsChanged(options.copy(cameraDistance = it)) },
            )
            FeatureToggle(
                title = "Camera height",
                description = "Unavailable · PC-derived mapping requires PSP-native re-derivation.",
                checked = options.cameraHeightEnabled,
                enabled = false,
                onCheckedChange = { onOptionsChanged(options.copy(cameraHeightEnabled = it)) },
            )
            SliderSetting(
                label = "Camera Height",
                value = options.cameraHeight,
                range = PatchOptions.CAMERA_HEIGHT_RANGE,
                steps = 39,
                enabled = !busy && options.cameraHeightEnabled,
                onValueChange = { onOptionsChanged(options.copy(cameraHeight = it)) },
            )

            HorizontalDivider()
            ExpandableRow(
                title = "Combat Mods",
                description = "Unavailable while the PC mod is re-derived against the English-patched PSP executable.",
                expanded = combatExpanded,
                enabled = !busy,
                onExpandedChange = { combatExpanded = it },
            )
            AnimatedVisibility(combatExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FeatureToggle(
                        title = "Hit-aware cancels",
                        description = "Unavailable · PSP runtime validation pending.",
                        checked = options.hitAwareCancels,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(hitAwareCancels = it)) },
                    )
                    FeatureToggle(
                        title = "Invincibility windows",
                        description = "Unavailable · PSP runtime validation pending.",
                        checked = options.invincibilityWindows,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(invincibilityWindows = it)) },
                    )
                    FeatureToggle(
                        title = "Extended defense",
                        description = "Unavailable · PSP-native re-derivation pending.",
                        checked = options.extendedDefense,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(extendedDefense = it)) },
                    )
                    FeatureToggle(
                        title = "Command cancels",
                        description = "Unavailable · PSP-native re-derivation pending.",
                        checked = options.commandCancels,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(commandCancels = it)) },
                    )
                    FeatureToggle(
                        title = "Telemetry",
                        description = "Unavailable · PSP-native re-derivation pending.",
                        checked = options.telemetry,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(telemetry = it)) },
                    )
                    FeatureToggle(
                        title = "Critical Mode abilities",
                        description = "Unavailable · PSP runtime validation pending.",
                        checked = options.criticalModeAbilities,
                        enabled = false,
                        onCheckedChange = { onOptionsChanged(options.copy(criticalModeAbilities = it)) },
                    )
                    FeatureToggle(
                        title = "Critical Mode passives",
                        description = "Unavailable · PSP runtime validation pending.",
                        checked = options.criticalModePassives,
                        enabled = false,
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
                            description = "Unavailable · PSP-native re-derivation pending.",
                            checked = options.strictSteamExclusions,
                            enabled = false,
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
            .clip(PatcherNestedCornerShape)
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
        PatcherSwitch(
            checked = checked,
            onCheckedChange = null,
            modifier = Modifier.padding(start = 8.dp),
            enabled = enabled,
        )
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
            .clip(PatcherNestedCornerShape)
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
private fun SliderSetting(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    enabled: Boolean,
    onValueChange: (Float) -> Unit,
) {
    val tenths = (value * 10f).toInt()
    val formattedValue = "${tenths / 10}.${kotlin.math.abs(tenths % 10)}"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Text(formattedValue, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        PatcherSlider(
            value = value,
            onValueChange = { onValueChange((it * 10f).toInt() / 10f) },
            valueRange = range,
            steps = steps,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun OutputCard(
    output: PlatformOutputSelection?,
    onSelect: () -> Unit,
    onPatch: () -> Unit,
    onCancel: () -> Unit,
    busy: Boolean,
    canPatch: Boolean,
    isPatching: Boolean,
    progress: PatchProgress?,
    modifier: Modifier = Modifier,
) {
    PatcherSurface(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("4. Patch", style = MaterialTheme.typography.titleLarge)
            Text(
                output?.displayName ?: "Choose a separate output ISO path.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PatcherButton("Choose output…", onSelect, enabled = !busy && canPatch)
                PatcherButton("Patch ISO", onPatch, enabled = !busy && canPatch && output != null)
            }
            if (isPatching) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PatcherIndeterminateProgress(Modifier.size(44.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Patching ISO", style = MaterialTheme.typography.titleSmall)
                        Text(
                            progress?.detail ?: "Preparing patch pipeline…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = onCancel) { Text("Cancel") }
                }
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
                    "Check payload structure and selected settings. Gameplay validation is separate.",
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

        PatcherStatus.Cancelled -> MessageCard("Cancelled", "The operation was cancelled. Previously completed outputs remain available.")
        is PatcherStatus.Busy -> MessageCard("In progress", progress?.detail ?: status.label)
        is PatcherStatus.Failure -> MessageCard("Unable to continue", status.message)
        is PatcherStatus.Complete -> MessageCard(
            "Patched ISO ready",
            "Output: " + status.outputLocation +
                "\nSource EBOOT SHA-256: " + status.result.sourceEbootSha256 +
                "\nPatched EBOOT SHA-256: " + status.result.patchedEbootSha256,
        )

        is PatcherStatus.Verification -> {
            val title = when (status.result.status) {
                IsoVerificationStatus.VERIFIED_PATCHED -> "Structure verified"
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
    val pages = remember {
        listOf(AppPage.PATCHER, AppPage.HD_TEXTURES, AppPage.LOGS, AppPage.INFO)
    }
    PatcherNavigationBar(
        items = listOf("Setup", "HD Textures", "Logs", "Info"),
        selectedIndex = pages.indexOf(page).coerceAtLeast(0),
        onSelected = { index -> pages.getOrNull(index)?.let(onPageSelected) },
        modifier = Modifier.fillMaxWidth(),
    )
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
            NavigationItem("HD Textures", page == AppPage.HD_TEXTURES) { onPageSelected(AppPage.HD_TEXTURES) }
            NavigationItem("Logs", page == AppPage.LOGS) { onPageSelected(AppPage.LOGS) }
            NavigationItem("Info", page == AppPage.INFO) { onPageSelected(AppPage.INFO) }
        }
    }
}

@Composable
private fun NavigationItem(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().clip(PatcherNestedCornerShape),
        shape = PatcherNestedCornerShape,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp),
        colors = ButtonDefaults.textButtonColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            softWrap = true,
            textAlign = TextAlign.Center,
        )
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
    HD_TEXTURES,
    LOGS,
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

