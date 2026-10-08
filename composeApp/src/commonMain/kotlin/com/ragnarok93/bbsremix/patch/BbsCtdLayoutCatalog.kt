package com.ragnarok93.bbsremix.patch

import com.ragnarok93.bbsremix.iso.Iso9660Reader
import com.ragnarok93.bbsremix.iso.IsoBytePatch
import com.ragnarok93.bbsremix.iso.IsoFormatException
import com.ragnarok93.bbsremix.iso.IsoImageInfo
import okio.Path

/**
 * Exact BBS0 CTD fingerprint manifest derived from the user's verified,
 * compact BBS0 UI ZIP. All offsets are in *plaintext BBS0.DAT*, not the ISO.
 *
 * This manifest only covers seven CTDs linked by ARC records. The global
 * BBSA directory also indexes more message resources, and these must be
 * audited before shipping any subtitle/UI scaling.
 *
 * This planner is intentionally NOT integrated into the production ISO patch
 * path. No live scaling claims are made until PPSSPP rendering is verified.
 */
internal object BbsCtdLayoutCatalog {
    const val BBS0_SIZE = 754655232L

    data class Candidate(
        val fileName: String,
        val offsetInArchive: Long,
        val size: Int,
        val sourceSha256: String,
    )

    val verifiedCtds: List<Candidate> = listOf(
        Candidate("CT00000.ctd", 754538496L, 16384,
            "bb5779f3e12c8d6484a15de9c49e786ec3217d11c0f336666e801f70259d1ede"),
        Candidate("CT00500.ctd", 754585600L, 14336,
            "af6faf8bb66021d6cd488f0f6ed4e1de41bba98a713d0ad4f65d3c9b30c6aeff"),
        Candidate("CT00200.ctd", 754583552L, 2048,
            "d49c0d6796da4244fcbeb1985807048dd56e01c343fbe72f9d247c2c53b4000e"),
        Candidate("CTit000.ctd", 754614272L, 8192,
            "d96fa76f0aea630f6558fc2c18dd0f6c81119d5de5d86ea7e3fdcbe3de473b11"),
        Candidate("CTih000.ctd", 754599936L, 14336,
            "b93a3f35663a8d1f8977b49dfdaf654464f4f22bd9b1bf88d14856c5b0c8f538"),
        Candidate("CTmn600.ctd", 754286592L, 4096,
            "9d1e6c7cd03fb684033a6c947a883cbf71520d61b4f6ca34e782fcc0faa2fb22"),
        Candidate("CTrp500.ctd", 754505728L, 4096,
            "e76632b1a4b17154fdaed6b11079540d000f583eee7c638e7fa57a2cb6731328"),
    )

    /** The inspected file with 25 subtitle-style CTD layouts. */
    val subtitleCandidate: Candidate = verifiedCtds.first()

    fun planExperimentalSubtitleOverlay(
        iso: Path,
        image: IsoImageInfo,
        reader: Iso9660Reader,
        percent: Int,
    ): IsoBytePatch? {
        require(UiScaleSettings.isSelectable(percent))
        if (percent == 100) return null
        val path = "PSP_GAME/USRDIR/BBS0.DAT"
        val entry = reader.findOptionalEntry(iso, image, path)
            ?: throw IsoFormatException("$path is missing from the selected ISO.")
        if (entry.size != BBS0_SIZE) {
            throw IsoFormatException("$path size differs from the supplied BBS0 index.")
        }
        val ctd = subtitleCandidate
        if (ctd.offsetInArchive > entry.size ||
            ctd.size.toLong() > entry.size - ctd.offsetInArchive
        ) {
            throw IsoFormatException("CTD location exceeds the BBS0 archive.")
        }
        val absolute = entry.dataOffset + ctd.offsetInArchive
        val stock = reader.readAt(iso, absolute, ctd.size)
        if (sha256Hex(stock) != ctd.sourceSha256) {
            throw IsoFormatException("CT00000.ctd did not match the verified plaintext source.")
        }
        val edited = BbsCtdGeometry.scaleSubtitleLayouts(stock, percent)
        if (edited.subtitleRowsChanged != 25) {
            throw IsoFormatException("Expected 25 verified subtitle layouts; refusing incomplete edit.")
        }
        check(edited.bytes.size == stock.size)
        return IsoBytePatch(absolute, stock, edited.bytes, "BBS0.DAT/CT00000.ctd")
    }
}
