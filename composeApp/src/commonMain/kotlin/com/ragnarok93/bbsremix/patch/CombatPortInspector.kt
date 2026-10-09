package com.ragnarok93.bbsremix.patch

/**
 * Read-only PSP combat-port investigation. No mutable copy, executable hook,
 * patch table or Stage 4/5 payload is produced by this class.
 *
 * The legacy combat payload targeted the PSP dynamic overlay arena. Static
 * ELF verification cannot prove an overlay-safe live hook; this inspection
 * deliberately reports that gap rather than advertising gameplay support.
 */
internal object CombatPortInspector {
    const val SUPPORTED_SHA256 =
        "8c8947e83b829199f82370c4c638856718886d9a0a525892fed22ce6a8b26ca7"
    const val SUPPORTED_SIZE = 3_589_832
    const val VA_FILE_DELTA = 0x08803000
    const val INPUT_HOOK_VA = 0x08816904
    const val INPUT_HOOK_EXPECTED = 0x8FB00048.toInt()
    const val OVERLAY_START_VA = 0x08B6EE7C
    private const val LEGACY_COMBAT_VA = 0x08B70000
    private const val LEGACY_WRAPPER_VA = 0x08B71280

    data class Report(
        val sourceSha256: String,
        val exactSupportedSource: Boolean,
        val unmodifiedSource: Boolean,
        val overlayCollision: Boolean,
        val hookMatchesReference: Boolean,
        val lines: List<String>,
    ) {
        val summary: String get() = lines.joinToString("\n")
    }

    fun inspect(source: ByteArray): Report {
        val hash = sha256Hex(source)
        val exact = source.size == SUPPORTED_SIZE && hash == SUPPORTED_SHA256
        val elf = source.size >= 0x54 &&
            source[0] == 0x7f.toByte() && source[1] == 'E'.code.toByte() &&
            source[2] == 'L'.code.toByte() && source[3] == 'F'.code.toByte()
        val hookOffset = INPUT_HOOK_VA - VA_FILE_DELTA
        val hasHook = elf && hookOffset >= 0 && hookOffset + 4 <= source.size
        val hookWord = if (hasHook) source.readIntLe(hookOffset) else null
        val hookMatches = hookWord == INPUT_HOOK_EXPECTED
        val phnum = if (elf && source.size >= 0x2e) source.readShortLe(0x2c) else -1
        val untouched = exact && phnum == 2 && hookMatches
        val collision = LEGACY_COMBAT_VA >= OVERLAY_START_VA &&
            LEGACY_WRAPPER_VA >= OVERLAY_START_VA

        val lines = mutableListOf<String>()
        lines += "COMBAT PORT INSPECTION — READ ONLY (does not patch the ISO)"
        lines += "EBOOT: size=${source.size}; sha256=$hash; ELF=$elf; exact_supported_source=$exact"
        lines += "ELF program headers: count=$phnum; unmodified_supported_source=$untouched"
        if (elf && source.size >= 0x34) {
            val phoff = source.readIntLe(0x1c)
            val entsize = source.readShortLe(0x2a)
            if (phnum in 1..4 && entsize >= 0x20 && phoff >= 0 &&
                phoff.toLong() + phnum.toLong() * entsize <= source.size
            ) {
                for (i in 0 until phnum) {
                    val at = phoff + i * entsize
                    val type = source.readIntLe(at)
                    val fileOffset = source.readIntLe(at + 4).toUInt().toLong()
                    val va = source.readIntLe(at + 8).toUInt().toLong()
                    val fileBytes = source.readIntLe(at + 0x10).toUInt().toLong()
                    val memoryBytes = source.readIntLe(at + 0x14).toUInt().toLong()
                    lines += "LOAD[$i]: type=$type file_offset=${hex(fileOffset)} " +
                        "va=${hex(va)} file_bytes=$fileBytes mem_bytes=$memoryBytes " +
                        "bounds_ok=${fileOffset + fileBytes <= source.size && memoryBytes >= fileBytes}"
                }
            } else {
                lines += "ELF header table cannot be decoded safely; do not use this source to derive hooks."
            }
        }
        lines += "Resident/overlay boundary: dynamic_overlay_start=${hex(OVERLAY_START_VA.toLong())}"
        lines += "Historical Stage4=${hex(LEGACY_COMBAT_VA.toLong())}; Stage5=${hex(LEGACY_WRAPPER_VA.toLong())}; " +
            "overlay_collision=$collision"
        lines += "Input-site ${hex(INPUT_HOOK_VA.toLong())}: current=${hookWord?.let { hex(it.toUInt().toLong()) } ?: "unavailable"} " +
            "expected=${hex(INPUT_HOOK_EXPECTED.toUInt().toLong())} matches=$hookMatches"
        if (hookMatches) {
            lines += "ABI HAZARD at 0x08816904: original instruction restores saved register s0 " +
                "(lw s0, +0x48(sp)); it is NOT an unused post-input slot. Replacing it " +
                "with JAL without preserving the restore changes the caller's machine state."
        }
        lines += "Candidate instruction windows below are for manual PSP disassembly; they do not establish combat semantics."
        // Only a small fixed set of windows, all inside the exact supported
        // resident EBOOT's original segment; never export bulk game code.
        for ((label, va) in listOf(
            "controller/post-input" to 0x088168F0,
            "legacy candidate state reader" to 0x08B16D20,
            "candidate player-state data (unverified)" to 0x08B6A490,
        )) {
            val off = va - VA_FILE_DELTA
            if (!elf || off < 0 || off > source.size - 0x30) {
                lines += "$label: window unavailable"
                continue
            }
            val values = (0 until 12).joinToString(" ") { i ->
                hex(source.readIntLe(off + i * 4).toUInt().toLong())
            }
            lines += "$label @ ${hex(va.toLong())}: $values"
        }
        lines += PspCombatStaticAnalysis.inspect(source)
        lines += "Known PC-port gameplay groups: hit-aware cancels; invincibility; extended defense; " +
            "command cancels; Critical abilities/passives; exclusions; telemetry."
        lines += "Status: UNVALIDATED. No gameplay feature is enabled by this report. " +
            "Runtime-safe resident sites, overlay lifetime, player-state offsets and per-option behavior " +
            "must be proved before a PSP combat-patch profile can be activated."
        return Report(hash, exact, untouched, collision, hookMatches, lines)
    }

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')
}
