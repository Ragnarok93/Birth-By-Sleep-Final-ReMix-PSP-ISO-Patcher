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
    fun right_stick_payload_directly_polls_right_analog_without_capture_hook() {
        val code = Stage5Payloads.s2Blob
        assertEquals(168, code.size)
        assertEquals(0x08b6ee80, Stage5Payloads.S2_RIGHT_X_VA)
        assertEquals(0x08b6eed4, Stage5Payloads.S2_RIGHT_Y_VA)
        assertEquals(0x27bdffd0, code.readIntLe(0))
        assertEquals(0xafbf002c.toInt(), code.readIntLe(4))
        assertEquals(0x0e2c5b4e, code.readIntLe(16))
        assertEquals(0x93a8001a.toInt(), code.readIntLe(32))
        assertEquals(0x0e2c5b4e, code.readIntLe(84 + 16))
        assertEquals(0x93a8001b.toInt(), code.readIntLe(84 + 32))
        assertTrue(Stage5Payloads.S2_FILE_OFFSET + code.size <= 0x36c000)
    }

    @Test
    fun right_stick_profile_leaves_original_controller_poll_call_intact() {
        val options = PatchOptions()
        val image = ByteArray(3_589_832)
        image.copyAt(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        image.writeShortLe(0x2c, 2)
        image.writeIntLe(0x44, Stage5Payloads.s2NewSegmentSize)
        image.writeIntLe(0x48, Stage5Payloads.s2NewSegmentSize)
        image.writeIntLe(0x08816688 - 0x08803000, 0x0e2c5b4e)
        image.copyAt(Stage5Payloads.S2_FILE_OFFSET, Stage5Payloads.s2Blob)
        image.writeIntLe(0x08940fec - 0x08803000, 0)
        image.writeIntLe(0x0898f68c - 0x08803000, 0)
        image.writeIntLe(0x0898f850 - 0x08803000, 0)
        image.writeIntLe(0x0898f69c - 0x08803000, jal(Stage5Payloads.S2_RIGHT_X_VA))
        image.writeIntLe(0x0898f6dc - 0x08803000, jal(Stage5Payloads.S2_RIGHT_X_VA))
        image.writeIntLe(0x0898f860 - 0x08803000, jal(Stage5Payloads.S2_RIGHT_Y_VA))
        image.writeIntLe(0x0898f8a0 - 0x08803000, jal(Stage5Payloads.S2_RIGHT_Y_VA))
        image.writeFloatLe(Stage5Payloads.CAMERA_FREE_HEIGHT_VA - 0x08803000, 1.5f)
        image.writeFloatLe(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA - 0x08803000, 1.0f)

        assertEquals(0x0e2c5b4e, image.readIntLe(0x08816688 - 0x08803000))
        val result = engine.verifyPatched(image, options)
        assertTrue(result.verified, result.problems.joinToString())
        assertEquals(RuntimeValidation.PENDING, result.runtimeValidation)
    }
}
