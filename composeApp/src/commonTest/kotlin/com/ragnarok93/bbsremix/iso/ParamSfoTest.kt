package com.ragnarok93.bbsremix.iso

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ParamSfoTest {
    @Test
    fun reads_the_disc_id_entry() {
        assertEquals("ULJM05775", parseDiscId(paramSfo("ULJM05775")))
    }

    @Test
    fun rejects_malformed_tables_and_unexpected_ids() {
        assertNull(parseDiscId(byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte())))
        assertNull(parseDiscId(paramSfo("../../bad")))
        assertNull(parseDiscId(paramSfo("ULJM05775").copyOf(21)))
    }

    private fun paramSfo(discId: String): ByteArray {
        val key = "DISC_ID\u0000".encodeToByteArray()
        val value = (discId + "\u0000").encodeToByteArray()
        val keyTableOffset = 36
        val dataTableOffset = keyTableOffset + key.size
        return ByteArray(dataTableOffset + value.size).also { bytes ->
            byteArrayOf(0, 'P'.code.toByte(), 'S'.code.toByte(), 'F'.code.toByte()).copyInto(bytes)
            writeU32(bytes, 8, keyTableOffset)
            writeU32(bytes, 12, dataTableOffset)
            writeU32(bytes, 16, 1)
            writeU16(bytes, 20, 0)
            writeU16(bytes, 22, 0x0204)
            writeU32(bytes, 24, value.size)
            writeU32(bytes, 28, value.size)
            writeU32(bytes, 32, 0)
            key.copyInto(bytes, keyTableOffset)
            value.copyInto(bytes, dataTableOffset)
        }
    }

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = value.toByte()
        bytes[offset + 1] = (value ushr 8).toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Int) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr (index * 8)).toByte()
    }
}
