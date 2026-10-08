package com.ragnarok93.bbsremix.patch

import kotlin.math.abs

/**
 * PSP-native camera geometry patch for the exact supported English-patched
 * ULJM-05775 MainApp.
 *
 * MainApp owns a resident fallback camera table at 0x08B59F00, but normal
 * gameplay resolves a BCam resource and copies 0x70 bytes from that resource
 * over the resident working table through the camera-only copier at
 * 0x0893DBF4. A static-table-only patch is therefore overwritten at runtime.
 *
 * This implementation patches both parts of the native path:
 *  - the resident fallback values, for the no-resource path; and
 *  - the camera-only 0x70-byte copier, replacing it with an equivalent word
 *    copy followed by selected Y/Z overrides.
 *
 * The two native camera-mode position vectors begin at working-table offsets
 * 0x20 and 0x50. Y is height (+0x24/+0x54); Z is signed distance
 * (+0x28/+0x58). The user-facing distance is positive and stored as -distance.
 *
 * No extra ELF segment or overlay payload is used.
 */
internal object PspNativeCameraGeometryPatch {
    const val CAMERA_TABLE_VA = 0x08B59F00
    const val CAMERA_TABLE_SIGNATURE = 0x41435040

    const val MODE1_RECORD_VA = 0x08B59F10
    const val MODE2_RECORD_VA = 0x08B59F40

    const val MODE1_HEIGHT_VA = 0x08B59F24
    const val MODE1_DISTANCE_VA = 0x08B59F28
    const val MODE2_HEIGHT_VA = 0x08B59F54
    const val MODE2_DISTANCE_VA = 0x08B59F58

    const val MODE1_HEIGHT_ORIGINAL = 1.5f
    const val MODE2_HEIGHT_ORIGINAL = 1.0f
    const val MODE1_DISTANCE_ORIGINAL = -3.5f
    const val MODE2_DISTANCE_ORIGINAL = -3.5f

    const val CAMERA_COPY_ROUTINE_VA = 0x0893DBF4
    const val CAMERA_COPY_ROUTINE_SIZE = 0xB0

    private const val MODE1_ID = 1
    private const val MODE2_ID = 2
    private const val HOMOGENEOUS_W = 1.0f
    private const val EPSILON = 0.000001f
    private const val VA_FILE_DELTA = 0x08803000
    private const val COPY_WORD_COUNT = CAMERA_COPY_ROUTINE_SIZE / 4

    private val originalCopyRoutine = intArrayOf(
        0x8CA60000.toInt(), 0x8CA70004.toInt(), 0xC4AC0008.toInt(), 0x44086000,
        0xAC860000.toInt(), 0xAC870004.toInt(), 0xAC880008.toInt(), 0xC4AD000C.toInt(),
        0x44066800, 0xAC86000C.toInt(), 0x8CA60010.toInt(), 0xAC860010.toInt(),
        0xC4AE0014.toInt(), 0xE48E0014.toInt(), 0xC4AE0018.toInt(), 0xE48E0018.toInt(),
        0x8CA6001C.toInt(), 0xAC86001C.toInt(), 0x24860020, 0x24A70020,
        0xD8E00000.toInt(), 0xF8C00000.toInt(), 0x24860030, 0x24A70030,
        0xD8E00000.toInt(), 0xF8C00000.toInt(), 0x8CA60040.toInt(), 0xAC860040.toInt(),
        0xC4AC0044.toInt(), 0xE48C0044.toInt(), 0xC4AC0048.toInt(), 0xE48C0048.toInt(),
        0x8CA6004C.toInt(), 0xAC86004C.toInt(), 0x24860050, 0x24A70050,
        0xD8E00000.toInt(), 0xF8C00000.toInt(), 0x24860060, 0x24A50060,
        0xD8A00000.toInt(), 0xF8C00000.toInt(), 0x03E00008, 0x00801025,
    )

    fun validateSource(data: ByteArray, problems: MutableList<String>) {
        val signatureOffset = fileOffset(CAMERA_TABLE_VA)
        if (signatureOffset < 0 || signatureOffset + 4 > data.size ||
            data.readIntLe(signatureOffset) != CAMERA_TABLE_SIGNATURE
        ) {
            problems += "The PSP player-camera parameter table signature does not match the reference EBOOT."
            return
        }

        validateInt(data, MODE1_RECORD_VA, MODE1_ID, "camera mode 1 record", problems)
        validateInt(data, MODE2_RECORD_VA, MODE2_ID, "camera mode 2 record", problems)
        validateFloat(data, MODE1_HEIGHT_VA, MODE1_HEIGHT_ORIGINAL, "camera mode 1 height", problems)
        validateFloat(data, MODE1_DISTANCE_VA, MODE1_DISTANCE_ORIGINAL, "camera mode 1 distance", problems)
        validateFloat(data, MODE2_HEIGHT_VA, MODE2_HEIGHT_ORIGINAL, "camera mode 2 height", problems)
        validateFloat(data, MODE2_DISTANCE_VA, MODE2_DISTANCE_ORIGINAL, "camera mode 2 distance", problems)
        validateFloat(data, MODE1_RECORD_VA + 0x1c, HOMOGENEOUS_W, "camera mode 1 vector W", problems)
        validateFloat(data, MODE2_RECORD_VA + 0x1c, HOMOGENEOUS_W, "camera mode 2 vector W", problems)
        validateCopyRoutine(data, originalCopyRoutine, "native camera resource copier", problems)
    }

    fun apply(data: ByteArray, options: PatchOptions) {
        if (options.appliesCameraDistance) {
            val signedDistance = -options.cameraDistance
            data.writeFloatLe(fileOffset(MODE1_DISTANCE_VA), signedDistance)
            data.writeFloatLe(fileOffset(MODE2_DISTANCE_VA), signedDistance)
        }
        if (options.appliesCameraHeight) {
            data.writeFloatLe(fileOffset(MODE1_HEIGHT_VA), options.cameraHeight)
            data.writeFloatLe(fileOffset(MODE2_HEIGHT_VA), options.cameraHeight)
        }

        if (options.appliesCameraDistance || options.appliesCameraHeight) {
            writeCopyRoutine(data, patchedCopyRoutine(options))
        }
    }

    fun verifyPatched(data: ByteArray, options: PatchOptions, problems: MutableList<String>) {
        val expectedMode1Height = if (options.appliesCameraHeight) options.cameraHeight else MODE1_HEIGHT_ORIGINAL
        val expectedMode2Height = if (options.appliesCameraHeight) options.cameraHeight else MODE2_HEIGHT_ORIGINAL
        val expectedMode1Distance = if (options.appliesCameraDistance) -options.cameraDistance else MODE1_DISTANCE_ORIGINAL
        val expectedMode2Distance = if (options.appliesCameraDistance) -options.cameraDistance else MODE2_DISTANCE_ORIGINAL

        validateInt(data, CAMERA_TABLE_VA, CAMERA_TABLE_SIGNATURE, "camera table signature", problems)
        validateInt(data, MODE1_RECORD_VA, MODE1_ID, "camera mode 1 record", problems)
        validateInt(data, MODE2_RECORD_VA, MODE2_ID, "camera mode 2 record", problems)
        validateFloat(data, MODE1_HEIGHT_VA, expectedMode1Height, "camera mode 1 height", problems)
        validateFloat(data, MODE1_DISTANCE_VA, expectedMode1Distance, "camera mode 1 distance", problems)
        validateFloat(data, MODE2_HEIGHT_VA, expectedMode2Height, "camera mode 2 height", problems)
        validateFloat(data, MODE2_DISTANCE_VA, expectedMode2Distance, "camera mode 2 distance", problems)
        validateFloat(data, MODE1_RECORD_VA + 0x1c, HOMOGENEOUS_W, "camera mode 1 vector W", problems)
        validateFloat(data, MODE2_RECORD_VA + 0x1c, HOMOGENEOUS_W, "camera mode 2 vector W", problems)

        val expectedRoutine = if (options.appliesCameraDistance || options.appliesCameraHeight) {
            patchedCopyRoutine(options)
        } else {
            originalCopyRoutine
        }
        validateCopyRoutine(data, expectedRoutine, "camera resource copier profile", problems)
    }

    internal fun patchedCopyRoutine(options: PatchOptions): IntArray {
        require(options.appliesCameraDistance || options.appliesCameraHeight)

        val words = mutableListOf(
            0x00801025,          // move v0,a0 -- preserve original destination for return/overrides
            0x3408001C,          // ori t0,zero,28 -- 0x70 bytes / 4
            0x8CA90000.toInt(),  // loop: lw t1,0(a1)
            0xAC890000.toInt(),  //       sw t1,0(a0)
            0x24A50004,          //       addiu a1,a1,4
            0x24840004,          //       addiu a0,a0,4
            0x2508FFFF,          //       addiu t0,t0,-1
            0x1500FFFA,          //       bnez t0,loop
            0x00000000,          //       nop
        )

        if (options.appliesCameraHeight) {
            words += loadWordIntoT0(options.cameraHeight.toBits())
            words += 0xAC480024.toInt() // sw t0,0x24(v0)
            words += 0xAC480054.toInt() // sw t0,0x54(v0)
        }
        if (options.appliesCameraDistance) {
            words += loadWordIntoT0((-options.cameraDistance).toBits())
            words += 0xAC480028.toInt() // sw t0,0x28(v0)
            words += 0xAC480058.toInt() // sw t0,0x58(v0)
        }

        words += 0x03E00008 // jr ra
        words += 0x00000000 // nop
        while (words.size < COPY_WORD_COUNT) words += 0x00000000
        check(words.size == COPY_WORD_COUNT)
        return words.toIntArray()
    }

    private fun loadWordIntoT0(value: Int): List<Int> = listOf(
        0x3C080000 or ((value ushr 16) and 0xffff), // lui t0,hi
        0x35080000 or (value and 0xffff),            // ori t0,t0,lo
    )

    private fun writeCopyRoutine(data: ByteArray, words: IntArray) {
        val offset = fileOffset(CAMERA_COPY_ROUTINE_VA)
        words.forEachIndexed { index, word ->
            data.writeIntLe(offset + index * 4, word)
        }
    }

    private fun validateCopyRoutine(
        data: ByteArray,
        expected: IntArray,
        description: String,
        problems: MutableList<String>,
    ) {
        val offset = fileOffset(CAMERA_COPY_ROUTINE_VA)
        if (offset < 0 || offset + CAMERA_COPY_ROUTINE_SIZE > data.size) {
            problems += "$description is outside the supported EBOOT."
            return
        }
        expected.forEachIndexed { index, word ->
            if (data.readIntLe(offset + index * 4) != word) {
                problems += "$description mismatch at VA 0x${(CAMERA_COPY_ROUTINE_VA + index * 4).toString(16)}."
                return
            }
        }
    }

    private fun validateInt(
        data: ByteArray,
        virtualAddress: Int,
        expected: Int,
        description: String,
        problems: MutableList<String>,
    ) {
        val offset = fileOffset(virtualAddress)
        if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != expected) {
            problems += "$description mismatch at VA 0x${virtualAddress.toString(16)}."
        }
    }

    private fun validateFloat(
        data: ByteArray,
        virtualAddress: Int,
        expected: Float,
        description: String,
        problems: MutableList<String>,
    ) {
        val offset = fileOffset(virtualAddress)
        if (offset < 0 || offset + 4 > data.size || abs(data.readFloatLe(offset) - expected) > EPSILON) {
            problems += "$description mismatch at VA 0x${virtualAddress.toString(16)}."
        }
    }

    private fun fileOffset(virtualAddress: Int): Int = virtualAddress - VA_FILE_DELTA
}
