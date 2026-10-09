package com.ragnarok93.bbsremix.patch

/**
 * Additional bounded MIPS/ELF forensic analysis. All output is source
 * metadata (counts, virtual addresses and structural candidates), never
 * extracted executable bytes or a patch plan. Works on MainApp and other
 * file-backed ELF modules, but does not resolve PRX relocations at runtime.
 */
internal object PspCombatDeepStaticInspector {
    private const val MAX_SECTION_SCAN_BYTES = 16 * 1024 * 1024
    private const val MAX_SAMPLE_SITES = 5
    private const val MAX_POINTER_SITES = 4

    private val playerOffsets = listOf(
        0x4c to "motion-context",
        0x190 to "entity invincibility",
        0x220 to "player-state",
        0x22c to "player-substate",
        0x22e to "command-state",
        0x234 to "player-flags",
        0x238 to "trigger-flags",
        0x23c to "status-flags",
        0x268 to "command-kind",
    )
    private val pointers = listOf(
        0x089E74A8L to "player resolver",
        0x089E6334L to "named script-call helper",
        0x08B07020L to "cancel setter",
        0x08B07084L to "cancel getter",
        0x089D6850L to "entity invincibility API",
        0x089EA344L to "player invincibility API",
        0x08B2DE9CL to "OnHitAttack constant A",
        0x08B2FE34L to "OnHitAttack constant B",
        0x08B31C04L to "OnHitAttack constant C",
    )

    data class OffsetResult(
        val offset: Int,
        val label: String,
        val loads: Int,
        val stores: Int,
        val sampleVas: List<Long>,
    )
    data class PointerResult(val address: Long, val label: String, val count: Int, val examples: List<Long>)
    data class AddressBuildResult(val address: Long, val label: String, val count: Int, val examples: List<Long>)
    data class Result(
        val recognizedElf: Boolean,
        val executableInstructions: Int,
        val jalrCalls: Int,
        val jrReturnsOrBranches: Int,
        val directCalls: Int,
        val offsetResults: List<OffsetResult>,
        val pointerResults: List<PointerResult>,
        val addressBuildResults: List<AddressBuildResult>,
        val lines: List<String>,
    )

    fun inspect(source: ByteArray, label: String): Result {
        val elf = isMipsElf(source)
        val sectionMap = if (elf) PspCombatStaticAnalysis.sectionMap(source) else emptyList()
        val report = mutableListOf("DEEP STATIC: $label (read-only, bounded ELF/MIPS analysis)")
        if (!elf) {
            report += "Not a supported little-endian ELF32/MIPS image; no MIPS inference."
            return Result(false, 0, 0, 0, 0, emptyList(), emptyList(), emptyList(), report)
        }

        val eType = source.readShortLe(16)
        val entry = source.readIntLe(24).toUInt().toLong()
        report += "ELF32 MIPS: type=0x${eType.toString(16)} entry=${hex(entry)} " +
            "section_count=${sectionMap.size}; relocatable modules require runtime relocation."
        val code = sectionMap.filter {
            it.executable && it.length <= MAX_SECTION_SCAN_BYTES &&
                it.address + it.length.toLong() <= 0x1_0000_0000L
        }
        val ignoredCode = sectionMap.count { it.executable } - code.size
        var scanned = 0
        var direct = 0
        var indirect = 0
        var returns = 0
        val loadCounts = IntArray(playerOffsets.size)
        val storeCounts = IntArray(playerOffsets.size)
        val samples = Array(playerOffsets.size) { mutableListOf<Long>() }
        val builtSamples = pointers.associate { it.first to mutableListOf<Long>() }
        val builtCounts = pointers.associate { it.first to 0 }.toMutableMap()
        for (section in code) {
            for (local in 0 until section.length - 3 step 4) {
                val pc = section.address + local
                val word = source.readIntLe(section.fileOffset + local)
                val op = word ushr 26
                scanned++
                if (op == 3) direct++
                // A bounded approximation of a 32-bit MIPS address build:
                // LUI rt,hi followed within 4 instructions by ADDIU/ORI
                // rt,rt,lo. This is not full register dataflow analysis.
                if (op == 0x0f) {
                    val rt = (word ushr 16) and 31
                    val hi = word and 0xffff
                    for (distance in 1..4) {
                        val nextLocal = local + distance * 4
                        if (nextLocal + 4 > section.length) break
                        val next = source.readIntLe(section.fileOffset + nextLocal)
                        val operation = next ushr 26
                        if (operation != 0x09 && operation != 0x0d) continue
                        val base = (next ushr 21) and 31
                        val dest = (next ushr 16) and 31
                        if (base != rt || dest != rt) continue
                        val lo = next and 0xffff
                        val built = if (operation == 0x09) {
                            ((hi shl 16) + lo.toShort().toInt()).toUInt().toLong()
                        } else {
                            ((hi shl 16) or lo).toUInt().toLong()
                        }
                        val examples = builtSamples[built] ?: continue
                        builtCounts[built] = builtCounts.getValue(built) + 1
                        if (examples.size < MAX_POINTER_SITES) examples += pc
                    }
                }
                if (op == 0) {
                    val funct = word and 63
                    if (funct == 9) indirect++ // JALR
                    if (funct == 8) returns++ // JR (not necessarily return to ra)
                }
                // This is an opcode/offset census across ALL object types;
                // only object-provenance tracing can attribute a player field.
                val read = op == 0x20 || op == 0x21 || op == 0x23 ||
                    op == 0x24 || op == 0x25 || op == 0x31 // LB, LH, LW, LBU, LHU, LWC1
                val write = op == 0x28 || op == 0x29 || op == 0x2b ||
                    op == 0x39 // SB, SH, SW, SWC1
                if (!read && !write) continue
                val immediate = word and 0xffff
                for ((index, pair) in playerOffsets.withIndex()) {
                    if (immediate != pair.first) continue
                    if (read) loadCounts[index]++ else storeCounts[index]++
                    if (samples[index].size < MAX_SAMPLE_SITES) samples[index] += pc
                }
            }
        }
        val offsets = playerOffsets.mapIndexed { i, pair ->
            OffsetResult(pair.first, pair.second, loadCounts[i], storeCounts[i], samples[i])
        }
        report += "Executable MIPS: instructions=$scanned JAL=$direct JALR=$indirect JR=$returns; " +
            "skipped_large_or_unsafe_sections=$ignoredCode."
        report += "Offset opcode census (ALL object types, not proven player state):"
        offsets.forEach { row ->
            report += "  +0x${row.offset.toString(16)} ${row.label}: " +
                "reads=${row.loads} writes=${row.stores}" +
                if (row.sampleVas.isEmpty()) "" else
                    " example_pc=${row.sampleVas.joinToString(",") { hex(it) }}"
        }

        // References are scanned only in validated, file-backed non-executable
        // ELF sections. Same numeric pointer may be a constant, not a call.
        val candidates = pointers.associate { it.first to mutableListOf<Long>() }
        val counts = pointers.associate { it.first to 0 }.toMutableMap()
        for (section in sectionMap.filter {
            !it.executable && it.name != ".shstrtab" &&
                it.length <= MAX_SECTION_SCAN_BYTES &&
                it.address + it.length.toLong() <= 0x1_0000_0000L
        }) {
            for (local in 0 until section.length - 3 step 4) {
                val word = source.readIntLe(section.fileOffset + local).toUInt().toLong()
                val matches = candidates[word] ?: continue
                counts[word] = counts.getValue(word) + 1
                if (matches.size < MAX_POINTER_SITES) matches += section.address + local
            }
        }
        val pointerResults = pointers.map { (va, name) ->
            PointerResult(va, name, counts.getValue(va), candidates.getValue(va))
        }
        val addressBuilds = pointers.map { (va, name) ->
            AddressBuildResult(va, name, builtCounts.getValue(va), builtSamples.getValue(va))
        }
        report += "Probable LUI+ADDIU/ORI address materializations (four-instruction window; not proof of dataflow):"
        addressBuilds.filter { it.count > 0 }.forEach { value ->
            report += "  ${value.label} ${hex(value.address)}: candidates=${value.count}" +
                " sample_lui_pc=${value.examples.joinToString(",") { hex(it) }}"
        }
        if (addressBuilds.all { it.count == 0 }) report += "  none in current target catalog."
        report += "File-backed data pointer census (numeric references, NOT code XREFs):"
        pointerResults.forEach { value ->
            report += "  ${value.label} ${hex(value.address)}: count=${value.count}" +
                if (value.examples.isEmpty()) "" else
                    " sample_data_va=${value.examples.joinToString(",") { hex(it) }}"
        }
        report += "STATIC LIMITS: JALR targets unresolved, PRX relocations unapplied, " +
            "object provenance unknown, no safe hook/code cave established."
        return Result(true, scanned, indirect, returns, direct, offsets, pointerResults, addressBuilds, report)
    }

    private fun isMipsElf(source: ByteArray): Boolean =
        source.size >= 0x34 &&
            source[0] == 0x7f.toByte() &&
            source[1] == 'E'.code.toByte() &&
            source[2] == 'L'.code.toByte() &&
            source[3] == 'F'.code.toByte() &&
            source[4] == 1.toByte() && source[5] == 1.toByte() &&
            source.readShortLe(18) == 8

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')
}
