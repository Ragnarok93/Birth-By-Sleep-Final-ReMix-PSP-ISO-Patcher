package com.ragnarok93.bbsremix.patch

/**
 * Resident, in-place right-stick camera patch for the exact supported
 * English-patched ULJM-05775 EBOOT.
 *
 * This intentionally does not allocate code in MainApp's .overlays arena and
 * does not add/extend an ELF LOAD segment. PPSSPP exposes the second analog
 * stick in SceCtrlData bytes 10/11 (analog[1][0/1], historically Rsrv[0/1]).
 * MainApp's existing input update captures the final sample into two otherwise
 * unreferenced padding bytes. The captured pair has bit 7 flipped on each axis,
 * producing signed-centered bytes; the tagged camera getters signed-load and
 * negate them so stick direction matches camera direction. Only native camera
 * axis call sites use the magic selector.
 */
internal object PspNativeRightStickPatch {
    const val OVERLAY_ARENA_VA = 0x08B6EE7C
    const val RIGHT_STICK_MAGIC = 0x5253
    const val RIGHT_STICK_X_BYTE_VA = 0x08B4199A
    const val RIGHT_STICK_Y_BYTE_VA = 0x08B4199B
    const val RIGHT_STICK_SELECTOR = 0x34045253

    data class WordPatch(
        val virtualAddress: Int,
        val expected: Int,
        val replacement: Int,
        val description: String,
    )

    data class BytePatch(
        val virtualAddress: Int,
        val expected: Int,
        val replacement: Int,
        val description: String,
    )

    data class RequiredWord(
        val virtualAddress: Int,
        val expected: Int,
        val description: String,
    )

    val wordPatches = listOf(
        // Raw X getter. Untagged callers branch directly to the original
        // left-X getter at 0x088164D0, preserving native behavior.
        WordPatch(0x088162F8, 0x27BDFFF0, 0x34035253, "right-X selector magic"),
        WordPatch(0x088162FC, 0xAFBF0000.toInt(), 0x14830074, "right-X default branch"),
        WordPatch(0x08816300, 0x0E205934, 0x3C0208B4, "right-X resident base"),
        WordPatch(0x08816304, 0x00000000, 0x8042199A.toInt(), "right-X signed captured byte"),
        WordPatch(0x08816308, 0x8FBF0000.toInt(), 0x03E00008, "right-X return"),
        WordPatch(0x0881630C, 0x03E00008, 0x00021023, "right-X invert in delay slot"),
        WordPatch(0x08816310, 0x27BD0010, 0x00000000, "right-X tail padding"),

        // Raw Y getter. Untagged callers branch directly to the original
        // left-Y getter at 0x088164F8.
        WordPatch(0x08816314, 0x27BDFFF0, 0x34035253, "right-Y selector magic"),
        WordPatch(0x08816318, 0xAFBF0000.toInt(), 0x14830077, "right-Y default branch"),
        WordPatch(0x0881631C, 0x0E20593E, 0x3C0208B4, "right-Y resident base"),
        WordPatch(0x08816320, 0x00000000, 0x8042199B.toInt(), "right-Y signed captured byte"),
        WordPatch(0x08816324, 0x8FBF0000.toInt(), 0x03E00008, "right-Y return"),
        WordPatch(0x08816328, 0x03E00008, 0x00000000, "right-Y preserve centered polarity"),
        WordPatch(0x0881632C, 0x27BD0010, 0x00000000, "right-Y tail padding"),

        // MainApp input update. $t2 points one record past the final 16-byte
        // CtrlData sample when the loop exits, so -6 is a naturally aligned
        // halfword containing analog[1][0/1]. XOR 0x8080 maps each unsigned
        // 0..255 axis to a signed-centered byte without adding instructions.
        // Left analog remains stored at 0x08B41980/84.
        WordPatch(0x0881683C, 0x3C0408B4, 0x9545FFFA.toInt(), "capture right-stick XY halfword"),
        WordPatch(0x08816840, 0x3C0508B4, 0x38A58080, "center right-stick XY bytes"),
        WordPatch(0x08816848, 0xACA71970.toInt(), 0xAC871970.toInt(), "reuse resident input-state base"),
        WordPatch(0x0881684C, 0x3C0408B4, 0xA485199A.toInt(), "store centered right-stick XY"),

        // RemasteredControls-equivalent camera behavior.
        WordPatch(0x08940FEC, 0x508000BA, 0x00000000, "remove L modifier from camera"),
        WordPatch(0x0898F68C, 0x1C80000B, 0x00000000, "force Type-B horizontal camera"),
        WordPatch(0x0898F850, 0x1C80000B, 0x00000000, "force Type-B vertical camera"),

        // Keep the original camera JALs. Their delay slots tag only these
        // four calls as right-stick reads.
        WordPatch(0x0898F6A0, 0x00000000, RIGHT_STICK_SELECTOR, "right-stick X selector 1"),
        WordPatch(0x0898F6E0, 0x00000000, RIGHT_STICK_SELECTOR, "right-stick X selector 2"),
        WordPatch(0x0898F864, 0x00000000, RIGHT_STICK_SELECTOR, "right-stick Y selector 1"),
        WordPatch(0x0898F8A4, 0x00000000, RIGHT_STICK_SELECTOR, "right-stick Y selector 2"),
    )

    val bytePatches = emptyList<BytePatch>()

    val requiredUnchangedWords = listOf(
        RequiredWord(0x08816688, 0x0E2C5B4E, "native controller poll call"),
        RequiredWord(0x0898F69C, 0x0E2058CC, "native horizontal analog getter call 1"),
        RequiredWord(0x0898F6DC, 0x0E2058CC, "native horizontal analog getter call 2"),
        RequiredWord(0x0898F860, 0x0E2058D8, "native vertical analog getter call 1"),
        RequiredWord(0x0898F8A0, 0x0E2058D8, "native vertical analog getter call 2"),
    )

    fun validateSource(data: ByteArray, problems: MutableList<String>) {
        wordPatches.forEach { patch ->
            val offset = fileOffset(patch.virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != patch.expected) {
                val found = if (offset >= 0 && offset + 4 <= data.size) {
                    "0x" + data.readIntLe(offset).toUInt().toString(16)
                } else {
                    "out-of-range"
                }
                problems += "${patch.description} mismatch at VA 0x${patch.virtualAddress.toString(16)} (got $found)."
            }
        }
        bytePatches.forEach { patch ->
            val offset = fileOffset(patch.virtualAddress)
            if (offset !in data.indices || (data[offset].toInt() and 0xff) != patch.expected) {
                problems += "${patch.description} mismatch at VA 0x${patch.virtualAddress.toString(16)}."
            }
        }
        requiredUnchangedWords.forEach { required ->
            val offset = fileOffset(required.virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != required.expected) {
                problems += "${required.description} no longer matches the reference EBOOT."
            }
        }
        if (wordPatches.any { it.virtualAddress >= OVERLAY_ARENA_VA } ||
            bytePatches.any { it.virtualAddress >= OVERLAY_ARENA_VA }
        ) {
            problems += "Right-stick patch attempts to write inside MainApp's dynamic overlay arena."
        }
    }

    fun apply(data: ByteArray) {
        wordPatches.forEach { patch ->
            data.writeIntLe(fileOffset(patch.virtualAddress), patch.replacement)
        }
        bytePatches.forEach { patch ->
            data[fileOffset(patch.virtualAddress)] = patch.replacement.toByte()
        }
    }

    fun verifyPatched(data: ByteArray, problems: MutableList<String>) {
        wordPatches.forEach { patch ->
            val offset = fileOffset(patch.virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != patch.replacement) {
                problems += "${patch.description} is missing or altered."
            }
        }
        bytePatches.forEach { patch ->
            val offset = fileOffset(patch.virtualAddress)
            if (offset !in data.indices || (data[offset].toInt() and 0xff) != patch.replacement) {
                problems += "${patch.description} is missing or altered."
            }
        }
        requiredUnchangedWords.forEach { required ->
            val offset = fileOffset(required.virtualAddress)
            if (offset < 0 || offset + 4 > data.size || data.readIntLe(offset) != required.expected) {
                problems += "${required.description} was unexpectedly replaced."
            }
        }
    }

    private fun fileOffset(virtualAddress: Int): Int = virtualAddress - VA_FILE_DELTA

    private const val VA_FILE_DELTA = 0x08803000
}
