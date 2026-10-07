package com.ragnarok93.bbsremix.patch

import kotlin.math.abs

/**
 * PSP-native camera geometry patch for the exact supported English-patched
 * ULJM-05775 MainApp.
 *
 * Static analysis of the resident player-camera parameter table at 0x08B59F00
 * shows two 0x30-byte camera-mode records selected by the native camera state
 * machine. Their first homogeneous position vector begins at record +0x10:
 *
 *   mode 1: [0.0, 1.5, -3.5, 1.0]
 *   mode 2: [0.0, 1.0, -3.5, 1.0]
 *
 * MainApp passes those vectors directly into its native camera transform setup
 * (0x0893DF48 / 0x0893E024 -> 0x08AE0AF0). Therefore Y is the native height
 * component and Z is the native signed camera distance. The user-facing
 * distance remains positive and is written as -distance to the PSP table.
 *
 * This patch changes resident .data only. It adds no code, does not touch the
 * dynamic overlay arena, and leaves ELF program headers/load sizes unchanged.
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

    private const val MODE1_ID = 1
    private const val MODE2_ID = 2
    private const val HOMOGENEOUS_W = 1.0f
    private const val EPSILON = 0.000001f
    private const val VA_FILE_DELTA = 0x08803000

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
