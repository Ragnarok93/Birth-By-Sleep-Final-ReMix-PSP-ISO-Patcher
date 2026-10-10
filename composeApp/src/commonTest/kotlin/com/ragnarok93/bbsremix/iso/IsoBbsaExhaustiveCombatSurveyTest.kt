package com.ragnarok93.bbsremix.iso

import okio.FileSystem
import okio.buffer
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoBbsaExhaustiveCombatSurveyTest {
    private val fs = FileSystem.SYSTEM

    private fun ByteArray.u16(at: Int, value: Int) {
        this[at] = value.toByte()
        this[at + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.u32(at: Int, value: Int) {
        u16(at, value)
        u16(at + 2, value ushr 16)
    }

    private fun index(): ByteArray = ByteArray(2048).also { out ->
        "bbsa".encodeToByteArray().copyInto(out)
        out.u32(4, 6)
        out.u16(0x0e, 4)
        out.u32(0x14, 0x400)
        out.u16(0x1a, 16)
        out.u32(0x1c, 300)
        out.u32(0x20, 100)
        out.u32(0x24, 150)
        out.u32(0x28, 200)
        out.u32(0x2c, 260)
        fun record(k: Int, name: String, sector: Int, count: Int) {
            val at = 0x400 + k * 12
            out.u32(at, IsoBbsaDirectoryEvidence.fileNameHash(name).toInt())
            out.u32(at + 4, (sector shl 12) or count)
            out.u32(at + 8, 0xC0000000.toInt())
        }
        record(0, "G01", 101, 1)
        record(1, "B11CD00", 102, 1)
        record(2, "B11CD00", 102, 1) // duplicate index allocation
        record(3, "UNBOUNDED", 103, 0xfff)
    }

    private fun chunk(): ByteArray {
        val body = mutableListOf<Byte>()
        fun byte(x: Int) { body += x.toByte() }
        fun int(x: Int) { repeat(4) { byte(x ushr (8 * it)) } }
        fun str(s: String) {
            int(s.length + 1)
            s.encodeToByteArray().forEach { body += it }
            byte(0)
        }
        int(0) // source
        int(0); int(0) // line ranges
        byte(0); byte(0); byte(2); byte(2)
        int(1); int(30) // RETURN
        int(3)
        for (name in listOf("OnHitAttack", "SetTrgFlagCancel", "OnUpdate")) {
            byte(4); str(name)
        }
        int(0); int(0); int(0); int(0)
        return byteArrayOf(0x1b, 76, 117, 97, 0x51, 0, 1, 4, 4, 4, 4, 0) +
            body.toByteArray()
    }

    @Test
    fun invalid_and_missing_extents_fail_closed() {
        val entry = IsoBbsaLuaCategorySurvey.Entry(0x400, 1L, 102L, 1)
        val location = IsoBbsaIndexedPayloadProbe.Location(1, 3, 3 * 2048L)
        val dat = IsoDirectoryEntry("BBS1.DAT", "BBS1.DAT", 0, 5, 3 * 2048L, 0, 48, 2048)
        assertEquals("OUTSIDE_PHYSICAL_DAT_EXTENT",
            IsoBbsaExhaustiveCombatSurvey.decide(entry, dat, location, 0).reason)
        assertEquals("UNMAPPED_ARCHIVE_OR_MISSING_DAT",
            IsoBbsaExhaustiveCombatSurvey.decide(entry, null, location, 0).reason)
        assertEquals("TOTAL_READ_BUDGET_EXCEEDED",
            IsoBbsaExhaustiveCombatSurvey.decide(entry, dat.copy(size = 32 * 2048L),
                location, 32L * 1024L * 1024L).reason)
        assertEquals("ZERO_OR_SENTINEL_SECTORS",
            IsoBbsaExhaustiveCombatSurvey.decide(entry.copy(sectors = 0xfff),
                dat, location, 0).reason)
        assertTrue(IsoBbsaExhaustiveCombatSurvey.decide(entry,
            dat.copy(size = 32 * 2048L), location, 0).permitted)
    }

    @Test
    fun scans_all_records_including_g01_and_duplicates_without_modifying_source() {
        val source = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-exhaustive-lua-test.iso"
        fs.delete(source, mustExist = false)
        val disk = ByteArray(40 * 2048)
        val valid = chunk()
        valid.copyInto(disk, 7 * 2048) // archive1 logical101 -> physical2
        valid.copyInto(disk, 8 * 2048) // archive1 logical102 -> physical3
        val dat = IsoDirectoryEntry(
            "PSP_GAME/USRDIR/BBS1.DAT", "BBS1.DAT",
            0, 5, 25 * 2048L, 0, 48, 2048,
        )
        try {
            fs.sink(source).buffer().use { it.write(disk) }
            val report = IsoBbsaExhaustiveCombatSurvey.inspect(
                source, index(), mapOf(1 to dat), Iso9660Reader(fs),
            )
            assertEquals(4, report.indexed)
            assertEquals(3, report.parsed)
            assertEquals(1, report.rejected)
            assertEquals(3L * 2048L, report.totalReadBytes)
            val logged = report.lines.joinToString("\n")
            assertContains(logged, "LUA[1/4]")
            assertContains(logged, "LUA[4/4]")
            assertContains(logged, "ZERO_OR_SENTINEL_SECTORS")
            assertContains(logged, "duplicate_of_indexes=0x")
            assertContains(logged, "scripts_with_hit_name=3")
            assertContains(logged, "NATIVE_API SetTrgFlagCancel scripts=3")
            assertTrue(fs.source(source).buffer().use { it.readByteArray() }
                .contentEquals(disk))
        } finally {
            fs.delete(source, mustExist = false)
        }
    }

    @Test
    fun bad_index_is_reported_instead_of_faked_as_empty_scan() {
        val source = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "bbs-exhaustive-bad-test.iso"
        fs.delete(source, mustExist = false)
        try {
            fs.sink(source).buffer().use { it.write(ByteArray(2048)) }
            val report = IsoBbsaExhaustiveCombatSurvey.inspect(
                source, byteArrayOf(1, 2, 3), emptyMap(), Iso9660Reader(fs),
            )
            assertEquals(1, report.rejected)
            assertFalse(report.lines.none { it.contains("INVALID BBSA INDEX") })
        } finally {
            fs.delete(source, mustExist = false)
        }
    }
}
