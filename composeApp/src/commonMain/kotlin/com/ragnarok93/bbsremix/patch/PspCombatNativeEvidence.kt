package com.ragnarok93.bbsremix.patch

/**
 * Source-fingerprinted native PSP combat primitives. These instructions are
 * derived from the supported ULJM-05775 EBOOT, not the Steam process image.
 * They establish static bit/field usage, NOT when a gameplay event occurs.
 */
internal object PspCombatNativeEvidence {
    private const val VA_FILE_DELTA = 0x08803000
    private const val INPUT_RESTORE_VA = 0x08816904
    private const val PLAYER_FLAG_OFFSET = 0x238
    private const val CANCEL_FLAG = 0x1000

    data class Signature(
        val title: String,
        val address: Int,
        val instructions: IntArray,
    )

    // Fails closed if the exact supported source was translated/updated.
    private val signatures = listOf(
        Signature(
            "SetTrgFlagCancel: clear/set bit 0x1000 at player+0x238",
            0x08B07020,
            intArrayOf(
                0x8C860238.toInt(), 0x2407EFFF, 0x30A50001, 0x00C73024,
                0x00052B00, 0x00C52825, 0x03E00008, 0xAC850238.toInt(),
            ),
        ),
        Signature(
            "IsTrgFlagCancel: read player+0x238 and return cancel bit",
            0x08B07084,
            intArrayOf(0x8C840238.toInt(), 0x30821000, 0x03E00008, 0x00021302),
        ),
        Signature(
            "native cancel consumer: flag check and player substate update",
            0x08955F10,
            intArrayOf(
                0x8E040238.toInt(), 0x30841000, 0x14800006, 0x3404000E,
                0x8E040240.toInt(), 0x00912024, 0x148001D7, 0x00000000,
                0x3404000E, 0xA604022C.toInt(),
            ),
        ),
        Signature(
            "native cancel cleanup: clear 0x1000 on state transition",
            0x08955FD8,
            intArrayOf(
                0x8E040238.toInt(), 0x30841000, 0x54800007, 0x8E040234.toInt(),
                0x8E040240.toInt(), 0x3C050010, 0x00852024, 0x148001A4,
                0x00000000, 0x8E040234.toInt(), 0x3405000E, 0x2406FFFE,
                0xA605022C.toInt(), 0x8E050238.toInt(), 0x00862024,
                0x2406EFFF, 0xAE040234.toInt(), 0x00A62024, 0xAE040238.toInt(),
            ),
        ),
        Signature(
            "post-input instruction: saved register s0 restoration",
            INPUT_RESTORE_VA,
            intArrayOf(0x8FB00048.toInt(), 0x8FB1004C.toInt(), 0x8FB20050.toInt()),
        ),
    )

    data class Report(val verifiedNativeSignatures: Int, val totalSignatures: Int, val lines: List<String>)

    fun inspect(source: ByteArray, exactSupportedSource: Boolean): Report {
        val lines = mutableListOf<String>()
        lines += "Native PSP combat source signatures (read-only; not runtime validation):"
        var matches = 0
        for (signature in signatures) {
            val offset = signature.address - VA_FILE_DELTA
            val matchesSource = offset >= 0 &&
                offset.toLong() + signature.instructions.size.toLong() * 4 <= source.size &&
                signature.instructions.indices.all {
                    source.readIntLe(offset + it * 4) == signature.instructions[it]
                }
            if (matchesSource) matches++
            lines += "  ${signature.title} @ 0x${signature.address.toUInt().toString(16).uppercase()}: " +
                (if (matchesSource) "match" else "MISMATCH")
        }
        lines += "Native instruction matches=$matches/${signatures.size}; exact_source=$exactSupportedSource."
        if (matches == signatures.size && exactSupportedSource) {
            lines += "STATIC PROOF: PSP-native bit 0x${CANCEL_FLAG.toString(16)} in " +
                "player+0x${PLAYER_FLAG_OFFSET.toString(16)} is set/tested/cleared; " +
                "native state handlers use player+0x22C."
            lines += "UNPROVEN: input timing, hit-confirm flag bits at player+0x23C, " +
                "invulnerability field ownership, runtime player-pointer lifetime, " +
                "Critical bonus ability layout, and safe resident hook/dispatch."
        } else {
            lines += "Native source evidence incomplete; no combat semantics may be inferred."
        }
        lines += "All combat toggles remain unavailable for ISO patching until a " +
            "lifecycle-safe runtime integration is established."
        return Report(matches, signatures.size, lines)
    }
}
