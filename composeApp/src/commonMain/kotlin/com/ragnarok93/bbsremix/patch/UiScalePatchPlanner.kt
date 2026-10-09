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
                )?.let { patch ->
                    requireExpectedDigest(patch, "BBS0.DAT",
                        BbsCtdLayoutCatalog.subtitleCandidate.offsetInArchive, percent)
                    patches += patch
                }
            } else {
                for (candidate in BbsUiLayoutCatalog.suppliedCandidates.filter { it.element == element }) {
                    cancellation.throwIfCancelled()
                    BbsUiLayoutCatalog.planCandidate(
                        iso, image, reader, candidate, percent,
                    )?.let { patch ->
                        requireExpectedDigest(patch, candidate.digestArchive, candidate.digestOffset, percent)
                        patches += patch
                    }
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

    private fun requireExpectedDigest(
        patch: IsoBytePatch,
        archive: String,
        offsetInArchive: Long,
        percent: Int,
    ) {
        val prefix = UiScaleExpectedDigests.expectedPrefix(archive, offsetInArchive, percent)
            ?: throw IsoFormatException("No reference digest for " + archive + "@" + offsetInArchive)
        if (!sha256Hex(patch.replacement).startsWith(prefix)) {
            throw IsoFormatException(
                "Generated UI geometry differs from reference " + percent + "% digest for " +
                    patch.label + "; refusing to create an unverified ISO.",
            )
        }
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
     * and every reference digest against the requested percentage. It can
     * authenticate the bytes but cannot certify correct in-game rendering.
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
                val expected = if (percent == 100) ctd.sourceSha256 else
                    UiScaleExpectedDigests.expectedPrefix("BBS0.DAT", ctd.offsetInArchive, percent)
                if (expected == null || !sha256Hex(bytes).startsWith(expected)) {
                    return false to ("Subtitle CTD does not match requested " + percent + "% source profile.")
                }
                if (percent != 100) changed++
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
                    val expected = if (percent == 100) candidate.originalSha256 else
                        UiScaleExpectedDigests.expectedPrefix(candidate.digestArchive, candidate.digestOffset, percent)
                    if (expected == null || !sha256Hex(bytes).startsWith(expected)) {
                        return false to (candidate.layout + " does not match requested " + percent + "% profile.")
                    }
                    if (percent != 100) changed++
                }
            }
        }
        return (changed > 0) to if (changed > 0) {
            changed.toString() + " UI resource digests match the requested scaling percentages. " +
                "Correct in-game positioning and animations still require PPSSPP gameplay checks."
        } else {
            "No selected UI scaling resources were changed."
        }
    }
}
