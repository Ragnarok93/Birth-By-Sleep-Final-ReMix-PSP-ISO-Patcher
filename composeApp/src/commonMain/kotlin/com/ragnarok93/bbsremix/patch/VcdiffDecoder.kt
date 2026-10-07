package com.ragnarok93.bbsremix.patch

/**
 * Random-access source bytes for bounded-memory VCDIFF decoding.
 */
interface VcdiffByteSource {
    val size: Long

    fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    )
}

/**
 * Append-only output with random reads for VCD_TARGET windows.
 *
 * Implementations should write into a temporary/staging target. A malformed delta can fail after
 * earlier complete windows have been appended.
 */
interface VcdiffByteTarget {
    val size: Long

    fun append(bytes: ByteArray, length: Int)

    fun readAt(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    )
}

data class VcdiffLimits(
    val maxTargetBytes: Long = 2_000_000_000L,
    val maxWindowBytes: Int = 16 * 1024 * 1024,
    val maxApplicationHeaderBytes: Int = 4 * 1024,
    val maxDeltaBytes: Int = 64 * 1024 * 1024,
)

data class VcdiffDecodeResult(
    val targetBytesWritten: Long,
    val windowsDecoded: Int,
)

class VcdiffFormatException(message: String) : IllegalArgumentException(message)

/**
 * A bounded-memory decoder for the VCDIFF default code table and xdelta3 extensions used by the
 * pinned Aqua model delta: application headers, VCD_SOURCE/VCD_TARGET windows, and Adler-32 checks.
 *
 * Secondary compressors and application-defined code tables are rejected explicitly.
 */
object VcdiffDecoder {
    private const val VCD_DECOMPRESS = 0x01
    private const val VCD_CODETABLE = 0x02
    private const val VCD_APPHEADER = 0x04
    private const val VCD_SOURCE = 0x01
    private const val VCD_TARGET = 0x02
    private const val VCD_ADLER32 = 0x04

    private val codeTable: List<CodeEntry> = buildDefaultCodeTable()

    fun decode(
        source: VcdiffByteSource,
        delta: ByteArray,
        target: VcdiffByteTarget,
        limits: VcdiffLimits = VcdiffLimits(),
        cancellation: CancellationToken = NeverCancelled,
    ): VcdiffDecodeResult {
        validateLimits(limits)
        require(delta.size <= limits.maxDeltaBytes) { "The VCDIFF delta exceeds the configured size limit." }
        require(source.size >= 0L) { "The VCDIFF source has an invalid size." }
        require(target.size == 0L) { "The VCDIFF output target must be empty before decoding." }

        val input = ByteCursor(delta, 0, delta.size)
        if (input.readByte() != 0xd6 || input.readByte() != 0xc3 ||
            input.readByte() != 0xc4 || input.readByte() != 0x00
        ) {
            fail("The input is not a supported VCDIFF stream.")
        }

        val headerIndicator = input.readByte()
        if ((headerIndicator and (VCD_DECOMPRESS or VCD_CODETABLE)) != 0) {
            fail("The VCDIFF stream uses an unsupported secondary compressor or custom code table.")
        }
        if ((headerIndicator and (VCD_DECOMPRESS or VCD_CODETABLE or VCD_APPHEADER).inv()) != 0) {
            fail("The VCDIFF stream contains unsupported header flags.")
        }
        if ((headerIndicator and VCD_APPHEADER) != 0) {
            val appHeaderLength = input.readVarInt()
            if (appHeaderLength > limits.maxApplicationHeaderBytes) {
                fail("The VCDIFF application header exceeds the configured size limit.")
            }
            input.skip(appHeaderLength)
        }

        var totalTargetBytes = 0L
        var windowsDecoded = 0
        while (input.remaining > 0) {
            cancellation.throwIfCancelled()
            val windowIndicator = input.readByte()
            if ((windowIndicator and (VCD_SOURCE or VCD_TARGET or VCD_ADLER32).inv()) != 0) {
                fail("The VCDIFF stream contains unsupported window flags.")
            }
            val sourceMode = windowIndicator and (VCD_SOURCE or VCD_TARGET)
            if (sourceMode == (VCD_SOURCE or VCD_TARGET)) {
                fail("A VCDIFF window cannot select both source and target data.")
            }

            val sourceLength: Long
            val sourcePosition: Long
            if (sourceMode != 0) {
                sourceLength = input.readVarInt()
                sourcePosition = input.readVarInt()
            } else {
                sourceLength = 0L
                sourcePosition = 0L
            }

            val sourceEnd = checkedAdd(sourcePosition, sourceLength)
            when (sourceMode) {
                VCD_SOURCE -> if (sourceEnd > source.size) {
                    fail("A VCDIFF source window exceeds the source file.")
                }
                VCD_TARGET -> if (sourceEnd > target.size) {
                    fail("A VCDIFF target window refers to output that has not been decoded.")
                }
            }

            val deltaWindowLength = input.readVarInt()
            if (deltaWindowLength > input.remaining.toLong()) {
                fail("A VCDIFF delta window is truncated.")
            }
            val deltaWindow = input.take(deltaWindowLength)
            val targetLength = deltaWindow.readVarInt()
            if (targetLength > limits.maxWindowBytes.toLong()) {
                fail("A VCDIFF target window exceeds the configured memory limit.")
            }
            val targetLengthInt = targetLength.toInt()
            val nextTotal = checkedAdd(totalTargetBytes, targetLength)
            if (nextTotal > limits.maxTargetBytes) {
                fail("The VCDIFF target exceeds the configured output limit.")
            }

            val deltaIndicator = deltaWindow.readByte()
            if (deltaIndicator != 0) {
                fail("The VCDIFF window uses unsupported compressed sections.")
            }

            val dataLength = deltaWindow.readVarInt()
            val instructionLength = deltaWindow.readVarInt()
            val addressLength = deltaWindow.readVarInt()
            val checksumLength = if ((windowIndicator and VCD_ADLER32) != 0) 4L else 0L
            val sectionLength = checkedAdd(checkedAdd(dataLength, instructionLength), addressLength)
            if (checkedAdd(sectionLength, checksumLength) != deltaWindow.remaining.toLong()) {
                fail("A VCDIFF window has inconsistent section lengths.")
            }

            val dataSection = deltaWindow.take(dataLength)
            val instructionSection = deltaWindow.take(instructionLength)
            val addressSection = deltaWindow.take(addressLength)
            val checksum = if (checksumLength != 0L) deltaWindow.readUInt32() else null
            if (deltaWindow.remaining != 0) {
                fail("A VCDIFF window contains trailing data.")
            }

            val windowOutput = ByteArray(targetLengthInt)
            var outputPosition = 0
            val addressCache = AddressCache()

            fun process(instruction: Instruction) {
                if (instruction.type == InstructionType.NOOP) return
                val sizeLong = if (instruction.size == 0) instructionSection.readVarInt() else instruction.size.toLong()
                if (sizeLong <= 0L || sizeLong > targetLength.toLong() - outputPosition.toLong()) {
                    fail("A VCDIFF instruction has an invalid target size.")
                }
                val size = sizeLong.toInt()
                when (instruction.type) {
                    InstructionType.ADD -> {
                        dataSection.copyTo(windowOutput, outputPosition, size)
                        outputPosition += size
                    }
                    InstructionType.RUN -> {
                        val value = dataSection.readByte().toByte()
                        windowOutput.fill(value, outputPosition, outputPosition + size)
                        outputPosition += size
                    }
                    InstructionType.COPY -> {
                        val here = checkedAdd(sourceLength, outputPosition.toLong())
                        val address = addressCache.readAddress(addressSection, instruction.mode, here)
                        if (address < 0L || address >= here) {
                            fail("A VCDIFF COPY address is outside the decoded window.")
                        }
                        when {
                            address < sourceLength -> {
                                if (checkedAdd(address, sizeLong) > sourceLength) {
                                    fail("A VCDIFF COPY crosses the source-window boundary.")
                                }
                                val absoluteOffset = checkedAdd(sourcePosition, address)
                                if (sourceMode == VCD_SOURCE) {
                                    source.readAt(absoluteOffset, windowOutput, outputPosition, size)
                                } else if (sourceMode == VCD_TARGET) {
                                    target.readAt(absoluteOffset, windowOutput, outputPosition, size)
                                } else {
                                    fail("A VCDIFF COPY uses a source window that was not declared.")
                                }
                                outputPosition += size
                            }
                            else -> {
                                var targetAddress = address - sourceLength
                                repeat(size) { copied ->
                                    if (targetAddress < 0L || targetAddress >= outputPosition.toLong()) {
                                        fail("A VCDIFF COPY refers to target bytes that are not available.")
                                    }
                                    windowOutput[outputPosition] = windowOutput[targetAddress.toInt()]
                                    outputPosition++
                                    targetAddress++
                                    if ((copied and 0x3fff) == 0) cancellation.throwIfCancelled()
                                }
                            }
                        }
                    }
                    InstructionType.NOOP -> Unit
                }
            }

            while (instructionSection.remaining > 0) {
                cancellation.throwIfCancelled()
                val entry = codeTable[instructionSection.readByte()]
                process(entry.first)
                process(entry.second)
            }

            if (outputPosition != targetLengthInt) {
                fail("The VCDIFF instructions did not produce the declared target size.")
            }
            if (dataSection.remaining != 0 || addressSection.remaining != 0) {
                fail("A VCDIFF window did not consume all data and address bytes.")
            }
            if (checksum != null && adler32(windowOutput) != checksum) {
                fail("A VCDIFF target window failed its Adler-32 check.")
            }

            val priorSize = target.size
            target.append(windowOutput, windowOutput.size)
            if (target.size != checkedAdd(priorSize, targetLength)) {
                fail("The VCDIFF output target did not append the complete window.")
            }
            totalTargetBytes = nextTotal
            windowsDecoded++
        }

        if (windowsDecoded == 0) fail("The VCDIFF stream does not contain any target windows.")
        return VcdiffDecodeResult(totalTargetBytes, windowsDecoded)
    }

    private fun validateLimits(limits: VcdiffLimits) {
        require(limits.maxTargetBytes > 0L) { "The VCDIFF output limit must be positive." }
        require(limits.maxWindowBytes > 0) { "The VCDIFF window limit must be positive." }
        require(limits.maxApplicationHeaderBytes >= 0) { "The VCDIFF application-header limit is invalid." }
        require(limits.maxDeltaBytes >= 0) { "The VCDIFF delta limit is invalid." }
    }

    private fun adler32(bytes: ByteArray): Long {
        var a = 1L
        var b = 0L
        for (byte in bytes) {
            a = (a + (byte.toInt() and 0xff)) % 65_521L
            b = (b + a) % 65_521L
        }
        return (b shl 16) or a
    }

    private fun checkedAdd(left: Long, right: Long): Long {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            fail("A VCDIFF length or address overflows the supported range.")
        }
        return left + right
    }

    private fun fail(message: String): Nothing = throw VcdiffFormatException(message)

    private data class Instruction(
        val type: InstructionType,
        val size: Int = 0,
        val mode: Int = 0,
    )

    private data class CodeEntry(
        val first: Instruction,
        val second: Instruction,
    )

    private enum class InstructionType {
        NOOP,
        ADD,
        RUN,
        COPY,
    }

    private class AddressCache {
        private val near = LongArray(4)
        private val same = LongArray(3 * 256)
        private var nextNear = 0

        fun readAddress(section: ByteCursor, mode: Int, here: Long): Long {
            val address = when (mode) {
                0 -> section.readVarInt()
                1 -> {
                    val distance = section.readVarInt()
                    if (distance > here) fail("A VCDIFF HERE address is invalid.")
                    here - distance
                }
                in 2..5 -> checkedAdd(near[mode - 2], section.readVarInt())
                in 6..8 -> same[(mode - 6) * 256 + section.readByte()]
                else -> fail("A VCDIFF COPY uses an unknown address mode.")
            }
            near[nextNear] = address
            nextNear = (nextNear + 1) % near.size
            same[(address % same.size).toInt()] = address
            return address
        }
    }

    private class ByteCursor(
        private val bytes: ByteArray,
        start: Int,
        private val end: Int,
    ) {
        var position: Int = start
            private set

        val remaining: Int
            get() = end - position

        init {
            if (start < 0 || end < start || end > bytes.size) {
                fail("A VCDIFF section has invalid bounds.")
            }
        }

        fun readByte(): Int {
            if (position >= end) fail("The VCDIFF stream is truncated.")
            return bytes[position++].toInt() and 0xff
        }

        fun readVarInt(): Long {
            var value = 0L
            var count = 0
            while (true) {
                if (count >= 9) fail("A VCDIFF integer is too large.")
                val byte = readByte()
                val digit = (byte and 0x7f).toLong()
                if (value > (Long.MAX_VALUE - digit) / 128L) {
                    fail("A VCDIFF integer is too large.")
                }
                value = value * 128L + digit
                count++
                if ((byte and 0x80) == 0) return value
            }
        }

        fun readUInt32(): Long =
            (readByte().toLong() shl 24) or
                (readByte().toLong() shl 16) or
                (readByte().toLong() shl 8) or
                readByte().toLong()

        fun copyTo(destination: ByteArray, destinationOffset: Int, length: Int) {
            if (length < 0 || length > remaining || destinationOffset < 0 ||
                destinationOffset > destination.size || length > destination.size - destinationOffset
            ) {
                fail("A VCDIFF data section is truncated.")
            }
            bytes.copyInto(destination, destinationOffset, position, position + length)
            position += length
        }

        fun skip(length: Long) {
            if (length < 0L || length > remaining.toLong()) {
                fail("A VCDIFF section is truncated.")
            }
            position += length.toInt()
        }

        fun take(length: Long): ByteCursor {
            if (length < 0L || length > remaining.toLong()) {
                fail("A VCDIFF section is truncated.")
            }
            val start = position
            position += length.toInt()
            return ByteCursor(bytes, start, position)
        }
    }

    private fun buildDefaultCodeTable(): List<CodeEntry> {
        val table = MutableList(256) { CodeEntry(Instruction(InstructionType.NOOP), Instruction(InstructionType.NOOP)) }
        var tableIndex = 0
        fun entry(first: Instruction, second: Instruction = Instruction(InstructionType.NOOP)) {
            if (tableIndex >= table.size) fail("The built-in VCDIFF code table is invalid.")
            table[tableIndex++] = CodeEntry(first, second)
        }
        entry(Instruction(InstructionType.RUN))
        entry(Instruction(InstructionType.ADD))
        for (size in 1..17) entry(Instruction(InstructionType.ADD, size))

        for (mode in 0..8) {
            entry(Instruction(InstructionType.COPY, mode = mode))
            for (size in 4..18) entry(Instruction(InstructionType.COPY, size, mode))
        }
        for (mode in 0..5) {
            for (addSize in 1..4) {
                for (copySize in 4..6) {
                    entry(
                        Instruction(InstructionType.ADD, addSize),
                        Instruction(InstructionType.COPY, copySize, mode),
                    )
                }
            }
        }
        for (mode in 6..8) {
            for (addSize in 1..4) {
                entry(
                    Instruction(InstructionType.ADD, addSize),
                    Instruction(InstructionType.COPY, 4, mode),
                )
            }
        }
        for (mode in 0..8) {
            entry(
                Instruction(InstructionType.COPY, 4, mode),
                Instruction(InstructionType.ADD, 1),
            )
        }
        if (tableIndex != 256) fail("The built-in VCDIFF code table is incomplete.")
        return table
    }
}
