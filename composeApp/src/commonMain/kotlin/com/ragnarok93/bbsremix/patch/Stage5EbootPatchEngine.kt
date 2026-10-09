package com.ragnarok93.bbsremix.patch

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

        if (options.appliesFrameRate) {
            PspNativeFrameRatePatch.validateSource(data, problems)
        }
        if (options.rightStickCamera) {
            PspNativeRightStickPatch.validateSource(data, problems)
        }
        if (options.appliesCameraDistance || options.appliesCameraHeight) {
            PspNativeCameraGeometryPatch.validateSource(data, problems)
        }

        return EbootInspection(fingerprint, problems.isEmpty(), problems)
    }

    override fun patch(data: ByteArray, options: PatchOptions): PatchedEboot {
        options.requireValid()
        val inspection = inspect(data, options)
        if (!inspection.supported) {
            throw PatchValidationException(inspection.problems.joinToString(" "))
        }

        val output = data.copyOf()
        if (options.appliesFrameRate) {
            PspNativeFrameRatePatch.apply(output, options.fpsTarget)
        }
        if (options.rightStickCamera) {
            PspNativeRightStickPatch.apply(output)
        }

        if (options.appliesCameraDistance || options.appliesCameraHeight) {
            PspNativeCameraGeometryPatch.apply(output, options)
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

        if (!data.startsWith(ELF_MAGIC)) problems += "The embedded EBOOT is not a decrypted ELF."
        if (data.size < SUPPORTED_SIZE) {
            problems += "The embedded EBOOT is smaller than the supported input profile."
            return PatchVerification(false, problems)
        }

        if (options.rightStickCamera) {
            PspNativeRightStickPatch.verifyPatched(data, problems)
            if (data.size >= PH0_MEMSZ_OFFSET + 4 &&
                (data.readIntLe(PH0_FILESZ_OFFSET) != OLD_SEGMENT_SIZE ||
                    data.readIntLe(PH0_MEMSZ_OFFSET) != OLD_SEGMENT_SIZE)
            ) {
                problems += "The PSP-native right-stick patch must not extend LOAD #0."
            }
            if (data.size >= E_PHNUM_OFFSET + 2 && data.readShortLe(E_PHNUM_OFFSET) != 2) {
                problems += "The PSP-native right-stick patch must keep the original two program headers."
            }
            if (data.size >= THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE &&
                !data.isZero(THIRD_PHDR_OFFSET, THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE)
            ) {
                problems += "The PSP-native right-stick patch unexpectedly populated the reserved third program-header slot."
            }
            if (data.size >= Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size &&
                !data.isZero(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                )
            ) {
                problems += "Legacy right-stick code is present inside MainApp's dynamic overlay arena."
            }
        } else {
            if (data.size >= PH0_MEMSZ_OFFSET + 4 &&
                (data.readIntLe(PH0_FILESZ_OFFSET) != OLD_SEGMENT_SIZE ||
                    data.readIntLe(PH0_MEMSZ_OFFSET) != OLD_SEGMENT_SIZE)
            ) {
                problems += "The output EBOOT contains an unexpected LOAD #0 extension."
            }
            if (data.size >= Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size &&
                !data.isZero(
                    Stage5Payloads.S2_FILE_OFFSET,
                    Stage5Payloads.S2_FILE_OFFSET + Stage5Payloads.s2Blob.size,
                )
            ) {
                problems += "The output EBOOT contains a legacy overlay-based right-stick payload."
            }
        }

        // Archived Stage 4/5 combat code must never be emitted by the runtime
        // patcher. Its historical hook replaced an s0 restore in the caller
        // epilogue, and both executable payload addresses are overlay-owned.
        if (data.size >= E_PHNUM_OFFSET + 2 && data.readShortLe(E_PHNUM_OFFSET) != 2) {
            problems += "The output EBOOT contains an unexpected third program header."
        }
        if (data.size >= THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE &&
            !data.isZero(THIRD_PHDR_OFFSET, THIRD_PHDR_OFFSET + PROGRAM_HEADER_SIZE)
        ) {
            problems += "The output EBOOT contains an obsolete Stage 4/5 program header."
        }

        PspNativeFrameRatePatch.verifyPatched(data, options.fpsTarget, problems)
        PspNativeCameraGeometryPatch.verifyPatched(data, options, problems)

        return PatchVerification(problems.isEmpty(), problems.distinct())
    }

    private companion object {
        const val SUPPORTED_SHA256 = "8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7"
        const val SUPPORTED_SIZE = 3589832
        const val E_PHNUM_OFFSET = 0x2c
        const val PH0_FILESZ_OFFSET = 0x44
        const val PH0_MEMSZ_OFFSET = 0x48
        const val OLD_SEGMENT_SIZE = 0x0036ae64
        const val THIRD_PHDR_OFFSET = 0x74
        const val PROGRAM_HEADER_SIZE = 0x20
        val ELF_MAGIC = byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte())
        val PRX_MAGIC = byteArrayOf('~'.code.toByte(), 'P'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())
        val SCE_MAGIC = byteArrayOf('~'.code.toByte(), 'S'.code.toByte(), 'C'.code.toByte(), 'E'.code.toByte())
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

