package com.ragnarok93.bbsremix.patch

import com.ragnarok93.bbsremix.iso.Iso9660Reader
import com.ragnarok93.bbsremix.iso.IsoBytePatch
import com.ragnarok93.bbsremix.iso.IsoFormatException
import com.ragnarok93.bbsremix.iso.IsoImageInfo
import okio.Path

/** Fingerprinted, fail-closed experimental UI overlays, independent per category. */
internal object UiScalePatchPlanner {
    fun plan(
        iso: Path,
        image: IsoImageInfo,
        reader: Iso9660Reader,
        options: PatchOptions,
        cancellation: CancellationToken = NeverCancelled,
    ): List<IsoBytePatch> {
        if (!options.appliesUiScaling) return emptyList()
        val patches = mutableListOf<IsoBytePatch>()
        for (element in UiScaleElement.entries) {
            cancellation.throwIfCancelled()
            val percent = options.uiScaling[element]
            if (percent == 100) continue
            val previousCount = patches.size
            if (element == UiScaleElement.SUBTITLES) {
                BbsCtdLayoutCatalog.planExperimentalSubtitleOverlay(
                    iso, image, reader, percent,
                )?.let(patches::add)
            } else {
                for (candidate in BbsUiLayoutCatalog.suppliedCandidates.filter { it.element == element }) {
                    cancellation.throwIfCancelled()
                    BbsUiLayoutCatalog.planCandidate(
                        iso, image, reader, candidate, percent,
                    )?.let(patches::add)
                }
            }
            if (patches.size == previousCount) {
                throw IsoFormatException(
                    "No supported assets changed for " + element.title + " at " + percent + "%.",
                )
            }
        }
        val sorted = patches.sortedBy { it.absoluteOffset }
        for (i in 1 until sorted.size) {
            val before = sorted[i - 1]
            val after = sorted[i]
            if (before.absoluteOffset + before.expected.size > after.absoluteOffset) {
                throw IsoFormatException(
                    "UI patch ranges overlap: " + before.label + " and " + after.label,
                )
            }
        }
        return sorted
    }

    /** Authenticate committed bytes against exactly the patch plan used. */
    fun verifyCommitted(
        output: Path,
        reader: Iso9660Reader,
        patches: List<IsoBytePatch>,
        cancellation: CancellationToken = NeverCancelled,
    ) {
        for (patch in patches) {
            cancellation.throwIfCancelled()
            val actual = reader.readAt(output, patch.absoluteOffset, patch.replacement.size)
            if (!actual.contentEquals(patch.replacement)) {
                throw IsoFormatException("UI output mismatch for " + patch.label)
            }
        }
    }

    /**
     * Verify Output operates without the original source. Check file structure
     * and selected resources against known original hashes, but deliberately
     * cannot claim an exact scaling percentage or correct in-game rendering.
     */
    fun inspectStandaloneOutput(
        iso: Path,
        image: IsoImageInfo,
        reader: Iso9660Reader,
        options: PatchOptions,
    ): Pair<Boolean, String> {
        if (!options.appliesUiScaling) return true to "No UI scaling selected."
        var changed = 0
        for (element in UiScaleElement.entries) {
            val percent = options.uiScaling[element]
            if (percent == 100) continue
            if (element == UiScaleElement.SUBTITLES) {
                val ctd = BbsCtdLayoutCatalog.subtitleCandidate
                val entry = reader.findOptionalEntry(iso, image, "PSP_GAME/USRDIR/BBS0.DAT")
                    ?: return false to "BBS0.DAT is missing."
                if (entry.size != BbsCtdLayoutCatalog.BBS0_SIZE) {
                    return false to "BBS0.DAT has an unexpected size."
                }
                val bytes = reader.readAt(iso, entry.dataOffset + ctd.offsetInArchive, ctd.size)
                try { BbsCtdGeometry.scaleSubtitleLayouts(bytes, 100) }
                catch (_: IllegalArgumentException) { return false to "Subtitle CTD structure is invalid." }
                if (sha256Hex(bytes) == ctd.sourceSha256) {
                    return false to "Subtitle CTD remains stock."
                }
                changed++
            } else {
                val candidates = BbsUiLayoutCatalog.suppliedCandidates.filter { it.element == element }
                for (candidate in candidates) {
                    val entry = reader.findOptionalEntry(
                        iso, image, "PSP_GAME/USRDIR/" + candidate.archive,
                    ) ?: return false to (candidate.archive + " is missing.")
                    if (entry.size != candidate.archiveSize) return false to (candidate.archive + " has an unexpected size.")
                    val bytes = reader.readAt(iso, entry.dataOffset + candidate.offsetInArchive, candidate.layoutSize)
                    try { BbsL2dGeometry.scale(bytes, 100) }
                    catch (_: IllegalArgumentException) { return false to (candidate.layout + " is invalid.") }
                    val isStock = sha256Hex(bytes) == candidate.originalSha256
                    if (percent != 100 && isStock) {
                        return false to (candidate.layout + " is stock despite the requested " + percent + "% scale.")
                    }
                    if (percent == 100 && !isStock) {
                        return false to (candidate.layout + " differs from stock at 100%.")
                    }
                    if (percent != 100) changed++
                }
            }
        }
        return (changed > 0) to if (changed > 0) {
            changed.toString() + " UI resources have non-stock, structurally valid geometry. " +
                "Exact percentages and PPSSPP presentation still require comparison with the source ISO and gameplay."
        } else {
            "No selected UI scaling resources were changed."
        }
    }
}
