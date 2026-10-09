package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PspOverlayConflictAuditTest {
    private fun mappedModule(
        address: Long,
        memoryBytes: Long,
        flags: Long = 7,
        malformed: Boolean = false,
    ) = PspElfModuleMap.Report(
        elfType = 65448,
        entry = 0,
        segments = listOf(
            PspElfModuleMap.Segment(
                type = 1,
                fileOffset = 84,
                virtualAddress = address,
                fileBytes = minOf(memoryBytes, 0x1000),
                memoryBytes = memoryBytes,
                flags = flags,
                inSource = true,
            ),
        ),
        malformed = malformed,
        lines = emptyList(),
    )

    @Test
    fun historical_stage4_and_stage5_overlap_declared_module_load_range() {
        val overlap = PspOverlayConflictAudit.evaluate(
            "BATTLE_DICE.ELF",
            mappedModule(0x08B6EE80, 284928),
        )
        assertEquals(2, overlap.size)
        assertEquals(
            setOf(0x08B70000L, 0x08B71280L),
            overlap.map { it.site }.toSet(),
        )
        assertEquals(
            0,
            PspOverlayConflictAudit.evaluate(
                "CAMP_MAIN.ELF",
                mappedModule(0x08B799D0, 172864),
            ).size,
        )
        val summary = PspOverlayConflictAudit.summarize(overlap, 2).joinToString("\n")
        assertContains(summary, "overlapping_executable_modules=1/2")
        assertContains(summary, "not module residency")
        assertContains(summary, "not safe permanent code caves")
    }

    @Test
    fun nonexecuting_invalid_or_truncated_segments_do_not_prove_overlap() {
        val noExec = mappedModule(0x08B6EE80, 284928, flags = 6)
        assertTrue(PspOverlayConflictAudit.evaluate("no-x", noExec).isEmpty())
        val invalid = mappedModule(0x08B6EE80, 284928, malformed = true)
        assertTrue(PspOverlayConflictAudit.evaluate("invalid", invalid).isEmpty())
        val edge = mappedModule(0x08B6EE80, 0x1180)
        // End-exclusive: address 0x08B70000 is NOT occupied.
        assertTrue(PspOverlayConflictAudit.evaluate("edge", edge).isEmpty())
    }
}
