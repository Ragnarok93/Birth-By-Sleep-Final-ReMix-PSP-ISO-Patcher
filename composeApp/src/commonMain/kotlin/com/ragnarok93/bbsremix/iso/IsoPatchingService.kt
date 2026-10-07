package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.EbootInspection
import com.ragnarok93.bbsremix.patch.EbootPatchEngine
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.NoProgress
import com.ragnarok93.bbsremix.patch.PatchOptions
import com.ragnarok93.bbsremix.patch.PatchPhase
import com.ragnarok93.bbsremix.patch.PatchProgress
import com.ragnarok93.bbsremix.patch.PatchValidationException
import com.ragnarok93.bbsremix.patch.ProgressReporter
import com.ragnarok93.bbsremix.patch.Stage5EbootPatchEngine
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.FileSystem
import okio.Path

data class IsoPreflight(
    val image: IsoImageInfo,
    val eboot: EbootInspection,
    val game: PspGameMetadata,
)

data class IsoPatchResult(
    val source: Path,
    val destination: Path,
    val sourceEbootSha256: String,
    val patchedEbootSha256: String,
    val outputSize: Long,
    val relocatedEboot: Boolean,
)

data class IsoVerificationResult(
    val candidate: Path,
    val expectedEbootSha256: String,
    val actualEbootSha256: String,
    val outputSize: Long,
)

class IsoPatchingService(
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val reader: Iso9660Reader = Iso9660Reader(fileSystem),
    private val rebuilder: Iso9660Rebuilder = Iso9660Rebuilder(fileSystem),
    private val engine: EbootPatchEngine = Stage5EbootPatchEngine(),
) {
    fun preflight(
        source: Path,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoPreflight {
        options.requireValid()
        cancellation.throwIfCancelled()
        val sourceSize = fileSystem.metadata(source).size ?: 0L
        progress.report(PatchProgress(PatchPhase.INSPECTING, 0L, sourceSize, "Reading ISO9660 directory records"))
        val image = reader.inspect(source)
        cancellation.throwIfCancelled()
        progress.report(PatchProgress(PatchPhase.STAGING_EBOOT, 0L, image.eboot.size, "Reading PSP_GAME/SYSDIR/EBOOT.BIN"))
        val eboot = engine.inspect(reader.readEntry(source, image.eboot), options)
        val game = readGameMetadata(source, image)
        progress.report(PatchProgress(PatchPhase.STAGING_EBOOT, image.eboot.size, image.eboot.size, "Source ISO preflight complete"))
        return IsoPreflight(image, eboot, game)
    }

    fun patchTo(
        source: Path,
        destination: Path,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoPatchResult {
        if (source == destination) throw PatchValidationException("The output ISO must be different from the source ISO.")
        val preflight = preflight(source, options, cancellation, progress)
        if (!preflight.eboot.supported) {
            throw PatchValidationException(preflight.eboot.problems.joinToString(" "))
        }

        val destinationWasAbsent = !fileSystem.exists(destination)
        try {
            cancellation.throwIfCancelled()
            val originalEboot = reader.readEntry(source, preflight.image.eboot)
            progress.report(PatchProgress(PatchPhase.PATCHING_EBOOT, 0L, originalEboot.size.toLong(), "Applying Birth By Sleep - Final ReMix"))
            val patched = engine.patch(originalEboot, options)
            progress.report(PatchProgress(PatchPhase.PATCHING_EBOOT, patched.bytes.size.toLong(), patched.bytes.size.toLong(), "EBOOT patch validated"))

            rebuilder.rebuild(source, destination, preflight.image, patched.bytes, cancellation, progress)
            cancellation.throwIfCancelled()
            progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 0L, 1L, "Re-opening rebuilt ISO"))
            val outputImage = reader.inspect(destination)
            val embedded = reader.readEntry(destination, outputImage.eboot)
            if (!embedded.contentEquals(patched.bytes)) {
                throw IsoFormatException("Rebuilt ISO validation failed: embedded EBOOT does not match the patched result.")
            }
            if (outputImage.eboot.size != patched.bytes.size.toLong()) {
                throw IsoFormatException("Rebuilt ISO validation failed: EBOOT directory size is inconsistent.")
            }
            progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 1L, 1L, "Rebuilt ISO is valid"))
            val outputSize = fileSystem.metadata(destination).size
                ?: throw IsoFormatException("Unable to determine rebuilt ISO size.")
            return IsoPatchResult(
                source = source,
                destination = destination,
                sourceEbootSha256 = preflight.eboot.fingerprint.sha256,
                patchedEbootSha256 = patched.outputSha256,
                outputSize = outputSize,
                relocatedEboot = outputImage.eboot.extentSector != preflight.image.eboot.extentSector,
            )
        } catch (error: Throwable) {
            if (destinationWasAbsent) fileSystem.delete(destination, mustExist = false)
            throw error
        }
    }

    fun verifyPatchedOutput(
        source: Path,
        candidate: Path,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoVerificationResult {
        options.requireValid()
        cancellation.throwIfCancelled()
        progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 0L, 3L, "Preparing expected patched EBOOT"))

        val sourceImage = reader.inspect(source)
        val originalEboot = reader.readEntry(source, sourceImage.eboot)
        val sourceInspection = engine.inspect(originalEboot, options)
        if (!sourceInspection.supported) {
            throw PatchValidationException(sourceInspection.problems.joinToString(" "))
        }
        val expected = engine.patch(originalEboot, options)

        cancellation.throwIfCancelled()
        progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 1L, 3L, "Inspecting selected output ISO"))
        val candidateImage = reader.inspect(candidate)
        val actual = reader.readEntry(candidate, candidateImage.eboot)

        cancellation.throwIfCancelled()
        progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 2L, 3L, "Comparing embedded patched EBOOT"))
        if (!actual.contentEquals(expected.bytes)) {
            throw PatchValidationException(
                "The selected ISO does not match the patch options currently selected for this source ISO."
            )
        }

        progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 3L, 3L, "Output ISO verified successfully"))
        return IsoVerificationResult(
            candidate = candidate,
            expectedEbootSha256 = expected.outputSha256,
            actualEbootSha256 = sha256Hex(actual),
            outputSize = fileSystem.metadata(candidate).size
                ?: throw IsoFormatException("Unable to determine selected output ISO size."),
        )
    }

    private fun readGameMetadata(source: Path, image: IsoImageInfo): PspGameMetadata {
        val fields = runCatching {
            val entry = reader.findEntry(source, image.root, PARAM_SFO_PATH) ?: return@runCatching emptyMap()
            parseParamSfo(reader.readEntry(source, entry))
        }.getOrDefault(emptyMap())

        val cover = runCatching {
            val entry = reader.findEntry(source, image.root, ICON0_PATH) ?: return@runCatching null
            if (entry.isDirectory || entry.size !in 1..MAX_COVER_ART_BYTES) return@runCatching null
            reader.readEntry(source, entry)
        }.getOrNull()

        return PspGameMetadata(
            title = fields["TITLE"],
            discId = fields["DISC_ID"],
            version = fields["APP_VER"] ?: fields["DISC_VERSION"],
            coverArtPng = cover,
        )
    }

    private companion object {
        const val PARAM_SFO_PATH = "PSP_GAME/PARAM.SFO"
        const val ICON0_PATH = "PSP_GAME/ICON0.PNG"
        const val MAX_COVER_ART_BYTES = 4L * 1024L * 1024L
    }
}
