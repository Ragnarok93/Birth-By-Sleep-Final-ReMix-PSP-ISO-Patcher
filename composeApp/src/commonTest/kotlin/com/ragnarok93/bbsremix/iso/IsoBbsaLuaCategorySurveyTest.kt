package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoBbsaLuaCategorySurveyTest {
    private val fs = FileSystem.SYSTEM

    private fun index(): ByteArray = ByteArray(2048).also {
        "bbsa".encodeToByteArray().copyInto(it)
        it.u32(4, 6)
        it.u16(0x0e, 5)
        it.u32(0x14, 0x400)
        it.u16(0x1a, 16)
        it.u32(0x1c, 300)
        it.u32(0x20, 100)
        it.u32(0x24, 150)
        it.u32(0x28, 200)
        it.u32(0x2c, 260)
        fun record(k: Int, nameHash: Long, packed: Int, dirId: Long) {
            val offset = 0x400 + k * 12
            it.u32(offset, nameHash.toInt())
            it.u32(offset + 4, packed)
            it.u32(offset + 8, dirId.toInt())
        }
        record(0, IsoBbsaDirectoryEvidence.fileNameHash("G01"), (101 shl 12) or 2, 0xC0000000L)
        record(1, IsoBbsaDirectoryEvidence.fileNameHash("COMBAT_TEST"), (102 shl 12) or 1, 0xC0000000L)
        record(2, IsoBbsaDirectoryEvidence.fileNameHash("GIMMICK_TEST"), (103 shl 12) or 1, 0xC0000000L)
        record(3, IsoBbsaDirectoryEvidence.fileNameHash("NOT_LUA"), (104 shl 12) or 1, 0x53534F42L)
        record(4, IsoBbsaDirectoryEvidence.fileNameHash("TOO_LARGE"), (105 shl 12) or 0xfff, 0xC0000000L)
    }

    private class Chunk {
        val data = mutableListOf<Byte>()
        fun b(value: Int) { data += value.toByte() }
        fun i(value: Int) { for (s in 0 until 4) b(value ushr (s * 8)) }
        fun string(value: String) {
            val bytes = value.encodeToByteArray()
            i(bytes.size + 1)
            bytes.forEach { data += it }
            b(0)
        }
        fun build(strings: List<String>): ByteArray {
            i(0) // no proto source
            i(0) // line defined
            i(0) // last line defined
            b(0) // upvalues
            b(0) // parameters
            b(2) // vararg
            b(2) // stack
            i(1) // instructions
            i(30) // placeholder
            i(strings.size)
            strings.forEach {
                b(4) // LUA_TSTRING
                string(it)
            }
            i(0) // child protos
            i(0) // line info
            i(0) // locals
            i(0) // upvalue debug names
            return byteArrayOf(0x1b, 76, 117, 97, 0x51, 0, 1, 4, 4, 4, 4, 0) +
                data.toByteArray()
        }
    }

    private fun ByteArray.u16(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.u32(at: Int, value: Int) {
        u16(at, value)
        u16(at + 2, value ushr 16)
    }

    @Test
    fun validates_and_counts_all_lua_category_records_not_unrelated_dir_hashes() {
        val index = index()
        val all = IsoBbsaLuaCategorySurvey.entries(index)
        assertEquals(4, all?.size)
        assertEquals(0xC0000000L, indexU32(index, all!![0].indexOffset + 8))
        assertEquals(0xfff, all.last().sectors)
        assertEquals(0, all.first().sectors - 2)
        assertTrue(IsoBbsaLuaCategorySurvey.entries(index.copyOf(16)) == null)
        assertTrue(IsoBbsaLuaCategorySurvey.entries(index.copyOf().apply {
            u32(0x14, 2047)
        }) == null)
        assertTrue(IsoBbsaLuaCategorySurvey.entries(index.copyOf().apply {
            u16(0x0e, 0)
        }) == null)
    }

    @Test
    fun deterministic_even_selection_retains_both_ends_without_duplicates() {
        val entries = (0 until 100).map {
            IsoBbsaLuaCategorySurvey.Entry(it * 12, it.toLong(), 100L + it, 1)
        }
        val chosen = IsoBbsaLuaCategorySurvey.select(entries)
        assertEquals(24, chosen.size)
        assertEquals(0, chosen.first().indexOffset)
        assertEquals(99 * 12, chosen.last().indexOffset)
        assertEquals(chosen.map { it.indexOffset }.distinct(), chosen.map { it.indexOffset })
        assertEquals(entries.take(12), IsoBbsaLuaCategorySurvey.select(entries.take(12)))
    }

    @Test
    fun priority_named_lua_candidates_are_selected_even_if_uniform_sample_misses_them() {
        val list = (0 until 90).map {
            IsoBbsaLuaCategorySurvey.Entry(
                it * 12,
                if (it == 41) IsoBbsaDirectoryEvidence.fileNameHash("B11CD00")
                else if (it == 47) IsoBbsaDirectoryEvidence.fileNameHash("VENTUS")
                else if (it == 52) IsoBbsaDirectoryEvidence.fileNameHash("G14SW00")
                else it.toLong(),
                200L + it, 1)
        }
        val selected = IsoBbsaLuaCategorySurvey.selectPrioritized(list)
        assertEquals(24, selected.size)
        assertContains(selected.map { it.indexOffset }, 41 * 12)
        assertContains(selected.map { it.indexOffset }, 47 * 12)
        assertContains(selected.map { it.indexOffset }, 52 * 12)
        assertEquals(selected.map { it.indexOffset }.sorted(),
            selected.map { it.indexOffset })
        assertEquals("B11CD00", IsoBbsaLuaCategorySurvey.nameHint(
            IsoBbsaDirectoryEvidence.fileNameHash("B11CD00")))
        assertEquals("VENTUS", IsoBbsaLuaCategorySurvey.nameHint(
            IsoBbsaDirectoryEvidence.fileNameHash("VENTUS")))
        assertEquals("not in verified short-name hints",
            IsoBbsaLuaCategorySurvey.nameHint(0x12345678L))
    }

    @Test
    fun reads_only_small_non_g01_lua_allocations_and_reports_exact_combat_name_candidates() {
        val source = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-lua-category-survey.iso"
        fs.delete(source, mustExist = false)
        val bytes = ByteArray(32 * 2048)
        // Mock DAT begins at ISO sector 5. Logical sectors 102/103
        // are BBS1 physical 3/4, ISO sectors 8/9.
        Chunk().build(listOf("OnHitAttack", "SetTrgFlagCancel", "OnUpdate"))
            .copyInto(bytes, 8 * 2048)
        Chunk().build(listOf("OnInit", "EntityFactory"))
            .copyInto(bytes, 9 * 2048)
        try {
            fs.sink(source).buffer().use { it.write(bytes) }
            val before = fs.source(source).buffer().use { it.readByteArray() }
            val dat = IsoDirectoryEntry(
                "PSP_GAME/USRDIR/BBS1.DAT", "BBS1.DAT",
                0, 5, 20 * 2048L, 0, 48, 2048,
            )
            val report = IsoBbsaLuaCategorySurvey.inspect(
                source, index(), mapOf(1 to dat), Iso9660Reader(fs),
            )
            assertTrue(report.indexValid)
            assertEquals(4, report.luaRecords)
            assertEquals(2, report.eligibleRecords)
            assertEquals(2, report.samples.size)
            assertEquals("VALID Lua 5.1 structure", report.samples.first().result)
            assertContains(report.samples.first().hitEvents, "OnHitAttack")
            assertContains(report.samples.first().combatApis, "SetTrgFlagCancel")
            assertContains(report.samples.first().callbacks, "OnUpdate")
            assertTrue(report.samples[1].hitEvents.isEmpty())
            val logged = report.lines.joinToString("\n")
            assertContains(logged, "chunks_with_exact_hit_event_name_constants=1")
            assertContains(logged, "COMBAT STRING CANDIDATE")
            assertContains(logged, "NOT a full-Lua-index scan")
            assertTrue(fs.source(source).buffer().use { it.readByteArray() }
                .contentEquals(before))
        } finally {
            fs.delete(source, mustExist = false)
        }
    }

    private fun indexU32(bytes: ByteArray, at: Int): Long =
        (bytes[at].toLong() and 255L) or
            ((bytes[at + 1].toLong() and 255L) shl 8) or
            ((bytes[at + 2].toLong() and 255L) shl 16) or
            ((bytes[at + 3].toLong() and 255L) shl 24)
}
