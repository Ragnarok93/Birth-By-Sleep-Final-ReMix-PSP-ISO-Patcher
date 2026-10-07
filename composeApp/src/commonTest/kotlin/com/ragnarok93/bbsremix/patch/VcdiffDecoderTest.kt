package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VcdiffDecoderTest {
    @Test
    fun decodes_add_with_xdelta_application_header_and_adler32() {
        val delta = file(
            applicationHeader = "opaque source-path metadata".encodeToByteArray(),
            windows = listOf(
                window(
                    indicator = 0x04,
                    targetLength = 4,
                    data = "abc!".encodeToByteArray(),
                    instructions = byteArrayOf(1, 4),
                    addresses = byteArrayOf(),
                    checksum = 0x03950148,
                ),
            ),
        )
        val target = MemoryTarget()

        val result = VcdiffDecoder.decode(MemorySource(byteArrayOf()), delta, target)

        assertEquals(4L, result.targetBytesWritten)
        assertEquals(1, result.windowsDecoded)
        assertContentEquals("abc!".encodeToByteArray(), target.bytes())
    }

    @Test
    fun decodes_default_copy_table_self_near_and_same_modes() {
        val delta = file(
            windows = listOf(
                window(
                    indicator = 0x01,
                    sourceLength = 4,
                    sourcePosition = 0,
                    targetLength = 12,
                    data = byteArrayOf(),
                    instructions = byteArrayOf(20, 52, 116),
                    addresses = byteArrayOf(0, 0, 0),
                ),
            ),
        )
        val target = MemoryTarget()

        VcdiffDecoder.decode(MemorySource("abcd".encodeToByteArray()), delta, target)

        assertContentEquals("abcdabcdabcd".encodeToByteArray(), target.bytes())
    }

    @Test
    fun decodes_overlapping_target_copy_run_and_target_source_windows() {
        val delta = file(
            windows = listOf(
                window(
                    indicator = 0,
                    targetLength = 5,
                    data = byteArrayOf('A'.code.toByte()),
                    instructions = byteArrayOf(1, 1, 20),
                    addresses = byteArrayOf(0),
                ),
                window(
                    indicator = 0,
                    targetLength = 3,
                    data = byteArrayOf('Z'.code.toByte()),
                    instructions = byteArrayOf(0, 3),
                    addresses = byteArrayOf(),
                ),
                window(
                    indicator = 0x02,
                    sourceLength = 8,
                    sourcePosition = 0,
                    targetLength = 5,
                    data = byteArrayOf('A'.code.toByte()),
                    instructions = byteArrayOf(20, 1, 1),
                    addresses = byteArrayOf(0),
                ),
            ),
        )
        val target = MemoryTarget()

        VcdiffDecoder.decode(MemorySource(byteArrayOf()), delta, target)

        assertContentEquals("AAAAAZZZAAAAA".encodeToByteArray(), target.bytes())
    }

    @Test
    fun rejects_bad_checksums_unsupported_flags_and_output_overflow() {
        val badChecksum = file(
            windows = listOf(
                window(
                    indicator = 0x04,
                    targetLength = 4,
                    data = "abc!".encodeToByteArray(),
                    instructions = byteArrayOf(1, 4),
                    addresses = byteArrayOf(),
                    checksum = 0,
                ),
            ),
        )
        assertFailsWith<VcdiffFormatException> {
            VcdiffDecoder.decode(MemorySource(byteArrayOf()), badChecksum, MemoryTarget())
        }

        val compressedHeader = file(headerIndicator = 0x01, windows = emptyList())
        assertFailsWith<VcdiffFormatException> {
            VcdiffDecoder.decode(MemorySource(byteArrayOf()), compressedHeader, MemoryTarget())
        }

        val oversizedTarget = file(
            windows = listOf(
                window(
                    indicator = 0,
                    targetLength = 4,
                    data = "abcd".encodeToByteArray(),
                    instructions = byteArrayOf(1, 4),
                    addresses = byteArrayOf(),
                ),
            ),
        )
        assertFailsWith<VcdiffFormatException> {
            VcdiffDecoder.decode(
                MemorySource(byteArrayOf()),
                oversizedTarget,
                MemoryTarget(),
                limits = VcdiffLimits(maxTargetBytes = 3),
            )
        }
    }

    private fun file(
        headerIndicator: Int = 0,
        applicationHeader: ByteArray = byteArrayOf(),
        windows: List<ByteArray>,
    ): ByteArray {
        val result = mutableListOf<Byte>()
        result.addAll(byteArrayOf(0xd6.toByte(), 0xc3.toByte(), 0xc4.toByte(), 0).toList())
        result += headerIndicator.toByte()
        if (headerIndicator and 0x04 != 0) {
            result += varInt(applicationHeader.size.toLong())
            result += applicationHeader.toList()
        }
        windows.forEach { result.addAll(it.toList()) }
        return result.toByteArray()
    }

    private fun window(
        indicator: Int,
        targetLength: Long,
        data: ByteArray,
        instructions: ByteArray,
        addresses: ByteArray,
        sourceLength: Long = 0,
        sourcePosition: Long = 0,
        checksum: Int? = null,
    ): ByteArray {
        val delta = mutableListOf<Byte>()
        delta += varInt(targetLength)
        delta += 0.toByte()
        delta += varInt(data.size.toLong())
        delta += varInt(instructions.size.toLong())
        delta += varInt(addresses.size.toLong())
        delta += data.toList()
        delta += instructions.toList()
        delta += addresses.toList()
        checksum?.let { value ->
            for (shift in 24 downTo 0 step 8) delta += (value ushr shift).toByte()
        }

        val result = mutableListOf<Byte>()
        result += indicator.toByte()
        if (indicator and 0x03 != 0) {
            result += varInt(sourceLength)
            result += varInt(sourcePosition)
        }
        result += varInt(delta.size.toLong())
        result += delta
        return result.toByteArray()
    }

    private fun varInt(number: Long): List<Byte> {
        require(number >= 0L)
        var value = number
        val groups = mutableListOf<Int>()
        do {
            groups += (value and 0x7f).toInt()
            value = value shr 7
        } while (value > 0)
        groups.reverse()
        return groups.mapIndexed { index, group ->
            (group or if (index < groups.lastIndex) 0x80 else 0).toByte()
        }
    }

    private class MemorySource(private val bytes: ByteArray) : VcdiffByteSource {
        override val size: Long
            get() = bytes.size.toLong()

        override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
            require(offset >= 0L && offset + length <= bytes.size.toLong())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + length)
        }
    }

    private class MemoryTarget : VcdiffByteTarget {
        private var bytes = byteArrayOf()

        override val size: Long
            get() = bytes.size.toLong()

        override fun append(bytes: ByteArray, length: Int) {
            require(length in 0..bytes.size)
            this.bytes += bytes.copyOfRange(0, length)
        }

        override fun readAt(offset: Long, destination: ByteArray, destinationOffset: Int, length: Int) {
            require(offset >= 0L && offset + length <= bytes.size.toLong())
            bytes.copyInto(destination, destinationOffset, offset.toInt(), offset.toInt() + length)
        }

        fun bytes(): ByteArray = bytes.copyOf()
    }
}
