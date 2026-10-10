package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IsoBbsaLinkedResourceProbeTest {
    private val fs = FileSystem.SYSTEM

    private fun bbsa(): ByteArray = ByteArray(2048).also { x ->
        "bbsa".encodeToByteArray().copyInto(x)
        x.write32(4, 6)
        x.write16(0x08, 1)
        x.write16(0x0e, 1)
        x.write32(0x10, 0x100)
        x.write32(0x14, 0x200)
        x.write16(0x1a, 16)
        x.write32(0x1c, 300)
        x.write32(0x20, 100)
        x.write32(0x24, 150)
        x.write32(0x28, 200)
        x.write32(0x2c, 260)

        // One partition file, referenced by the Aqua character link.
        x.write32(0x30, 0x20004350)
        x.write16(0x34, 1)
        x.write16(0x36, 0)
        x.write32(0x100, IsoBbsaDirectoryEvidence.fileNameHash("XAA002AQ").toInt())
        x.write32(0x104, (102 shl 12) or 2)
        // Lua resources live in the separate 12-byte directory table;
        // OpenKh indexes the stem "G01" rather than "G01.LUB".
        x.write32(0x200, IsoBbsaDirectoryEvidence.fileNameHash("G01").toInt())
        x.write32(0x204, (104 shl 12) or 1)
        x.write32(0x208, 0xC0000000.toInt())
    }

    private fun links(): List<IsoArcMetadataProbe.Entry> {
        val arc = ByteArray(16 + 2 * 32)
        "ARC\u0000".encodeToByteArray().copyInto(arc)
        arc[4] = 1
        arc[6] = 2
        arc.write32(16, 0xC0000000.toInt())
        "g01.lub".encodeToByteArray().copyInto(arc, 16 + 16)
        arc.write32(48, 0x20004350)
        "xaa002aq".encodeToByteArray().copyInto(arc, 48 + 16)
        val table = IsoArcMetadataProbe.inspectTable(arc, 4096)
        assertTrue(table.valid)
        assertEquals(2, table.externalLinks)
        return table.entries.filter { it.isExternalLink }
    }

    @Test
    fun resolves_lua_stem_via_12byte_index_and_pc_aqua_in_partition_table() {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-linked-resource-lookup.iso"
        fs.delete(path, mustExist = false)
        val file = ByteArray(18 * 2048)
        // Mock BBS1.DAT begins at ISO sector 5. Logical sector 104
        // maps to physical BBS1 sector 5, total ISO sector 10.
        // Minimal structurally valid Lua 5.1 float32 chunk in allocated
        // BBS1 sector, with no constants, children, or debug entries.
        val luaStart = 10 * 2048
        byteArrayOf(0x1B, 0x4C, 0x75, 0x61, 0x51,
            0, 1, 4, 4, 4, 4, 0).copyInto(file, luaStart)
        file[luaStart + 27] = 2 // Proto maxstacksize
        file[luaStart + 28] = 1 // one 4-byte Lua instruction
        file[luaStart + 32] = 30 // synthetic RETURN opcode
        "ARC\u0000".encodeToByteArray().copyInto(file, 8 * 2048)
        try {
            fs.sink(path).buffer().use { it.write(file) }
            val before = fs.source(path).buffer().use { it.readByteArray() }
            val dat = IsoDirectoryEntry("PSP_GAME/USRDIR/BBS1.DAT",
                "BBS1.DAT", 0, 5, 12 * 2048L, 0, 48, 2048)
            val result = IsoBbsaLinkedResourceProbe.inspect(path, bbsa(),
                mapOf(1 to dat), links(), Iso9660Reader(fs))
            assertEquals(2, result.links.size)
            val lua = result.links.first()
            assertEquals("g01.lub", lua.name)
            assertEquals("G01", lua.basename)
            assertEquals(0x040C749EL, lua.filenameHash)
            assertEquals(1, lua.directoryTableMatches)
            assertEquals(0, lua.partitionFileMatches)
            assertEquals(104L, lua.candidates.single().globalSector)
            assertEquals("BBSA 12-byte directory", lua.candidates.single().namespace)
            assertEquals(1, result.probes.first().location?.archiveIndex)
            assertEquals(5L, result.probes.first().location?.physicalSector)
            assertContains(result.probes.first().signature, "Lua bytecode signature")
            assertContains(result.lines.joinToString("\n"), "VALID bounded Lua 5.1 prototype structure")
            assertContains(result.lines.joinToString("\n"), "allocated_chunk_sha256=")
            assertContains(result.lines.joinToString("\n"), "parser_status=VALID")

            val pc = result.links.last()
            assertEquals(0, pc.directoryTableMatches)
            assertEquals(1, pc.partitionFileMatches)
            assertEquals(0x20004350L, pc.directoryId)
            assertEquals(102L, pc.candidates.single().globalSector)
            assertContains(result.lines.joinToString("\n"), "arc/pc_aqua")
            assertContains(result.lines.joinToString("\n"), "lua (BBSA directory category)")
            assertTrue(before.contentEquals(fs.source(path).buffer().use {
                it.readByteArray()
            }))
        } finally {
            fs.delete(path, mustExist = false)
        }
    }

    @Test
    fun full_extension_hash_does_not_count_as_extensionless_name() {
        val index = bbsa()
        index.write32(0x200, IsoBbsaDirectoryEvidence.fileNameHash("G01.LUB").toInt())
        val result = IsoBbsaLinkedResourceProbe.inspect(
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "unused.iso", index,
            emptyMap(), links(), Iso9660Reader(fs))
        val lua = result.links.first()
        assertEquals(0, lua.directoryTableMatches)
        assertEquals(0, lua.partitionFileMatches)
        assertTrue(lua.candidates.isEmpty())
        assertFalse(result.lines.joinToString("\n").contains("header=Lua bytecode signature"))
    }

    @Test
    fun malformed_partition_index_reports_unverified_not_zero_matches() {
        val index = bbsa()
        index.write32(0x10, 2047)
        val result = IsoBbsaLinkedResourceProbe.inspect(
            FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "unused.iso",
            index, emptyMap(), links(), Iso9660Reader(fs))
        assertEquals(null, result.links.last().partitionFileMatches)
        // A malformed partition table does not erase a valid Lua
        // directory-file index match; its payload extent is still bounded.
        assertEquals(1, result.links.first().directoryTableMatches)
        assertContains(result.lines.joinToString("\n"),
            "8byte_partition_file_index=UNVERIFIED")
        assertEquals(1, result.probes.size)
        assertContains(result.probes.first().signature, "UNVERIFIED archive file/extent")
    }

    private fun ByteArray.write16(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
    }
    private fun ByteArray.write32(at: Int, value: Int) {
        write16(at, value)
        write16(at + 2, value ushr 16)
    }
}
