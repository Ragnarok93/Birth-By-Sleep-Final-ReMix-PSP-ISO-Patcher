package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Bounded, read-only resolution of *validated* ARC external dependencies.
 * Exact directory/path ID AND CRC32(UPPERCASE(filename without extension))
 * must agree with an indexed file record before an asset header is read.
 *
 * Both BBSA file namespaces are searched independently: 12-byte directory
 * records (e.g. lua/) and 8-byte partition-file entries (e.g. arc/pc_*).
 * Does not assume script execution or access runtime PSP state.
 */
internal object IsoBbsaLinkedResourceProbe {
    private const val SECTOR = 2048L
    private const val HEADER = 0x30
    private const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    private const val MAX_DIRECTORY_RECORDS = 32768
    private const val MAX_PARTITIONS = 256
    private const val MAX_PARTITION_FILE_RECORDS = 100000
    private const val MAX_LINKS = 16
    private const val MAX_EXAMPLES_PER_LINK = 4
    private const val MAX_TOTAL_HEADER_READS = 12
    private const val HEADER_SAMPLE_BYTES = 64
    private const val MAX_LUA_CHUNK_READS = 2

    data class IndexedCandidate(
        val namespace: String,
        val indexByteOffset: Int,
        val globalSector: Long,
        val sectorCount: Int,
    )

    data class LinkEvidence(
        val name: String,
        val directoryId: Long,
        val basename: String,
        val filenameHash: Long,
        val directoryTableMatches: Int?,
        val partitionFileMatches: Int?,
        val candidates: List<IndexedCandidate>,
    )

    data class AssetProbe(
        val name: String,
        val namespace: String,
        val location: IsoBbsaIndexedPayloadProbe.Location?,
        val signature: String,
        val prefixHex: String?,
        val sampleSha256: String?,
    )

    data class Report(
        val links: List<LinkEvidence>,
        val probes: List<AssetProbe>,
        val lines: List<String>,
    )

    fun inspect(
        source: Path,
        index: ByteArray,
        archiveFiles: Map<Int, IsoDirectoryEntry>,
        validatedLinks: List<IsoArcMetadataProbe.Entry>,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "      ARC LINK INDEX LOOKUP — READ ONLY (path ID + uppercase extensionless name):",
        )
        if (index.size !in HEADER..MAX_INDEX_BYTES || !isBbsa(index) ||
            u32(index, 4) !in 5L..6L
        ) return Report(emptyList(), emptyList(),
            lines + "        UNVERIFIED BBS0 index; no index searches or asset reads.")
        val refs = validatedLinks.filter { it.isExternalLink }.take(MAX_LINKS)
        if (refs.isEmpty()) return Report(emptyList(), emptyList(),
            lines + "        No validated ARC external references.")

        val dirCount = u16(index, 0x0e)
        val dirBase = u32(index, 0x14)
        val directoriesValid = dirCount in 1..MAX_DIRECTORY_RECORDS &&
            tableFits(index.size, dirBase, dirCount.toLong(), 12L, HEADER.toLong())

        val partitionCount = u16(index, 0x08)
        val partitionFilesBase = u32(index, 0x10)
        val descriptorsEnd = HEADER + partitionCount.toLong() * 8L
        var partitionsValid = partitionCount in 1..MAX_PARTITIONS &&
            tableFits(index.size, HEADER.toLong(), partitionCount.toLong(), 8L,
                HEADER.toLong()) &&
            partitionFilesBase >= descriptorsEnd &&
            partitionFilesBase <= index.size
        data class Partition(val directoryId: Long, val offset: Int, val count: Int)
        val partitions = mutableListOf<Partition>()
        var totalPartitionRecords = 0L
        if (partitionsValid) {
            for (i in 0 until partitionCount) {
                cancellation.throwIfCancelled()
                val pos = HEADER + i * 8
                val name = u32(index, pos)
                val count = u16(index, pos + 4)
                val first = u16(index, pos + 6)
                val base = partitionFilesBase + first.toLong() * 8L
                totalPartitionRecords += count
                if (totalPartitionRecords > MAX_PARTITION_FILE_RECORDS ||
                    !tableFits(index.size, base, count.toLong(), 8L,
                        partitionFilesBase)
                ) {
                    partitionsValid = false
                    break
                }
                partitions += Partition(name, base.toInt(), count)
            }
        }
        lines += "        12byte_directory_index=" +
            if (directoriesValid) "VALID records=$dirCount" else "UNVERIFIED"
        lines += "        8byte_partition_file_index=" +
            if (partitionsValid) "VALID partition_descriptors=$partitionCount " +
                "bounded_records=$totalPartitionRecords" else "UNVERIFIED"

        val linkReports = mutableListOf<LinkEvidence>()
        val assetProbes = mutableListOf<AssetProbe>()
        var luaChunksRead = 0
        for (link in refs) {
            cancellation.throwIfCancelled()
            // In the BBSA index, filenames are recorded without their
            // extensions. The ARC entry name retains its extension.
            val stem = link.name.substringBeforeLast('.', link.name).uppercase()
            val fileHash = IsoBbsaDirectoryEvidence.fileNameHash(stem)
            val candidateList = mutableListOf<IndexedCandidate>()
            var dirHits = 0
            var partitionHits = 0

            if (directoriesValid) {
                for (i in 0 until dirCount) {
                    if (i % 512 == 0) cancellation.throwIfCancelled()
                    val at = dirBase.toInt() + i * 12
                    if (u32(index, at) != fileHash ||
                        u32(index, at + 8) != link.reference) continue
                    dirHits++
                    if (candidateList.size < MAX_EXAMPLES_PER_LINK) {
                        val packed = u32(index, at + 4)
                        candidateList += IndexedCandidate(
                            "BBSA 12-byte directory", at,
                            packed ushr 12, (packed and 0xfffL).toInt(),
                        )
                    }
                }
            }
            if (partitionsValid) {
                for (part in partitions.filter { it.directoryId == link.reference }) {
                    for (i in 0 until part.count) {
                        if (i % 512 == 0) cancellation.throwIfCancelled()
                        val at = part.offset + i * 8
                        if (u32(index, at) != fileHash) continue
                        partitionHits++
                        if (candidateList.size < MAX_EXAMPLES_PER_LINK) {
                            val packed = u32(index, at + 4)
                            candidateList += IndexedCandidate(
                                "BBSA 8-byte partition file", at,
                                packed ushr 12, (packed and 0xfffL).toInt(),
                            )
                        }
                    }
                }
            }
            val evidence = LinkEvidence(link.name, link.reference, stem,
                fileHash, if (directoriesValid) dirHits else null,
                if (partitionsValid) partitionHits else null, candidateList)
            linkReports += evidence
            lines += "        ${link.name} directory_id=${hex(link.reference)} " +
                "path=${IsoBbsaDirectoryEvidence.knownArcDirectory(link.reference)} " +
                "index_basename=$stem crc32=${hex(fileHash)} " +
                "12byte_matches=${dirHits.takeIf { directoriesValid } ?: "UNVERIFIED"} " +
                "partition_matches=${partitionHits.takeIf { partitionsValid } ?: "UNVERIFIED"}."
            for (candidate in candidateList) {
                val loc = IsoBbsaIndexedPayloadProbe.map(
                    index, candidate.globalSector, candidate.sectorCount,
                )
                var signature = "UNVERIFIED logical/physical archive mapping"
                var digest: String? = null
                var preview: String? = null
                var luaLines: List<String> = emptyList()
                if (loc != null) {
                    val entry = archiveFiles[loc.archiveIndex]
                    val fullSize = candidate.sectorCount.toLong() * SECTOR
                    if (entry == null || entry.isDirectory ||
                        loc.archiveRelativeByteOffset > entry.size ||
                        fullSize > entry.size - loc.archiveRelativeByteOffset
                    ) {
                        signature = "UNVERIFIED archive file/extent"
                    } else if (assetProbes.size < MAX_TOTAL_HEADER_READS) {
                        val sample = reader.readAt(source,
                            entry.dataOffset + loc.archiveRelativeByteOffset,
                            minOf(HEADER_SAMPLE_BYTES.toLong(), fullSize).toInt(),
                        )
                        cancellation.throwIfCancelled()
                        digest = sha256Hex(sample)
                        preview = sample.take(16).joinToString("") {
                            (it.toInt() and 255).toString(16).uppercase().padStart(2, '0')
                        }
                        signature = IsoBbsaIndexedPayloadProbe.classify(sample)
                        if (link.reference == 0xC0000000L &&
                            signature.startsWith("Lua bytecode signature") &&
                            fullSize in 12L..IsoLua51MetadataInspector.MAX_INPUT_BYTES.toLong() &&
                            luaChunksRead < MAX_LUA_CHUNK_READS
                        ) {
                            // Only a confirmed index pair and valid ISO extent
                            // may trigger this bounded, in-memory Lua 5.1
                            // metadata check. Bytecode is never executed or
                            // exported; no other resource type is staged.
                            luaChunksRead++
                            cancellation.throwIfCancelled()
                            val chunk = reader.readAt(source,
                                entry.dataOffset + loc.archiveRelativeByteOffset,
                                fullSize.toInt(),
                            )
                            cancellation.throwIfCancelled()
                            val metadata = IsoLua51MetadataInspector.inspect(chunk)
                            luaLines = metadata.lines + "        Indexed Lua " +
                                "allocated_chunk_sha256=${sha256Hex(chunk)} " +
                                "allocated_bytes=${chunk.size}; " +
                                "parser_status=${if (metadata.valid) "VALID" else "UNVERIFIED"}."
                        }
                    } else signature = "UNVERIFIED: header sample budget exhausted"
                }
                val probe = AssetProbe(link.name, candidate.namespace,
                    loc, signature, preview, digest)
                assetProbes += probe
                lines += "          ${candidate.namespace} index_at=${candidate.indexByteOffset} " +
                    "logical_sector=${candidate.globalSector} " +
                    "sectors=${candidate.sectorCount} " +
                    "physical=${loc?.let { "BBS${it.archiveIndex}.DAT " +
                        "sector=${it.physicalSector} relative_byte=${it.archiveRelativeByteOffset}" }
                        ?: "UNVERIFIED"} header=${probe.signature} " +
                    "sample_sha256=${digest ?: "UNVERIFIED"} " +
                    "first16_hex=${preview ?: "UNVERIFIED"}"
                lines += luaLines
            }
        }
        if (validatedLinks.count { it.isExternalLink } > MAX_LINKS) {
            lines += "        Truncated to first $MAX_LINKS external references."
        }
        lines += "        LIMIT: Matching a name+path index entry and/or bytecode magic " +
            "does NOT prove execution, handler dispatch, combat state, or actor ownership. " +
            "At most $MAX_LUA_CHUNK_READS Lua-category allocated chunks " +
            "(each <= ${IsoLua51MetadataInspector.MAX_INPUT_BYTES} bytes) " +
            "are parsed in memory. No file extraction or ISO modifications."
        return Report(linkReports, assetProbes, lines)
    }

    private fun isBbsa(bytes: ByteArray) =
        bytes[0] == 'b'.code.toByte() && bytes[1] == 'b'.code.toByte() &&
            bytes[2] == 's'.code.toByte() && bytes[3] == 'a'.code.toByte()
    private fun tableFits(size: Int, base: Long, count: Long, stride: Long,
                          minBase: Long): Boolean =
        base >= minBase && base <= size && count >= 0 &&
            count <= (size - base) / stride

    private fun hex(v: Long) =
        "0x" + v.toString(16).uppercase().padStart(8, '0')
    private fun u16(b: ByteArray, p: Int) =
        (b[p].toInt() and 255) or ((b[p + 1].toInt() and 255) shl 8)
    private fun u32(b: ByteArray, p: Int) =
        u16(b, p).toLong() or (u16(b, p + 2).toLong() shl 16)
}
