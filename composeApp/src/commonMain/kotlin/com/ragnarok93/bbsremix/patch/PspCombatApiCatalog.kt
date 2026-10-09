package com.ragnarok93.bbsremix.patch

/**
 * Source-anchored native script API map, derived only from the supported
 * English-patched ULJM05775 EBOOT. The registration name, function pointer,
 * and characteristic MIPS instructions must ALL agree.
 *
 * The exposed script names document an API; they do not prove that the
 * specified call is safe for injection, or that a field is live at all times.
 * Read-only: this class never writes to an EBOOT or makes a combat patch.
 */
internal object PspCombatApiCatalog {
    private const val DELTA = 0x08803000
    internal data class Opcode(val va: Int, val expected: Int)
    internal data class Registration(
        val name: String,
        val pairVa: Int,
        val functionVa: Int,
        val observedBehavior: String,
        val opcodes: List<Opcode>,
    )
    internal val known = listOf(
        Registration(
            "IsAttacking", 0x08B2A20C, 0x089DC664,
            "resolves attack entity and queries native routine 0x088E6294; NOT hit-confirm",
            listOf(
                Opcode(0x089DC67C, 0x0E29EDDA),
                Opcode(0x089DC684, 0x0E23A3A7),
                Opcode(0x089DC698, 0x0E2398A5),
            ),
        ),
        Registration(
            "GetMotionNowFrame", 0x08B2A024, 0x089DB50C,
            "resolves motion context via 0x088E5EB8 and returns a float to Lua",
            listOf(
                Opcode(0x089DB52C, 0x0E23A3A7),
                Opcode(0x089DB540, 0x0E2397AE),
                Opcode(0x089DB54C, 0x0E29EE3B),
            ),
        ),
        Registration(
            "GetPlayerState", 0x08B2D280, 0x089E924C, "reads player +0x220",
            listOf(Opcode(0x089E9280, 0x8C850220.toInt())),
        ),
        Registration(
            "GetSubState", 0x08B2D288, 0x089E92B4, "reads signed halfword player +0x22C",
            listOf(Opcode(0x089E92E8, 0x8485022C.toInt())),
        ),
        Registration(
            "GetCommandKind", 0x08B2D260, 0x089E78F4, "reads command kind from player +0x268",
            listOf(Opcode(0x089E7928, 0x94840000.toInt())),
        ),
        Registration(
            "GetCommandState", 0x08B2D270, 0x089E91E4, "reads signed command-state halfword player +0x22E",
            listOf(Opcode(0x089E9218, 0x8485022E.toInt())),
        ),
        Registration(
            "GetCommandSubcate", 0x08B2D268, 0x089E7970,
            "looks up subcategory byte at 0x08B1AE44 + (kind * 16) + 3",
            listOf(
                Opcode(0x089E79EC, 0x3C0608B2.toInt()),
                Opcode(0x089E79F0, 0x00042100.toInt()),
                Opcode(0x089E79F4, 0x24C6AE44.toInt()),
                Opcode(0x089E7A00, 0x90850003.toInt()),
            ),
        ),
        Registration(
            "GetCommandCategory", 0x08B2D278, 0x089E7A38, "looks up unsigned category byte at table 0x08B1AE44 + (kind * 16) + 1",
            listOf(Opcode(0x089E7AB4, 0x3C0608B2.toInt()), Opcode(0x089E7AB8, 0x00042100.toInt()), Opcode(0x089E7ABC, 0x24C6AE44.toInt()), Opcode(0x089E7AC8, 0x90850001.toInt())),
        ),
        Registration(
            "SetTrgFlagCancel", 0x08B2D1B8, 0x089E7604, "sets/clears bit 0x1000 at resolved player +0x238",
            listOf(Opcode(0x089E763C, 0x8E240238.toInt()), Opcode(0x089E7640, 0x2405EFFF.toInt()), Opcode(0x089E764C, 0x00062B00.toInt()), Opcode(0x089E7654, 0xAE240238.toInt())),
        ),
        Registration(
            "IsTrgFlagCancel", 0x08B2D318, 0x089E8BC0, "reads cancel bit 0x1000 at resolved player +0x238",
            listOf(Opcode(0x089E8BF4, 0x8C850238.toInt()), Opcode(0x089E8BFC, 0x30A51000.toInt()), Opcode(0x089E8C04, 0x00052B02.toInt())),
        ),
        Registration(
            "EnableInvincible", 0x08B28B08, 0x089D6850, "sets/clears entity flag 0x8000 at resolved entity +0x190",
            listOf(Opcode(0x089D6888, 0x8E240190.toInt()), Opcode(0x089D6890, 0x24A57FFF.toInt()), Opcode(0x089D689C, 0x00062BC0.toInt()), Opcode(0x089D68A4, 0xAE240190.toInt())),
        ),
        Registration(
            "IsInvincible", 0x08B28BC0, 0x089D6DF4, "reads entity invincibility bit at +0x190",
            listOf(Opcode(0x089D6E28, 0x8C850190.toInt()), Opcode(0x089D6E30, 0x30A58000.toInt()), Opcode(0x089D6E38, 0x00052BC2.toInt())),
        ),
        Registration(
            "SetPlayerFlagInvincible", 0x08B2D4B8, 0x089EA344, "sets/clears player bit 0x1 at +0x234 (distinct from entity flag)",
            listOf(Opcode(0x089EA37C, 0x8E240234.toInt()), Opcode(0x089EA380, 0x2405FFFE.toInt()), Opcode(0x089EA388, 0x30450001.toInt()), Opcode(0x089EA390, 0xAE240234.toInt())),
        ),
    )

    data class Finding(val name: String, val matched: Boolean, val description: String)
    data class Report(val lines: List<String>, val findings: List<Finding>)

    fun inspect(source: ByteArray, exactSupportedSource: Boolean): Report {
        // Exact fingerprint is verified by the caller. Even on an unknown
        // source we may report structural mismatches, never enable gameplay.
        val findings = known.map { entry ->
            val pair = entry.pairVa - DELTA
            val nameVa = if (fits(source, pair, 8)) source.readIntLe(pair) else 0
            val functionVa = if (fits(source, pair, 8)) source.readIntLe(pair + 4) else 0
            val nameStart = nameVa.toLong() - DELTA
            val nameBytes = entry.name.encodeToByteArray()
            val nameMatches = nameStart >= 0 && nameStart <= Int.MAX_VALUE &&
                fits(source, nameStart.toInt(), nameBytes.size + 1) &&
                nameBytes.indices.all { source[nameStart.toInt() + it] == nameBytes[it] } &&
                source[nameStart.toInt() + nameBytes.size] == 0.toByte()
            val pointerMatches = functionVa == entry.functionVa
            val opcodesMatch = entry.opcodes.all { opcode ->
                val offset = opcode.va - DELTA
                fits(source, offset, 4) && source.readIntLe(offset) == opcode.expected
            }
            Finding(entry.name, nameMatches && pointerMatches && opcodesMatch,
                entry.observedBehavior)
        }
        val lines = mutableListOf(
            "Native script API registrations: read-only exact-source verification.",
        )
        findings.forEachIndexed { index, finding ->
            val entry = known[index]
            lines += "  ${finding.name} @ 0x${entry.functionVa.toUInt().toString(16).uppercase()} " +
                "(name + pointer + opcodes): ${if (finding.matched) "match" else "MISMATCH"}; " +
                finding.description
        }
        lines += "Registration matches=${findings.count { it.matched }}/${findings.size}; " +
            "exact_supported_source=$exactSupportedSource."
        lines += if (exactSupportedSource && findings.all { it.matched })
            "STATIC VERIFIED: script name/address binding, selected native field accesses " +
                "and distinct player/entity invincibility mechanisms. No combat runtime claims."
        else
            "STATIC UNVERIFIED: source mismatch or missing API signatures; do not infer gameplay semantics."
        return Report(lines, findings)
    }

    private fun fits(source: ByteArray, offset: Int, size: Int): Boolean =
        offset >= 0 && size >= 0 && offset <= source.size && size <= source.size - offset
}
