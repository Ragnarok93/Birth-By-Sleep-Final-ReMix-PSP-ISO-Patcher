package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatEventEvidenceTest {
    @Test
    fun only_full_null_terminated_event_names_inside_rodata_are_counted() {
        val source = ByteArray(256)
        // PSP event strings can follow a non-ASCII metadata byte (0x08).
        val payload = byteArrayOf(0x08) + "OnHitAttack\u0000".encodeToByteArray() +
            byteArrayOf(0x08) + "OnHitBody\u0000".encodeToByteArray() +
            byteArrayOf(0x08) + "OnHitAttackBg\u0000".encodeToByteArray() +
            "SuffixOnHitAttack\u0000".encodeToByteArray()
        payload.copyInto(source, 16)
        "OnHitBody\u0000".encodeToByteArray().copyInto(source, 160) // decoy in .data
        val rodata = PspCombatStaticAnalysis.Section(".rodata", 0x08B20000, 16, payload.size, false)
        val data = PspCombatStaticAnalysis.Section(".data", 0x08B21000, 160, 20, false)

        val before = source.copyOf()
        val events = PspCombatEventEvidence.scan(source, listOf(rodata, data))
            .associate { it.name to it.occurrences }
        assertEquals(listOf(0x08B20001L), events.getValue("OnHitAttack"))
        assertEquals(listOf(0x08B2000EL), events.getValue("OnHitBody"))
        assertEquals(1, events.getValue("OnHitAttackBg").size)
        assertTrue(source.contentEquals(before))
    }

    @Test
    fun aligned_self_pointer_constants_are_structural_not_executed_callbacks() {
        val source = ByteArray(256)
        val base = 0x08B20000
        val names = listOf(
            "OnHitAttack" to 0,
            "OnHitBody" to 32,
            "OnHitAttackBg" to 64,
        )
        for ((name, delta) in names) {
            val encoded = name.encodeToByteArray()
            encoded.copyInto(source, 16 + delta)
            source[16 + delta + encoded.size] = 0
            val alignedEnd = (encoded.size + 1 + 3) and 3.inv()
            source.writeIntLe(16 + delta + alignedEnd, base + delta)
        }
        val rodata = PspCombatStaticAnalysis.Section(
            ".rodata", base.toLong(), 16, 120, false,
        )
        val before = source.copyOf()
        val constants = PspCombatEventEvidence.alignedStringPointers(source, listOf(rodata))
        assertEquals(3, constants.size)
        assertEquals(
            listOf("OnHitAttack", "OnHitBody", "OnHitAttackBg"),
            constants.map { it.name },
        )
        assertEquals(listOf(12L, 44L, 80L), constants.map { it.pointerVa - base })
        assertTrue(source.contentEquals(before))

        val broken = source.copyOf()
        broken.writeIntLe(16 + 44, base)
        assertEquals(
            2,
            PspCombatEventEvidence.alignedStringPointers(broken, listOf(rodata)).size,
        )
        assertTrue(
            PspCombatEventEvidence.alignedStringPointers(
                source,
                listOf(rodata.copy(name = ".text", executable = true)),
            ).isEmpty(),
        )
        assertTrue(
            PspCombatEventEvidence.alignedStringPointers(
                source,
                listOf(rodata.copy(length = 9)),
            ).isEmpty(),
        )
    }

    @Test
    fun truncated_unmapped_and_executable_sections_must_fail_closed() {
        val data = "OnHitAttack\u0000".encodeToByteArray()
        val invalid = listOf(
            PspCombatStaticAnalysis.Section(".rodata", 0, -1, 12, false),
            PspCombatStaticAnalysis.Section(".rodata", 0, 0, 100, false),
            PspCombatStaticAnalysis.Section(".text", 0, 0, 12, true),
        )
        assertTrue(PspCombatEventEvidence.scan(data, invalid).all { it.occurrences.isEmpty() })
        val empty = PspCombatEventEvidence.inspect(data).joinToString("\n")
        assertContains(empty, "Unverified:")
        assertFalse(empty.contains("gameplay confirmed"))
    }
}
