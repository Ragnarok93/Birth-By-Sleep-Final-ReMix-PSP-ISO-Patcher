package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatScriptCallEvidenceTest {
    private val delta = 0x08803000

    private fun fakeEboot(): ByteArray = ByteArray(3_589_832).also { b ->
        for (site in PspCombatScriptCallEvidence.sites) {
            site.words.forEach { b.writeIntLe(it.va - delta, it.value) }
            for ((va, name) in listOf(
                site.stringVa to site.name,
                site.managerNameVa to "EntityManager",
            )) {
                name.encodeToByteArray().copyInto(b, va - delta)
                b[va - delta + name.length] = 0
                val pointerVa = (va + name.length + 4) and -4
                b.writeIntLe(pointerVa - delta, va)
            }
        }
        PspCombatScriptCallEvidence.helperStart.forEach {
            b.writeIntLe(it.va - delta, it.value)
        }
    }

    @Test
    fun full_named_call_sites_are_authenticated_without_writing_source() {
        val bytes = fakeEboot()
        val before = bytes.copyOf()
        val report = PspCombatScriptCallEvidence.inspect(bytes, true)
        assertTrue(report.helperMatched)
        assertEquals(3, report.sites.size)
        assertTrue(report.sites.all { it.verified })
        assertContains(report.lines.joinToString("\n"), "STATIC VERIFIED")
        assertContains(report.lines.joinToString("\n"), "No OnHitAttack handler")
        assertTrue(before.contentEquals(bytes), "No mutation of EBOOT is allowed")
    }

    @Test
    fun stale_unknown_sources_are_not_certified_even_when_bytes_match() {
        val report = PspCombatScriptCallEvidence.inspect(fakeEboot(), false)
        assertTrue(report.sites.all { it.verified })
        assertContains(report.lines.joinToString("\n"), "STATIC UNVERIFIED")
    }

    @Test
    fun corrupting_helper_branch_or_name_pointer_fails_closed() {
        val bytes = fakeEboot()
        val helper = bytes.copyOf()
        helper[PspCombatScriptCallEvidence.helperStart[0].va - delta] = 0
        val brokenHelper = PspCombatScriptCallEvidence.inspect(helper, true)
        assertFalse(brokenHelper.helperMatched)
        assertTrue(brokenHelper.sites.none { it.verified })

        val name = bytes.copyOf()
        val site = PspCombatScriptCallEvidence.sites.first()
        name[site.stringVa - delta] = 0
        assertFalse(PspCombatScriptCallEvidence.inspect(name, true).sites.first().verified)

        val pointer = bytes.copyOf()
        val aligned = (site.stringVa + site.name.length + 4) and -4
        pointer.writeIntLe(aligned - delta, 0)
        assertFalse(PspCombatScriptCallEvidence.inspect(pointer, true).sites.first().verified)

        assertTrue(PspCombatScriptCallEvidence.inspect(ByteArray(0), false).sites.none { it.verified })
    }
}
