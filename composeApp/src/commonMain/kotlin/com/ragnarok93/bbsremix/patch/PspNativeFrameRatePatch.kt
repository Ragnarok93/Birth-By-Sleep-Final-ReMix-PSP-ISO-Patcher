package com.ragnarok93.bbsremix.patch

/**
 * Resident frame-rate selector for the exact supported English-patched
 * ULJM-05775 MainApp.
 *
 * The public 60 FPS CWCheat forces runtime flag 0 at 0x09F25EC8. MainApp's
 * resident setter at 0x088074B0 owns that flag and the timing scalar at
 * 0x08B41870:
 *
 *   mode 1 -> scalar 2.0f
 *   mode 0 -> scalar 1.0f
 *
 * The 60 FPS profile forces the native mode-0 path and its 1.0f timing scalar.
 * This is intentionally an in-place resident patch: no overlay allocation,
 * additional ELF segment, or BSS file write is required.
 */
internal object PspNativeFrameRatePatch {
    const val SETTER_VA = 0x088074B0
    const val FORCE_MODE_BRANCH_VA = 0x088074BC
    const val SCALAR_LOAD_VA = 0x088074D8
    const val RUNTIME_MODE_VA = 0x09F25EC8
    const val RUNTIME_SCALAR_VA = 0x08B41870

    private const val VA_FILE_DELTA = 0x08803000

    private val originalWords = linkedMapOf(
        0x088074B0 to 0x3C0609F2,
        0x088074B4 to 0x8CC25EC8.toInt(),
        0x088074B8 to 0x34070001,
        0x088074BC to 0x14870006,
        0x088074C0 to 0x3C0508B4,
        0x088074C4 to 0x3C074000,
        0x088074C8 to 0x44876000,
        0x088074CC to 0xE4AC1870.toInt(),
        0x088074D0 to 0x03E00008,
        0x088074D4 to 0xACC45EC8.toInt(),
        0x088074D8 to 0x3C043F80,
        0x088074DC to 0x44846000,
        0x088074E0 to 0x34040000,
        0x088074E4 to 0xE4AC1870.toInt(),
        0x088074E8 to 0x03E00008,
        0x088074EC to 0xACC45EC8.toInt(),
    )

    fun validateSource(data: ByteArray, problems: MutableList<String>) {
        originalWords.forEach { (virtualAddress, expected) ->
            val offset = fileOffset(virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != expected) {
                val found = if (offset >= 0 && offset + 4 <= data.size) {
                    "0x" + data.readIntLe(offset).toUInt().toString(16)
                } else {
                    "out-of-range"
                }
                problems += "Frame-rate source mismatch at VA 0x${virtualAddress.toString(16)} (got $found)."
            }
        }
    }

    fun apply(data: ByteArray, targetFps: Int) {
        require(targetFps == 60) { "Unsupported patched FPS target: $targetFps" }

        // Always take the native mode-0/60-FPS branch.
        data.writeIntLe(fileOffset(FORCE_MODE_BRANCH_VA), 0x10000006)

        val scalarBits = 1.0f.toBits()
        data.writeIntLe(fileOffset(0x088074D8), 0x3C040000 or ((scalarBits ushr 16) and 0xffff))
        data.writeIntLe(fileOffset(0x088074DC), 0x34840000 or (scalarBits and 0xffff))
        data.writeIntLe(fileOffset(0x088074E0), 0x44846000)
        data.writeIntLe(fileOffset(0x088074E4), 0xE4AC1870.toInt())
        data.writeIntLe(fileOffset(0x088074E8), 0x03E00008)
        data.writeIntLe(fileOffset(0x088074EC), 0xACC05EC8.toInt())
    }

    fun verifyPatched(data: ByteArray, targetFps: Int, problems: MutableList<String>) {
        if (targetFps == 30) {
            originalWords.forEach { (virtualAddress, expected) ->
                val offset = fileOffset(virtualAddress)
                if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != expected) {
                    problems += "The output contains an unselected frame-rate patch at VA 0x${virtualAddress.toString(16)}."
                    return
                }
            }
            return
        }

        if (targetFps != 60) {
            problems += "Unsupported FPS target $targetFps."
            return
        }

        val scalarBits = 1.0f.toBits()
        val expected = linkedMapOf(
            0x088074BC to 0x10000006,
            0x088074D8 to (0x3C040000 or ((scalarBits ushr 16) and 0xffff)),
            0x088074DC to (0x34840000 or (scalarBits and 0xffff)),
            0x088074E0 to 0x44846000,
            0x088074E4 to 0xE4AC1870.toInt(),
            0x088074E8 to 0x03E00008,
            0x088074EC to 0xACC05EC8.toInt(),
        )

        expected.forEach { (virtualAddress, word) ->
            val offset = fileOffset(virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != word) {
                problems += "The $targetFps FPS patch is missing or altered at VA 0x${virtualAddress.toString(16)}."
            }
        }

        // Instructions outside the rewritten mode-0 tail stay native.
        for (virtualAddress in listOf(
            0x088074B0, 0x088074B4, 0x088074B8, 0x088074C0,
            0x088074C4, 0x088074C8, 0x088074CC, 0x088074D0, 0x088074D4,
        )) {
            val expectedWord = originalWords.getValue(virtualAddress)
            val offset = fileOffset(virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != expectedWord) {
                problems += "Frame-rate patch unexpectedly changed native instruction at VA 0x${virtualAddress.toString(16)}."
            }
        }
    }

    internal const val TIMING_SCALAR_60 = 1.0f

    internal fun originalWord(virtualAddress: Int): Int = originalWords.getValue(virtualAddress)

    private fun fileOffset(virtualAddress: Int): Int = virtualAddress - VA_FILE_DELTA
}
