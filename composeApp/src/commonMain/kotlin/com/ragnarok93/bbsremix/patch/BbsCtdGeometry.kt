package com.ragnarok93.bbsremix.patch

/**
 * Research-only CTD subtitle geometry editor, based on OpenKh.Bbs.Ctd.
 *
 * BBS CTD layout rows are 0x20 bytes. The 0x0c-byte message records and
 * dialog text are never modified. Style 5 is Subtitle, as defined by OpenKh.
 * The exported CT00000.ctd has 25 subtitle rows with alignment 2 (Center).
 *
 * IMPORTANT: This does not prove the game's renderer uses every CTD field
 * dynamically. Keep isolated from shipping ISO patches pending PPSSPP checks.
 */
internal object BbsCtdGeometry {
    private const val LAYOUT_BYTES = 0x20
    private const val MESSAGE_BYTES = 0x0c
    private const val SUBTITLE_STYLE = 5
    private const val CENTER_ALIGNMENT = 2

    data class Result(
        val bytes: ByteArray,
        val subtitleRowsChanged: Int,
        val fieldsChanged: Int,
    )

    fun scaleSubtitleLayouts(source: ByteArray, percent: Int): Result {
        require(UiScaleSettings.isSelectable(percent)) {
            "CTD subtitle scaling must be 70–100% in 5% steps."
        }
        require(source.size >= 0x20 &&
            source.copyOfRange(0, 4).contentEquals("@CTD".encodeToByteArray()) &&
            source.i32(4) == 1
        ) { "Unsupported CTD header." }
        val layoutCount = source.u16(0x0c)
        val messageCount = source.u16(0x0e)
        val messages = source.i32(0x10)
        val layouts = source.i32(0x14)
        val text = source.i32(0x18)
        requireRange(source, messages, messageCount, MESSAGE_BYTES, "CTD messages")
        requireRange(source, layouts, layoutCount, LAYOUT_BYTES, "CTD layouts")
        require(text in 0x20..source.size) { "CTD message text starts out of bounds." }
        require(messages >= 0x20 && layouts >= 0x20 &&
            nonOverlapping(messages, messageCount * MESSAGE_BYTES, layouts, layoutCount * LAYOUT_BYTES) &&
            text >= layouts + layoutCount * LAYOUT_BYTES &&
            text >= messages + messageCount * MESSAGE_BYTES
        ) { "CTD layout/message/text ranges overlap." }

        // Every message must address one valid layout and a bounded text
        // string. This validates independent files without decoding game text.
        for (i in 0 until messageCount) {
            val row = messages + i * MESSAGE_BYTES
            val contentOffset = source.i32(row + 4)
            val layoutIndex = source.u16(row + 8)
            require(layoutIndex < layoutCount && contentOffset in text until source.size) {
                "CTD message references an invalid layout or text address."
            }
            require((contentOffset until source.size).any { source[it] == 0.toByte() }) {
                "CTD message has no terminating NUL within its allocated file."
            }
        }

        val output = source.copyOf()
        var rows = 0
        var fields = 0
        for (i in 0 until layoutCount) {
            val base = layouts + i * LAYOUT_BYTES
            val style = source[base + 9].toInt() and 0xff
            if (style != SUBTITLE_STYLE) continue
            require((source[base + 8].toInt() and 0xff) == CENTER_ALIGNMENT) {
                "Unsupported subtitle alignment; only centered layouts are verified."
            }
            val oldX = source.u16(base)
            val oldY = source.u16(base + 2)
            val oldWidth = source.u16(base + 4)
            val oldHeight = source.u16(base + 6)
            require(oldWidth > 0 && oldHeight > 0 &&
                oldX + oldWidth <= 65535 && oldY + oldHeight <= 65535
            ) { "Invalid subtitle rectangle geometry." }
            val newWidth = rounded(oldWidth, percent).coerceAtLeast(1)
            val newHeight = rounded(oldHeight, percent).coerceAtLeast(1)
            // Preserve the box's original horizontal center and bottom line.
            val newX = oldX + (oldWidth - newWidth) / 2
            val newY = oldY + oldHeight - newHeight
            val rowFields = arrayOf(
                0 to newX,
                2 to newY,
                4 to newWidth,
                6 to newHeight,
                0x0c to rounded(source.u16(base + 0x0c), percent).coerceAtLeast(1),
                0x0e to rounded(source.u16(base + 0x0e), percent),
                0x10 to rounded(source.u16(base + 0x10), percent),
                0x12 to rounded(source.u16(base + 0x12), percent),
                0x14 to rounded(source.u16(base + 0x14), percent),
                0x16 to rounded(source.u16(base + 0x16), percent),
            )
            var changes = 0
            for ((delta, value) in rowFields) {
                require(value in 0..0xffff) { "Subtitle geometry exceeds unsigned 16-bit range." }
                val before = source.u16(base + delta)
                if (value != before) {
                    output.w16(base + delta, value)
                    fields++
                    changes++
                }
            }
            if (changes != 0) rows++
        }
        if (percent == 100) {
            check(source.contentEquals(output)) { "Stock CTD scaling must preserve every byte." }
        }
        return Result(output, rows, fields)
    }

    private fun rounded(value: Int, percent: Int): Int = (value * percent + 50) / 100

    private fun nonOverlapping(a: Int, aBytes: Int, b: Int, bBytes: Int): Boolean =
        aBytes == 0 || bBytes == 0 || a + aBytes <= b || b + bBytes <= a

    private fun requireRange(data: ByteArray, offset: Int, count: Int, stride: Int, label: String) {
        require(offset >= 0 && offset <= data.size && count >= 0 &&
            count <= (data.size - offset) / stride
        ) { "$label exceeds the bounded CTD resource." }
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    private fun ByteArray.i32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)

    private fun ByteArray.w16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
}
