package com.ragnarok93.bbsremix.platform

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.PatchPhase
import com.ragnarok93.bbsremix.patch.PatchProgress
import com.ragnarok93.bbsremix.patch.ProgressReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.prefs.Preferences

class DesktopFileGateway(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileGateway {
    private val preferences = Preferences.userNodeForPackage(DesktopFileGateway::class.java)

    override suspend fun pickSource(): PlatformFileSelection? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Select a Birth By Sleep Final Mix ISO", FileDialog.LOAD)
        dialog.filenameFilter = java.io.FilenameFilter { _, name -> name.endsWith(".iso", ignoreCase = true) }
        dialog.isVisible = true
        val file = dialog.file ?: return@withContext null
        val path = File(dialog.directory, file).absoluteFile
        PlatformFileSelection(path.name, path.absolutePath, path.absolutePath.toPath())
    }

    override suspend fun pickOutput(suggestedName: String): PlatformOutputSelection? = withContext(Dispatchers.Main) {
        val dialog = FileDialog(null as Frame?, "Save patched ISO", FileDialog.SAVE)
        dialog.file = suggestedName
        dialog.isVisible = true
        val file = dialog.file ?: return@withContext null
        val path = File(dialog.directory, file).absoluteFile
        PlatformOutputSelection(path.name, path.absolutePath, path.absolutePath.toPath())
    }

    override suspend fun createTempPath(prefix: String, suffix: String): Path {
        val root = System.getProperty("java.io.tmpdir").toPath()
        return root / "$prefix-\${UUID.randomUUID()}$suffix"
    }

    override suspend fun stageSource(
        source: PlatformFileSelection,
        destination: Path,
        cancellation: CancellationToken,
        progress: ProgressReporter,
    ) {
        val sourcePath = source.token as? Path ?: throw FileGatewayException("The desktop source selection is invalid.")
        copy(sourcePath, destination, cancellation, progress, PatchPhase.STAGING_EBOOT, "Copying ISO to private workspace")
    }

    override suspend fun commitOutput(
        temporary: Path,
        destination: PlatformOutputSelection,
        cancellation: CancellationToken,
        progress: ProgressReporter,
    ) {
        val destinationPath = destination.token as? Path ?: throw FileGatewayException("The desktop output selection is invalid.")
        if (fileSystem.exists(destinationPath)) {
            throw FileGatewayException("The selected output already exists; choose a new output path.")
        }
        cancellation.throwIfCancelled()
        fileSystem.atomicMove(temporary, destinationPath)
        progress.report(PatchProgress(PatchPhase.COMMITTING_OUTPUT, 1L, 1L, "Patched ISO committed"))
    }

    override suspend fun deleteTemp(path: Path) {
        fileSystem.delete(path, mustExist = false)
    }

    override fun isSameSourceAndOutput(source: PlatformFileSelection, output: PlatformOutputSelection): Boolean {
        val sourcePath = source.token as? Path ?: return false
        val outputPath = output.token as? Path ?: return false
        return try {
            File(sourcePath.toString()).canonicalPath == File(outputPath.toString()).canonicalPath
        } catch (_: java.io.IOException) {
            sourcePath.toString() == outputPath.toString()
        }
    }

    override fun shouldShowDonationPrompt(): Boolean =
        preferences.getBoolean(DONATION_PROMPT_KEY, true)

    override fun suppressDonationPrompt() {
        preferences.putBoolean(DONATION_PROMPT_KEY, false)
    }

    override fun openExternalUrl(url: String) {
        runCatching {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI(url))
        }
    }

    private fun copy(
        source: Path,
        destination: Path,
        cancellation: CancellationToken,
        progress: ProgressReporter,
        phase: PatchPhase,
        detail: String,
    ) {
        val total = fileSystem.metadata(source).size ?: 0L
        fileSystem.source(source).buffer().use { input ->
            fileSystem.sink(destination, mustCreate = true).buffer().use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                var completed = 0L
                while (true) {
                    cancellation.throwIfCancelled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    output.write(buffer, 0, read)
                    completed += read
                    progress.report(PatchProgress(phase, completed, total, detail))
                }
            }
        }
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 1024 * 1024
        const val DONATION_PROMPT_KEY = "show-kofi-prompt"
    }
}
