package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatDeepStaticInspectorTest {
    private fun mipsFixture(): ByteArray = ByteArray(0x500).also { b ->
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            .copyInto(b)
        b[4] = 1 // ELF32
        b[5] = 1 // little endian
        b.writeShortLe(16, 2) // ET_EXEC
        b.writeShortLe(18, 8) // EM_MIPS
        b.writeIntLe(24, 0x08816000)
        b.writeIntLe(0x20, 0x80)
        b.writeShortLe(0x2e, 0x28)
        b.writeShortLe(0x30, 4)
        b.writeShortLe(0x32, 3)

        // .text
        b.writeIntLe(0xa8, 1)
        b.writeIntLe(0xac, 1)
        b.writeIntLe(0xb0, 6)
        b.writeIntLe(0xb4, 0x08816000)
        b.writeIntLe(0xb8, 0x200)
        b.writeIntLe(0xbc, 0x40)
        // .rodata
        b.writeIntLe(0xd0, 7)
        b.writeIntLe(0xd4, 1)
        b.writeIntLe(0xd8, 2)
        b.writeIntLe(0xdc, 0x08B20000)
        b.writeIntLe(0xe0, 0x300)
        b.writeIntLe(0xe4, 0x40)
        // .shstrtab
        b.writeIntLe(0xf8, 15)
        b.writeIntLe(0xfc, 3)
        b.writeIntLe(0x108, 0x400)
        val names = "\u0000.text\u0000.rodata\u0000.shstrtab\u0000".encodeToByteArray()
        b.writeIntLe(0x10c, names.size)
        names.copyInto(b, 0x400)

        b.writeIntLe(0x200, jal(0x089E74A8))
        b.writeIntLe(0x204, 0x0320F809) // jalr ra,t9
        b.writeIntLe(0x208, 0x03E00008) // jr ra
        b.writeIntLe(0x20c, 0x8E040238.toInt()) // lw a0,0x238(s0)
        b.writeIntLe(0x210, 0xAE040238.toInt()) // sw a0,0x238(s0)
        b.writeIntLe(0x214, 0x8E05023C.toInt()) // lw a1,0x23c(s0)
        b.writeIntLe(0x218, 0xC4800024.toInt()) // lwc1 f0,0x24(a0) (not counted as +0x238)
        b.writeIntLe(0x21c, 0x3C0808B2) // lui t0,0x08B2
        b.writeIntLe(0x220, 0x3508DE9C.toInt()) // ori t0,t0,0xDE9C
        b.writeIntLe(0x224, 0x3C0908B3) // lui t1,0x08B3
        b.writeIntLe(0x228, 0x2529DE9C) // addiu t1,t1,-8548 = 0x08B2DE9C
        b.writeIntLe(0x300, 0x089E74A8)
        b.writeIntLe(0x304, 0x08B2DE9C)
    }

    @Test
    fun mips_opcodes_and_data_references_are_reported_without_inferring_actor_type() {
        val source = mipsFixture()
        val before = source.copyOf()
        val report = PspCombatDeepStaticInspector.inspect(source, "fixture")
        assertTrue(report.recognizedElf)
        assertEquals(16, report.executableInstructions)
        assertEquals(1, report.directCalls)
        assertEquals(1, report.jalrCalls)
        assertEquals(1, report.jrReturnsOrBranches)
        val cancel = report.offsetResults.single { it.offset == 0x238 }
        assertEquals(1, cancel.loads)
        assertEquals(1, cancel.stores)
        assertEquals(listOf(0x0881600cL, 0x08816010L), cancel.sampleVas)
        assertEquals(1, report.pointerResults.single { it.address == 0x089E74A8L }.count)
        assertEquals(1, report.pointerResults.single { it.address == 0x08B2DE9CL }.count)
        assertEquals(
            2,
            report.addressBuildResults.single { it.address == 0x08B2DE9CL }.count,
        )
        assertContains(report.lines.joinToString("\n"), "LUI+ADDIU/ORI")
        assertContains(report.lines.joinToString("\n"), "ALL object types")
        assertContains(report.lines.joinToString("\n"), "JALR targets unresolved")
        assertTrue(source.contentEquals(before))
    }

    @Test
    fun non_mips_truncated_and_corrupt_sources_do_not_trigger_disassembly() {
        for (input in listOf(
            ByteArray(0), byteArrayOf(0x7f),
            mipsFixture().apply { writeShortLe(18, 3) },
            mipsFixture().apply { this[5] = 2 },
        )) {
            val result = PspCombatDeepStaticInspector.inspect(input, "unknown")
            assertFalse(result.recognizedElf)
            assertEquals(0, result.executableInstructions)
            assertContains(result.lines.joinToString("\n"), "no MIPS inference")
        }
    }

    @Test
    fun malformed_section_header_table_reports_no_executable_references() {
        val input = mipsFixture()
        input.writeIntLe(0x20, 0x4ff)
        val report = PspCombatDeepStaticInspector.inspect(input, "malformed")
        assertTrue(report.recognizedElf)
        assertEquals(0, report.executableInstructions)
        assertEquals(0, report.jalrCalls)
        assertTrue(report.pointerResults.all { it.count == 0 })
    }
}
