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
        assertContains(result.sampledNames.joinToString(" "), "command.bin")
        assertContains(result.sampledNames.joinToString(" "), "battle.arc")
        assertContains(result.observations.joinToString(" "), "structural_status=VALID")
        assertContains(result.observations.joinToString(" "), "meaning NOT proven")
        assertTrue(bytes.contentEquals(unchanged))
    }

    @Test
    fun invalid_payload_and_invalid_names_reject_false_positive_magic() {
        val source = fixture()
        val oversized = source.copyOf()
        oversized[16 + 4] = 0x00
        oversized[16 + 5] = 0x20 // 8192, outside source
        assertFalse(IsoArcMetadataProbe.inspectTable(oversized, 8192).valid)

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
