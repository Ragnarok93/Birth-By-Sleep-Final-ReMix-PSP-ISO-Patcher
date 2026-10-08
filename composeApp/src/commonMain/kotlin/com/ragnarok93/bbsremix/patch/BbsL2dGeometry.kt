package com.ragnarok93.bbsremix.patch

/**
 * Research-stage editor for the static geometry inside a BBS L2D asset.
 *
 * OpenKh documents the LY2 layout/node coordinate fields and SP2 group's
 * on-screen vertices, and documented LY2 font-size bytes. SP2 texture
 * coordinates, RGBA, animation control data and font style metadata remain
 * unchanged. This is an experimental test build: runtime-generated gauges,
 * SQ2 animated translation/scale and in-game HUD anchoring remain unverified.
 */
internal object BbsL2dGeometry {
    data class Result(
        val bytes: ByteArray,
        val layoutFieldsChanged: Int,
        val nodeFieldsChanged: Int,
        val groupFieldsChanged: Int,
        val fontSizeFieldsChanged: Int,
    ) {
        val totalFieldsChanged: Int get() =
            layoutFieldsChanged + nodeFieldsChanged + groupFieldsChanged + fontSizeFieldsChanged
    }

    fun scale(source: ByteArray, percent: Int): Result {
        require(UiScaleSettings.isSelectable(percent)) {
            "L2D scale must be 70–100% in 5% increments."
        }
        val output = source.copyOf()
        requireSignature(output, 0, "L2D@")
        require(output.size >= HEADER_SIZE && output.readIntLe(0x2c) == output.size) {
            "Unexpected L2D header size."
        }

        val ly2 = output.readIntLe(0x28)
        requireRange(output, ly2, 1, 0x40, "LY2 header")
        requireSignature(output, ly2, "LY2@")
        val layoutCount = output.readIntLe(ly2 + 0x10)
        val layoutOffset = relative(output, ly2, ly2 + 0x14, "LY2 layouts")
        val nodeCount = output.readIntLe(ly2 + 0x20)
        val nodeOffset = relative(output, ly2, ly2 + 0x24, "LY2 nodes")
        val fontCount = output.readIntLe(ly2 + 0x28)
        val fontOffset = relative(output, ly2, ly2 + 0x2c, "LY2 fonts")
        requireRange(output, layoutOffset, layoutCount, 0x10, "LY2 layouts")
        requireRange(output, nodeOffset, nodeCount, 0x20, "LY2 nodes")
        requireRange(output, fontOffset, fontCount, 0x10, "LY2 fonts")

        var layouts = 0
        for (i in 0 until layoutCount) {
            val item = layoutOffset + i * 0x10
            if (scaleInt16(output, item + 0x0c, percent)) layouts++
            if (scaleInt16(output, item + 0x0e, percent)) layouts++
        }
        var nodes = 0
        for (i in 0 until nodeCount) {
            val item = nodeOffset + i * 0x20
            if (scaleInt16(output, item + 0x16, percent)) nodes++
            if (scaleInt16(output, item + 0x18, percent)) nodes++
        }

        // L2D's SQ2P offset points to an array of relative SQ2P pointers,
        // not directly to the first SQ2P structure.
        val sequenceSetCount = output.readIntLe(0x20)
        val sequenceTable = output.readIntLe(0x24)
        requireRange(output, sequenceTable, sequenceSetCount, 4, "SQ2P pointer table")
        // LY2 Font Info stores an int8 size at byte 8 in each 0x10 record.
        // Preserve 0 (unassigned) and negative special values; scale only
        // documented positive font sizes, leaving colors/center/type/IDs intact.
        var fonts = 0
        for (i in 0 until fontCount) {
            val sizeOffset = fontOffset + i * 0x10 + 0x08
            val old = output[sizeOffset].toInt()
            if (old <= 0) continue
            val updated = ((old * percent + 50) / 100).coerceAtLeast(1)
            if (old != updated) {
                output[sizeOffset] = updated.toByte()
                fonts++
            }
        }

        var groups = 0
        for (i in 0 until sequenceSetCount) {
            val sq2p = relative(output, sequenceTable, sequenceTable + i * 4, "SQ2P pointer")
            requireRange(output, sq2p, 1, 0x40, "SQ2P header")
            requireSignature(output, sq2p, "SQ2P")
            val sp2 = relative(output, sq2p, sq2p + 0x10, "SP2 pointer")
            val sq2 = relative(output, sq2p, sq2p + 0x14, "SQ2 pointer")
            requireRange(output, sp2, 1, 0x40, "SP2 header")
            requireRange(output, sq2, 1, 0x40, "SQ2 header")
            requireSignature(output, sp2, "SP2@")
            requireSignature(output, sq2, "SQ2@")

            // Validate the unmodified UV/texture parts and sprite-table extents.
            val partCount = output.readIntLe(sp2 + 0x10)
            val partOffset = relative(output, sp2, sp2 + 0x14, "SP2 parts")
            requireRange(output, partOffset, partCount, 0x18, "SP2 parts")
            val spriteCount = output.readIntLe(sp2 + 0x20)
            val spriteOffset = relative(output, sp2, sp2 + 0x24, "SP2 sprites")
            requireRange(output, spriteOffset, spriteCount, 4, "SP2 sprites")

            val groupCount = output.readIntLe(sp2 + 0x18)
            val groupOffset = relative(output, sp2, sp2 + 0x1c, "SP2 groups")
            requireRange(output, groupOffset, groupCount, 0x0c, "SP2 groups")
            val keyCount = output.readIntLe(sq2 + 0x28)
            val keyOffset = relative(output, sq2, sq2 + 0x2c, "SQ2 keys")
            requireRange(output, keyOffset, keyCount, 0x0c, "SQ2 keys")
            val groupEnd = groupOffset + groupCount * 0x0c
            fun disjoint(otherOffset: Int, otherSize: Int): Boolean =
                groupCount == 0 || otherSize == 0 ||
                    groupEnd <= otherOffset || otherOffset + otherSize <= groupOffset
            require(
                disjoint(partOffset, partCount * 0x18) &&
                    disjoint(spriteOffset, spriteCount * 4) &&
                    disjoint(keyOffset, keyCount * 0x0c) &&
                    disjoint(sp2, 0x40) && disjoint(sq2, 0x40) &&
                    disjoint(layoutOffset, layoutCount * 0x10) &&
                    disjoint(nodeOffset, nodeCount * 0x20) &&
                    disjoint(fontOffset, fontCount * 0x10)
            ) { "SP2 on-screen geometry overlaps texture, animation or layout metadata." }
            for (j in 0 until groupCount) {
                val item = groupOffset + j * 0x0c
                for (field in 0..3) {
                    if (scaleInt16(output, item + field * 2, percent)) groups++
                }
            }
        }
        if (percent == UiScaleSettings.STOCK_PERCENT) {
            check(output.contentEquals(source)) { "100% L2D scaling changed the stock input." }
        }
        return Result(output, layouts, nodes, groups, fonts)
    }

    private fun requireSignature(bytes: ByteArray, offset: Int, magic: String) {
        require(offset >= 0 && offset <= bytes.size - magic.length &&
            magic.indices.all { bytes[offset + it] == magic[it].code.toByte() }
        ) { "Expected $magic at 0x${offset.toString(16)}." }
    }

    private fun relative(bytes: ByteArray, base: Int, field: Int, description: String): Int {
        requireRange(bytes, field, 1, 4, description)
        val delta = bytes.readIntLe(field)
        require(base >= 0 && delta >= 0 && base <= bytes.size && delta <= bytes.size - base) {
            "$description points outside its L2D."
        }
        return base + delta
    }

    private fun requireRange(bytes: ByteArray, offset: Int, count: Int, stride: Int, description: String) {
        require(offset >= 0 && offset <= bytes.size && count >= 0 && stride > 0 &&
            count <= (bytes.size - offset) / stride
        ) { "$description exceeds the bounded L2D asset." }
    }

    private fun scaleInt16(bytes: ByteArray, offset: Int, percent: Int): Boolean {
        val old = bytes.readShortLe(offset).toShort().toInt()
        val rounded = (kotlin.math.abs(old) * percent + 50) / 100
        val updated = if (old < 0) -rounded else rounded
        require(updated in Short.MIN_VALUE..Short.MAX_VALUE)
        if (old == updated) return false
        bytes.writeShortLe(offset, updated)
        return true
    }

    private const val HEADER_SIZE = 0x40
}
