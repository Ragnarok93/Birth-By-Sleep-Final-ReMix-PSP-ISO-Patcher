package com.ragnarok93.bbsremix.iso

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoBbsaDirectoryEvidenceTest {
    private val external = IsoBbsaDirectoryEvidence.ExternalReference(
        "PSP_GAME/USRDIR/BBS1.DAT", 156_127_232L, "g01lua", 0x4D4D4947L,
    )
    private fun index(): ByteArray = ByteArray(2048).also { bytes ->
        "bbsa".encodeToByteArray().copyInto(bytes)
        bytes.writeU32(4, 6)
        bytes.writeU16(0x0e, 3)
        bytes.writeU32(0x14, 0x40)
        bytes.writeU16(0x08, 2) // BBSA partition descriptor count
        bytes.writeU32(0x10, 0x80) // first 8-byte PARTITION FILE entry, not descriptor
        bytes.writeU32(0x30, 0x4D4D4947) // descriptor 0: arc/gimmick
        bytes.writeU16(0x34, 2) // two entries
        bytes.writeU16(0x36, 0) // entry index 0
        bytes.writeU32(0x38, 0xCAFE1234.toInt()) // descriptor 1: unrelated path
        bytes.writeU16(0x3c, 1)
        bytes.writeU16(0x3e, 2) // entry index 2
        bytes.writeU32(0x80, 0x64B5573A) // CRC32("g01lua")
        bytes.writeU32(0x84, (0x1AA shl 12) or 2)
        bytes.writeU32(0x88, 0xBBAADDCC.toInt()) // other gimmick file hash
        bytes.writeU32(0x8c, (0x1AB shl 12) or 1)
        bytes.writeU32(0x90, 0x0FF00FF0) // other directory
        bytes.writeU32(0x94, (0x11 shl 12) or 4)
        // Three BBSA directory records; two share the same directory hash
        // but represent different filename hashes. Hash agreement by itself
        // does not resolve the "g01lua" link's file identity.
        bytes.writeU32(0x40, 0x12345678)
        bytes.writeU32(0x44, (0x1AB shl 12) or 3)
        bytes.writeU32(0x48, 0x4D4D4947)
        bytes.writeU32(0x4c, 0x76543210)
        bytes.writeU32(0x50, (0x1AC shl 12) or 0xfff)
        bytes.writeU32(0x54, 0x4D4D4947)
        bytes.writeU32(0x58, 0x0BADF00D)
        bytes.writeU32(0x5c, (0x20 shl 12) or 1)
        bytes.writeU32(0x60, 0xCAFE1234.toInt())
    }

    @Test
    fun correlates_documented_directory_hash_and_decodes_packed_sector_metadata() {
        val source = index()
        val original = source.copyOf()
        val report = IsoBbsaDirectoryEvidence.inspect(source, listOf(external))
        assertTrue(report.validIndex)
        assertEquals(3, report.directoryCount)
        assertEquals(0x40L, report.directoryTableOffset)
        val match = report.matches.single()
        assertEquals(2, match.matchingRecords)
        assertEquals(listOf(0x12345678L, 0x76543210L),
            match.examples.map { it.fileNameHash })
        assertEquals(listOf(0x1ABL, 0x1ACL),
            match.examples.map { it.startSector })
        assertEquals(listOf(3, 0xfff), match.examples.map { it.sectorCount })
        assertContains(report.lines.joinToString("\n"), "g01lua dir_hash=0x4D4D4947")
        assertContains(report.lines.joinToString("\n"), "streaming sentinel")
        assertContains(report.lines.joinToString("\n"), "does NOT resolve")
        assertEquals(0, report.globalNameMatches.single().directoryFileMatches)
        assertEquals(1, report.globalNameMatches.single().partitionFileMatches)
        assertEquals("arc/gimmick",
            IsoBbsaDirectoryEvidence.knownArcDirectory(
                report.globalNameMatches.single().partitionExamples.single().partitionId,
            ))
        assertContains(report.lines.joinToString("\n"), "GLOBAL BBSA filename-hash census")
        assertTrue(report.partitionValid)
        assertEquals(2, report.partitionCount)
        assertEquals(1, report.partitionMatches.single().matchingPartitions)
        assertEquals(2, report.partitionMatches.single().examples.single().fileCount)
        assertEquals(1, report.partitionMatches.single().matchingFiles)
        assertEquals(0x64B5573AL, report.partitionMatches.single().filenameHash)
        assertEquals(0x1AAL, report.partitionMatches.single().candidateFiles.single().startSector)
        assertContains(report.lines.joinToString("\n"), "matching_BBSA_partition_headers=1")
        assertContains(report.lines.joinToString("\n"), "matching_files_within_partition=1")
        assertContains(report.lines.joinToString("\n"), "arc/gimmick")
        assertTrue(source.contentEquals(original))
    }

    @Test
    fun unmatched_hash_is_not_treated_as_a_resolution() {
        val report = IsoBbsaDirectoryEvidence.inspect(index(), listOf(
            external.copy(directoryHash = 0xAABBCCDDL),
        ))
        assertTrue(report.validIndex)
        assertEquals(0, report.matches.single().matchingRecords)
        assertEquals(0, report.partitionMatches.single().matchingPartitions)
        // Same filename hash still occurs in another partition. A missing
        // directory ID match is not evidence that a filename is absent.
        assertEquals(1, report.globalNameMatches.single().partitionFileMatches)
        assertContains(report.lines.joinToString("\n"), "matching_BBSA_directory_entries=0")
    }

    @Test
    fun invalid_partition_table_cannot_claim_a_zero_match_when_file_table_is_valid() {
        val source = index().apply { writeU32(0x10, 2047) }
        val report = IsoBbsaDirectoryEvidence.inspect(source, listOf(external))
        assertTrue(report.validIndex)
        assertFalse(report.partitionValid)
        assertTrue(report.partitionMatches.isEmpty())
        assertEquals(null, report.globalNameMatches.single().partitionFileMatches)
        assertEquals(2, report.matches.single().matchingRecords)
        assertContains(report.lines.joinToString("\n"), "BBSA partition headers UNVERIFIED")
        assertContains(report.lines.joinToString("\n"), "No partition match/no-match claim")
    }

    @Test
    fun path_identifiers_are_not_crc32_and_hashing_matches_openkh() {
        assertEquals("arc/gimmick",
            IsoBbsaDirectoryEvidence.knownArcDirectory(0x4D4D4947))
        assertEquals("arc/boss",
            IsoBbsaDirectoryEvidence.knownArcDirectory(0x53534F42))
        assertEquals("unknown path ID",
            IsoBbsaDirectoryEvidence.knownArcDirectory(0x12345678))
        assertEquals(0x64B5573AL,
            IsoBbsaDirectoryEvidence.fileNameHash("g01lua"))
        assertEquals(0x00000000L,
            IsoBbsaDirectoryEvidence.fileNameHash(""))
        assertEquals(0xCBF43926L,
            IsoBbsaDirectoryEvidence.fileNameHash("123456789"))
        // CRC32(path) intentionally differs from the fixed GIMM directory ID.
        assertFalse(
            IsoBbsaDirectoryEvidence.fileNameHash("arc/gimmick") ==
                0x4D4D4947L,
        )
    }

    @Test
    fun same_filename_in_another_index_namespace_is_not_called_a_resolved_link() {
        val source = index()
        source.writeU32(0x58, 0x64B5573A) // third 12-byte record filename
        source.writeU32(0x60, 0xC0000000.toInt()) // lua-category path hint
        source.writeU32(0x80, 0xBBAADDCC.toInt()) // remove gimmick filename match
        source.writeU32(0x90, 0x64B5573A) // move filename to other partition
        val report = IsoBbsaDirectoryEvidence.inspect(source, listOf(external))
        assertTrue(report.validIndex)
        assertTrue(report.partitionValid)
        val local = report.partitionMatches.single()
        assertEquals(1, local.matchingPartitions)
        assertEquals(0, local.matchingFiles)
        val global = report.globalNameMatches.single()
        assertEquals(1, global.directoryFileMatches)
        assertEquals(1, global.partitionFileMatches)
        assertEquals(0xC0000000L, global.directoryExamples.single().directoryHash)
        assertEquals(0xCAFE1234L, global.partitionExamples.single().partitionId)
        assertContains(report.lines.joinToString("\n"), "category_hint=lua")
        assertContains(report.lines.joinToString("\n"), "filename CRC32 matches are candidate")
    }

    @Test
    fun malformed_header_table_and_version_fail_closed_without_claiming_no_matches() {
        for (source in listOf(
            byteArrayOf(0x7f),
            index().apply { writeU32(4, 9) },
            index().apply { writeU32(0x14, 2045) },
            index().apply { writeU16(0x0e, 65535) },
            index().apply { writeU16(0x0e, 0) },
        )) {
            val report = IsoBbsaDirectoryEvidence.inspect(source, listOf(external))
            assertFalse(report.validIndex)
            assertTrue(report.matches.isEmpty())
            assertContains(report.lines.joinToString("\n"), "UNVERIFIED")
            assertContains(report.lines.joinToString("\n"), "no match/no-match claims")
        }
    }

    private fun ByteArray.writeU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeU32(offset: Int, value: Int) {
        writeU16(offset, value)
        writeU16(offset + 2, value ushr 16)
    }
}
