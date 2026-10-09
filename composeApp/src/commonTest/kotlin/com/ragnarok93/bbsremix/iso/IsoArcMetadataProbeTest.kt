package com.ragnarok93.bbsremix.iso

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IsoArcMetadataProbeTest {
    private fun fixture(): ByteArray = ByteArray(16 + 2 * 32).also { data ->
        byteArrayOf('A'.code.toByte(), 'R'.code.toByte(), 'C'.code.toByte(), 0)
            .copyInto(data)
        data[4] = 1 // ARC version
        data[6] = 2 // count
        // First record is local: 16+2*32=80 directory bytes, payload at 0x100.
        data[16 + 4] = 0x00
        data[16 + 5] = 0x01
        data[16 + 8] = 0x20
        "command.bin".encodeToByteArray().copyInto(data, 16 + 16)
        // Second record is an external hash/directory link.
        data[48] = 0x5a
        "battle.arc".encodeToByteArray().copyInto(data, 48 + 16)
    }

    @Test
    fun checks_full_bounded_table_and_distinguishes_payload_and_links() {
        val bytes = fixture()
        val unchanged = bytes.copyOf()
        val result = IsoArcMetadataProbe.inspectTable(bytes, 8192)
        assertTrue(result.valid)
        assertEquals(2, result.entryCount)
        assertEquals(2, result.validatedEntries)
        assertEquals(1, result.externalLinks)
        assertEquals(1, result.payloadRecords)
        assertEquals(2, result.entries.size)
        assertEquals("command.bin", result.entries[0].name)
        assertEquals(256L, result.entries[0].payloadOffset)
        assertEquals(32L, result.entries[0].payloadSize)
        assertEquals("battle.arc", result.entries[1].name)
        assertEquals(0x5aL, result.entries[1].reference)
        assertContains(result.observations.joinToString(" "), "raw_id=0x0000005A")
        assertContains(result.observations.joinToString(" "), "arc_relative_offset=256")
        assertContains(result.sampledNames.joinToString(" "), "command.bin")
        assertContains(result.sampledNames.joinToString(" "), "battle.arc")
        assertContains(result.observations.joinToString(" "), "structural_status=VALID")
        assertContains(result.observations.joinToString(" "), "meaning NOT proven")
        assertTrue(bytes.contentEquals(unchanged))
    }

    @Test
    fun lua_named_external_link_is_only_a_candidate_with_a_raw_identifier() {
        val table = fixture()
        "g01lua".encodeToByteArray().copyInto(table, 48 + 16)
        // The link name is shorter than the existing name, so ensure
        // the trailing bytes cannot accidentally look like another name.
        table.fill(0.toByte(), 48 + 16 + 6, 48 + 32)
        val report = IsoArcMetadataProbe.inspectTable(table, 8192)
        assertTrue(report.valid)
        assertEquals(0x5aL, report.entries.single { it.name == "g01lua" }.reference)
        assertContains(report.observations.joinToString(" "), "script-name candidate ONLY")
        assertContains(report.observations.joinToString(" "), "destination unresolved")
    }

    @Test
    fun invalid_payload_and_invalid_names_reject_false_positive_magic() {
        val source = fixture()
        val oversized = source.copyOf()
        oversized[16 + 4] = 0x00
        oversized[16 + 5] = 0x20 // 8192, outside source
        assertFalse(IsoArcMetadataProbe.inspectTable(oversized, 8192).valid)
        assertTrue(IsoArcMetadataProbe.inspectTable(oversized, 8192).entries.isEmpty())

        val invalidName = source.copyOf()
        invalidName[16 + 16] = '/'.code.toByte()
        assertFalse(IsoArcMetadataProbe.inspectTable(invalidName, 8192).valid)

        assertFalse(IsoArcMetadataProbe.inspectTable(source.copyOf(32), 8192).valid)
        assertFalse(IsoArcMetadataProbe.inspectTable(source, 64).valid)
        val badMagic = source.copyOf()
        badMagic[0] = 0
        assertFalse(IsoArcMetadataProbe.inspectTable(badMagic, 8192).valid)

        val hugeCount = source.copyOf()
        hugeCount[6] = 0xff.toByte()
        hugeCount[7] = 0x7f
        assertFalse(IsoArcMetadataProbe.inspectTable(hugeCount, 8192).valid)
    }
}
