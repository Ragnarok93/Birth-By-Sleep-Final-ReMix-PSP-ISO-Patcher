package com.ragnarok93.bbsremix.patch

/**
 * Detailed *static* MIPS callsite and object-offset investigation for all
 * file-backed ELF executable sections. No assumed object types or runtime
 * control flow. Unlike the small summary inspector, emits every matching
 * known-helper JAL and every matching candidate field access with context.
 */
internal object PspCombatNativeXrefSurvey {
    private const val MAX_SECTION_BYTES = 16 * 1024 * 1024
    private val knownTargets = linkedMapOf(
        0x08816688L to "controller capture original callsite",
        0x089E74A8L to "player resolver",
        0x089E6334L to "named Lua script-call helper",
        0x08B07020L to "native cancel setter",
        0x08B07084L to "native cancel getter",
        0x08B07094L to "attack-status bit setter",
        0x08B070B8L to "attack-status bit getter",
        0x08955F10L to "cancel flag consumer",
        0x08955FD8L to "cancel transition cleanup",
        0x089E7604L to "script SetTrgFlagCancel",
        0x089E8BC0L to "script IsTrgFlagCancel",
        0x089D6850L to "script EnableInvincible",
        0x089D6DF4L to "script IsInvincible",
        0x089EA344L to "script SetPlayerFlagInvincible",
        0x089DB50CL to "script GetMotionNowFrame",
        0x089DC664L to "script IsAttacking",
        0x089E924CL to "script GetPlayerState",
        0x089E92B4L to "script GetSubState",
        0x089E78F4L to "script GetCommandKind",
        0x089E91E4L to "script GetCommandState",
        0x089E7970L to "script GetCommandSubcate",
        0x089E7A38L to "script GetCommandCategory",
        0x089F1F18L to "script-call setup",
        0x089F5830L to "script CallFunctionNoArg",
        0x089F58B0L to "script CallFunction",
    )
    private val offsets = linkedMapOf(
        0x4C to "motion context",
        0x190 to "invincibility entity bit",
        0x220 to "player state candidate",
        0x22C to "player substate candidate",
        0x22E to "command state candidate",
        0x234 to "player flags candidate",
        0x238 to "trigger cancel flag candidate",
        0x23C to "attack status candidate (NOT hit-confirm)",
        0x268 to "command kind candidate",
    )
    data class Report(
        val supportedElf: Boolean,
        val executableInstructions: Int,
        val helperCalls: Int,
        val offsetAccesses: Int,
        val lines: List<String>,
    )

    fun inspect(data: ByteArray, label: String): Report {
        val lines = mutableListOf(
            "FULL NATIVE COMBAT XREF SURVEY — $label (read-only)",
            "  Direct JALs and field-offset matches only; all mapped object types; JALR unresolved."
        )
        val sections = PspCombatStaticAnalysis.sectionMap(data)
            .filter { it.executable && it.length <= MAX_SECTION_BYTES &&
                it.address + it.length <= 0x1_0000_0000L }
        if (sections.isEmpty()) {
            lines += "  NO VALID EXECUTABLE SECTIONS; no direct-call or field ownership inference."
            return Report(false, 0, 0, 0, lines)
        }
        var instructions = 0
        var callCount = 0
        var fieldCount = 0
        val callCounts = mutableMapOf<Long, Int>()
        val fieldCounts = mutableMapOf<Int, Pair<Int, Int>>()
        for (section in sections) {
            lines += "  SECTION ${section.name} va=0x${section.address.toString(16)} bytes=${section.length}"
            fun word(local: Int): Int = data.readIntLe(section.fileOffset + local)
            for (local in 0 until section.length - 3 step 4) {
                val pc = section.address + local
                val instruction = word(local)
                val op = instruction ushr 26
                instructions++
                if (op == 3) {
                    val target = ((pc + 4) and 0xF0000000L) or
                        ((instruction.toLong() and 0x03FF_FFFFL) shl 2)
                    val name = knownTargets[target]
                    if (name != null) {
                        callCount++
                        callCounts[target] = (callCounts[target] ?: 0) + 1
                        val delay = if (local + 8 <= section.length)
                            "0x${word(local + 4).toUInt().toString(16)}" else "NO_FILE_BACKED_SLOT"
                        val preceding = if (local >= 4)
                            "0x${word(local - 4).toUInt().toString(16)}" else "OUTSIDE_SECTION"
                        lines += "    JAL site=0x${pc.toString(16)} target=0x${target.toString(16)} " +
                            "name=$name prior_word=$preceding delay_word=$delay " +
                            "return_va=0x${(pc + 8).toString(16)}"
                    }
                }
                // This loop scans hundreds of thousands of words: do not
                // allocate a Set for every opcode decoded.
                val reading = when (op) {
                    0x20, 0x21, 0x23, 0x24, 0x25, 0x31 -> true
                    else -> false
                }
                val writing = when (op) {
                    0x28, 0x29, 0x2B, 0x39 -> true
                    else -> false
                }
                if (!reading && !writing) continue
                val offset = instruction and 0xffff
                val name = offsets[offset] ?: continue
                fieldCount++
                val counts = fieldCounts[offset] ?: (0 to 0)
                fieldCounts[offset] = if (reading) counts.copy(first = counts.first + 1)
                    else counts.copy(second = counts.second + 1)
                val base = (instruction ushr 21) and 31
                val register = (instruction ushr 16) and 31
                val preceding = if (local >= 4)
                    "0x${word(local - 4).toUInt().toString(16)}" else "OUTSIDE_SECTION"
                val following = if (local + 8 <= section.length)
                    "0x${word(local + 4).toUInt().toString(16)}" else "OUTSIDE_SECTION"
                lines += "    FIELD_ACCESS site=0x${pc.toString(16)} kind=${if (reading) "READ" else "WRITE"} " +
                    "name=$name offset=0x${offset.toString(16)} base_reg=$base data_reg=$register " +
                    "word=0x${instruction.toUInt().toString(16)} " +
                    "prior_word=$preceding next_word=$following"
            }
        }
        lines += "NATIVE XREF TOTAL: executable_instructions=$instructions " +
            "known_helper_direct_calls=$callCount candidate_offset_accesses=$fieldCount"
        for ((address, name) in knownTargets) {
            lines += "  NATIVE_TARGET name=$name va=0x${address.toString(16)} direct_calls=${callCounts[address] ?: 0}"
        }
        for ((offset, name) in offsets) {
            val (reads, writes) = fieldCounts[offset] ?: (0 to 0)
            lines += "  FIELD_OFFSET name=$name offset=0x${offset.toString(16)} " +
                "reads=$reads writes=$writes"
        }
        lines += "PROVENANCE LIMIT: candidate field offsets may belong to arbitrary structures. " +
            "No JALR target, overlay relocation, pointer liveness, callback execution " +
            "or safe hook insertion is inferred from this static scan."
        return Report(true, instructions, callCount, fieldCount, lines)
    }
}
