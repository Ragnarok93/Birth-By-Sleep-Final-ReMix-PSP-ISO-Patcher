package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class BbsCtdGeometryTest {
    @Test fun stockIsByteIdenticalAndValidatesMessageBounds() {
        val source = fixture()
        val result = BbsCtdGeometry.scaleSubtitleLayouts(source, 100)
        assertContentEquals(source, result.bytes)
        assertEquals(0, result.subtitleRowsChanged)
        assertEquals(0, result.fieldsChanged)
    }

    @Test fun subtitle85PercentPreservesCenterBottomAndDialogueData() {
        val original = fixture()
        val result = BbsCtdGeometry.scaleSubtitleLayouts(original, 85)
        val scaled = result.bytes
        assertEquals(1, result.subtitleRowsChanged)
        assertEquals(155, scaled.u16(0x40))
        assertEquals(228, scaled.u16(0x42))
        assertEquals(170, scaled.u16(0x44))
        assertEquals(20, scaled.u16(0x46))
        assertEquals(15, scaled.u16(0x4c))
        assertEquals(240, scaled.u16(0x40) + scaled.u16(0x44) / 2)
        assertEquals(248, scaled.u16(0x42) + scaled.u16(0x46))
        assertContentEquals(original.copyOfRange(0x20, 0x40), scaled.copyOfRange(0x20, 0x40))
        assertContentEquals(original.copyOfRange(0x60, 0x100), scaled.copyOfRange(0x60, 0x100))
        assertEquals(5, scaled[0x49].toInt() and 0xff)
    }

    @Test fun allSelectableScalesPreserveSizeAndNonSubtitleLayout() {
        val original = fixture()
        for (percent in 70..100 step 5) {
            val result = BbsCtdGeometry.scaleSubtitleLayouts(original, percent)
            assertEquals(original.size, result.bytes.size)
            assertContentEquals(original.copyOfRange(0x60, 0x80), result.bytes.copyOfRange(0x60, 0x80))
            assertContentEquals(original.copyOfRange(0x80, original.size),
                result.bytes.copyOfRange(0x80, result.bytes.size))
        }
    }

    @Test fun failsClosedOnBadLayoutsOrTextPointers() {
        assertFailsWith<IllegalArgumentException> {
            BbsCtdGeometry.scaleSubtitleLayouts(fixture().apply { this[0] = 0 }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsCtdGeometry.scaleSubtitleLayouts(fixture().apply { w32(0x14, 0x120) }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsCtdGeometry.scaleSubtitleLayouts(fixture().apply { w32(0x24, 0xffff) }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsCtdGeometry.scaleSubtitleLayouts(fixture().apply { this[0x48] = 0 }, 85)
        }
        assertFailsWith<IllegalArgumentException> {
            BbsCtdGeometry.scaleSubtitleLayouts(fixture(), 71)
        }
    }

    private fun fixture() = ByteArray(0x100).apply {
        "@CTD".encodeToByteArray().copyInto(this, 0)
        w32(4, 1)
        w16(0x0c, 2) // one subtitle, one unrelated message box
        w16(0x0e, 1)
        w32(0x10, 0x20)
        w32(0x14, 0x40)
        w32(0x18, 0x80)
        w32(0x20, 77) // message id
        w32(0x24, 0x80) // text offset
        w16(0x28, 0) // subtitle layout index
        w16(0x40, 140)
        w16(0x42, 224)
        w16(0x44, 200)
        w16(0x46, 24)
        this[0x48] = 2 // Center alignment
        this[0x49] = 5 // Subtitle style
        w16(0x4a, 512) // text alignment (reserved, untouched)
        w16(0x4c, 18) // font size
        w16(0x50, 0) // horizontal spacing
        w16(0x52, 2) // vertical spacing
        w16(0x60, 25)
        w16(0x62, 25)
        w16(0x64, 100)
        w16(0x66, 18)
        this[0x69] = 6 // Dice style unrelated, untouched
        "TEST".encodeToByteArray().copyInto(this, 0x80)
    }
    private fun ByteArray.w16(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }
    private fun ByteArray.w32(offset: Int, value: Int) {
        w16(offset, value and 0xffff)
        w16(offset + 2, value ushr 16)
    }
    private fun ByteArray.u16(offset: Int) =
        (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)
}
