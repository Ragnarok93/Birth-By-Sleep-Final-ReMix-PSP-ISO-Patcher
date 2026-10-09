package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled

/**
 * Bounded, read-only cross-check of external ARC directory hashes against
 * BBSA index directory records. Implements the documented BBSA v5/v6
 * header offsets and twelve-byte directory layout. It does not resolve
 * archive partition, runtime asset names, or encrypted DAT payloads.
 */
internal object IsoBbsaDirectoryEvidence {
    private const val HEADER_BYTES = 0x30
    private const val DIRECTORY_RECORD_BYTES = 12
    private const val PARTITION_RECORD_BYTES = 8
    private const val MAX_PARTITION_RECORDS = 256
    private const val MAX_DIRECTORY_RECORDS = 32768
    private const val MAX_REPORTED_MATCHES = 8
    private const val MAX_LINKS = 64

    data class ExternalReference(
        val archive: String,
        val arcRelativeOffset: Long,
        val name: String,
        val directoryHash: Long,
    )

    data class DirectoryRecord(
        val indexByteOffset: Int,
        val fileNameHash: Long,
        val directoryHash: Long,
        val startSector: Long,
        val sectorCount: Int,
    )

    data class PartitionRecord(
        val indexByteOffset: Int,
        val directoryHash: Long,
        val fileCount: Int,
        val entryOffset: Int,
    )

    data class PartitionMatch(
        val external: ExternalReference,
        val matchingPartitions: Int,
        val examples: List<PartitionRecord>,
    )

    data class Match(
        val external: ExternalReference,
        val matchingRecords: Int,
        val examples: List<DirectoryRecord>,
    )

    data class Report(
        val validIndex: Boolean,
        val directoryCount: Int,
        val directoryTableOffset: Long,
        val matches: List<Match>,
        val lines: List<String>,
        val partitionValid: Boolean = false,
        val partitionCount: Int = 0,
        val partitionMatches: List<PartitionMatch> = emptyList(),
    )

    fun inspect(
        indexPrefix: ByteArray,
        references: List<ExternalReference>,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "BBSA external ARC directory-hash correlation (read-only, index records only):",
        )
        if (indexPrefix.size < HEADER_BYTES ||
            indexPrefix[0] != 'b'.code.toByte() ||
            indexPrefix[1] != 'b'.code.toByte() ||
            indexPrefix[2] != 's'.code.toByte() ||
            indexPrefix[3] != 'a'.code.toByte()
        ) return invalid(lines, "missing/truncated BBSA header")
        val version = u32(indexPrefix, 4)
        val directoryCount = u16(indexPrefix, 0x0e)
        val offset = u32(indexPrefix, 0x14)
        val tableBytes = directoryCount.toLong() * DIRECTORY_RECORD_BYTES
        if (version !in 5L..6L || directoryCount > MAX_DIRECTORY_RECORDS ||
            directoryCount == 0 || offset < HEADER_BYTES ||
            offset > indexPrefix.size.toLong() ||
            tableBytes > indexPrefix.size.toLong() - offset
        ) return invalid(
            lines,
            "unsupported/unbounded directory table: version=$version count=$directoryCount offset=$offset",
        )
        lines += "  BBSA version=$version directory_entries=$directoryCount " +
            "directory_table_byte_offset=$offset index_prefix_bytes=${indexPrefix.size}."
        val selected = references.take(MAX_LINKS)
        val matchLists = selected.map { mutableListOf<DirectoryRecord>() }
        val matchCounts = IntArray(selected.size)
        for (i in 0 until directoryCount) {
            if (i % 512 == 0) cancellation.throwIfCancelled()
            val at = offset.toInt() + i * DIRECTORY_RECORD_BYTES
            val fileHash = u32(indexPrefix, at)
            val packed = u32(indexPrefix, at + 4)
            val dirHash = u32(indexPrefix, at + 8)
            for (j in selected.indices) {
                if (selected[j].directoryHash != dirHash) continue
                matchCounts[j]++
                if (matchLists[j].size < MAX_REPORTED_MATCHES) {
                    matchLists[j] += DirectoryRecord(
                        at, fileHash, dirHash, packed ushr 12, (packed and 0xfff).toInt(),
                    )
                }
            }
        }
        val matches = selected.mapIndexed { i, reference ->
            Match(reference, matchCounts[i], matchLists[i])
        }

        // BBSA has a second, independent namespace: partition directory
        // records (8 bytes each), distinct from its 12-byte file records.
        // This is a bounded hash-field comparison, not arbitrary byte search.
        val partitionCount = u16(indexPrefix, 0x08)
        val partitionOffset = u32(indexPrefix, 0x10)
        val partitionBytes = partitionCount.toLong() * PARTITION_RECORD_BYTES
        val partitionValid = partitionCount in 1..MAX_PARTITION_RECORDS &&
            partitionOffset >= HEADER_BYTES &&
            partitionOffset <= indexPrefix.size.toLong() &&
            partitionBytes <= indexPrefix.size.toLong() - partitionOffset
        val partitionMatches = if (partitionValid) {
            val partitionLists = selected.map { mutableListOf<PartitionRecord>() }
            val partitionCounts = IntArray(selected.size)
            for (i in 0 until partitionCount) {
                if (i % 64 == 0) cancellation.throwIfCancelled()
                val at = partitionOffset.toInt() + i * PARTITION_RECORD_BYTES
                val dirHash = u32(indexPrefix, at)
                val files = u16(indexPrefix, at + 4)
                val fileTableOffset = u16(indexPrefix, at + 6)
                for (j in selected.indices) {
                    if (selected[j].directoryHash != dirHash) continue
                    partitionCounts[j]++
                    if (partitionLists[j].size < MAX_REPORTED_MATCHES) {
                        partitionLists[j] += PartitionRecord(at, dirHash, files, fileTableOffset)
                    }
                }
            }
            selected.mapIndexed { i, ref ->
                PartitionMatch(ref, partitionCounts[i], partitionLists[i])
            }
        } else emptyList()
        lines += "  Link candidates=${references.size}; checked=${selected.size}; " +
            "only BBSA index DIRECTORY fields were matched (not raw-byte occurrences)."
        for (match in matches) {
            val hash = hex(match.external.directoryHash)
            lines += "  ${match.external.archive}@${match.external.arcRelativeOffset} " +
                "${match.external.name} dir_hash=$hash: " +
                "matching_BBSA_directory_entries=${match.matchingRecords}."
            match.examples.forEach { record ->
                lines += "    candidate index_byte_offset=${record.indexByteOffset} " +
                    "file_name_hash=${hex(record.fileNameHash)} " +
                    "start_sector=${record.startSector} sector_count=${record.sectorCount}" +
                    if (record.sectorCount == 0xfff) " (streaming sentinel)" else ""
            }
        }
        if (partitionValid) {
            lines += "  BBSA partition table: count=$partitionCount " +
                "byte_offset=$partitionOffset; exact partition directory-hash comparison:"
            for (match in partitionMatches) {
                lines += "  ${match.external.name} hash=${hex(match.external.directoryHash)}: " +
                    "matching_BBSA_partitions=${match.matchingPartitions}."
                match.examples.forEach { record ->
                    lines += "    partition index_byte_offset=${record.indexByteOffset} " +
                        "directory_hash=${hex(record.directoryHash)} " +
                        "files=${record.fileCount} raw_file_table_offset=${record.entryOffset}"
                }
            }
        } else {
            lines += "  BBSA partition table UNVERIFIED: declared=$partitionCount " +
                "offset=$partitionOffset out of bounded index range; " +
                "no partition match/no-match conclusions."
        }
        lines += "LIMIT: directory-hash agreement alone does NOT resolve the named ARC link. " +
            "The index may include multiple filenames for one directory, and " +
            "the referenced file/partition and actual script data remain unknown."
        return Report(true, directoryCount, offset, matches, lines,
            partitionValid, partitionCount, partitionMatches)
    }

    private fun invalid(lines: List<String>, reason: String): Report =
        Report(false, 0, 0, emptyList(),
            lines + "  BBSA directory table UNVERIFIED: $reason; no match/no-match claims.")

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
