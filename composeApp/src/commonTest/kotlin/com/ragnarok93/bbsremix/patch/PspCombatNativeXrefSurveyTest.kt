package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatNativeXrefSurveyTest {
    private fun elf(): ByteArray = ByteArray(0x400).also { bytes ->
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            .copyInto(bytes)
        bytes[4] = 1
        bytes[5] = 1
        bytes.writeIntLe(0x20, 0x100)
        bytes.writeShortLe(0x2e, 0x28)
        bytes.writeShortLe(0x30, 3)
        bytes.writeShortLe(0x32, 2)
        // ELF section #1: .text
        bytes.writeIntLe(0x128, 1)
        bytes.writeIntLe(0x12c, 1)
        bytes.writeIntLe(0x130, 6)
        bytes.writeIntLe(0x134, 0x08816000)
        bytes.writeIntLe(0x138, 0x200)
        bytes.writeIntLe(0x13c, 0x20)
        // ELF section #2: .shstrtab
        bytes.writeIntLe(0x150, 7)
        bytes.writeIntLe(0x154, 3)
        bytes.writeIntLe(0x160, 0x300)
        bytes.writeIntLe(0x164, 17)
        byteArrayOf(0, 46, 116, 101, 120, 116, 0,
            46, 115, 104, 115, 116, 114, 116, 97, 98, 0)
            .copyInto(bytes, 0x300)
        bytes.writeIntLe(0x200, 0x0C000000 or ((0x08B07020 ushr 2) and 0x03ff_ffff))
        bytes.writeIntLe(0x204, 0x8C860238.toInt()) // READ 0x238(a0)
        bytes.writeIntLe(0x208, 0xAC850238.toInt()) // WRITE 0x238(a0)
        bytes.writeIntLe(0x20c, 0x8C86023C.toInt()) // READ 0x23c(a0)
        bytes.writeIntLe(0x240, 0x0C000000 or ((0x08B07020 ushr 2) and 0x03ff_ffff))
    }

    @Test
    fun emits_callsite_delay_slot_and_candidate_fields_without_inventing_hit_confirmation() {
        val bytes = elf()
        val source = bytes.copyOf()
        val report = PspCombatNativeXrefSurvey.inspect(bytes, "synthetic test")
        assertTrue(report.supportedElf)
        assertEquals(8, report.executableInstructions)
        assertEquals(1, report.helperCalls)
        assertEquals(3, report.offsetAccesses)
        val text = report.lines.joinToString("\n")
        assertContains(text, "site=0x8816000")
        assertContains(text, "target=0x8b07020")
        assertContains(text, "delay_word=0x8c860238")
        assertContains(text, "kind=READ")
        assertContains(text, "kind=WRITE")
        assertContains(text, "NOT hit-confirm")
        assertContains(text, "No JALR target")
        assertTrue(bytes.contentEquals(source), "static survey must not mutate the EBOOT")
    }

    @Test
    fun invalid_section_map_fails_closed() {
        val report = PspCombatNativeXrefSurvey.inspect(ByteArray(48), "corrupt")
        assertFalse(report.supportedElf)
        assertEquals(0, report.helperCalls)
        assertContains(report.lines.joinToString("\n"), "NO VALID EXECUTABLE SECTIONS")
    }
}
