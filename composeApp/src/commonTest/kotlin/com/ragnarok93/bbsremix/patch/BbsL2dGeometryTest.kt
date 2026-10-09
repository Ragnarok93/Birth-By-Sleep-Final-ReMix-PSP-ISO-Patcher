package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BbsL2dGeometryTest {
    private fun fixture(): ByteArray = ByteArray(0x300).apply {
        fun signature(offset: Int, value: String) {
            value.forEachIndexed { i, ch -> this[offset + i] = ch.code.toByte() }
        }
        signature(0x00, "L2D@")
        writeIntLe(0x20, 1)       // sequence-set count
        writeIntLe(0x24, 0x40)    // SQ2P pointer table
        writeIntLe(0x28, 0x200)   // LY2
        writeIntLe(0x2c, size)
        writeIntLe(0x40, 0x10)    // SQ2P header at 0x50
        signature(0x50, "SQ2P")
        writeIntLe(0x60, 0x40)    // SP2 at 0x90
        writeIntLe(0x64, 0xc0)    // SQ2 at 0x110
        signature(0x90, "SP2@")
        writeIntLe(0xa0, 1)       // parts
        writeIntLe(0xa4, 0x50)    // parts at 0xe0
        writeIntLe(0xa8, 1)       // group count
        writeIntLe(0xac, 0x40)    // group at 0xd0
        writeIntLe(0xb0, 1)       // sprite count
        writeIntLe(0xb4, 0x68)    // sprite at 0xf8
        writeShortLe(0xf8, 1)    // the sprite owns one group
        writeShortLe(0xfa, 0)    // first group index
        writeShortLe(0xd0, -200)
        writeShortLe(0xd2, -80)
        writeShortLe(0xd4, 100)
        writeShortLe(0xd6, 60)
        // UVs, color, group flags and sprite indices must remain untouched.
        writeShortLe(0xe0, 18)
        writeShortLe(0xe2, 26)
        writeShortLe(0xe4, 178)
        writeShortLe(0xe6, 226)
        signature(0x110, "SQ2@")
        writeIntLe(0x130, 1)     // one SQ2 animation
        writeIntLe(0x134, 0x40)  // animation at 0x150
        writeIntLe(0x138, 2)     // two SQ2 keys
        writeIntLe(0x13c, 0x60)  // keys at 0x170
        writeShortLe(0x156, 0)   // first key index
        this[0x159] = 1          // BaseX: one key
        this[0x15a] = 1          // BaseY: one key
        writeIntLe(0x174, 80f.toBits())
        writeIntLe(0x180, (-50f).toBits())
        signature(0x200, "LY2@")
        writeIntLe(0x210, 1)      // layout count
        writeIntLe(0x214, 0x40)   // layout at 0x240
        writeIntLe(0x220, 1)      // node count
        writeIntLe(0x224, 0x50)   // node at 0x250
        writeIntLe(0x228, 1)      // one font-info entry
        writeIntLe(0x22c, 0x70)   // font-info at 0x270
        this[0x278] = 18         // positive font size at record offset 0x08
        this[0x279] = 2          // kind preserved
        this[0x27a] = 1          // centered flag preserved
        writeShortLe(0x24c, -240)
        writeShortLe(0x24e, -135)
        writeShortLe(0x264, -1)   // parent IDX
        writeShortLe(0x266, 60)
        writeShortLe(0x268, -20)
    }

    @Test
    fun stock_scale_is_exact_byte_identity() {
        val source = fixture()
        val result = BbsL2dGeometry.scale(source, 100)
        assertContentEquals(source, result.bytes)
        assertEquals(0, result.totalFieldsChanged)
    }

    @Test
    fun geometry_shrinks_but_all_texture_uv_and_metadata_bytes_are_preserved() {
        val source = fixture()
        val result = BbsL2dGeometry.scale(source, 85)
        assertEquals(source.size, result.bytes.size)
        assertEquals(-170, result.bytes.readShortLe(0xd0).toShort().toInt())
        assertEquals(-68, result.bytes.readShortLe(0xd2).toShort().toInt())
        assertEquals(85, result.bytes.readShortLe(0xd4).toShort().toInt())
        assertEquals(51, result.bytes.readShortLe(0xd6).toShort().toInt())
        // Screen-space layout and root-node anchors remain at stock XY.
        assertEquals(-240, result.bytes.readShortLe(0x24c).toShort().toInt())
        assertEquals(-135, result.bytes.readShortLe(0x24e).toShort().toInt())
        assertEquals(60, result.bytes.readShortLe(0x266).toShort().toInt())
        assertEquals(-20, result.bytes.readShortLe(0x268).toShort().toInt())
        assertContentEquals(source.copyOfRange(0xe0, 0xf8), result.bytes.copyOfRange(0xe0, 0xf8))
        assertContentEquals(source.copyOfRange(0x110, 0x150), result.bytes.copyOfRange(0x110, 0x150))
        assertContentEquals(source.copyOfRange(0x150, 0x170), result.bytes.copyOfRange(0x150, 0x170))
        assertEquals(68f.toBits(), result.bytes.readIntLe(0x174))
        assertEquals((-42.5f).toBits(), result.bytes.readIntLe(0x180))
        assertEquals(2, result.animationPositionKeysChanged)
        assertEquals(0, result.layoutFieldsChanged)
        assertEquals(0, result.nodeFieldsChanged)
        assertEquals(4, result.groupFieldsChanged)
        assertEquals(1, result.fontSizeFieldsChanged)
        assertEquals(15, result.bytes[0x278].toInt() and 0xff)
        assertEquals(2, result.bytes[0x279].toInt() and 0xff)
        assertEquals(1, result.bytes[0x27a].toInt() and 0xff)
        assertContentEquals(source, fixture()) // source untouched
    }

    @Test
    fun native_psp_screen_edge_anchors_are_preserved_at_every_supported_scale() {
        val bare = fixture()
        for (percent in 70..100 step 5) {
            val scaled = BbsL2dGeometry.scale(bare, percent)
            assertContentEquals(
                bare.copyOfRange(0x24c, 0x250),
                scaled.bytes.copyOfRange(0x24c, 0x250),
            )
            assertContentEquals(
                bare.copyOfRange(0x266, 0x26a),
                scaled.bytes.copyOfRange(0x266, 0x26a),
            )
        }
        val rightTop = fixture().apply {
            writeShortLe(0x24c, 235)
            writeShortLe(0x24e, -136)
        }
        val rightScaled = BbsL2dGeometry.scale(rightTop, 70)
        assertEquals(235, rightScaled.bytes.readShortLe(0x24c).toShort().toInt())
        assertEquals(-136, rightScaled.bytes.readShortLe(0x24e).toShort().toInt())
    }

    @Test
    fun a_child_node_shrinks_relative_to_its_unchanged_parent_anchor() {
        val source = fixture().apply {
            // Make room for a second, parented node; move font info clear of it.
            writeIntLe(0x22c, 0xa0)
            copyInto(this, destinationOffset = 0x2a0, startIndex = 0x270, endIndex = 0x280)
            writeIntLe(0x220, 2)
            writeShortLe(0x284, 0) // parent node index 0
            writeShortLe(0x286, 40)
            writeShortLe(0x288, -30)
        }
        val scaled = BbsL2dGeometry.scale(source, 70)
        // Root placement remains anchored.
        assertEquals(60, scaled.bytes.readShortLe(0x266).toShort().toInt())
        assertEquals(-20, scaled.bytes.readShortLe(0x268).toShort().toInt())
        // Child offset contracts relative to the root.
        assertEquals(28, scaled.bytes.readShortLe(0x286).toShort().toInt())
        assertEquals(-21, scaled.bytes.readShortLe(0x288).toShort().toInt())
        assertEquals(2, scaled.nodeFieldsChanged)
        assertEquals(13, scaled.bytes[0x2a8].toInt() and 0xff) // font 18 -> 13
    }

    @Test
    fun sq2_animated_translation_is_scaled_once_and_time_and_sprite_scale_are_not_modified() {
        val original = fixture()
        val result = BbsL2dGeometry.scale(original, 70)
        assertEquals(56f.toBits(), result.bytes.readIntLe(0x174))
        assertEquals((-35f).toBits(), result.bytes.readIntLe(0x180))
        assertEquals(2, result.animationPositionKeysChanged)
        assertContentEquals(original.copyOfRange(0x150, 0x170),
            result.bytes.copyOfRange(0x150, 0x170))
        assertContentEquals(original.copyOfRange(0x170, 0x174),
            result.bytes.copyOfRange(0x170, 0x174))
        assertContentEquals(original.copyOfRange(0x17c, 0x180),
            result.bytes.copyOfRange(0x17c, 0x180))
        assertContentEquals(original, fixture())
    }

    @Test
    fun font_info_scaling_is_bounded_and_does_not_modify_style_or_texture_bytes() {
        val baseline = fixture()
        for (percent in 70..100 step 5) {
            val scaled = BbsL2dGeometry.scale(baseline, percent)
            val expected = (18 * percent + 50) / 100
            assertEquals(expected, scaled.bytes[0x278].toInt() and 0xff)
            assertEquals(2, scaled.bytes[0x279].toInt() and 0xff)
            assertEquals(1, scaled.bytes[0x27a].toInt() and 0xff)
            assertContentEquals(baseline.copyOfRange(0xe0, 0xf8),
                scaled.bytes.copyOfRange(0xe0, 0xf8))
        }
        val noSize = baseline.copyOf().apply { this[0x278] = 0 }
        val zero = BbsL2dGeometry.scale(noSize, 70)
        assertEquals(0, zero.fontSizeFieldsChanged)
        assertEquals(0, zero.bytes[0x278].toInt() and 0xff)
    }

    @Test
    fun exact_single_block_zero_padding_is_preserved_when_scaling() {
        val bare = fixture()
        val arcEntry = bare.copyOf(bare.size + 0x10)
        assertEquals(0x300, arcEntry.readIntLe(0x2c))
        assertEquals(0x310, arcEntry.size)

        val stock = BbsL2dGeometry.scale(arcEntry, 100)
        assertContentEquals(arcEntry, stock.bytes)
        assertEquals(0, stock.totalFieldsChanged)

        for (scale in 70..95 step 5) {
            val padded = BbsL2dGeometry.scale(arcEntry, scale)
            val expected = BbsL2dGeometry.scale(bare, scale)
            assertEquals(arcEntry.size, padded.bytes.size)
            assertContentEquals(expected.bytes,
                padded.bytes.copyOfRange(0, bare.size))
            assertContentEquals(ByteArray(0x10),
                padded.bytes.copyOfRange(bare.size, arcEntry.size))
            assertEquals(expected.totalFieldsChanged, padded.totalFieldsChanged)
        }
        assertContentEquals(bare, fixture())
    }

    @Test
    fun rejects_nonzero_or_oversized_arc_padding_and_declared_size_overflows() {
        val bare = fixture()
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(bare.copyOf(bare.size + 0x10).apply {
                this[lastIndex] = 1
            }, 70)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(bare.copyOf(bare.size + 0x20), 70)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(bare.copyOf().apply {
                writeIntLe(0x2c, size + 0x10)
            }, 70)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(bare.copyOf().apply {
                writeIntLe(0x2c, 0x30)
            }, 70)
        }
    }

    @Test
    fun rejects_out_of_range_unexpected_signatures_and_invalid_offsets() {
        val source = fixture()
        for (percent in listOf(0, 69, 71, 99, 101, 120)) {
            assertFailsWith<IllegalArgumentException> { BbsL2dGeometry.scale(source, percent) }
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(source.copyOf().apply { this[0] = 0 }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(source.copyOf().apply { writeIntLe(0x24, 0x7fffffff) }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(source.copyOf().apply { writeIntLe(0x2c, size + 1) }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsL2dGeometry.scale(source.copyOf().apply {
                // Redirect SP2 screen-space groups into the protected UV table.
                writeIntLe(0xac, 0x50)
            }, 85)
        }
        assertTrue(UiScaleSettings.isSelectable(70))
    }
}
