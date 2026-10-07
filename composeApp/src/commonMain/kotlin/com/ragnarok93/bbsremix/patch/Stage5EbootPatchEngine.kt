package com.ragnarok93.bbsremix.patch

import kotlin.math.abs

class Stage5EbootPatchEngine : EbootPatchEngine {
    override fun inspect(data: ByteArray, options: PatchOptions): EbootInspection {
        val problems = mutableListOf<String>()
        problems += options.validate().map { it.message }

        val representation = when {
            data.startsWith(ELF_MAGIC) -> EbootRepresentation.DECRYPTED_ELF
            data.startsWith(PRX_MAGIC) || data.startsWith(SCE_MAGIC) -> EbootRepresentation.ENCRYPTED_PRX
            else -> EbootRepresentation.UNKNOWN
        }
        val fingerprint = EbootFingerprint(
            size = data.size.toLong(),
            sha256 = sha256Hex(data),
            representation = representation,
        )

        if (representation == EbootRepresentation.ENCRYPTED_PRX) {
            problems += "EBOOT.BIN is an encrypted PSP PRX; the supported input is the exact decrypted English-patched ELF."
        } else if (representation != EbootRepresentation.DECRYPTED_ELF) {
            problems += "EBOOT.BIN is neither the supported decrypted ELF nor a recognized PSP PRX container."
        }
        if (data.size != SUPPORTED_SIZE) {
            problems += "Unsupported EBOOT size ${data.size}; expected $SUPPORTED_SIZE bytes."
        }
        if (fingerprint.sha256 != SUPPORTED_SHA256) {
            problems += "Unsupported EBOOT SHA-256 ${fingerprint.sha256}; expected $SUPPORTED_SHA256."
        }

        if (data.size >= E_PHNUM_OFFSET + 2 && data.readShortLe(E_PHNUM_OFFSET) != 2) {
            problems += "Unexpected ELF program-header count; expected 2."
        }
        if (data.size >= PH0_MEMSZ_OFFSET + 4) {
            if (data.readIntLe(PH0_FILESZ_OFFSET) != OLD_SEGMENT_SIZE ||
                data.readIntLe(PH0_MEMSZ_OFFSET) != OLD_SEGMENT_SIZE
            ) {
                problems += "Unexpected LOAD #0 file or memory size."
            }
        }
        if (data.size >= THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE &&
            !data.isZero(THIRD_PHDR_OFFSET, THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE)
        ) {
            problems += "The reserved third program-header slot is not zero-filled."
        }

        val postInput = options.combatFeatures || options.appliesCameraDistance
        if (options.rightStickCamera) {
            validateCameraPayload(data, problems)
        }
        if (postInput) {
            validatePostInputHook(data, problems)
        }
        if (options.appliesCameraHeight) {
            validateCameraHeight(data, problems)
        }

        return EbootInspection(fingerprint, problems.isEmpty(), problems)
    }

    override fun patch(data: ByteArray, options: PatchOptions): PatchedEboot {
        options.requireValid()
        val inspection = inspect(data, options)
        if (!inspection.supported) {
            throw PatchValidationException(inspection.problems.joinToString(" "))
        }

        var output = data.copyOf()
        if (options.rightStickCamera) {
            output.writeIntLe(PH0_FILESZ_OFFSET, Stage5Payloads.s2NewSegmentSize)
            output.writeIntLe(PH0_MEMSZ_OFFSET, Stage5Payloads.s2NewSegmentSize)
            output.copyAt(Stage5Payloads.S2_FILE_OFFSET, Stage5Payloads.s2Blob)
            cameraPatches.forEach { (virtualAddress, expected, replacement, description) ->
                val offset = fileOffset(virtualAddress)
                check(output.readIntLe(offset) == expected) { "$description changed after verification" }
                output.writeIntLe(offset, replacement)
            }
        }

        val postInput = options.combatFeatures || options.appliesCameraDistance
        if (postInput) {
            output.writeIntLe(fileOffset(POST_INPUT_HOOK.first), jal(Stage5Payloads.S5_WRAPPER_VA))
            output = addStage5(output, options)
        }

        if (options.appliesCameraHeight) {
            output.writeFloatLe(fileOffset(Stage5Payloads.CAMERA_FREE_HEIGHT_VA), options.cameraHeight)
            output.writeFloatLe(fileOffset(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA), options.cameraHeight)
        }

        return PatchedEboot(
            bytes = output,
            input = inspection.fingerprint,
            outputSha256 = sha256Hex(output),
        )
    }

    override fun verifyPatched(data: ByteArray, options: PatchOptions): PatchVerification {
        val problems = mutableListOf<String>()
        problems += options.validate().map { it.message }
        if (!options.hasSelectedFeature) return PatchVerification(false, problems)

        val postInput = options.combatFeatures || options.appliesCameraDistance
        if (!data.startsWith(ELF_MAGIC)) problems += "The embedded EBOOT is not a decrypted ELF."
        if (data.size < SUPPORTED_SIZE) {
            problems += "The embedded EBOOT is smaller than the supported input profile."
            return PatchVerification(false, problems)
        }

        if (options.rightStickCamera) {
            requireRange(data, Stage5Payloads.S2_FILE_OFFSET, Stage5Payloads.s2Blob.size, problems)
            if (data.size >= PH0_MEMSZ_OFFSET + 4 &&
                (data.readIntLe(PH0_FILESZ_OFFSET) != Stage5Payloads.s2NewSegmentSize ||
                    data.readIntLe(PH0_MEMSZ_OFFSET) != Stage5Payloads.s2NewSegmentSize)
            ) {
                problems += "The right-stick camera segment is not enabled."
            }
            if (data.size >= Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size &&
                !data.copyOfRange(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                ).contentEquals(Stage5Payloads.s2Blob)
            ) {
                problems += "The right-stick camera payload is missing or altered."
            }
            cameraPatches.forEach { (virtualAddress, _, replacement, description) ->
                val offset = fileOffset(virtualAddress)
                if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != replacement) {
                    problems += "$description is not enabled in the output EBOOT."
                }
            }
        } else {
            if (data.size >= PH0_MEMSZ_OFFSET + 4 &&
                (data.readIntLe(PH0_FILESZ_OFFSET) != OLD_SEGMENT_SIZE ||
                    data.readIntLe(PH0_MEMSZ_OFFSET) != OLD_SEGMENT_SIZE)
            ) {
                problems += "The output EBOOT contains an unselected right-stick camera segment."
            }
            if (data.size >= Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size &&
                !data.isZero(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                )
            ) {
                problems += "The output EBOOT contains an unselected right-stick camera payload."
            }
        }

        if (postInput) {
            requireRange(data, Stage5Payloads.S4_FILE_OFFSET, Stage5Payloads.S5_SEGMENT_PAD, problems)
            if (data.size >= E_PHNUM_OFFSET + 2 && data.readShortLe(E_PHNUM_OFFSET) != 3) {
                problems += "The Stage 5 program header is missing."
            }
            val expectedHeader = intArrayOf(
                1,
                Stage5Payloads.S4_FILE_OFFSET,
                Stage5Payloads.S4_VA,
                Stage5Payloads.S4_VA,
                Stage5Payloads.S5_SEGMENT_PAD,
                Stage5Payloads.S5_SEGMENT_PAD,
                7,
                0x1000,
            )
            expectedHeader.forEachIndexed { index, expected ->
                val offset = THIRD_PHDR_OFFSET + index * 4
                if (offset + 4 > data.size || data.readIntLe(offset) != expected) {
                    problems += "The Stage 5 program header does not match the selected configuration."
                }
            }
            val hookOffset = fileOffset(POST_INPUT_HOOK.first)
            if (hookOffset < 0 || hookOffset + 4 > data.size ||
                data.readIntLe(hookOffset) != jal(Stage5Payloads.S5_WRAPPER_VA)
            ) {
                problems += "The Stage 5 runtime hook is missing."
            }
            val expectedSegment = stage5Segment(options)
            if (data.size >= Stage5Payloads.S4_FILE_OFFSET + expectedSegment.size &&
                !data.copyOfRange(
                    Stage5Payloads.S4_FILE_OFFSET,
                    Stage5Payloads.S4_FILE_OFFSET + expectedSegment.size,
                ).contentEquals(expectedSegment)
            ) {
                problems += "The combat payload, telemetry initialization, or segment padding is altered."
            }
            val configOffset = Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S4_CONFIG_OFFSET
            if (configOffset >= data.size ||
                (data[configOffset].toInt() and 0xff) != makeConfig(options)
            ) {
                problems += "The Stage 5 feature configuration does not match the selected toggles."
            }
            val distanceOffset = Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S4_CAMERA_DISTANCE_OFFSET
            if (distanceOffset + 4 > data.size ||
                abs(data.readFloatLe(distanceOffset) - options.cameraDistance) > 0.0001f
            ) {
                problems += "The camera-distance configuration does not match the selected value."
            }
            val wrapperOffset = Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S5_WRAPPER_OFFSET
            if (wrapperOffset + Stage5Payloads.s5WrapperBlob.size > data.size ||
                !data.copyOfRange(
                    wrapperOffset,
                    wrapperOffset + Stage5Payloads.s5WrapperBlob.size,
                ).contentEquals(Stage5Payloads.s5WrapperBlob)
            ) {
                problems += "The Stage 5 wrapper is missing or altered."
            }
            val passiveOffset = Stage5Payloads.S4_FILE_OFFSET + Stage5Payloads.S5_PASSIVES_CONFIG_OFFSET
            val expectedPassive = 0
            if (passiveOffset >= data.size || (data[passiveOffset].toInt() and 0xff) != expectedPassive) {
                problems += "The Critical Mode passive configuration does not match the selected toggle."
            }
        } else if (data.size >= E_PHNUM_OFFSET + 2 && data.readShortLe(E_PHNUM_OFFSET) != 2) {
            problems += "The output EBOOT contains an unselected Stage 5 segment."
        }

        if (options.appliesCameraHeight) {
            val freeOffset = fileOffset(Stage5Payloads.CAMERA_FREE_HEIGHT_VA)
            val lockOffset = fileOffset(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA)
            if (freeOffset + 4 > data.size || abs(data.readFloatLe(freeOffset) - options.cameraHeight) > 0.0001f) {
                problems += "The free-camera height does not match the selected value."
            }
            if (lockOffset + 4 > data.size || abs(data.readFloatLe(lockOffset) - options.cameraHeight) > 0.0001f) {
                problems += "The lock-on camera height does not match the selected value."
            }
        } else {
            val freeOffset = fileOffset(Stage5Payloads.CAMERA_FREE_HEIGHT_VA)
            val lockOffset = fileOffset(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA)
            if (freeOffset + 4 > data.size || abs(data.readFloatLe(freeOffset) - Stage5Payloads.CAMERA_FREE_HEIGHT_ORIG) > 0.0001f) {
                problems += "The output EBOOT contains an unselected free-camera height change."
            }
            if (lockOffset + 4 > data.size || abs(data.readFloatLe(lockOffset) - Stage5Payloads.CAMERA_LOCK_HEIGHT_ORIG) > 0.0001f) {
                problems += "The output EBOOT contains an unselected lock-on height change."
            }
        }

        return PatchVerification(problems.isEmpty(), problems.distinct())
    }

    private fun requireRange(data: ByteArray, offset: Int, size: Int, problems: MutableList<String>) {
        if (offset < 0 || size < 0 || offset > data.size || size > data.size - offset) {
            problems += "The output EBOOT is too small for the selected patch payload."
        }
    }

    private fun validateCameraPayload(data: ByteArray, problems: MutableList<String>) {
        val payloadEnd = Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size
        if (payloadEnd > NEXT_ORIGINAL_LOAD_FILE_OFFSET || payloadEnd > data.size) {
            problems += "The camera payload would overlap the original LOAD #1 or exceed the EBOOT."
            return
        }
        if (!data.isZero(Stage5Payloads.S2_FILE_OFFSET, payloadEnd)) {
            problems += "The camera code cave is not zero-filled."
        }
        cameraPatches.forEach { (virtualAddress, expected, _, description) ->
            val offset = fileOffset(virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != expected) {
                val found = if (offset >= 0 && offset + 4 <= data.size) data.readIntLe(offset).toUInt().toString(16) else "out-of-range"
                problems += "$description mismatch at VA 0x${virtualAddress.toString(16)} (got 0x$found)."
            }
        }
    }

    private fun validatePostInputHook(data: ByteArray, problems: MutableList<String>) {
        val offset = fileOffset(POST_INPUT_HOOK.first)
        if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != POST_INPUT_HOOK.second) {
            problems += "The post-input hook instruction does not match the reference EBOOT."
        }
        if (Stage5Payloads.S4_VA + Stage5Payloads.S5_SEGMENT_PAD >= Stage5Payloads.ORIGINAL_LOAD1_VA) {
            problems += "The Stage 5 payload would overlap the original LOAD #1."
        }
    }

    private fun validateCameraHeight(data: ByteArray, problems: MutableList<String>) {
        val free = fileOffset(Stage5Payloads.CAMERA_FREE_HEIGHT_VA)
        val lock = fileOffset(Stage5Payloads.CAMERA_LOCK_HEIGHT_VA)
        if (free < 0 || free + 4 > data.size ||
            abs(data.readFloatLe(free) - Stage5Payloads.CAMERA_FREE_HEIGHT_ORIG) > 0.000001f
        ) {
            problems += "The free-camera height constant does not match the reference EBOOT."
        }
        if (lock < 0 || lock + 4 > data.size ||
            abs(data.readFloatLe(lock) - Stage5Payloads.CAMERA_LOCK_HEIGHT_ORIG) > 0.000001f
        ) {
            problems += "The lock-on camera height constant does not match the reference EBOOT."
        }
    }

    private fun stage5Segment(options: PatchOptions): ByteArray {
        val config = makeConfig(options)
        val segment = Stage5Payloads.s4Blob.copyOf().toMutableList()
        while (segment.size < Stage5Payloads.S5_WRAPPER_OFFSET) segment.add(0.toByte())
        segment += Stage5Payloads.s5WrapperBlob.toList()
        while (segment.size < Stage5Payloads.S5_PASSIVES_CONFIG_OFFSET) segment.add(0.toByte())
        segment.add(0.toByte()) // advanced passive writes are gated off
        while (segment.size < Stage5Payloads.S5_SEGMENT_PAD) segment.add(0.toByte())
        val blob = segment.map { it.toByte() }.toByteArray()
        blob[Stage5Payloads.S4_CONFIG_OFFSET] = config.toByte()
        blob.writeFloatLe(Stage5Payloads.S4_CAMERA_DISTANCE_OFFSET, options.cameraDistance)

        return blob
    }

    private fun addStage5(output: ByteArray, options: PatchOptions): ByteArray {
        val blob = stage5Segment(options)
        val programHeader = intArrayOf(
            1,
            Stage5Payloads.S4_FILE_OFFSET,
            Stage5Payloads.S4_VA,
            Stage5Payloads.S4_VA,
            blob.size,
            blob.size,
            7,
            0x1000,
        )
        programHeader.forEachIndexed { index, value ->
            output.writeIntLe(THIRD_PHDR_OFFSET + index * 4, value)
        }
        output.writeShortLe(E_PHNUM_OFFSET, 3)
        val requiredSize = Stage5Payloads.S4_FILE_OFFSET + blob.size
        val expanded = if (output.size < requiredSize) output.copyOf(requiredSize) else output
        expanded.copyAt(Stage5Payloads.S4_FILE_OFFSET, blob)
        return expanded
    }

    private fun makeConfig(options: PatchOptions): Int {
        var config = if (options.combatFeatures) 0x3c else 0
        if (options.combatFeatures) {
            if (options.strictSteamExclusions) config = config and 0x20.inv()
            if (!options.extendedDefense) config = config and 0x04.inv()
            if (!options.commandCancels) config = config and 0x08.inv()
            if (!options.telemetry) config = config and 0x10.inv()
        }
        if (options.appliesCameraDistance) config = config or 0x80
        return config
    }

    private fun fileOffset(virtualAddress: Int): Int = virtualAddress - VA_FILE_DELTA

    private data class CameraPatch(
        val virtualAddress: Int,
        val expected: Int,
        val replacement: Int,
        val description: String,
    )

    private companion object {
        const val SUPPORTED_SHA256 = "8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7"
        const val SUPPORTED_SIZE = 3589832
        const val VA_FILE_DELTA = 0x08803000
        const val E_PHNUM_OFFSET = 0x2c
        const val PH0_FILESZ_OFFSET = 0x44
        const val PH0_MEMSZ_OFFSET = 0x48
        const val OLD_SEGMENT_SIZE = 0x0036ae64
        const val THIRD_PHDR_OFFSET = 0x74
        const val PROGRAM_HEADER_SIZE = 0x20
        const val NEXT_ORIGINAL_LOAD_FILE_OFFSET = 0x0036c000
        const val POST_INPUT_HOOK_VA = 0x08816904
        const val POST_INPUT_HOOK_EXPECTED = 0x8fb00048.toInt()
        val POST_INPUT_HOOK = POST_INPUT_HOOK_VA to POST_INPUT_HOOK_EXPECTED
        val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val PRX_MAGIC = byteArrayOf('~'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())
        val SCE_MAGIC = byteArrayOf('~'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())
        val cameraPatches = listOf(
            CameraPatch(0x08940fec, 0x508000ba, 0, "remove L modifier from camera"),
            CameraPatch(0x0898f68c, 0x1c80000b, 0, "force Type-B horizontal camera"),
            CameraPatch(0x0898f850, 0x1c80000b, 0, "force Type-B vertical camera"),
            CameraPatch(0x0898f69c, 0x0e2058cc, jal(Stage5Payloads.S2_RIGHT_X_VA), "right-stick X read 1"),
            CameraPatch(0x0898f6dc, 0x0e2058cc, jal(Stage5Payloads.S2_RIGHT_X_VA), "right-stick X read 2"),
            CameraPatch(0x0898f860, 0x0e2058d8, jal(Stage5Payloads.S2_RIGHT_Y_VA), "right-stick Y read 1"),
            CameraPatch(0x0898f8a0, 0x0e2058d8, jal(Stage5Payloads.S2_RIGHT_Y_VA), "right-stick Y read 2"),
        )
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

