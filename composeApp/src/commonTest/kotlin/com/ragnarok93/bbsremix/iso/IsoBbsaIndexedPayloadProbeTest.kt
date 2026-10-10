package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IsoBbsaIndexedPayloadProbeTest {
    private val fs = FileSystem.SYSTEM

    private fun header(): ByteArray = ByteArray(2048).also {
        "bbsa".encodeToByteArray().copyInto(it)
        it.putU32(4, 6)
        it.putU16(0x1a, 16) // BBS0 physical-sectors offset
        it.putU32(0x1c, 300) // global sector count
        it.putU32(0x20, 100) // BBS1
        it.putU32(0x24, 150) // BBS2
        it.putU32(0x28, 200) // BBS3
        it.putU32(0x2c, 260) // BBS4
    }

    @Test
    fun maps_logical_sectors_to_openkh_physical_offsets_and_rejects_crossing_extents() {
        val index = header()
        assertEquals(66L, IsoBbsaIndexedPayloadProbe.map(index, 50, 2)?.physicalSector)
        assertEquals(0, IsoBbsaIndexedPayloadProbe.map(index, 50, 2)?.archiveIndex)
        assertEquals(1L, IsoBbsaIndexedPayloadProbe.map(index, 100, 2)?.physicalSector)
        assertEquals(3L, IsoBbsaIndexedPayloadProbe.map(index, 102, 2)?.physicalSector)
        assertEquals(1, IsoBbsaIndexedPayloadProbe.map(index, 102, 2)?.archiveIndex)
        assertEquals(1L, IsoBbsaIndexedPayloadProbe.map(index, 150, 2)?.physicalSector)
        assertEquals(2, IsoBbsaIndexedPayloadProbe.map(index, 150, 2)?.archiveIndex)
        assertEquals(1L, IsoBbsaIndexedPayloadProbe.map(index, 260, 2)?.physicalSector)
        assertEquals(4, IsoBbsaIndexedPayloadProbe.map(index, 260, 2)?.archiveIndex)
        assertNull(IsoBbsaIndexedPayloadProbe.map(index, 149, 2))
        assertNull(IsoBbsaIndexedPayloadProbe.map(index, 299, 2))
        assertNull(IsoBbsaIndexedPayloadProbe.map(index, 100, 0))
        assertNull(IsoBbsaIndexedPayloadProbe.map(index, 100, 0xfff))
        assertNull(IsoBbsaIndexedPayloadProbe.map(index.copyOf(32), 102, 2))
        assertNull(IsoBbsaIndexedPayloadProbe.map(index.copyOf().apply {
            putU32(0x28, 149)
        }, 102, 2))
    }

    @Test
    fun read_only_bytecode_header_probe_uses_only_verified_candidate_and_bounded_dat_sector() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-indexed-payload-probe.iso"
        fs.delete(path, mustExist = false)
        val index = header()
        index.putU16(0x08, 1) // one partition
        index.putU16(0x0e, 1) // one directory record
        index.putU32(0x10, 0x80) // partition-file array
        index.putU32(0x14, 0x40) // directory-file array
        index.putU32(0x30, 0x4D4D4947) // gimmick
        index.putU16(0x34, 1)
        index.putU16(0x36, 0)
        index.putU32(0x80, 0xF5BE1086.toInt()) // G01LUA filename CRC32
        index.putU32(0x84, (102 shl 12) or 2)

        val image = ByteArray(32 * 2048)
        // BBS1 starts at mock disc sector 5; global 102 => physical sector 3.
        byteArrayOf(0x1b, 0x4c, 0x75, 0x61, 0x51, 0, 1).copyInto(image, (5 + 3) * 2048)
        try {
            fs.sink(path).buffer().use { it.write(image) }
            val entry = IsoDirectoryEntry(
                path = "PSP_GAME/USRDIR/BBS1.DAT",
                name = "BBS1.DAT",
                recordOffset = 0,
                extentSector = 5,
                size = 10L * 2048L,
                flags = 0,
                recordLength = 48,
                sectorSize = 2048,
            )
            val link = IsoBbsaDirectoryEvidence.ExternalReference(
                "PSP_GAME/USRDIR/BBS1.DAT", 156_127_232L,
                "g01lua", 0x4D4D4947L,
            )
            val reader = Iso9660Reader(fs)
            val correlation = IsoBbsaDirectoryEvidence.inspect(index, listOf(link))
            val before = fs.source(path).buffer().use { it.readByteArray() }
            val result = IsoBbsaIndexedPayloadProbe.inspect(
                path, index, mapOf(1 to entry), correlation, reader,
            )
            assertEquals(1, result.probes.size)
            val sample = result.probes.single()
            assertNotNull(sample.location)
            assertEquals(1, sample.location.archiveIndex)
            assertEquals(3L * 2048L, sample.location.archiveRelativeByteOffset)
            assertContains(sample.format, "Lua bytecode signature")
            assertContains(sample.format, "0x51")
            assertEquals(102L, sample.logicalSector)
            assertEquals(2, sample.sectorCount)
            assertTrue(sample.first32Hex?.startsWith("1B4C756151") == true)
            assertEquals(64, sample.sampledSha256?.length)
            assertTrue(before.contentEquals(fs.source(path).buffer().use { it.readByteArray() }))

            val absent = IsoBbsaIndexedPayloadProbe.inspect(
                path, index, emptyMap(), correlation, reader,
            )
            assertContains(absent.lines.joinToString("\n"), "target DAT missing/out of bounds")
            assertTrue(absent.probes.single().sampledSha256 == null)

            val wrong = IsoBbsaDirectoryEvidence.inspect(index, listOf(
                link.copy(name = "does_not_exist"),
            ))
            assertTrue(IsoBbsaIndexedPayloadProbe.inspect(
                path, index, mapOf(1 to entry), wrong, reader,
            ).probes.isEmpty())
        } finally {
            fs.delete(path, mustExist = false)
        }
    }

    @Test
    fun identifies_exa_and_abc_headers_without_decoding_or_semantic_claims() {
        assertContains(
            IsoBbsaIndexedPayloadProbe.classify(
                byteArrayOf('e'.code.toByte(), 'x'.code.toByte(),
                    'a'.code.toByte(), 0, 0, 0),
            ),
            "EXA header",
        )
        assertContains(
            IsoBbsaIndexedPayloadProbe.classify(
                byteArrayOf('@'.code.toByte(), 'A'.code.toByte(),
                    'B'.code.toByte(), 'C'.code.toByte()),
            ),
            "ABC header",
        )
    }

    @Test
    fun signatures_never_claim_semantics_from_name_alone() {
        assertContains(
            IsoBbsaIndexedPayloadProbe.classify(byteArrayOf(0x1b, 0x4c, 0x75, 0x61, 0x51)),
            "bytecode NOT decoded",
        )
        assertContains(
            IsoBbsaIndexedPayloadProbe.classify(byteArrayOf(1, 2, 3, 4, 5)),
            "unknown header",
        )
    }

    private fun ByteArray.putU16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
    private fun ByteArray.putU32(offset: Int, value: Int) {
        putU16(offset, value)
        putU16(offset + 2, value ushr 16)
    }
}
