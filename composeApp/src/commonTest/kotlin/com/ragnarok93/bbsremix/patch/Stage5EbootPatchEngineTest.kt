package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Stage5EbootPatchEngineTest {
    private val engine = Stage5EbootPatchEngine()

    @Test
    fun unsupported_input_reports_size_hash_and_representation() {
        val result = engine.inspect("not an EBOOT".encodeToByteArray(), PatchOptions())

        assertEquals(EbootRepresentation.UNKNOWN, result.fingerprint.representation)
        assertTrue(result.problems.any { it.contains("size", ignoreCase = true) })
        assertTrue(result.problems.any { it.contains("SHA-256") })
        assertTrue(result.problems.any { it.contains("neither", ignoreCase = true) })
        assertTrue(!result.supported)
    }

    @Test
    fun encrypted_prx_is_rejected_without_relaxing_the_elf_fingerprint() {
        val encrypted = "~PSP".encodeToByteArray() + ByteArray(256)
        val result = engine.inspect(encrypted, PatchOptions())

        assertEquals(EbootRepresentation.ENCRYPTED_PRX, result.fingerprint.representation)
        assertContains(result.problems.joinToString("\n"), "encrypted PSP PRX")
        assertFailsWith<PatchValidationException> {
            engine.patch(encrypted, PatchOptions())
        }
    }

    @Test
    fun reference_payload_sizes_are_stable() {
        assertEquals(370, Stage5Payloads.s2Blob.size)
        assertEquals(4680, Stage5Payloads.s4Blob.size)
        assertEquals(104, Stage5Payloads.s5WrapperBlob.size)
        assertEquals(0x1320, Stage5Payloads.S5_SEGMENT_PAD)
    }
}
