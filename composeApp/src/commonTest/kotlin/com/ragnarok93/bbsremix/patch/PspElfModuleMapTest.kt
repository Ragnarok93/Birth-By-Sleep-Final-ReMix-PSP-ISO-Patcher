package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspElfModuleMapTest {
    private fun fixture(): ByteArray = ByteArray(256).also { b ->
        byteArrayOf(0x7f, 69, 76, 70).copyInto(b)
        b[4] = 1
        b[5] = 1
        b.writeShortLe(16, 2)
        b.writeShortLe(18, 8)
        b.writeIntLe(24, 0x08804018)
        b.writeIntLe(28, 0x34) // program-header table
        b.writeShortLe(42, 0x20)
        b.writeShortLe(44, 1)
        b.writeIntLe(0x34, 1) // PT_LOAD
        b.writeIntLe(0x38, 0x80)
        b.writeIntLe(0x3c, 0x08804018)
        b.writeIntLe(0x44, 0x40)
        b.writeIntLe(0x48, 0x80)
        b.writeIntLe(0x4c, 5) // RX
    }

    @Test
    fun static_segment_map_reports_bounds_without_authenticating_hook_lifetime() {
        val bytes = fixture()
        val saved = bytes.copyOf()
        val report = PspElfModuleMap.inspect(bytes, "synthetic")
        assertFalse(report.malformed)
        assertEquals(2, report.elfType)
        assertEquals(1, report.segments.size)
        assertEquals(0x08804018L, report.segments.single().virtualAddress)
        assertTrue(report.segments.single().inSource)
        assertContains(report.lines.joinToString("\n"), "program_headers=1")
        assertContains(report.lines.joinToString("\n"), "No code-cave/overlay lifetime guarantee")
        assertTrue(bytes.contentEquals(saved))
    }

    @Test
    fun invalid_headers_and_truncated_files_fail_closed() {
        val zero = PspElfModuleMap.inspect(ByteArray(0), "empty")
        assertTrue(zero.malformed)
        val truncated = fixture().apply { writeIntLe(28, 254) }
        assertTrue(PspElfModuleMap.inspect(truncated, "truncated").malformed)
        val oversized = fixture().apply { writeIntLe(0x38, 250) }
        assertTrue(PspElfModuleMap.inspect(oversized, "offset").malformed)
        val shortMemory = fixture().apply { writeIntLe(0x48, 8) }
        assertTrue(PspElfModuleMap.inspect(shortMemory, "memory").malformed)
        val absent = fixture().apply { writeShortLe(44, 0) }
        assertFalse(PspElfModuleMap.inspect(absent, "no_ph").malformed)
    }
}
