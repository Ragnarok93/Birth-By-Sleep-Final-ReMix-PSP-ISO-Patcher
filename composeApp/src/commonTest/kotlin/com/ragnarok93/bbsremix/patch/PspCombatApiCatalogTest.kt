package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatApiCatalogTest {
    private val delta = 0x08803000

    private fun syntheticRegistrations(): ByteArray = ByteArray(3_589_832).also { bytes ->
        PspCombatApiCatalog.known.forEachIndexed { index, entry ->
            // Test-owned fake text; no copyrighted EBOOT or real game assets.
            val nameOffset = 0x350000 + index * 80
            val chars = entry.name.encodeToByteArray()
            chars.copyInto(bytes, nameOffset)
            bytes[nameOffset + chars.size] = 0
            bytes.writeIntLe(entry.pairVa - delta, nameOffset + delta)
            bytes.writeIntLe(entry.pairVa - delta + 4, entry.functionVa)
            entry.opcodes.forEach { bytes.writeIntLe(it.va - delta, it.expected) }
        }
    }

    @Test
    fun source_api_bindings_require_matching_names_pointers_and_instructions() {
        val original = syntheticRegistrations()
        val saved = original.copyOf()
        val report = PspCombatApiCatalog.inspect(original, exactSupportedSource = true)
        assertEquals(PspCombatApiCatalog.known.size, report.findings.size)
        assertTrue(report.findings.all { it.matched })
        assertContains(report.lines.joinToString("\n"), "STATIC VERIFIED")
        assertTrue(original.contentEquals(saved), "Static analysis must be immutable")

        PspCombatApiCatalog.known.forEach { entry ->
            val badName = original.copyOf()
            val at = badName.readIntLe(entry.pairVa - delta) - delta
            badName[at] = badName[at].inc()
            assertFalse(
                PspCombatApiCatalog.inspect(badName, true).findings
                    .single { it.name == entry.name }.matched,
            )

            val badPointer = original.copyOf()
            badPointer.writeIntLe(entry.pairVa - delta + 4, 0)
            assertFalse(
                PspCombatApiCatalog.inspect(badPointer, true).findings
                    .single { it.name == entry.name }.matched,
            )

            val badOpcode = original.copyOf()
            val pc = entry.opcodes.first().va - delta
            badOpcode[pc] = (badOpcode[pc].toInt() xor 1).toByte()
            assertFalse(
                PspCombatApiCatalog.inspect(badOpcode, true).findings
                    .single { it.name == entry.name }.matched,
            )
        }
    }

    @Test
    fun unknown_source_or_truncated_sections_must_not_be_reported_as_verified() {
        val source = syntheticRegistrations()
        val report = PspCombatApiCatalog.inspect(source, exactSupportedSource = false)
        assertContains(report.lines.joinToString("\n"), "STATIC UNVERIFIED")
        assertTrue(report.findings.all { it.matched }) // structure alone does not prove provenance

        for (unknown in listOf(ByteArray(0), byteArrayOf(0x7f), source.copyOf(128))) {
            val partial = PspCombatApiCatalog.inspect(unknown, exactSupportedSource = false)
            assertTrue(partial.findings.all { !it.matched })
            assertContains(partial.lines.joinToString("\n"), "STATIC UNVERIFIED")
        }
    }

    @Test
    fun catalog_treats_player_and_entity_invincibility_as_distinct_mechanisms() {
        val desc = PspCombatApiCatalog.known.associate { it.name to it.observedBehavior }
        assertContains(desc.getValue("SetPlayerFlagInvincible"), "+0x234")
        assertContains(desc.getValue("EnableInvincible"), "+0x190")
        assertContains(desc.getValue("GetCommandCategory"), "0x08B1AE44")
        assertContains(desc.getValue("GetPlayerState"), "+0x220")
        assertContains(desc.getValue("GetSubState"), "+0x22C")
        assertContains(desc.getValue("IsAttacking"), "NOT hit-confirm")
        assertContains(desc.getValue("IsAttacking"), "recursively")
        assertContains(desc.getValue("GetMotionNowFrame"), "motion+0x24")
        assertContains(desc.getValue("GetMotionNowFrame"), "wrap-handled")
        assertContains(desc.getValue("GetCommandState"), "+0x22E")
        assertContains(desc.getValue("GetCommandSubcate"), "16) + 3")
    }
}
