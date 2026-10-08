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
    fun frame_rate_patch_forces_native_mode_zero_and_expected_timing_scalars() {
        val original = ByteArray(3_589_832)
        for (address in 0x088074B0..0x088074EC step 4) {
            original.writeIntLe(fileOffset(address), PspNativeFrameRatePatch.originalWord(address))
        }

        val originalCopy = original.copyOf()
        val noPatchProblems = mutableListOf<String>()
        PspNativeFrameRatePatch.verifyPatched(originalCopy, 30, noPatchProblems)
        assertTrue(noPatchProblems.isEmpty(), noPatchProblems.joinToString())

        val expectedScalarBits = mapOf(
            60 to 0x3F800000,
            90 to 0x3F2AAAAB,
            120 to 0x3F000000,
        )

        for ((fps, bits) in expectedScalarBits) {
            val image = original.copyOf()
            val sourceProblems = mutableListOf<String>()
            PspNativeFrameRatePatch.validateSource(image, sourceProblems)
            assertTrue(sourceProblems.isEmpty(), sourceProblems.joinToString())

            PspNativeFrameRatePatch.apply(image, fps)

            assertEquals(0x10000006, image.readIntLe(fileOffset(0x088074BC)))
            assertEquals(
                0x3C040000 or ((bits ushr 16) and 0xffff),
                image.readIntLe(fileOffset(0x088074D8)),
            )
            assertEquals(
                0x34840000.toInt() or (bits and 0xffff),
                image.readIntLe(fileOffset(0x088074DC)),
            )
            assertEquals(0x44846000, image.readIntLe(fileOffset(0x088074E0)))
            assertEquals(0xE4AC1870.toInt(), image.readIntLe(fileOffset(0x088074E4)))
            assertEquals(0x03E00008, image.readIntLe(fileOffset(0x088074E8)))
            assertEquals(0xACC05EC8.toInt(), image.readIntLe(fileOffset(0x088074EC)))

            val problems = mutableListOf<String>()
            PspNativeFrameRatePatch.verifyPatched(image, fps, problems)
            assertTrue(problems.isEmpty(), problems.joinToString())
        }

        assertEquals(1.0f, PspNativeFrameRatePatch.timingScalar(60))
        assertEquals(2.0f / 3.0f, PspNativeFrameRatePatch.timingScalar(90))
        assertEquals(0.5f, PspNativeFrameRatePatch.timingScalar(120))
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
        assertEquals(0x00000000, replacements[0x08816328])

        fun centered(raw: Int): Int {
            val stored = raw xor 0x80
            return if (stored < 0x80) stored else stored - 0x100
        }

        fun transformedX(raw: Int): Int = -centered(raw)
        fun transformedY(raw: Int): Int = centered(raw)

        assertEquals(0, transformedX(0x80))
        assertEquals(0, transformedY(0x80))
        assertTrue(transformedX(0x00) > 0)
        assertTrue(transformedX(0xFF) < 0)
        assertTrue(transformedY(0x00) < 0)
        assertTrue(transformedY(0xFF) > 0)
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
        seedCameraGeometry(image)

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
    fun camera_geometry_uses_native_mode_vectors_and_preserves_elf_layout() {
        val image = ByteArray(3_589_832)
        image.copyAt(0, byteArrayOf(0x7f, 0x45, 0x4c, 0x46))
        image.writeShortLe(E_PHNUM_OFFSET, 2)
        image.writeIntLe(PH0_FILESZ_OFFSET, OLD_SEGMENT_SIZE)
        image.writeIntLe(PH0_MEMSZ_OFFSET, OLD_SEGMENT_SIZE)
        seedCameraGeometry(image)

        val headerBefore = image.copyOfRange(0, 0x100)
        val overlayBefore = image.copyOfRange(
            Stage5Payloads.S2_FILE_OFFSET,
            Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
        )
        val options = PatchOptions(
            rightStickCamera = false,
            cameraDistanceEnabled = true,
            cameraDistance = 4.5f,
            cameraHeightEnabled = true,
            cameraHeight = 1.0f,
        )

        PspNativeCameraGeometryPatch.apply(image, options)

        assertEquals(
            -4.5f,
            image.readFloatLe(PspNativeCameraGeometryPatch.MODE1_DISTANCE_VA - VA_FILE_DELTA),
        )
        assertEquals(
            -4.5f,
            image.readFloatLe(PspNativeCameraGeometryPatch.MODE2_DISTANCE_VA - VA_FILE_DELTA),
        )
        assertEquals(
            1.0f,
            image.readFloatLe(PspNativeCameraGeometryPatch.MODE1_HEIGHT_VA - VA_FILE_DELTA),
        )
        assertEquals(
            1.0f,
            image.readFloatLe(PspNativeCameraGeometryPatch.MODE2_HEIGHT_VA - VA_FILE_DELTA),
        )

        assertEquals(2, image.readShortLe(E_PHNUM_OFFSET))
        assertEquals(OLD_SEGMENT_SIZE, image.readIntLe(PH0_FILESZ_OFFSET))
        assertEquals(OLD_SEGMENT_SIZE, image.readIntLe(PH0_MEMSZ_OFFSET))
        assertTrue(headerBefore.contentEquals(image.copyOfRange(0, 0x100)))
        assertTrue(
            overlayBefore.contentEquals(
                image.copyOfRange(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                ),
            ),
        )

        val problems = mutableListOf<String>()
        PspNativeCameraGeometryPatch.verifyPatched(image, options, problems)
        assertTrue(problems.isEmpty(), problems.joinToString())
    }

    @Test
    fun camera_geometry_copier_overrides_only_selected_components() {
        val heightOnly = PspNativeCameraGeometryPatch.patchedCopyRoutine(
            PatchOptions(
                rightStickCamera = false,
                cameraHeightEnabled = true,
                cameraHeight = 1.2f,
            ),
        ).toList()
        val distanceOnly = PspNativeCameraGeometryPatch.patchedCopyRoutine(
            PatchOptions(
                rightStickCamera = false,
                cameraDistanceEnabled = true,
                cameraDistance = 5.0f,
            ),
        ).toList()
        val combined = PspNativeCameraGeometryPatch.patchedCopyRoutine(
            PatchOptions(
                rightStickCamera = false,
                cameraHeightEnabled = true,
                cameraHeight = 1.2f,
                cameraDistanceEnabled = true,
                cameraDistance = 5.0f,
            ),
        ).toList()

        assertTrue(0xAC480024.toInt() in heightOnly)
        assertTrue(0xAC480054.toInt() in heightOnly)
        assertTrue(0xAC480028.toInt() !in heightOnly)
        assertTrue(0xAC480058.toInt() !in heightOnly)

        assertTrue(0xAC480028.toInt() in distanceOnly)
        assertTrue(0xAC480058.toInt() in distanceOnly)
        assertTrue(0xAC480024.toInt() !in distanceOnly)
        assertTrue(0xAC480054.toInt() !in distanceOnly)

        assertTrue(0xAC480024.toInt() in combined)
        assertTrue(0xAC480054.toInt() in combined)
        assertTrue(0xAC480028.toInt() in combined)
        assertTrue(0xAC480058.toInt() in combined)

        assertEquals(0x00801025, combined[0])
        assertEquals(0x3408001C, combined[1])
        assertEquals(0x1500FFFA, combined[7])
        assertEquals(PspNativeCameraGeometryPatch.CAMERA_COPY_ROUTINE_SIZE / 4, combined.size)
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
        seedCameraGeometry(image)

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

    private fun seedCameraGeometry(image: ByteArray) {
        image.writeIntLe(
            PspNativeCameraGeometryPatch.CAMERA_TABLE_VA - VA_FILE_DELTA,
            PspNativeCameraGeometryPatch.CAMERA_TABLE_SIGNATURE,
        )
        image.writeIntLe(PspNativeCameraGeometryPatch.MODE1_RECORD_VA - VA_FILE_DELTA, 1)
        image.writeIntLe(PspNativeCameraGeometryPatch.MODE2_RECORD_VA - VA_FILE_DELTA, 2)
        image.writeFloatLe(
            PspNativeCameraGeometryPatch.MODE1_HEIGHT_VA - VA_FILE_DELTA,
            PspNativeCameraGeometryPatch.MODE1_HEIGHT_ORIGINAL,
        )
        image.writeFloatLe(
            PspNativeCameraGeometryPatch.MODE1_DISTANCE_VA - VA_FILE_DELTA,
            PspNativeCameraGeometryPatch.MODE1_DISTANCE_ORIGINAL,
        )
        image.writeFloatLe(
            PspNativeCameraGeometryPatch.MODE2_HEIGHT_VA - VA_FILE_DELTA,
            PspNativeCameraGeometryPatch.MODE2_HEIGHT_ORIGINAL,
        )
        image.writeFloatLe(
            PspNativeCameraGeometryPatch.MODE2_DISTANCE_VA - VA_FILE_DELTA,
            PspNativeCameraGeometryPatch.MODE2_DISTANCE_ORIGINAL,
        )
        image.writeFloatLe(PspNativeCameraGeometryPatch.MODE1_RECORD_VA + 0x1C - VA_FILE_DELTA, 1.0f)
        image.writeFloatLe(PspNativeCameraGeometryPatch.MODE2_RECORD_VA + 0x1C - VA_FILE_DELTA, 1.0f)
        PspNativeCameraGeometryPatch.originalCopyRoutineWords().forEachIndexed { index, word ->
            image.writeIntLe(
                PspNativeCameraGeometryPatch.CAMERA_COPY_ROUTINE_VA - VA_FILE_DELTA + index * 4,
                word,
            )
        }
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
