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
    private const val MAX_SYMBOLS = 48
    private const val MAX_PROTO_SUMMARIES = 24
    // Registered names already located in the native EBOOT. Matching these
    // *constant strings* alone is not evidence of a native API invocation.
    private val NATIVE_COMBAT_API_NAMES = setOf(
        "IsAttacking", "GetMotionNowFrame", "GetPlayerState", "GetSubState",
        "GetCommandKind", "GetCommandState", "GetCommandSubcate",
        "GetCommandCategory", "SetTrgFlagCancel", "IsTrgFlagCancel",
        "EnableInvincible", "IsInvincible", "SetPlayerFlagInvincible",
    )
    private val HIT_EVENT_NAMES = setOf(
        "OnHitAttack", "OnHitBody", "OnHitAttackBg",
    )

    data class PrototypeSummary(
        val ordinal: Int,
        val depth: Int,
        val lineStart: Int,
        val lineEnd: Int,
        val parameters: Int,
        val instructions: Int,
        val printableStringConstants: Int,
        val callbackNameConstants: List<String>,
        val combatKeywordConstants: List<String>,
    )

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
        val printableConstantCount: Int = 0,
        val callbackNameConstants: List<String> = emptyList(),
        val nativeApiNameConstants: List<String> = emptyList(),
        val hitEventNameConstants: List<String> = emptyList(),
        val namedStringConstants: List<String> = emptyList(),
        val prototypeSummaries: List<PrototypeSummary> = emptyList(),
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
            lines += "          Complete printable ASCII string-constant census: " +
                "printable=${parser.printableConstantCount}/${parser.constantStrings}, " +
                "unique=${parser.uniqueStrings.size} " +
                "(case-sensitive, across all validated Lua prototypes)."
            lines += "          Candidate script callback-name constants=" +
                parser.callbackNames.size + ": " +
                parser.callbackNames.joinToString(" | ").ifEmpty { "none" }
            lines += "          Exact native combat API-name constants=" +
                parser.nativeApiNames.size + ": " +
                parser.nativeApiNames.joinToString(" | ").ifEmpty { "none" }
            lines += "          Exact hit-event-name constants=" +
                parser.hitEventNames.size + ": " +
                parser.hitEventNames.joinToString(" | ").ifEmpty { "none" }
            lines += "          Named API/callback string candidates (bounded examples)=" +
                parser.namedSymbols.take(MAX_SYMBOLS).size +
                "/${parser.namedSymbols.size}: " +
                parser.namedSymbols.take(MAX_SYMBOLS).joinToString(" | ").ifEmpty { "none" }
            for (prototype in parser.prototypes.take(MAX_PROTO_SUMMARIES)) {
                lines += "          Prototype[${prototype.ordinal}] depth=${prototype.depth} " +
                    "line_range=${prototype.lineStart}..${prototype.lineEnd} " +
                    "params=${prototype.parameters} instructions=${prototype.instructions} " +
                    "printable_strings=${prototype.printableStringConstants} " +
                    "callback_constants=${prototype.callbackNameConstants.joinToString(",").ifEmpty { "-" }} " +
                    "combat_constants=${prototype.combatKeywordConstants.joinToString(",").ifEmpty { "-" }}"
            }
            if (parser.prototypes.size > MAX_PROTO_SUMMARIES) {
                lines += "          Additional prototype summaries omitted: " +
                    (parser.prototypes.size - MAX_PROTO_SUMMARIES)
            }
            lines += "          Combat-keyword candidate constants=" +
                parser.combatStrings.size + ": " +
                parser.combatStrings.joinToString(" | ").ifEmpty { "none in bounded examples" }
            lines += "          LIMIT: Parsed structure/string constants do NOT prove a " +
                "gameplay hook, combat event delivery, successful runtime load, or code safety."
            Report(true, "validated bounded Lua 5.1 structure", parser.position,
                parser.functions, parser.instructions, parser.constantStrings,
                parser.visibleStrings, parser.combatStrings, lines,
                parser.printableConstantCount,
                parser.callbackNames.toList(), parser.nativeApiNames.toList(),
                parser.hitEventNames.toList(), parser.namedSymbols.toList(),
                parser.prototypes.toList())
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
        val uniqueStrings = linkedSetOf<String>()
        val namedSymbols = linkedSetOf<String>()
        val callbackNames = linkedSetOf<String>()
        val nativeApiNames = linkedSetOf<String>()
        val hitEventNames = linkedSetOf<String>()
        val prototypes = mutableListOf<PrototypeSummary>()
        var printableConstantCount = 0
            private set

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

        private fun isCombatKeyword(value: String): Boolean {
            val lower = value.lowercase()
            return listOf("attack", "hit", "guard", "cancel", "damage",
                "combo", "state", "motion", "player", "invuln",
                "dodge", "counter", "critical").any { lower.contains(it) }
        }

        private fun isCallbackName(value: String): Boolean =
            value.length > 2 && value.startsWith("On") &&
                value.drop(2).all { it.isLetterOrDigit() || it == '_' }

        private fun isNamedSymbol(value: String): Boolean =
            isCallbackName(value) ||
                (value.length > 3 && listOf("Set", "Get", "Is", "Enable",
                    "Entity", "Create", "Register", "Add", "Remove").any {
                        value.startsWith(it)
                    }) ||
                value == "__index" || value == "new"

        private fun recordString(value: String?) {
            if (value == null) return
            printableConstantCount++
            uniqueStrings += value
            if (visibleStrings.size < MAX_EXAMPLES &&
                !visibleStrings.contains(value)) visibleStrings += value
            if (isCombatKeyword(value) && combatStrings.size < MAX_EXAMPLES &&
                !combatStrings.contains(value)) combatStrings += value
            if (isCallbackName(value)) callbackNames += value
            if (value in NATIVE_COMBAT_API_NAMES) nativeApiNames += value
            if (value in HIT_EVENT_NAMES) hitEventNames += value
            if (isNamedSymbol(value)) namedSymbols += value
        }

        fun parseProto(depth: Int) {
            if (depth > MAX_DEPTH || functions >= MAX_FUNCTIONS) {
                throw DecodeError("prototype count/depth exceeds bounded limit")
            }
            functions++
            val ordinal = functions
            readString(capture = false) // source name (optional)
            val lineStart = readCount() // line defined
            val lineEnd = readCount() // last line defined
            val upvalues = readU8()
            val parameters = readU8() // parameter count
            readU8() // vararg flags
            val stack = readU8()
            if (stack == 0) throw DecodeError("zero Lua maxstacksize at $position")

            val instructionCount = countAndSkip(4) // Lua 5.1 instruction size
            instructions += instructionCount
            val nConstants = readCount()
            var printableInProto = 0
            val callbackInProto = linkedSetOf<String>()
            val combatInProto = linkedSetOf<String>()
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
                        val value = readString(capture = true)
                        recordString(value)
                        if (value != null) {
                            printableInProto++
                            if (isCallbackName(value)) callbackInProto += value
                            if (isCombatKeyword(value)) combatInProto += value
                        }
                    }
                    else -> throw DecodeError("unsupported Lua constant tag=$type")
                }
            }
            // Prototype ordering reflects Lua's preorder nested chunks,
            // not an observed runtime order of execution.
            prototypes += PrototypeSummary(ordinal, depth, lineStart, lineEnd,
                parameters, instructionCount, printableInProto,
                callbackInProto.take(MAX_EXAMPLES), combatInProto.take(MAX_EXAMPLES))
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
