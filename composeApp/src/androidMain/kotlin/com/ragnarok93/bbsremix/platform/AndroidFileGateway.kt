package com.ragnarok93.bbsremix.platform

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.texture.TextureInstallPlan
import com.ragnarok93.bbsremix.texture.TextureInstallProgress
import com.ragnarok93.bbsremix.texture.TextureInstallResult
import com.ragnarok93.bbsremix.patch.PatchPhase
import com.ragnarok93.bbsremix.patch.PatchProgress
import com.ragnarok93.bbsremix.patch.ProgressReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.buffer
import java.util.UUID
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.coroutines.resume

class AndroidFileGateway(
    private val activity: ComponentActivity,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : FileGateway {
    private val resolver: ContentResolver = activity.contentResolver
    private val cacheRoot: Path = activity.cacheDir.absolutePath.toPath() / "bbs-patcher"
    private val preferences = activity.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private var sourceContinuation: kotlinx.coroutines.CancellableContinuation<PlatformFileSelection?>? = null
    private var outputContinuation: kotlinx.coroutines.CancellableContinuation<PlatformOutputSelection?>? = null
    private var textureDestinationContinuation: kotlinx.coroutines.CancellableContinuation<PlatformDirectorySelection?>? = null

    private val sourceLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val continuation = sourceContinuation.also { sourceContinuation = null }
        continuation?.resume(uri?.let { PlatformFileSelection(displayName(it), it.toString(), it) })
    }

    private val outputLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val continuation = outputContinuation.also { outputContinuation = null }
        continuation?.resume(uri?.let { PlatformOutputSelection(displayName(it), it.toString(), it) })
    }

    private val textureDestinationLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val continuation = textureDestinationContinuation.also { textureDestinationContinuation = null }
        continuation?.resume(uri?.let {
            PlatformDirectorySelection("PPSSPP TEXTURES folder", "Selected PPSSPP TEXTURES folder", it)
        })
    }

    override suspend fun pickSource(): PlatformFileSelection? = suspendCancellableCoroutine { continuation ->
        sourceContinuation = continuation
        continuation.invokeOnCancellation { if (sourceContinuation === continuation) sourceContinuation = null }
        sourceLauncher.launch(arrayOf("application/x-cd-image", "application/octet-stream", "*/*"))
    }

    override suspend fun pickOutput(suggestedName: String): PlatformOutputSelection? = suspendCancellableCoroutine { continuation ->
        outputContinuation = continuation
        continuation.invokeOnCancellation { if (outputContinuation === continuation) outputContinuation = null }
        outputLauncher.launch(suggestedName)
    }

    override suspend fun pickTextureDestination(): PlatformDirectorySelection? = suspendCancellableCoroutine { continuation ->
        textureDestinationContinuation = continuation
        continuation.invokeOnCancellation {
            if (textureDestinationContinuation === continuation) textureDestinationContinuation = null
        }
        textureDestinationLauncher.launch(null)
    }

    override suspend fun installTexturePack(
        destination: PlatformDirectorySelection,
        plan: TextureInstallPlan,
        cancellation: CancellationToken,
        progress: (TextureInstallProgress) -> Unit,
    ): TextureInstallResult = withContext(Dispatchers.IO) {
        installTexturePackOnDocumentTree(resolver, destination, plan, cancellation, progress)
    }

    override suspend fun createTempPath(prefix: String, suffix: String): Path {
        fileSystem.createDirectories(cacheRoot)
        return cacheRoot / "$prefix-${UUID.randomUUID()}$suffix"
    }

    override suspend fun stageSource(
        source: PlatformFileSelection,
        destination: Path,
        cancellation: CancellationToken,
        progress: ProgressReporter,
    ) {
        val uri = source.token as? Uri ?: throw FileGatewayException("The Android source selection is invalid.")
        val total = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length }?.takeIf { it >= 0L } ?: 0L
        val input = resolver.openInputStream(uri) ?: throw FileGatewayException("Unable to open the selected ISO.")
        try {
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
                    progress.report(PatchProgress(PatchPhase.STAGING_EBOOT, completed, total, "Copying ISO to private workspace"))
                }
            }
        } finally {
            input.close()
        }
    }

    override suspend fun commitOutput(
        temporary: Path,
        destination: PlatformOutputSelection,
        cancellation: CancellationToken,
        progress: ProgressReporter,
    ) {
        val uri = destination.token as? Uri ?: throw FileGatewayException("The Android output selection is invalid.")
        val output = resolver.openOutputStream(uri, "w") ?: throw FileGatewayException("Unable to open the selected output location.")
        try {
            fileSystem.source(temporary).buffer().use { input ->
                output.use { stream ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    var completed = 0L
                    val total = fileSystem.metadata(temporary).size ?: 0L
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        stream.write(buffer, 0, read)
                        completed += read
                        progress.report(PatchProgress(PatchPhase.COMMITTING_OUTPUT, completed, total, "Writing patched ISO"))
                    }
                    stream.flush()
                }
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            throw FileGatewayException("The patched ISO could not be committed: ${error.message}")
        }
    }

    override suspend fun deleteTemp(path: Path) {
        fileSystem.delete(path, mustExist = false)
    }

    override fun isSameSourceAndOutput(source: PlatformFileSelection, output: PlatformOutputSelection): Boolean =
        (source.token as? Uri)?.toString() == (output.token as? Uri)?.toString()

    override fun shouldShowDonationPrompt(): Boolean =
        preferences.getBoolean(DONATION_PROMPT_KEY, true)

    override fun suppressDonationPrompt() {
        preferences.edit().putBoolean(DONATION_PROMPT_KEY, false).apply()
    }

    override fun openExternalUrl(url: String) {
        runCatching {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }
    }

    override fun localDateStamp(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("MMddyyyy", Locale.US))

    override fun localTimeStamp(): String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US))

    private fun displayName(uri: Uri): String {
        val cursor: Cursor? = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        cursor?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "selected.iso"
    }

    private companion object {
        const val COPY_BUFFER_SIZE = 1024 * 1024
        const val PREFERENCES_NAME = "bbs-remix-preferences"
        const val DONATION_PROMPT_KEY = "show-kofi-prompt"
    }
}
