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
    private const val MAX_TOTAL_PARTITION_FILE_RECORDS = 100000

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

    data class PartitionFileCandidate(
        val indexByteOffset: Int,
        val fileNameHash: Long,
        val startSector: Long,
        val sectorCount: Int,
    )

    data class PartitionMatch(
        val external: ExternalReference,
        val matchingPartitions: Int,
        val examples: List<PartitionRecord>,
        val filenameHash: Long = 0,
        val matchingFiles: Int = 0,
        val candidateFiles: List<PartitionFileCandidate> = emptyList(),
    )

    data class GlobalPartitionFileCandidate(
        val partitionId: Long,
        val indexByteOffset: Int,
        val startSector: Long,
        val sectorCount: Int,
    )

    data class GlobalNameMatch(
        val external: ExternalReference,
        val filenameCrc32: Long,
        val directoryFileMatches: Int,
        val directoryExamples: List<DirectoryRecord>,
        val partitionFileMatches: Int?,
        val partitionExamples: List<GlobalPartitionFileCandidate>,
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
        val globalNameMatches: List<GlobalNameMatch> = emptyList(),
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
        lines += "  BBSA filename lookup normalization: UPPERCASE name before UTF-8 CRC32 " +
            "(OpenKh Bbsa.cs GetHash(name.ToUpper()))."
        val selected = references.take(MAX_LINKS)
        val matchLists = selected.map { mutableListOf<DirectoryRecord>() }
        val matchCounts = IntArray(selected.size)
        val wantedHashes = selected.map { fileNameHash(it.name) }
        val globalDirectoryCounts = IntArray(selected.size)
        val globalDirectoryExamples = selected.map { mutableListOf<DirectoryRecord>() }
        for (i in 0 until directoryCount) {
            if (i % 512 == 0) cancellation.throwIfCancelled()
            val at = offset.toInt() + i * DIRECTORY_RECORD_BYTES
            val fileHash = u32(indexPrefix, at)
            val packed = u32(indexPrefix, at + 4)
            val dirHash = u32(indexPrefix, at + 8)
            for (j in selected.indices) {
                if (fileHash == wantedHashes[j]) {
                    globalDirectoryCounts[j]++
                    if (globalDirectoryExamples[j].size < MAX_REPORTED_MATCHES) {
                        globalDirectoryExamples[j] += DirectoryRecord(
                            at, fileHash, dirHash, packed ushr 12,
                            (packed and 0xfff).toInt(),
                        )
                    }
                }
                if (selected[j].directoryHash == dirHash) {
                    matchCounts[j]++
                    if (matchLists[j].size < MAX_REPORTED_MATCHES) {
                        matchLists[j] += DirectoryRecord(
                            at, fileHash, dirHash, packed ushr 12,
                            (packed and 0xfff).toInt(),
                        )
                    }
                }
            }
        }
        val matches = selected.mapIndexed { i, reference ->
            Match(reference, matchCounts[i], matchLists[i])
        }
        lines += "  Link candidates=${references.size}; checked=${selected.size}; " +
            "BBSA 12-byte FILE/DIRECTORY record path-hash field only."
        for (match in matches) {
            lines += "  ${match.external.archive}@${match.external.arcRelativeOffset} " +
                "${match.external.name} dir_hash=${hex(match.external.directoryHash)}: " +
                "matching_BBSA_directory_entries=${match.matchingRecords}."
            match.examples.forEach { record ->
                lines += "    candidate index_byte_offset=${record.indexByteOffset} " +
                    "file_name_hash=${hex(record.fileNameHash)} " +
                    "start_sector=${record.startSector} sector_count=${record.sectorCount}" +
                    if (record.sectorCount == 0xfff) " (streaming sentinel)" else ""
            }
        }


        // OpenKh.Bbs/Bbsa.cs: partition HEADERS start at fixed 0x30.
        // Header +0x10 is the base offset of the separate 8-byte
        // PARTITION-FILE-ENTRY ARRAY, *not* the partition header table.
        // Earlier versions mistakenly decoded the array as 15 headers,
        // yielding an invalid "0/15 partition matches" conclusion.
        val partitionCount = u16(indexPrefix, 0x08)
        val partitionFileEntriesBase = u32(indexPrefix, 0x10)
        val partitionHeaderLength = partitionCount.toLong() * PARTITION_RECORD_BYTES
        var partitionValid = partitionCount in 1..MAX_PARTITION_RECORDS &&
            HEADER_BYTES + partitionHeaderLength <= indexPrefix.size.toLong() &&
            partitionFileEntriesBase >= HEADER_BYTES + partitionHeaderLength &&
            partitionFileEntriesBase <= indexPrefix.size.toLong()
        data class ParsedPartition(
            val record: PartitionRecord,
            val fileStart: Int,
            val fileEnd: Int,
        )
        val partitions = mutableListOf<ParsedPartition>()
        if (partitionValid) {
            for (i in 0 until partitionCount) {
                if (i % 64 == 0) cancellation.throwIfCancelled()
                val at = HEADER_BYTES + i * PARTITION_RECORD_BYTES
                val dirHash = u32(indexPrefix, at)
                val files = u16(indexPrefix, at + 4)
                val fileTableIndex = u16(indexPrefix, at + 6)
                val fileStart = partitionFileEntriesBase + fileTableIndex.toLong() * 8L
                val fileBytes = files.toLong() * 8L
                if (fileStart > indexPrefix.size || fileBytes > indexPrefix.size - fileStart) {
                    partitionValid = false
                    break
                }
                partitions += ParsedPartition(
                    PartitionRecord(at, dirHash, files, fileTableIndex),
                    fileStart.toInt(), (fileStart + fileBytes).toInt(),
                )
            }
        }
        // Bound the total number of partition *file records* scanned,
        // including overlapping partition slices in malformed indexes.
        if (partitionValid && partitions.sumOf {
            it.record.fileCount.toLong()
        } > MAX_TOTAL_PARTITION_FILE_RECORDS) partitionValid = false

        val partitionMatches = if (partitionValid) selected.map { reference ->
            val matching = partitions.filter {
                it.record.directoryHash == reference.directoryHash
            }
            val wantedFileHash = fileNameHash(reference.name)
            var totalFiles = 0
            val fileExamples = mutableListOf<PartitionFileCandidate>()
            for (part in matching) {
                for (at in part.fileStart until part.fileEnd step 8) {
                    val nameHash = u32(indexPrefix, at)
                    if (nameHash != wantedFileHash) continue
                    totalFiles++
                    if (fileExamples.size < MAX_REPORTED_MATCHES) {
                        val info = u32(indexPrefix, at + 4)
                        fileExamples += PartitionFileCandidate(
                            at, nameHash, info ushr 12, (info and 0xfff).toInt(),
                        )
                    }
                }
            }
            PartitionMatch(
                reference, matching.size, matching.take(MAX_REPORTED_MATCHES).map { it.record },
                wantedFileHash, totalFiles, fileExamples,
            )
        } else emptyList()
        // Independent filename-only search across ALL file-hash fields,
        // including the paths not matching the ARC's directory pointer.
        // This is not a resource resolver; collisions and duplicate names
        // across different directories are possible.
        val allPartitionNameCounts = IntArray(selected.size)
        val allPartitionExamples = selected.map {
            mutableListOf<GlobalPartitionFileCandidate>()
        }
        if (partitionValid) {
            for (part in partitions) {
                for (at in part.fileStart until part.fileEnd step 8) {
                    val hash = u32(indexPrefix, at)
                    for (j in selected.indices) {
                        if (hash != wantedHashes[j]) continue
                        allPartitionNameCounts[j]++
                        if (allPartitionExamples[j].size < MAX_REPORTED_MATCHES) {
                            val packed = u32(indexPrefix, at + 4)
                            allPartitionExamples[j] += GlobalPartitionFileCandidate(
                                part.record.directoryHash, at, packed ushr 12,
                                (packed and 0xfff).toInt(),
                            )
                        }
                    }
                }
            }
        }
        val globalNameMatches = selected.mapIndexed { i, ref ->
            GlobalNameMatch(ref, wantedHashes[i],
                globalDirectoryCounts[i], globalDirectoryExamples[i],
                if (partitionValid) allPartitionNameCounts[i] else null,
                if (partitionValid) allPartitionExamples[i] else emptyList(),
            )
        }
        lines += "  GLOBAL BBSA filename-hash census (index-only; " +
            "not tied to ARC link target directory):"
        for (found in globalNameMatches) {
            lines += "    ${found.external.name} CRC32=${hex(found.filenameCrc32)}: " +
                "12byte_file_name_hash_matches=${found.directoryFileMatches}/$directoryCount; " +
                "partition_file_name_hash_matches=" +
                (found.partitionFileMatches?.toString() ?: "UNVERIFIED") + "."
            found.directoryExamples.forEach { item ->
                lines += "      12byte_index_entry_at=${item.indexByteOffset} " +
                    "path_id=${hex(item.directoryHash)} " +
                    "category_hint=${categoryHint(item.directoryHash)} " +
                    "sector=${item.startSector} sectors=${item.sectorCount}"
            }
            found.partitionExamples.forEach { item ->
                lines += "      partition_index_entry_at=${item.indexByteOffset} " +
                    "path_id=${hex(item.partitionId)} " +
                    "known_path=${knownArcDirectory(item.partitionId)} " +
                    "sector=${item.startSector} sectors=${item.sectorCount}"
            }
        }
        lines += "  LIMIT: filename CRC32 matches are candidate index entries only; " +
            "hash collisions, aliases, format variants, and unindexed resources " +
            "are not excluded by this census."

        if (partitionValid) {
            lines += "  BBSA partition headers: count=$partitionCount fixed_offset=48 " +
                "partition_file_entries_base=$partitionFileEntriesBase " +
                "(correct OpenKh layout; 0x10 is NOT the header offset)."
            for (match in partitionMatches) {
                lines += "  ${match.external.name} directory=${hex(match.external.directoryHash)} " +
                    "(${knownArcDirectory(match.external.directoryHash)}): " +
                    "matching_BBSA_partition_headers=${match.matchingPartitions}; " +
                    "filename_crc32=${hex(match.filenameHash)}; " +
                    "matching_files_within_partition=${match.matchingFiles}."
                match.examples.forEach { record ->
                    lines += "    partition descriptor_at=${record.indexByteOffset} " +
                        "directory_id=${hex(record.directoryHash)} " +
                        "files=${record.fileCount} file_entry_start_index=${record.entryOffset}"
                }
                match.candidateFiles.forEach { candidate ->
                    lines += "    indexed file candidate at=${candidate.indexByteOffset} " +
                        "name_hash=${hex(candidate.fileNameHash)} " +
                        "start_sector=${candidate.startSector} " +
                        "sector_count=${candidate.sectorCount}" +
                        if (candidate.sectorCount == 0xfff) " (streaming sentinel)" else ""
                }
            }
        } else {
            lines += "  BBSA partition headers UNVERIFIED: count=$partitionCount " +
                "file_entries_base=$partitionFileEntriesBase malformed/truncated index. " +
                "No partition match/no-match claim."
        }
        lines += "LIMIT: directory-hash agreement alone does NOT resolve the named ARC link. " +
            "The index may include multiple filenames for one directory, and " +
            "the referenced file/partition and actual script data remain unknown."
        return Report(true, directoryCount, offset, matches, lines,
            partitionValid, partitionCount, partitionMatches, globalNameMatches)
    }

    /** High-byte hints follow OpenKh's PathCategories, not a runtime proof. */
    internal fun categoryHint(hash: Long): String = when ((hash ushr 24).toInt()) {
        0xC0 -> "lua"
        0x00 -> "arc category / path-specific"
        0x80 -> "sound/bgm"
        0x90, 0x91, 0x92, 0x93, 0x94, 0x95 -> "sound/se"
        0xD0, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6,
        0xD7, 0xD8, 0xD9, 0xDA, 0xDB -> "message"
        else -> "unidentified"
    }

    /** Known path IDs from OpenKh.Bbs/Bbsa.cs, not CRC32 values. */
    internal fun knownArcDirectory(hash: Long): String = when (hash) {
        0x4D4D4947L -> "arc/gimmick"
        0x53534F42L -> "arc/boss"
        0x4D454E45L -> "arc/enemy"
        0x4E455645L -> "arc/event"
        0x0043504EL -> "arc/npc"
        0x00004350L -> "arc/pc"
        0x0050414DL -> "arc/map"
        0x00435445L -> "arc/etc"
        0x00535953L -> "arc/system"
        0x554E454DL -> "arc/menu"
        else -> "unknown path ID"
    }

    /**
     * OpenKh.Bbs/Bbsa.cs performs GetHash(name.ToUpper()) when resolving
     * partition filenames, while Bbsa.Hash.cs implements reflected CRC32.
     * An ARC name is often lowercase and must NOT be hashed as-is.
     * The decoded filenames in this game are ASCII, so invariant uppercase
     * produces the same hash across Android, Windows, Linux and macOS.
     */
    internal fun fileNameHash(name: String): Long {
        var crc = -1
        for (ch in name.uppercase().encodeToByteArray()) {
            var index = (crc xor (ch.toInt() and 255)) and 255
            repeat(8) {
                index = if ((index and 1) != 0) (index ushr 1) xor 0xEDB88320.toInt()
                    else index ushr 1
            }
            crc = (crc ushr 8) xor index
        }
        return crc.inv().toUInt().toLong()
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
