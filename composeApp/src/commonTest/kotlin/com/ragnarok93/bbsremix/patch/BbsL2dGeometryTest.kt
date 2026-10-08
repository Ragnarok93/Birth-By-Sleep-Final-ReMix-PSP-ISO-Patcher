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
        assertEquals(-204, result.bytes.readShortLe(0x24c).toShort().toInt())
        assertEquals(-115, result.bytes.readShortLe(0x24e).toShort().toInt())
        assertEquals(51, result.bytes.readShortLe(0x266).toShort().toInt())
        assertEquals(-17, result.bytes.readShortLe(0x268).toShort().toInt())
        assertContentEquals(source.copyOfRange(0xe0, 0xf8), result.bytes.copyOfRange(0xe0, 0xf8))
        assertContentEquals(source.copyOfRange(0x110, 0x200), result.bytes.copyOfRange(0x110, 0x200))
        assertEquals(2, result.layoutFieldsChanged)
        assertEquals(2, result.nodeFieldsChanged)
        assertEquals(4, result.groupFieldsChanged)
        assertEquals(1, result.fontSizeFieldsChanged)
        assertEquals(15, result.bytes[0x278].toInt() and 0xff)
        assertEquals(2, result.bytes[0x279].toInt() and 0xff)
        assertEquals(1, result.bytes[0x27a].toInt() and 0xff)
        assertContentEquals(source, fixture()) // source untouched
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
