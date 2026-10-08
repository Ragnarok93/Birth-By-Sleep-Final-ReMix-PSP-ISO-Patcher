package com.ragnarok93.bbsremix.patch

import com.ragnarok93.bbsremix.iso.Iso9660Reader
import com.ragnarok93.bbsremix.iso.IsoBytePatch
import com.ragnarok93.bbsremix.iso.IsoFormatException
import com.ragnarok93.bbsremix.iso.IsoImageInfo
import okio.Path

/**
 * Research-only catalog for layouts located in the supplied plaintext BBS1.DAT.
 *
 * Offsets are in the DAT, not the ISO. No candidate proves that all resources
 * needed by its GUI category are identified. This planner is intentionally
 * NOT connected to the shipping patch path until all renderer/keyframe/UI
 * dependencies and BBS0 assets have been analyzed and validated in PPSSPP.
 */
internal object BbsUiLayoutCatalog {
    data class Candidate(
        val element: UiScaleElement,
        val archive: String,
        val archiveSize: Long,
        val layout: String,
        val offsetInArchive: Long,
        val layoutSize: Int,
        val originalSha256: String,
    )

    val suppliedCandidates: List<Candidate> = listOf(
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_00.l2d",
            0xDDE1D0, 35856, "ddd889601e296fce30af8ae5ac8c11ab5fb6d5cb0e65769cb4a2bdcaae9cd2f8"),
        Candidate(UiScaleElement.COMMAND_DECK, "BBS1.DAT", BBS1_SIZE, "comm_01.l2d",
            0xE018E0, 356608, "ad1f61e62a08ff8cceec61ac8bd5259c3581f5410254e2d4fca3275e7c60032b"),
        Candidate(UiScaleElement.SHOTLOCK, "BBS1.DAT", BBS1_SIZE, "shot_00.l2d",
            0x15854C0, 73536, "d4a5a6cfb751c7841350b5cb038858cf2f1d7b419c372a78aaf16c50c3df2941"),
        Candidate(UiScaleElement.SHOTLOCK, "BBS1.DAT", BBS1_SIZE, "shot_02.l2d",
            0x15B0250, 26640, "787aaebe9a5b085cd3e72338c3f9e40879a5e78e48466bc2837a998c9de52bd3"),
        Candidate(UiScaleElement.GAUGES, "BBS1.DAT", BBS1_SIZE, "gauge_01.l2d",
            0x159F060, 70128, "b72c694db963838a4b7b29b32e802bf7fdce26507b021ae6cfbfc424749d15b8"),
        Candidate(UiScaleElement.PORTRAITS, "BBS1.DAT", BBS1_SIZE, "shp_face.l2d",
            0xAA46070, 10880, "f47ba763f57548434a59d8b07a381170219d09405489a0e46695b61c28353caf"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "pause.l2d",
            0xC392830, 16272, "0e4d0eae7b98f54a0de5c2ee952dcbfc0d83f042721e970eb3da8b1074f808de"),
        Candidate(UiScaleElement.MENUS, "BBS1.DAT", BBS1_SIZE, "camp.l2d",
            0xC2F6A50, 518320, "20db310957d3296bbceb326d938e1feb750b38f8dcf1f498900a15f118411983"),
    )

    /**
     * Construct one exact-source-verified, size-preserving experimental patch.
     * Only a test harness may invoke it until category-level validation is
     * complete. Never patch a mismatched or encrypted layout.
     */
    fun planCandidate(
        iso: Path,
        image: IsoImageInfo,
        reader: Iso9660Reader,
        candidate: Candidate,
        percent: Int,
    ): IsoBytePatch? {
        require(UiScaleSettings.isSelectable(percent))
        if (percent == 100) return null
        require(candidate in suppliedCandidates) { "UI layout is not in the inspected source manifest." }
        val path = "PSP_GAME/USRDIR/" + candidate.archive
        val entry = reader.findOptionalEntry(iso, image, path)
            ?: throw IsoFormatException(path + " is missing from the selected ISO.")
        if (entry.size != candidate.archiveSize) {
            throw IsoFormatException(path + " has an unexpected size; refusing unverified layout changes.")
        }
        if (candidate.offsetInArchive < 0L ||
            candidate.offsetInArchive > entry.size ||
            candidate.layoutSize.toLong() > entry.size - candidate.offsetInArchive
        ) {
            throw IsoFormatException(candidate.layout + " lies outside the selected DAT.")
        }
        val position = entry.dataOffset + candidate.offsetInArchive
        val old = reader.readAt(iso, position, candidate.layoutSize)
        if (sha256Hex(old) != candidate.originalSha256) {
            throw IsoFormatException(candidate.layout + " SHA-256 mismatch; the game layout was not changed.")
        }
        val scaled = BbsL2dGeometry.scale(old, percent)
        check(scaled.bytes.size == old.size)
        if (scaled.totalFieldsChanged == 0) {
            throw IsoFormatException(candidate.layout + " has no adjustable static geometry.")
        }
        return IsoBytePatch(position, old, scaled.bytes, candidate.archive + "/" + candidate.layout)
    }

    private const val BBS1_SIZE = 206092288L
}
