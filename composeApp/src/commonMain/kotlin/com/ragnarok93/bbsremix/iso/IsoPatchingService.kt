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
import com.ragnarok93.bbsremix.patch.UiScalePatchPlanner
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.FileSystem
import okio.Path
import okio.buffer

data class IsoPreflight(
    val image: IsoImageInfo,
    val eboot: EbootInspection,
    val coverArt: ByteArray?,
)

enum class IsoVerificationStatus {
    VERIFIED_PATCHED,
    UI_STRUCTURE_VERIFIED,
    UNPATCHED,
    INCOMPATIBLE,
    MALFORMED,
}

data class IsoVerificationResult(
    val status: IsoVerificationStatus,
    val message: String,
    val embeddedEbootSha256: String? = null,
    val outputSize: Long? = null,
)

data class IsoPatchResult(
    val source: Path,
    val destination: Path,
    val sourceEbootSha256: String,
    val patchedEbootSha256: String,
    val outputSize: Long,
    val relocatedEboot: Boolean,
    val uiAssetsPatched: Int = 0,
)

data class IsoRebuildDiagnosticResult(
    val source: Path,
    val destination: Path,
    val sourceEbootSha256: String,
    val rebuiltEbootSha256: String,
    val sourceSize: Long,
    val outputSize: Long,
    val byteIdentical: Boolean,
    val ebootExtentPreserved: Boolean,
    val ebootSizePreserved: Boolean,
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
        val sourceSize = fileSystem.metadata(source).size
            ?: throw IsoFormatException("Unable to determine ISO size.")
        progress.report(PatchProgress(PatchPhase.INSPECTING, 0L, sourceSize, "Reading ISO9660 directory records"))
        val image = reader.inspect(source)
        cancellation.throwIfCancelled()
        progress.report(PatchProgress(PatchPhase.STAGING_EBOOT, 0L, image.eboot.size, "Reading PSP_GAME/SYSDIR/EBOOT.BIN"))
        val eboot = engine.inspect(reader.readEntry(source, image.eboot), options)
        val coverArt = image.coverArt
            ?.takeIf { it.size in 1L..MAX_COVER_ART_BYTES }
            ?.let { entry -> runCatching { reader.readEntry(source, entry) }.getOrNull() }
        progress.report(PatchProgress(PatchPhase.STAGING_EBOOT, image.eboot.size, image.eboot.size, "EBOOT preflight complete"))
        return IsoPreflight(image, eboot, coverArt)
    }

    fun verifyOutput(
        output: Path,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoVerificationResult {
        try {
            options.requireValid()
            cancellation.throwIfCancelled()
            progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 0L, 1L, "Reading selected output ISO"))
            val image = reader.inspect(output)
            val embedded = reader.readEntry(output, image.eboot)
            cancellation.throwIfCancelled()
            val hash = sha256Hex(embedded)
            val inspection = engine.inspect(embedded, options)
            val patched = engine.verifyPatched(embedded, options)
            val uiInspection = if (options.appliesUiScaling) {
                UiScalePatchPlanner.inspectStandaloneOutput(output, image, reader, options)
            } else null
            val outputSize = fileSystem.metadata(output).size
            val result = when {
                uiInspection != null && !uiInspection.first -> IsoVerificationResult(
                    status = IsoVerificationStatus.INCOMPATIBLE,
                    message = uiInspection.second,
                    embeddedEbootSha256 = hash,
                    outputSize = outputSize,
                )
                uiInspection != null && (inspection.supported || patched.verified) -> IsoVerificationResult(
                    status = IsoVerificationStatus.UI_STRUCTURE_VERIFIED,
                    message = "UI resource structure and changed-source checks passed. " +
                        uiInspection.second + " Gameplay validation is pending.",
                    embeddedEbootSha256 = hash,
                    outputSize = outputSize,
                )
                inspection.supported -> IsoVerificationResult(
                    status = IsoVerificationStatus.UNPATCHED,
                    message = "The ISO is a supported source image, but the selected Final ReMix features are not applied.",
                    embeddedEbootSha256 = hash,
                    outputSize = outputSize,
                )
                patched.verified -> IsoVerificationResult(
                    status = IsoVerificationStatus.VERIFIED_PATCHED,
                    message = "The ISO structure and selected patch bytes match. Gameplay validation is pending.",
                    embeddedEbootSha256 = hash,
                    outputSize = outputSize,
                )
                else -> IsoVerificationResult(
                    status = IsoVerificationStatus.INCOMPATIBLE,
                    message = "The ISO is readable, but its embedded EBOOT does not match the selected Final ReMix configuration.",
                    embeddedEbootSha256 = hash,
                    outputSize = outputSize,
                )
            }
            progress.report(PatchProgress(PatchPhase.VALIDATING_OUTPUT, 1L, 1L, result.message))
            return result
        } catch (error: com.ragnarok93.bbsremix.patch.PatchCancelledException) {
            throw error
        } catch (error: IsoFormatException) {
            return IsoVerificationResult(
                status = IsoVerificationStatus.MALFORMED,
                message = error.message ?: "The selected file is not a valid ISO9660 image.",
            )
        } catch (error: IllegalArgumentException) {
            return IsoVerificationResult(
                status = IsoVerificationStatus.INCOMPATIBLE,
                message = error.message ?: "The selected output could not be verified.",
            )
        }
    }

    fun rebuildUnmodifiedTo(
        source: Path,
        destination: Path,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoRebuildDiagnosticResult {
        if (source == destination) {
            throw PatchValidationException("The diagnostic output must be different from the source ISO.")
        }
        if (fileSystem.exists(destination)) {
            throw IsoFormatException("The diagnostic output ISO already exists; choose a separate output path.")
        }

        cancellation.throwIfCancelled()
        val image = reader.inspect(source)
        val originalEboot = reader.readEntry(source, image.eboot)
        val sourceInspection = engine.inspect(originalEboot, PatchOptions())
        if (!sourceInspection.supported) {
            throw PatchValidationException(sourceInspection.problems.joinToString(" "))
        }

        val sourceSize = fileSystem.metadata(source).size
            ?: throw IsoFormatException("Unable to determine source ISO size.")
        progress.report(
            PatchProgress(
                PatchPhase.REBUILDING_ISO,
                0L,
                sourceSize,
                "Diagnostic rebuild: writing the original EBOOT unchanged",
            ),
        )
        rebuilder.rebuild(source, destination, image, originalEboot, cancellation, progress)

        cancellation.throwIfCancelled()
        val rebuiltImage = reader.inspect(destination)
        val rebuiltEboot = reader.readEntry(destination, rebuiltImage.eboot)
        val outputSize = fileSystem.metadata(destination).size
            ?: throw IsoFormatException("Unable to determine diagnostic ISO size.")

        val ebootExtentPreserved = rebuiltImage.eboot.extentSector == image.eboot.extentSector
        val ebootSizePreserved = rebuiltImage.eboot.size == image.eboot.size
        if (!rebuiltEboot.contentEquals(originalEboot)) {
            throw IsoFormatException("Diagnostic rebuild changed EBOOT.BIN bytes.")
        }
        if (!ebootExtentPreserved || !ebootSizePreserved) {
            throw IsoFormatException("Diagnostic rebuild changed the EBOOT directory extent or size.")
        }

        progress.report(
            PatchProgress(
                PatchPhase.VALIDATING_OUTPUT,
                0L,
                sourceSize,
                "Diagnostic rebuild: comparing the complete ISO byte-for-byte",
            ),
        )
        val byteIdentical = filesEqual(source, destination, cancellation, progress, sourceSize)
        if (!byteIdentical) {
            throw IsoFormatException(
                "Diagnostic rebuild changed bytes outside EBOOT.BIN. The ISO rebuild pipeline must be fixed before gameplay patching resumes.",
            )
        }

        progress.report(
            PatchProgress(
                PatchPhase.VALIDATING_OUTPUT,
                sourceSize,
                sourceSize,
                "Diagnostic rebuild is byte-identical to the source ISO",
            ),
        )
        return IsoRebuildDiagnosticResult(
            source = source,
            destination = destination,
            sourceEbootSha256 = sha256Hex(originalEboot),
            rebuiltEbootSha256 = sha256Hex(rebuiltEboot),
            sourceSize = sourceSize,
            outputSize = outputSize,
            byteIdentical = true,
            ebootExtentPreserved = true,
            ebootSizePreserved = true,
        )
    }

    fun patchTo(
        source: Path,
        destination: Path,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
        progress: ProgressReporter = NoProgress,
    ): IsoPatchResult {
        if (!RUNTIME_PATCHING_ENABLED) {
            throw PatchValidationException(
                "No runtime-validated PSP-native patch profile is enabled for the selected options. Use the right-stick-only profile or Diagnostic rebuild.",
            )
        }
        if (source == destination) throw PatchValidationException("The output ISO must be different from the source ISO.")
        val preflight = preflight(source, options, cancellation, progress)
        if (!preflight.eboot.supported) {
            throw PatchValidationException(preflight.eboot.problems.joinToString(" "))
        }

        val destinationWasAbsent = !fileSystem.exists(destination)
        try {
            cancellation.throwIfCancelled()
            val originalEboot = reader.readEntry(source, preflight.image.eboot)
            progress.report(PatchProgress(PatchPhase.PATCHING_EBOOT, 0L, originalEboot.size.toLong(), "Applying selected Final ReMix features"))
            val patched = engine.patch(originalEboot, options)
            progress.report(PatchProgress(PatchPhase.PATCHING_EBOOT, patched.bytes.size.toLong(), patched.bytes.size.toLong(), "EBOOT patch validated"))
            val uiPatches = UiScalePatchPlanner.plan(source, preflight.image, reader, options, cancellation)
            if (uiPatches.isNotEmpty()) {
                progress.report(PatchProgress(PatchPhase.PATCHING_EBOOT, uiPatches.size.toLong(), uiPatches.size.toLong(),
                    "Validated " + uiPatches.size + " source-fingerprinted UI assets"))
            }

            rebuilder.rebuild(source, destination, preflight.image, patched.bytes, cancellation, progress,
                extraPatches = uiPatches)
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
            UiScalePatchPlanner.verifyCommitted(destination, reader, uiPatches, cancellation)
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
                uiAssetsPatched = uiPatches.size,
            )
        } catch (error: Throwable) {
            if (destinationWasAbsent) fileSystem.delete(destination, mustExist = false)
            throw error
        }
    }

    private fun filesEqual(
        source: Path,
        destination: Path,
        cancellation: CancellationToken,
        progress: ProgressReporter,
        total: Long,
    ): Boolean {
        val sourceSize = fileSystem.metadata(source).size ?: return false
        val destinationSize = fileSystem.metadata(destination).size ?: return false
        if (sourceSize != destinationSize) return false

        fileSystem.source(source).buffer().use { left ->
            fileSystem.source(destination).buffer().use { right ->
                var compared = 0L
                val leftChunk = ByteArray(COMPARE_CHUNK_SIZE)
                val rightChunk = ByteArray(COMPARE_CHUNK_SIZE)
                while (compared < sourceSize) {
                    cancellation.throwIfCancelled()
                    val requested = minOf(COMPARE_CHUNK_SIZE.toLong(), sourceSize - compared).toInt()
                    val leftRead = left.read(leftChunk, 0, requested)
                    val rightRead = right.read(rightChunk, 0, requested)
                    if (leftRead != rightRead || leftRead <= 0) return false
                    for (index in 0 until leftRead) {
                        if (leftChunk[index] != rightChunk[index]) return false
                    }
                    compared += leftRead
                    progress.report(
                        PatchProgress(
                            PatchPhase.VALIDATING_OUTPUT,
                            compared,
                            total,
                            "Diagnostic rebuild: byte-for-byte ISO comparison",
                        ),
                    )
                }
            }
        }
        return true
    }

    private companion object {
        const val MAX_COVER_ART_BYTES = 4L * 1024L * 1024L
        const val COMPARE_CHUNK_SIZE = 1024 * 1024
        const val RUNTIME_PATCHING_ENABLED = true
    }
}

