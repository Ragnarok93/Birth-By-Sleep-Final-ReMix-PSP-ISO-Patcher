package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatStaticAnalysisTest {
    private fun sectionedElf(): ByteArray = ByteArray(0x400).also { bytes ->
        byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
            .copyInto(bytes, 0)
        bytes[4] = 1 // ELFCLASS32
        bytes[5] = 1 // little-endian
        bytes.writeIntLe(0x20, 0x100) // section-header table
        bytes.writeShortLe(0x2e, 0x28)
        bytes.writeShortLe(0x30, 4)
        bytes.writeShortLe(0x32, 3) // string table index

        // .text, executable, at 0x08816000
        bytes.writeIntLe(0x128, 1) // sh_name
        bytes.writeIntLe(0x12c, 1) // PROGBITS
        bytes.writeIntLe(0x130, 6) // ALLOC | EXECINSTR
        bytes.writeIntLe(0x134, 0x08816000)
        bytes.writeIntLe(0x138, 0x200)
        bytes.writeIntLe(0x13c, 0x20)

        // .data, not executable, containing a lookalike JAL word
        bytes.writeIntLe(0x150, 7)
        bytes.writeIntLe(0x154, 1)
        bytes.writeIntLe(0x158, 3) // ALLOC | WRITE
        bytes.writeIntLe(0x15c, 0x08B6A480)
        bytes.writeIntLe(0x160, 0x240)
        bytes.writeIntLe(0x164, 0x20)

        // .shstrtab
        bytes.writeIntLe(0x178, 13)
        bytes.writeIntLe(0x17c, 3)
        bytes.writeIntLe(0x188, 0x300)
        val names = byteArrayOf(
            0, '.'.code.toByte(), 't'.code.toByte(), 'e'.code.toByte(),
            'x'.code.toByte(), 't'.code.toByte(), 0,
            '.'.code.toByte(), 'd'.code.toByte(), 'a'.code.toByte(),
            't'.code.toByte(), 'a'.code.toByte(), 0,
            '.'.code.toByte(), 's'.code.toByte(), 'h'.code.toByte(),
            's'.code.toByte(), 't'.code.toByte(), 'r'.code.toByte(),
            't'.code.toByte(), 'a'.code.toByte(), 'b'.code.toByte(), 0,
        )
        bytes.writeIntLe(0x18c, names.size)
        names.copyInto(bytes, 0x300)

        bytes.writeIntLe(0x200, jal(0x08B16D38))
        bytes.writeIntLe(0x204, 0x8FB00048.toInt()) // stack restore (not a call)
        bytes.writeIntLe(0x240, jal(0x08B16D38)) // data-only decoy
    }

    @Test
    fun executable_sections_only_are_scanned_for_real_direct_calls() {
        val elf = sectionedElf()
        val baseline = elf.copyOf()
        val sections = PspCombatStaticAnalysis.sectionMap(elf)
        assertEquals(3, sections.size)
        assertEquals(".text", sections.first().name)
        assertTrue(sections.first().executable)
        assertFalse(sections[1].executable)
        assertEquals(".shstrtab", sections.last().name)

        val report = PspCombatStaticAnalysis.inspect(elf).joinToString("\n")
        assertContains(report, "1 executable section(s)")
        assertContains(report, "8 executable instructions")
        assertContains(report, "controller poll callee 0x08B16D38: 1 direct JAL call(s); sample site(s): 0x08816000")
        assertContains(report, "candidate player-state address 0x08B6A490: section=.data; executable=false")
        assertContains(report, "indirect JALR and runtime overlays not resolved")
        assertTrue(elf.contentEquals(baseline), "Combat analysis must not mutate the EBOOT")
    }

    @Test
    fun indirect_script_entries_are_not_mistaken_for_unused_code() {
        val elf = sectionedElf()
        // Inject one genuine direct call to the guarded PSP player resolver.
        elf.writeIntLe(0x208, jal(0x089E74A8))
        // A lookalike in .data must not increase the call count.
        elf.writeIntLe(0x244, jal(0x089E74A8))
        val summary = PspCombatStaticAnalysis.inspect(elf).joinToString("\\n")
        assertContains(summary, "guarded player resolver 0x089E74A8: 1 direct JAL call(s)")
        assertContains(summary, "native cancel flag setter 0x08B07020: 0 direct JAL call(s)")
        assertContains(summary, "zero direct JAL calls does not mean a native helper is unused")
    }

    @Test
    fun direct_call_totals_remain_exact_above_sample_limit() {
        val elf = sectionedElf()
        elf.writeIntLe(0x13c, 0x40) // 16 instructions in .text
        for (i in 0 until 14) {
            elf.writeIntLe(0x200 + i * 4, jal(0x089E74A8))
        }
        val summary = PspCombatStaticAnalysis.inspect(elf).joinToString("\\n")
        assertContains(summary, "guarded player resolver 0x089E74A8: 14 direct JAL call(s)")
        assertFalse(summary.contains("12+ (report cap)"))
        assertTrue(summary.contains("sample site(s):"))
    }

    @Test
    fun malformed_or_out_of_bounds_section_maps_are_not_scanned() {
        val original = sectionedElf()
        val badTable = original.copyOf().apply { writeIntLe(0x20, 0x3ff) }
        val badStringTable = original.copyOf().apply { writeIntLe(0x188, 0x3f0) }
        val invalidClass = original.copyOf().apply { this[4] = 2 }
        for (candidate in listOf(ByteArray(0), badTable, badStringTable, invalidClass)) {
            assertTrue(PspCombatStaticAnalysis.sectionMap(candidate).isEmpty())
            assertContains(PspCombatStaticAnalysis.inspect(candidate).joinToString("\n"), "scan skipped")
        }
    }

    @Test
    fun non_executable_sections_do_not_create_false_xrefs() {
        val source = sectionedElf()
        source.writeIntLe(0x200, 0)
        val report = PspCombatStaticAnalysis.inspect(source).joinToString("\n")
        assertContains(report, "controller poll callee 0x08B16D38: 0 direct JAL call(s)")
        assertContains(report, "8 executable instructions")
    }
}
