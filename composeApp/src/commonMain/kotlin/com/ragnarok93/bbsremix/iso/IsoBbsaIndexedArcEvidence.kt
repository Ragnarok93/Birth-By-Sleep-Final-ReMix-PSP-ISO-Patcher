package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Strictly bounded, read-only ARC member reconnaissance for an exact BBSA
 * partition-file match. Never treats a resource filename as proof of code.
 *
 * One ARC directory must fit into the already-read first 2048-byte sector.
 * Local files are only sampled after the entire directory passes validation
 * against the BBSA entry's allocated-sector length. At most 12 additional
 * 16-byte member signatures are read (192 bytes total); no payload exported.
 */
internal object IsoBbsaIndexedArcEvidence {
    private const val SECTOR = 2048L
    private const val MAX_MEMBER_PROBES = 12
    private const val MEMBER_SIGNATURE_BYTES = 16
    private const val MAX_ARC_DIRECTORY_BYTES = 2048

    data class MemberEvidence(
        val name: String,
        val offset: Long,
        val length: Long,
        val signature: String,
        val sampleSha256: String,
        val previewHex: String,
    )

    data class Result(
        val validDirectory: Boolean,
        val declaredEntries: Int,
        val externalLinks: Int,
        val localEntries: Int,
        val members: List<MemberEvidence>,
        val lines: List<String>,
    )

    fun inspect(
        source: Path,
        archive: IsoDirectoryEntry,
        arcRelativeOffset: Long,
        allocatedSectors: Int,
        firstSector: ByteArray,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Result {
        val lines = mutableListOf(
            "    INDEXED ARC MEMBERS — READ ONLY; directory and at most " +
                "$MAX_MEMBER_PROBES x $MEMBER_SIGNATURE_BYTES-byte signatures:"
        )
        val allocation = allocatedSectors.toLong() * SECTOR
        if (archive.isDirectory || allocatedSectors !in 1..0xffe ||
            arcRelativeOffset < 0 || arcRelativeOffset > archive.size ||
            allocation > archive.size - arcRelativeOffset ||
            firstSector.size < 16 || firstSector.size > MAX_ARC_DIRECTORY_BYTES
        ) return Result(false, 0, 0, 0, emptyList(),
            lines + "      UNVERIFIED: ARC/ISO extent or initial header is out of bounds.")

        cancellation.throwIfCancelled()
        // All metadata is decoded from the first sector, without staging the
        // complete allocated BBSA archive or its contained file data.
        val table = IsoArcMetadataProbe.inspectTable(firstSector, allocation)
        lines += table.observations.map { "      $it" }
        if (!table.valid) {
            lines += "      UNVERIFIED ARC directory; member data NOT sampled."
            return Result(false, table.entryCount, table.externalLinks,
                table.payloadRecords, emptyList(), lines)
        }
        val members = mutableListOf<MemberEvidence>()
        val actualArcStart = archive.dataOffset + arcRelativeOffset
        for (entry in table.entries) {
            cancellation.throwIfCancelled()
            if (entry.isExternalLink) {
                lines += "      EXTERNAL ${entry.name} directory_id=${hex(entry.reference)} " +
                    "(${IsoBbsaDirectoryEvidence.knownArcDirectory(entry.reference)}); " +
                    "linked asset not opened."
                continue
            }
            if (members.size >= MAX_MEMBER_PROBES) {
                lines += "      Local member signature budget reached; remaining members not sampled."
                break
            }
            // inspectTable already verified offset+size <= allocated region.
            // Check again before reading in case its contract changes.
            if (entry.payloadOffset < 0 || entry.payloadSize < 1 ||
                entry.payloadOffset > allocation ||
                entry.payloadSize > allocation - entry.payloadOffset
            ) {
                lines += "      UNVERIFIED member range ${entry.name}; not read."
                continue
            }
            val readSize = minOf(MEMBER_SIGNATURE_BYTES.toLong(),
                entry.payloadSize).toInt()
            val head = reader.readAt(source,
                actualArcStart + entry.payloadOffset, readSize)
            cancellation.throwIfCancelled()
            val kind = IsoBbsaIndexedPayloadProbe.classify(head)
            val preview = head.joinToString("") {
                (it.toInt() and 255).toString(16).uppercase().padStart(2, '0')
            }
            val evidence = MemberEvidence(entry.name, entry.payloadOffset,
                entry.payloadSize, kind, sha256Hex(head), preview)
            members += evidence
            lines += "      LOCAL ${evidence.name} arc_offset=${evidence.offset} " +
                "size=${evidence.length} header=${evidence.signature} " +
                "sampled_bytes=$readSize sha256=${evidence.sampleSha256} " +
                "prefix_hex=${evidence.previewHex}"
        }
        lines += "      LIMIT: ARC member names and 16-byte signature checks do NOT " +
            "identify handler semantics, hit-confirm callbacks, or runtime lifetimes."
        return Result(true, table.entryCount, table.externalLinks,
            table.payloadRecords, members, lines)
    }

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')
}
