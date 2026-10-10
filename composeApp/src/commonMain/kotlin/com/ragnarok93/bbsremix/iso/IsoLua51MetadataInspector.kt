package com.ragnarok93.bbsremix.iso

/**
 * Strictly bounded, read-only Lua 5.1 binary-chunk metadata parser.
 *
 * Follows Lua 5.1's lundump.c layout: header, recursive Proto source,
 * line ranges, register parameters, code, constants/prototypes, debug data.
 * It does NOT execute, decompile, disassemble opcodes or change script data.
 *
 * The caller MUST independently validate the originating BBSA index and ISO
 * extent; this class accepts only a small in-memory byte array.
 */
internal object IsoLua51MetadataInspector {
    const val MAX_INPUT_BYTES = 8 * 1024
    private const val MAX_FUNCTIONS = 128
    private const val MAX_DEPTH = 16
    private const val MAX_RECORDS = 8192
    private const val MAX_EXAMPLES = 18
    private const val MAX_NAME_LENGTH = 64

    data class Report(
        val valid: Boolean,
        val reason: String,
        val consumedBytes: Int,
        val functions: Int,
        val instructions: Int,
        val constantStrings: Int,
        val visibleExamples: List<String>,
        val combatTermExamples: List<String>,
        val lines: List<String>,
    )

    fun inspect(bytes: ByteArray): Report {
        val lines = mutableListOf("        LUA 5.1 CHUNK METADATA (bounded, read-only; not executed):")
        if (bytes.size !in 12..MAX_INPUT_BYTES) {
            lines += "          UNVERIFIED: invalid/unbounded chunk input size=${bytes.size}."
            return Report(false, "invalid input size", 0, 0, 0, 0,
                emptyList(), emptyList(), lines)
        }
        if (!(bytes[0] == 0x1b.toByte() &&
                bytes[1] == 'L'.code.toByte() &&
                bytes[2] == 'u'.code.toByte() &&
                bytes[3] == 'a'.code.toByte() &&
                bytes[4] == 0x51.toByte() &&
                bytes[5] == 0.toByte())
        ) {
            lines += "          UNVERIFIED: no supported Lua 5.1 format-0 signature."
            return Report(false, "unsupported Lua signature/version", 0, 0, 0, 0,
                emptyList(), emptyList(), lines)
        }
        val endian = bytes[6].toInt() and 0xff
        val intBytes = bytes[7].toInt() and 0xff
        val sizeTBytes = bytes[8].toInt() and 0xff
        val instructionBytes = bytes[9].toInt() and 0xff
        val numberBytes = bytes[10].toInt() and 0xff
        val integral = bytes[11].toInt() and 0xff
        lines += "          version=5.1 format=0 endian=${if (endian == 1) "little" else "unsupported"} " +
            "sizeof_int=$intBytes sizeof_size_t=$sizeTBytes " +
            "sizeof_instruction=$instructionBytes sizeof_number=$numberBytes integral=$integral."
        if (endian != 1 || intBytes != 4 ||
            sizeTBytes !in listOf(4, 8) || instructionBytes != 4 ||
            numberBytes !in listOf(4, 8) || integral !in 0..1
        ) {
            lines += "          UNVERIFIED: unsupported Lua ABI; payload NOT parsed."
            return Report(false, "unsupported Lua ABI", 12, 0, 0, 0,
                emptyList(), emptyList(), lines)
        }
        val parser = Cursor(bytes, sizeTBytes, numberBytes)
        return try {
            parser.parseProto(0)
            lines += "          VALID bounded Lua 5.1 prototype structure: " +
                "functions=${parser.functions} instructions=${parser.instructions} " +
                "string_constants=${parser.constantStrings} bytes_consumed=${parser.position}; " +
                "allocation_bytes=${bytes.size} (unparsed padding not interpreted)."
            if (parser.visibleStrings.isNotEmpty()) {
                lines += "          Example printable string constants (not execution evidence): " +
                    parser.visibleStrings.joinToString(" | ")
            }
            lines += "          Combat-keyword candidate constants=" +
                parser.combatStrings.size + ": " +
                parser.combatStrings.joinToString(" | ").ifEmpty { "none in bounded examples" }
            lines += "          LIMIT: Parsed structure/string constants do NOT prove a " +
                "gameplay hook, combat event delivery, successful runtime load, or code safety."
            Report(true, "validated bounded Lua 5.1 structure", parser.position,
                parser.functions, parser.instructions, parser.constantStrings,
                parser.visibleStrings, parser.combatStrings, lines)
        } catch (failure: DecodeError) {
            lines += "          UNVERIFIED: ${failure.message}; " +
                "no conclusion about Lua execution or combat behavior."
            Report(false, failure.message ?: "malformed chunk", parser.position,
                parser.functions, parser.instructions, parser.constantStrings,
                emptyList(), emptyList(), lines)
        }
    }

    private class DecodeError(message: String) : IllegalArgumentException(message)

    private class Cursor(
        private val bytes: ByteArray,
        private val sizeTBytes: Int,
        private val numberBytes: Int,
    ) {
        var position = 12
            private set
        var functions = 0
            private set
        var instructions = 0
            private set
        var constantStrings = 0
            private set
        val visibleStrings = mutableListOf<String>()
        val combatStrings = mutableListOf<String>()

        private fun take(count: Int) {
            if (count < 0 || count > bytes.size - position) {
                throw DecodeError("truncated/overflowed Lua record at byte=$position count=$count")
            }
            position += count
        }

        private fun readU8(): Int {
            if (position >= bytes.size) throw DecodeError("truncated Lua byte at $position")
            return bytes[position++].toInt() and 0xff
        }

        private fun readCount(): Int {
            if (bytes.size - position < 4) {
                throw DecodeError("truncated Lua 32-bit count at byte=$position")
            }
            val v = (readU8().toLong() or
                (readU8().toLong() shl 8) or
                (readU8().toLong() shl 16) or
                (readU8().toLong() shl 24))
            if (v > MAX_RECORDS) throw DecodeError(
                "negative/excessive count $v at byte=${position - 4}")
            return v.toInt()
        }

        private fun countAndSkip(elementSize: Int): Int {
            val count = readCount()
            if (count > (bytes.size - position) / elementSize) {
                throw DecodeError("Lua array exceeds allocated chunk at byte=$position")
            }
            take(count * elementSize)
            return count
        }

        private fun readString(capture: Boolean): String? {
            if (sizeTBytes > bytes.size - position) {
                throw DecodeError("truncated Lua size_t at $position")
            }
            var size = 0L
            for (i in 0 until sizeTBytes) {
                val b = readU8().toLong()
                if (i >= 4 && b != 0L) {
                    throw DecodeError("unbounded Lua string size_t at byte=$position")
                }
                if (i < 4) size = size or (b shl (i * 8))
            }
            if (size == 0L) return null
            if (size > bytes.size - position) {
                throw DecodeError("Lua string length $size exceeds chunk at $position")
            }
            val length = size.toInt()
            if (bytes[position + length - 1] != 0.toByte()) {
                throw DecodeError("Lua string missing NUL terminator at $position")
            }
            val value = if (capture && length in 2..(MAX_NAME_LENGTH + 1) &&
                (position until (position + length - 1)).all {
                    (bytes[it].toInt() and 0xff) in 32..126
                }
            ) {
                bytes.copyOfRange(position, position + length - 1).decodeToString()
            } else null
            take(length)
            return value
        }

        private fun recordString(value: String?) {
            if (value == null) return
            if (visibleStrings.size < MAX_EXAMPLES && !visibleStrings.contains(value)) {
                visibleStrings += value
            }
            val lower = value.lowercase()
            if (combatStrings.size < MAX_EXAMPLES &&
                listOf("attack", "hit", "guard", "cancel", "damage", "combo",
                    "state", "motion", "player", "invuln", "dodge",
                    "counter", "critical").any { lower.contains(it) } &&
                !combatStrings.contains(value)
            ) combatStrings += value
        }

        fun parseProto(depth: Int) {
            if (depth > MAX_DEPTH || functions >= MAX_FUNCTIONS) {
                throw DecodeError("prototype count/depth exceeds bounded limit")
            }
            functions++
            readString(capture = false) // source name (optional)
            readCount() // line defined
            readCount() // last line defined
            val upvalues = readU8()
            readU8() // parameter count
            readU8() // vararg flags
            val stack = readU8()
            if (stack == 0) throw DecodeError("zero Lua maxstacksize at $position")

            instructions += countAndSkip(4) // Lua 5.1 instruction size
            val nConstants = readCount()
            // Every constant has at least a one-byte type tag.
            if (nConstants > bytes.size - position) {
                throw DecodeError("constant count exceeds chunk remaining bytes")
            }
            repeat(nConstants) {
                when (val type = readU8()) {
                    0 -> Unit // nil
                    1 -> take(1) // boolean
                    3 -> take(numberBytes) // lua_Number
                    4 -> {
                        constantStrings++
                        recordString(readString(capture = true))
                    }
                    else -> throw DecodeError("unsupported Lua constant tag=$type")
                }
            }
            val children = readCount()
            if (children > (bytes.size - position) / 24) {
                throw DecodeError("nested proto count exceeds remaining chunk")
            }
            repeat(children) { parseProto(depth + 1) }
            countAndSkip(4) // lineinfo
            val locals = readCount()
            if (locals > (bytes.size - position) / (sizeTBytes + 8)) {
                throw DecodeError("local-name entries exceed remaining chunk")
            }
            repeat(locals) {
                readString(capture = false)
                readCount() // startpc
                readCount() // endpc
            }
            val upvalueNames = readCount()
            if (upvalueNames > upvalues || upvalueNames > (bytes.size - position) / sizeTBytes) {
                throw DecodeError("upvalue-name count exceeds declared upvalues/chunk")
            }
            repeat(upvalueNames) { readString(capture = false) }
        }
    }
}
