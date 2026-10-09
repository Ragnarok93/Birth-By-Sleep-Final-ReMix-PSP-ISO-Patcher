package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PspCombatNativeEvidenceTest {
    private fun syntheticNativeSites(): ByteArray = ByteArray(3_589_832).also { source ->
        // Strictly a fake EBOOT for structural tests; never treated as supported.
        PspCombatNativeEvidence.signatures.forEach { signature ->
            signature.instructions.forEachIndexed { index, instruction ->
                source.writeIntLe(signature.address - 0x08803000 + index * 4, instruction)
            }
        }
    }

    @Test
    fun all_native_cancel_signature_words_can_be_located_but_not_runtime_certified() {
        val source = syntheticNativeSites()
        val old = source.copyOf()
        val report = PspCombatNativeEvidence.inspect(source, exactSupportedSource = false)
        assertEquals(report.totalSignatures, report.verifiedNativeSignatures)
        assertContains(report.lines.joinToString("\n"), "exact_source=false")
        assertContains(report.lines.joinToString("\n"), "no combat semantics may be inferred")
        assertTrue(source.contentEquals(old))
    }

    @Test
    fun changing_any_cancel_bit_field_or_state_handler_fails_signature_verification() {
        val source = syntheticNativeSites()
        val valid = PspCombatNativeEvidence.inspect(source, true)
        assertContains(valid.lines.joinToString("\n"), "STATIC PROOF")
        assertContains(valid.lines.joinToString("\n"), "UNPROVEN")
        for (signature in PspCombatNativeEvidence.signatures) {
            val corrupted = source.copyOf()
            val offset = signature.address - 0x08803000
            corrupted[offset] = (corrupted[offset].toInt() xor 1).toByte()
            val report = PspCombatNativeEvidence.inspect(corrupted, false)
            assertEquals(report.totalSignatures - 1, report.verifiedNativeSignatures)
            assertFalse(report.lines.joinToString("\n").contains("STATIC PROOF"))
        }
    }
}
