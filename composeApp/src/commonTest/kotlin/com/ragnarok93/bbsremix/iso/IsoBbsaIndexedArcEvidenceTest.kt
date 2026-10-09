package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoBbsaIndexedArcEvidenceTest {
    private val fs = FileSystem.SYSTEM
    private val archiveExtent = 5L * 2048L
    private val archiveStart = 4L * 2048L
    private val reader = Iso9660Reader(fs)
    private val entry = IsoDirectoryEntry(
        path = "PSP_GAME/USRDIR/BBS1.DAT", name = "BBS1.DAT",
        recordOffset = 0L, extentSector = 4L,
        size = 5L * 2048L, flags = 0, recordLength = 48, sectorSize = 2048,
    )

    private fun archive(): ByteArray {
        val arc = ByteArray(4096)
        "ARC\u0000".encodeToByteArray().copyInto(arc)
        arc[4] = 1
        arc[6] = 9
        val offsets = intArrayOf(304, 900, 1024, 1152, 1280, 1408, 1536, 2600)
        for (i in offsets.indices) {
            val at = 16 + i * 32
            arc.putU32(at + 4, offsets[i])
            arc.putU32(at + 8, 64)
            val name = if (i == 0) "attack.lub" else "asset$i.bin"
            name.encodeToByteArray().copyInto(arc, at + 16)
        }
        val link = 16 + 8 * 32
        arc.putU32(link, 0x4D454E45) // OpenKh arc/enemy directory ID
        "enemyRef".encodeToByteArray().copyInto(arc, link + 16)
        byteArrayOf(0x1B, 0x4C, 0x75, 0x61, 0x51).copyInto(arc, offsets[0])
        "nonlua".encodeToByteArray().copyInto(arc, offsets[7])
        return arc
    }

    private fun withIso(arc: ByteArray, check: (okio.Path, ByteArray) -> Unit) {
        val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-indexed-arc-evidence.iso"
        fs.delete(path, mustExist = false)
        val iso = ByteArray(12 * 2048)
        arc.copyInto(iso, archiveStart.toInt())
        try {
            fs.sink(path).buffer().use { it.write(iso) }
            check(path, iso)
        } finally {
            fs.delete(path, mustExist = false)
        }
    }

    @Test
    fun valid_nine_entry_arc_classifies_lua_member_and_external_ref_without_modification() {
        val arc = archive()
        withIso(arc) { path, original ->
            val out = IsoBbsaIndexedArcEvidence.inspect(
                path, entry, 0, 2, arc.copyOfRange(0, 2048), reader,
            )
            assertTrue(out.validDirectory)
            assertEquals(9, out.declaredEntries)
            assertEquals(8, out.localEntries)
            assertEquals(1, out.externalLinks)
            assertEquals(8, out.members.size)
            val lua = out.members.first()
            assertEquals("attack.lub", lua.name)
            assertEquals(304L, lua.offset)
            assertEquals(64L, lua.length)
            assertContains(lua.signature, "Lua bytecode signature")
            assertContains(lua.previewHex, "1B4C756151")
            assertEquals(64, lua.sampleSha256.length)
            assertEquals(2600L, out.members.last().offset)
            assertContains(out.lines.joinToString("\n"), "EXTERNAL enemyRef")
            assertContains(out.lines.joinToString("\n"), "arc/enemy")
            assertContains(out.lines.joinToString("\n"), "ARC v1 table: declared=9")
            assertTrue(fs.source(path).buffer().use { it.readByteArray() }
                .contentEquals(original))
        }
    }

    @Test
    fun malformed_or_out_of_budget_member_never_triggers_additional_payload_reads() {
        val bad = archive()
        // First file would extend beyond two allocated 2K sectors.
        bad.putU32(16 + 8, 4096)
        withIso(bad) { path, _ ->
            val out = IsoBbsaIndexedArcEvidence.inspect(
                path, entry, 0, 2, bad.copyOfRange(0, 2048), reader,
            )
            assertFalse(out.validDirectory)
            assertTrue(out.members.isEmpty())
            assertContains(out.lines.joinToString("\n"), "UNVERIFIED ARC directory")
        }

        withIso(archive()) { path, _ ->
            val result = IsoBbsaIndexedArcEvidence.inspect(
                path, entry, 2048 * 4L, 2,
                archive().copyOfRange(0, 2048), reader,
            )
            assertFalse(result.validDirectory)
            assertTrue(result.members.isEmpty())
            assertContains(result.lines.joinToString("\n"), "out of bounds")
        }
    }

    @Test
    fun does_not_accept_arbitrary_magic_without_structurally_valid_table() {
        val file = archive()
        file[16 + 16] = 0 // empty member name
        withIso(file) { path, _ ->
            val out = IsoBbsaIndexedArcEvidence.inspect(
                path, entry, 0, 2, file.copyOfRange(0, 2048), reader,
            )
            assertFalse(out.validDirectory)
            assertEquals(0, out.members.size)
        }
    }

    private fun ByteArray.putU32(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }
}
