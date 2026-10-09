package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Bounded ISO DAT-sector reconnaissance. Game-specific archive internals are
 * not assumed: sampled ARC headers and signature bytes are candidates only.
 *
 * This deliberately does NOT scan the complete BBS0..BBS4 files or attempt
 * decompression. The report always gives a sampled/total sector denominator.
 */
internal object IsoArchiveSectorResearch {
    private const val SECTOR = 2048L
    private const val PREFIX_SECTORS = 8
    private const val SUFFIX_SECTORS = 8
    private const val INTERIOR_PROBES = 32
    private const val HEADER_BYTES = 64
    private const val MAX_MATCHES = 8

    internal data class ArcCandidate(val sector: Long, val version: Int, val fileCount: Int)
    data class Report(
        val totalSectors: Long,
        val samples: Int,
        val firstSectorSha256: String,
        val first16Hex: String,
        val arcCandidates: List<ArcCandidate>,
        val arcLikeCount: Int,
        val otherSignatures: Map<String, Int>,
        val lines: List<String>,
    )

    /** No allocation proportional to archive size. All offsets are in-file. */
    internal fun sampledSectors(size: Long): List<Long> {
        if (size < HEADER_BYTES) return emptyList()
        val available = (size - HEADER_BYTES) / SECTOR + 1
        val offsets = mutableSetOf<Long>()
        for (i in 0 until PREFIX_SECTORS) {
            if (i < available) offsets += i.toLong()
        }
        for (i in 0 until SUFFIX_SECTORS) {
            val sector = available - 1L - i
            if (sector >= 0) offsets += sector
        }
        if (available > 1) {
            for (i in 0 until INTERIOR_PROBES) {
                // Use Long math and calculate evenly spaced sample sectors.
                offsets += (available - 1L) * (i + 1L) / (INTERIOR_PROBES + 1L)
            }
        } else offsets += 0
        return offsets.filter { it in 0 until available }.sorted()
    }

    fun inspect(
        source: Path,
        entry: IsoDirectoryEntry,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        require(!entry.isDirectory && entry.size >= 0L) { "Archive must be a file." }
        val samples = sampledSectors(entry.size)
        val total = (entry.size + SECTOR - 1L) / SECTOR
        val other = mutableMapOf<String, Int>()
        val arc = mutableListOf<ArcCandidate>()
        val validatedArc = mutableListOf<String>()
        var arcLike = 0
        var firstSha = "unavailable"
        var firstHex = "unavailable"

        for (index in samples) {
            cancellation.throwIfCancelled()
            val bytes = reader.readAt(source, entry.dataOffset + index * SECTOR, HEADER_BYTES)
            if (index == 0L) {
                val firstSize = minOf(entry.size, SECTOR).toInt()
                // Full first-sector hash is consistent across platforms and
                // helps identify unidentified/packed game archive variants.
                val first = reader.readAt(source, entry.dataOffset, firstSize)
                firstSha = sha256Hex(first)
                firstHex = bytes.take(16).joinToString("") {
                    (it.toInt() and 255).toString(16).uppercase().padStart(2, '0')
                }
            }
            if (bytes.size >= 8 && bytes[0] == 'A'.code.toByte() &&
                bytes[1] == 'R'.code.toByte() && bytes[2] == 'C'.code.toByte() &&
                bytes[3] == 0.toByte()
            ) {
                val version = u16(bytes, 4)
                val count = u16(bytes, 6)
                val remaining = entry.size - index * SECTOR
                if (version == 1 && count in 1..1024 &&
                    16L + count.toLong() * 32L <= remaining
                ) {
                    arcLike++
                    if (arc.size < MAX_MATCHES) {
                        arc += ArcCandidate(index, version, count)
                        val table = IsoArcMetadataProbe.inspect(
                            source, entry, index * SECTOR, reader, cancellation,
                        )
                        validatedArc += "    Sector $index: " +
                            table.observations.joinToString(" | ")
                    }
                }
            } else {
                val signature = when {
                    bytes[0] == 'b'.code.toByte() && bytes[1] == 'b'.code.toByte() &&
                        bytes[2] == 's'.code.toByte() && bytes[3] == 'a'.code.toByte() -> "BBSA"
                    bytes[0] == 'L'.code.toByte() && bytes[1] == '2'.code.toByte() &&
                        bytes[2] == 'D'.code.toByte() && bytes[3] == '@'.code.toByte() -> "L2D@"
                    bytes[0] == '@'.code.toByte() && bytes[1] == 'C'.code.toByte() &&
                        bytes[2] == 'T'.code.toByte() && bytes[3] == 'D'.code.toByte() -> "@CTD"
                    bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                        bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte() -> "ELF"
                    else -> null
                }
                if (signature != null) other[signature] = (other[signature] ?: 0) + 1
            }
        }
        val lines = mutableListOf(
            "  DAT SPARSE PROBE ${entry.path}: sectors=$total sampled=${samples.size} " +
                "first_sector_sha256=$firstSha prefix16_hex=$firstHex",
            "    Sampled ARC-like v1 headers=$arcLike " +
                "(not a full archive scan, and candidate headers are not validated assets).",
        )
        arc.forEach {
            lines += "    ARC candidate relative_sector=${it.sector} " +
                "relative_offset=${it.sector * SECTOR} entries=${it.fileCount}."
        }
        lines += validatedArc
        if (other.isNotEmpty()) {
            lines += "    Other sampled sector magic: " + other.toSortedMap()
                .entries.joinToString(", ") { "${it.key}=${it.value}" }
        }
        lines += "    Zero sampled hits does NOT imply no ARC/data records elsewhere."
        return Report(total, samples.size, firstSha, firstHex, arc, arcLike, other, lines)
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)
}
