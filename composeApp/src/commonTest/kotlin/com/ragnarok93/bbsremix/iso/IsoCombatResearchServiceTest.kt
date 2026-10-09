package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.Path
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoCombatResearchServiceTest {
    private val fs = FileSystem.SYSTEM
    private val reader = Iso9660Reader(fs)

    @Test
    fun research_inventory_scans_additional_elf_without_touching_iso_or_packed_dat() {
        val source = fixture("inventory")
        try {
            val image = reader.inspect(source)
            val before = fs.source(source).buffer().use { it.readByteArray() }
            val report = IsoCombatResearchService(reader).inspect(source, image)
            val text = report.lines.joinToString("\n")
            assertEquals(5, report.fileCount)
            assertEquals(3, report.directories)
            assertEquals(1, report.scannedModules)
            assertFalse(report.truncated)
            assertTrue(report.sampledHeaders >= 3)
            assertContains(text, "MODULE.ELF")
            assertContains(text, "MIPS ELF32")
            assertContains(text, "EBOOT.BIN")
            assertContains(text, "DATA.DAT")
            assertContains(text, "BBSA game archive")
            assertContains(text, "index_sha256=")
            assertContains(text, "PSAR container")
            assertContains(text, "encrypted PSP")
            assertContains(text, "No combat patch")
            assertContains(text, "Packed DAT/ARC/CPK internals")
            assertTrue(before.contentEquals(fs.source(source).buffer().use { it.readByteArray() }))
            val integrated = IsoPatchingService(fs).inspectCombatPort(source).joinToString("\n")
            assertContains(integrated, "COMBAT PORT INSPECTION")
            assertContains(integrated, "ISO COMBAT RESEARCH")
            assertContains(integrated, "MODULE MODULE.ELF")
        } finally {
            fs.delete(source, mustExist = false)
        }
    }

    @Test
    fun known_header_classification_avoids_confusing_encrypted_or_other_elf_with_mips() {
        val research = IsoCombatResearchService(reader)
        assertEquals("empty file", research.classify(ByteArray(0)))
        assertEquals("BBSA game archive", research.classify("bbsa".encodeToByteArray()))
        assertEquals("encrypted PSP ~PSP", research.classify("~PSP".encodeToByteArray()))
        assertEquals("CPK archive (not decoded)", research.classify("CPK ".encodeToByteArray()))
        assertEquals("PSAR container (not decoded)", research.classify("PSAR".encodeToByteArray()))
        assertEquals("ZIP archive (not decoded)", research.classify(byteArrayOf(0x50, 0x4b, 3, 4)))
        val other = ByteArray(24)
        byteArrayOf(0x7f, 69, 76, 70).copyInto(other)
        other[4] = 1
        other[5] = 1
        other[18] = 3
        assertEquals("ELF other/unsupported; not disassembled", research.classify(other))
        other[18] = 8
        assertEquals("MIPS ELF32", research.classify(other))
    }

    private fun fixture(label: String): Path {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-combat-research-$label.iso"
        fs.delete(path, mustExist = false)
        val iso = ByteArray(64 * 2048)
        val pvd = ByteArray(2048).apply {
            this[0] = 1
            "CD001".encodeToByteArray().copyInto(this, 1)
            this[6] = 1
            both(this, 80, 64)
            both(this, 128, 2048)
            record("\u0000", 20, 2048, true).copyInto(this, 156)
        }
        pvd.copyInto(iso, 16 * 2048)
        val terminator = ByteArray(2048).apply {
            this[0] = 255.toByte()
            "CD001".encodeToByteArray().copyInto(this, 1)
            this[6] = 1
        }
        terminator.copyInto(iso, 17 * 2048)
        directory(iso, 20, listOf(
            record("\u0000", 20, 2048, true),
            record("\u0001", 20, 2048, true),
            record("PSP_GAME", 21, 2048, true),
            record("MODULE.ELF;1", 23, 128, false),
            record("DATA.DAT;1", 25, 2048, false),
            record("CRYPT.PRX;1", 26, 64, false),
            record("EVENT.PAK;1", 27, 64, false),
        ))
        directory(iso, 21, listOf(
            record("\u0000", 21, 2048, true),
            record("\u0001", 20, 2048, true),
            record("SYSDIR", 22, 2048, true),
        ))
        directory(iso, 22, listOf(
            record("\u0000", 22, 2048, true),
            record("\u0001", 21, 2048, true),
            record("EBOOT.BIN;1", 24, 256, false),
        ))
        val otherElf = ByteArray(128)
        byteArrayOf(0x7f, 0x45, 0x4c, 0x46).copyInto(otherElf)
        otherElf[4] = 1
        otherElf[5] = 1
        otherElf[18] = 8
        otherElf.copyInto(iso, 23 * 2048)
        ByteArray(256) { 1 }.copyInto(iso, 24 * 2048)
        val bbsa = ByteArray(2048)
        "bbsa".encodeToByteArray().copyInto(bbsa, 0)
        bbsa[4] = 5 // version 5
        bbsa[0x1a] = 1 // index occupies one 2048-byte sector
        bbsa.copyInto(iso, 25 * 2048)
        "~PSP".encodeToByteArray().copyInto(iso, 26 * 2048)
        "PSAR".encodeToByteArray().copyInto(iso, 27 * 2048)
        fs.sink(path).buffer().use { it.write(iso) }
        return path
    }

    private fun directory(iso: ByteArray, sector: Int, records: List<ByteArray>) {
        var position = sector * 2048
        for (rec in records) {
            rec.copyInto(iso, position)
            position += rec.size
        }
    }

    private fun record(name: String, sector: Int, length: Int, directory: Boolean): ByteArray {
        val bytes = if (name == "\u0000") byteArrayOf(0) else if (name == "\u0001") {
            byteArrayOf(1)
        } else name.encodeToByteArray()
        val total = (33 + bytes.size + 1) and -2
        return ByteArray(total).also {
            it[0] = total.toByte()
            both(it, 2, sector)
            both(it, 10, length)
            it[25] = if (directory) 2 else 0
            it[32] = bytes.size.toByte()
            bytes.copyInto(it, 33)
        }
    }

    private fun both(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
        bytes[offset + 2] = (value ushr 16).toByte()
        bytes[offset + 3] = (value ushr 24).toByte()
        bytes[offset + 4] = (value ushr 24).toByte()
        bytes[offset + 5] = (value ushr 16).toByte()
        bytes[offset + 6] = (value ushr 8).toByte()
        bytes[offset + 7] = value.toByte()
    }
}
