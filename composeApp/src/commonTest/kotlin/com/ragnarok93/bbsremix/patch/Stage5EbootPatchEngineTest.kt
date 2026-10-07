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
    fun right_stick_patch_is_resident_and_stays_out_of_overlay_arena() {
        assertTrue(
            PspNativeRightStickPatch.wordPatches.all {
                it.virtualAddress < PspNativeRightStickPatch.OVERLAY_ARENA_VA
            },
        )
        assertTrue(
            PspNativeRightStickPatch.bytePatches.all {
                it.virtualAddress < PspNativeRightStickPatch.OVERLAY_ARENA_VA
            },
        )
        assertEquals(0x08B4199A, PspNativeRightStickPatch.RIGHT_STICK_X_BYTE_VA)
        assertEquals(0x08B4199B, PspNativeRightStickPatch.RIGHT_STICK_Y_BYTE_VA)
        assertEquals(0x34045253, PspNativeRightStickPatch.RIGHT_STICK_SELECTOR)

        val xDefaultBranch = PspNativeRightStickPatch.wordPatches.single {
            it.virtualAddress == 0x088162FC
        }.replacement
        val yDefaultBranch = PspNativeRightStickPatch.wordPatches.single {
            it.virtualAddress == 0x08816318
        }.replacement
        assertEquals(0x088164D0, branchTarget(0x088162FC, xDefaultBranch))
        assertEquals(0x088164F8, branchTarget(0x08816318, yDefaultBranch))
    }

    @Test
    fun right_stick_axes_are_centered_and_inverted_in_place() {
        val replacements = PspNativeRightStickPatch.wordPatches.associate {
            it.virtualAddress to it.replacement
        }

        assertTrue(PspNativeRightStickPatch.bytePatches.isEmpty())
        assertEquals(0x9545FFFA.toInt(), replacements[0x0881683C])
        assertEquals(0x38A58080, replacements[0x08816840])
        assertEquals(0xA485199A.toInt(), replacements[0x0881684C])
        assertTrue(0x08816854 !in replacements)

        assertEquals(0x8042199A.toInt(), replacements[0x08816304])
        assertEquals(0x00021023, replacements[0x0881630C])
        assertEquals(0x8042199B.toInt(), replacements[0x08816320])
        assertEquals(0x00021023, replacements[0x08816328])

        fun transformed(raw: Int): Int {
            val stored = raw xor 0x80
            val signed = if (stored < 0x80) stored else stored - 0x100
            return -signed
        }

        assertEquals(0, transformed(0x80))
        assertTrue(transformed(0x00) > 0)
        assertTrue(transformed(0xFF) < 0)
    }

    @Test
    fun right_stick_patch_preserves_native_poll_and_camera_jals() {
        val required = PspNativeRightStickPatch.requiredUnchangedWords.associate {
            it.virtualAddress to it.expected
        }

        assertEquals(0x0E2C5B4E, required[0x08816688])
        assertEquals(0x0E2058CC, required[0x0898F69C])
        assertEquals(0x0E2058CC, required[0x0898F6DC])
        assertEquals(0x0E2058D8, required[0x0898F860])
        assertEquals(0x0E2058D8, required[0x0898F8A0])
    }

    @Test
    fun right_stick_patch_does_not_change_elf_program_headers_or_overlay_bytes() {
        val image = ByteArray(3_589_832)
        image.copyAt(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        image.writeShortLe(E_PHNUM_OFFSET, 2)
        image.writeIntLe(PH0_FILESZ_OFFSET, OLD_SEGMENT_SIZE)
        image.writeIntLe(PH0_MEMSZ_OFFSET, OLD_SEGMENT_SIZE)

        PspNativeRightStickPatch.wordPatches.forEach {
            image.writeIntLe(fileOffset(it.virtualAddress), it.expected)
        }
        PspNativeRightStickPatch.bytePatches.forEach {
            image[fileOffset(it.virtualAddress)] = it.expected.toByte()
        }
        PspNativeRightStickPatch.requiredUnchangedWords.forEach {
            image.writeIntLe(fileOffset(it.virtualAddress), it.expected)
        }
        image.writeFloatLe(Stage5Payloads.CAMERA_FREE_HEIGHT_VA - VA_FILE_DELTA, 1.5f)
        image.writeFloatLe(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA - VA_FILE_DELTA, 1.0f)

        val headerBefore = image.copyOfRange(0, 0x100)
        val overlayBefore = image.copyOfRange(
            Stage5Payloads.S2_FILE_OFFSET,
            Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
        )

        PspNativeRightStickPatch.apply(image)

        assertEquals(2, image.readShortLe(E_PHNUM_OFFSET))
        assertEquals(OLD_SEGMENT_SIZE, image.readIntLe(PH0_FILESZ_OFFSET))
        assertEquals(OLD_SEGMENT_SIZE, image.readIntLe(PH0_MEMSZ_OFFSET))
        assertTrue(image.copyOfRange(0x74, 0x94).all { it == 0.toByte() })
        assertTrue(
            overlayBefore.contentEquals(
                image.copyOfRange(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                ),
            ),
        )

        // Only the program-header-independent patch sites may differ from the
        // initial ELF header area.
        assertTrue(headerBefore.copyOfRange(0, 0x74).contentEquals(image.copyOfRange(0, 0x74)))
    }

    @Test
    fun right_stick_profile_verifies_without_a_legacy_overlay_payload() {
        val options = PatchOptions()
        val image = ByteArray(3_589_832)
        image.copyAt(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        image.writeShortLe(E_PHNUM_OFFSET, 2)
        image.writeIntLe(PH0_FILESZ_OFFSET, OLD_SEGMENT_SIZE)
        image.writeIntLe(PH0_MEMSZ_OFFSET, OLD_SEGMENT_SIZE)

        PspNativeRightStickPatch.wordPatches.forEach {
            image.writeIntLe(fileOffset(it.virtualAddress), it.replacement)
        }
        PspNativeRightStickPatch.bytePatches.forEach {
            image[fileOffset(it.virtualAddress)] = it.replacement.toByte()
        }
        PspNativeRightStickPatch.requiredUnchangedWords.forEach {
            image.writeIntLe(fileOffset(it.virtualAddress), it.expected)
        }
        image.writeFloatLe(Stage5Payloads.CAMERA_FREE_HEIGHT_VA - VA_FILE_DELTA, 1.5f)
        image.writeFloatLe(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA - VA_FILE_DELTA, 1.0f)

        val result = engine.verifyPatched(image, options)
        assertTrue(result.verified, result.problems.joinToString())
        assertEquals(RuntimeValidation.PENDING, result.runtimeValidation)
        assertTrue(
            image.copyOfRange(
                Stage5Payloads.S2_FILE_OFFSET,
                Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
            ).all { it == 0.toByte() },
        )
    }

    private fun branchTarget(pc: Int, instruction: Int): Int {
        val immediate = instruction.toShort().toInt()
        return pc + 4 + immediate * 4
    }

    private fun fileOffset(virtualAddress: Int): Int = virtualAddress - VA_FILE_DELTA

    private companion object {
        const val VA_FILE_DELTA = 0x08803000
        const val E_PHNUM_OFFSET = 0x2C
        const val PH0_FILESZ_OFFSET = 0x44
        const val PH0_MEMSZ_OFFSET = 0x48
        const val OLD_SEGMENT_SIZE = 0x0036AE64
    }
}
