package com.ragnarok93.bbsremix.patch

/**
 * Screen-anchor-preserving editor for UI geometry inside a BBS L2D asset.
 *
 * OpenKh documents the LY2 layout/node coordinate fields and SP2 group's
 * on-screen vertices, positive LY2 font-size bytes and SQ2 animation
 * translation keys (BaseX/Y and OffsetX/Y). SP2 UVs and RGBA, SQ2 frame
 * times/colors/rotations/scale curves and font style metadata are preserved.
 * Keeps LY2 top-level placement and parentless node anchors intact while
 * resizing geometry relative to them. Runtime-generated HUD geometry remains
 * experimental and needs visual in-game verification.
 */
internal object BbsL2dGeometry {
    data class Result(
        val bytes: ByteArray,
        val layoutFieldsChanged: Int,
        val nodeFieldsChanged: Int,
        val groupFieldsChanged: Int,
        val fontSizeFieldsChanged: Int,
        val animationPositionKeysChanged: Int,
    ) {
        val totalFieldsChanged: Int get() =
            layoutFieldsChanged + nodeFieldsChanged + groupFieldsChanged +
                fontSizeFieldsChanged + animationPositionKeysChanged
    }

    fun scale(source: ByteArray, percent: Int): Result {
        require(UiScaleSettings.isSelectable(percent)) {
            "L2D scale must be 70–100% in 5% increments."
        }
        require(source.size >= HEADER_SIZE) { "L2D header is truncated." }
        requireSignature(source, 0, "L2D@")
        val declaredSize = source.readIntLe(0x2c)
        require(declaredSize in HEADER_SIZE..source.size) {
            "Unexpected L2D header size: declared $declaredSize, allocated ${source.size}."
        }
        // Some genuine ARC entries include one extra, zero-filled 16-byte
        // trailer after the L2D's own length (e.g. bc01_00.l2d in BBS1).
        // Reject non-zero bytes and anything longer than the known single
        // alignment block, rather than trusting allocation padding as data.
        val tailSize = source.size - declaredSize
        require(tailSize == 0 || (tailSize == 0x10 &&
            (declaredSize and 0x0f) == 0 &&
            (declaredSize until source.size).all { source[it] == 0.toByte() })
        ) { "Unsupported L2D trailing bytes: $tailSize bytes." }
        // Every relative pointer, group, keyframe, etc. must stay within the
        // declared L2D size, not merely inside its containing ARC allocation.
        val output = source.copyOf(declaredSize)

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

        // A LY2 Layout's X/Y is the SCREEN-SPACE PLACEMENT of its subtree,
        // measured from the native 480x272 screen centre, not sprite geometry.
        // Examples from the actual game include (-240,-136), (-239,40),
        // (187,103), and (235,-130). Scaling these by 0.70 shifts the
        // entire widget 72 PSP pixels towards the centre and is exactly the
        // drift visible in user gameplay/menu screenshots.
        //
        // Keep the top-level placement unchanged. Scale only local node
        // translations, sprite-group vertices, fonts and SQ2 key offsets.
        // Root LY2 nodes (Parent IDX < 0) are placement anchors too: do NOT
        // move them. Child node coordinates remain relative and must shrink.
        val layouts = 0
        var nodes = 0
        for (i in 0 until nodeCount) {
            val item = nodeOffset + i * 0x20
            val parent = output.readShortLe(item + 0x14).toShort().toInt()
            if (parent < 0) continue
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
        var animatedPositions = 0
        val visitedAnimatedKeys = mutableSetOf<Int>()
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
            val animationCount = output.readIntLe(sq2 + 0x20)
            val animationOffset = relative(output, sq2, sq2 + 0x24, "SQ2 animations")
            requireRange(output, animationOffset, animationCount, 0x18, "SQ2 animations")
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

            // SQ2.Animation packs 11 per-kind key counts (status, BaseX/Y,
            // OffsetX/Y, RotateX/Y/Z, ScaleX/Y, Color). nOfsKeyData at +6
            // gives the first entry in the SQ2 key table; the counts start
            // at animation +8. See OpenKh's documented SQ2 format and the
            // supplied character/deck layout data.
            //
            // Scale *positional* floats, not SQ2 scale factors: the sprite
            // vertices are already scaled, so changing ScaleX/Y again would
            // double-shrink elements below the selected 70% floor.
            // Do not reinterpret color/status values as IEEE floats.
            for (animation in 0 until animationCount) {
                val anim = animationOffset + animation * 0x18
                var keyIndex = (output[anim + 6].toInt() and 0xff) or
                    ((output[anim + 7].toInt() and 0xff) shl 8)
                for (kind in 0..10) {
                    val numberOfKeys = output[anim + 8 + kind].toInt() and 0xff
                    for (key in 0 until numberOfKeys) {
                        // Some shipping files have unused animations with
                        // nOfsKeyData outside the declared key count. Skip
                        // those rather than reading into unrelated tables.
                        if (keyIndex < keyCount && kind in 1..4) {
                            val position = keyOffset + keyIndex * 0x0c + 4
                            if (visitedAnimatedKeys.add(position)) {
                                val original = Float.fromBits(output.readIntLe(position))
                                if (original.isFinite() &&
                                    original in -5000f..5000f &&
                                    original != 0f
                                ) {
                                    val scaled =
                                        (original.toDouble() * (percent.toDouble() / 100.0)).toFloat()
                                    if (scaled != original) {
                                        output.writeIntLe(position, scaled.toBits())
                                        animatedPositions++
                                    }
                                }
                            }
                        }
                        keyIndex++
                    }
                }
            }
        }
        val resultBytes = if (tailSize == 0) output else source.copyOf().also {
            output.copyInto(it, destinationOffset = 0)
        }
        if (percent == UiScaleSettings.STOCK_PERCENT) {
            check(resultBytes.contentEquals(source)) { "100% L2D scaling changed the stock input." }
        }
        return Result(resultBytes, layouts, nodes, groups, fonts, animatedPositions)
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
