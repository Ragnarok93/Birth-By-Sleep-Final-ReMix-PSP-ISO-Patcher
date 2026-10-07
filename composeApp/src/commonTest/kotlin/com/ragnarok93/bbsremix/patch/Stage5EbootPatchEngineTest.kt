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
        assertEquals(134, Stage5Payloads.s2Blob.size)
        assertEquals(4680, Stage5Payloads.s4Blob.size)
        assertEquals(104, Stage5Payloads.s5WrapperBlob.size)
        assertEquals(0x1320, Stage5Payloads.S5_SEGMENT_PAD)
    }

    @Test
    fun capture_reserves_argument_slots_and_relocates_both_helpers() {
        val code = Stage5Payloads.s2Blob
        assertEquals(0x27bdffe0, code.readIntLe(0))
        assertEquals(0xafbf001c.toInt(), code.readIntLe(4))
        assertEquals(0xafb00018.toInt(), code.readIntLe(8))
        assertEquals(0x00808021, code.readIntLe(12))
        assertEquals(0x08b6eed4, Stage5Payloads.S2_RIGHT_X_VA)
        assertEquals(0x08b6eee0, Stage5Payloads.S2_RIGHT_Y_VA)
        assertTrue(Stage5Payloads.S2_FILE_OFFSET + code.size <= 0x36c000)
    }

    @Test
    fun structural_match_remains_gameplay_pending_and_rejects_combat_code_corruption() {
        val options = PatchOptions(
            rightStickCamera = false,
            cameraDistanceEnabled = false,
            cameraHeightEnabled = false,
        )
        // A synthetic ELF layout, never accepted as a supported source by inspect().
        val image = ByteArray(Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S5_SEGMENT_PAD)
        image.copyAt(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        image.writeShortLe(0x2c, 3)
        image.writeIntLe(0x44, 0x36ae64)
        image.writeIntLe(0x48, 0x36ae64)
        intArrayOf(1, Stage5Payloads.S4_FILE_OFFSET, Stage5Payloads.S4_VA, Stage5Payloads.S4_VA,
            Stage5Payloads.S5_SEGMENT_PAD, Stage5Payloads.S5_SEGMENT_PAD, 7, 0x1000).forEachIndexed { i, word ->
            image.writeIntLe(0x74 + i * 4, word)
        }
        image.writeIntLe(0x08816904 - 0x08803000, jal(Stage5Payloads.S5_WRAPPER_VA))
        image.copyAt(Stage5Payloads.S4_FILE_OFFSET, Stage5Payloads.s4Blob)
        image[Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S4_CONFIG_OFFSET] = 0x3c
        image.copyAt(Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S5_WRAPPER_OFFSET, Stage5Payloads.s5WrapperBlob)
        image.writeFloatLe(Stage5Payloads.CAMERA_FREE_HEIGHT_VA - 0x08803000, 1.5f)
        image.writeFloatLe(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA - 0x08803000, 1.0f)
        val match = engine.verifyPatched(image, options)
        assertTrue(match.verified, match.problems.joinToString())
        assertEquals(RuntimeValidation.PENDING, match.runtimeValidation)
        image[Stage5Payloads.S4_FILE_OFFSET + 100] = (image[Stage5Payloads.S4_FILE_OFFSET + 100].toInt() xor 1).toByte()
        val corrupt = engine.verifyPatched(image, options)
        assertTrue(!corrupt.verified)
        assertContains(corrupt.problems.joinToString(), "combat payload")
    }
}

