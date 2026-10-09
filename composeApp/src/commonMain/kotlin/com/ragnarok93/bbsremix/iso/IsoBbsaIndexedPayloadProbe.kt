package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Maps an exact BBSA partition + uppercase filename-hash index hit to its
 * physical BBS*.DAT sector, following OpenKh's CalculateArchiveOffset.
 *
 * READ ONLY: inspect at most one 2048-byte sector of each validated candidate.
 * No decryption, disassembly, extraction, or runtime/hook assumptions.
 */
internal object IsoBbsaIndexedPayloadProbe {
    private const val SECTOR = 2048L
    private const val HEADER_BYTES = 0x30
    private const val MAX_MATCHES = 8
    private const val MAX_SAMPLE_BYTES = 2048
    private const val MAX_INLINE_BYTES = 32

    data class Location(
        val archiveIndex: Int,
        val physicalSector: Long,
        val archiveRelativeByteOffset: Long,
    )

    data class Probe(
        val name: String,
        val directoryHash: Long,
        val filenameHash: Long,
        val logicalSector: Long,
        val sectorCount: Int,
        val location: Location?,
        val format: String,
        val first32Hex: String?,
        val sampledSha256: String?,
    )

    data class Report(val probes: List<Probe>, val lines: List<String>)

    /**
     * OpenKh Bbsa.Entry.CalculateArchiveOffset maps the global BBSA sector
     * to a physical DAT sector. Archive 0 has a different base from BBS1..4.
     * Refuse malformed/non-monotone boundaries, streaming sentinel, and
     * logical ranges crossing an archive boundary.
     */
    internal fun map(
        header: ByteArray,
        logicalStart: Long,
        sectorCount: Int,
    ): Location? {
        if (header.size < HEADER_BYTES || !magic(header) ||
            u32(header, 4) !in 5L..6L || sectorCount !in 1..0xffe ||
            logicalStart < 0
        ) return null
        val archive0 = u16(header, 0x1a).toLong()
        val starts = longArrayOf(
            0,
            u32(header, 0x20), u32(header, 0x24),
            u32(header, 0x28), u32(header, 0x2c),
        )
        val total = u32(header, 0x1c)
        if (archive0 == 0L || starts[1] == 0L ||
            total <= starts[4] || starts[1] <= archive0 ||
            (1 until starts.size).any { i ->
                i > 1 && starts[i] <= starts[i - 1]
            } || logicalStart > total ||
            sectorCount.toLong() > total - logicalStart
        ) return null

        var index = 0
        for (i in 1..4) if (logicalStart >= starts[i]) index = i
        val archiveEndExclusive = if (index < 4) starts[index + 1] else total
        if (logicalStart >= archiveEndExclusive ||
            sectorCount.toLong() > archiveEndExclusive - logicalStart
        ) return null
        val physical = if (index == 0) {
            logicalStart + archive0
        } else {
            logicalStart - starts[index] + 1
        }
        if (physical < 0 || physical > Long.MAX_VALUE / SECTOR) return null
        return Location(index, physical, physical * SECTOR)
    }

    fun inspect(
        source: Path,
        bbs0Index: ByteArray,
        archives: Map<Int, IsoDirectoryEntry>,
        correlations: IsoBbsaDirectoryEvidence.Report,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "BBSA INDEXED PAYLOAD HEADER PROBES — READ ONLY (exact path ID + uppercased filename CRC32):",
        )
        val probes = mutableListOf<Probe>()
        if (!correlations.validIndex || !correlations.partitionValid) {
            lines += "  UNVERIFIED: BBSA index or partition-file table not valid; no payload read."
            return Report(probes, lines)
        }
        for (match in correlations.partitionMatches) {
            for (candidate in match.candidateFiles) {
                if (probes.size >= MAX_MATCHES) break
                cancellation.throwIfCancelled()
                val location = map(bbs0Index, candidate.startSector, candidate.sectorCount)
                val name = match.external.name
                val fmt: String
                var prefix: String? = null
                var digest: String? = null
                if (location == null) {
                    fmt = "UNVERIFIED logical->DAT mapping (malformed/crosses archive boundary)"
                } else {
                    val entry = archives[location.archiveIndex]
                    val len = candidate.sectorCount.toLong() * SECTOR
                    if (entry == null || entry.isDirectory ||
                        location.archiveRelativeByteOffset > entry.size ||
                        len > entry.size - location.archiveRelativeByteOffset
                    ) {
                        fmt = "UNVERIFIED: target DAT missing/out of bounds"
                    } else {
                        val readSize = minOf(len, MAX_SAMPLE_BYTES.toLong()).toInt()
                        val bytes = reader.readAt(
                            source, entry.dataOffset + location.archiveRelativeByteOffset,
                            readSize,
                        )
                        cancellation.throwIfCancelled()
                        fmt = classify(bytes)
                        prefix = bytes.take(MAX_INLINE_BYTES).joinToString("") {
                            (it.toInt() and 255).toString(16).uppercase().padStart(2, '0')
                        }
                        digest = sha256Hex(bytes)
                    }
                }
                val probe = Probe(name, match.external.directoryHash,
                    match.filenameHash, candidate.startSector, candidate.sectorCount,
                    location, fmt, prefix, digest)
                probes += probe
                val mapped = location?.let {
                    "BBS${it.archiveIndex}.DAT physical_sector=${it.physicalSector} " +
                        "relative_byte_offset=${it.archiveRelativeByteOffset}"
                } ?: "no safe mapping"
                lines += "  ${probe.name} path_id=${hex(probe.directoryHash)} " +
                    "filename_crc32=${hex(probe.filenameHash)} " +
                    "logical_sector=${probe.logicalSector} sectors=${probe.sectorCount} " +
                    "-> $mapped; header_format=${probe.format}; " +
                    "sample_bytes=${if (probe.sampledSha256 == null) 0 else MAX_SAMPLE_BYTES} " +
                    "sample_sha256=${probe.sampledSha256 ?: "UNVERIFIED"} " +
                    "prefix_hex=${probe.first32Hex ?: "UNVERIFIED"}"
            }
        }
        if (probes.isEmpty()) lines += "  No exact partition+filename index records to probe."
        if (probes.size == MAX_MATCHES) lines += "  Probe limit reached ($MAX_MATCHES)."
        lines += "  LIMIT: Index mapping and file signature cannot prove the payload was " +
            "successfully decoded, or that it implements any combat callback. " +
            "At most one 2 KiB sector is read per match; no assets are extracted."
        return Report(probes, lines)
    }

    internal fun classify(data: ByteArray): String = when {
        data.size >= 5 && data[0] == 0x1b.toByte() &&
            data[1] == 'L'.code.toByte() && data[2] == 'u'.code.toByte() &&
            data[3] == 'a'.code.toByte() ->
                "Lua bytecode signature (version_byte=0x" +
                    (data[4].toInt() and 255).toString(16).uppercase().padStart(2, '0') +
                    "; bytecode NOT decoded)"
        data.size >= 4 && data[0] == 'A'.code.toByte() &&
            data[1] == 'R'.code.toByte() && data[2] == 'C'.code.toByte() &&
            data[3] == 0.toByte() -> "ARC header (not decoded)"
        data.size >= 4 && data[0] == 0x29.toByte() &&
            data[1] == 0x41.toByte() && data[2] == 0x26.toByte() &&
            data[3] == 0x41.toByte() -> "ICE container signature (not decoded)"
        data.size >= 4 && data[0] == '~'.code.toByte() &&
            data[1] == 'P'.code.toByte() && data[2] == 'S'.code.toByte() &&
            data[3] == 'P'.code.toByte() -> "encrypted PSP module signature (not decoded)"
        else -> "unknown header (raw/encrypted/compressed/other)"
    }

    private fun magic(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 'b'.code.toByte() &&
            bytes[1] == 'b'.code.toByte() &&
            bytes[2] == 's'.code.toByte() && bytes[3] == 'a'.code.toByte()

    private fun hex(v: Long): String = "0x" + v.toString(16).uppercase().padStart(8, '0')
    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
