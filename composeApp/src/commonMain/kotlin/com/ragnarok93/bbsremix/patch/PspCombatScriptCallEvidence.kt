package com.ragnarok93.bbsremix.patch

/**
 * A narrowly fingerprinted, read-only inventory of indirect script-call
 * infrastructure in the exact English-patched ULJM05775 EBOOT.
 *
 * IMPORTANT: Generic string-based function invocation != a hit event.
 * This verifies immediate operands, their delay slots, and the named
 * string constants but does not assert how the engine schedules callbacks.
 */
internal object PspCombatScriptCallEvidence {
    private const val VA_FILE_DELTA = 0x08803000
    private const val GENERIC_HELPER = 0x089E6334
    internal data class Word(val va: Int, val value: Int)
    internal data class Site(
        val label: String,
        val name: String,
        val stringVa: Int,
        val managerNameVa: Int,
        val jalVa: Int,
        val words: List<Word>,
    )

    internal val sites = listOf(
        Site(
            "first script-function call setup", "CallFunction", 0x08B2DE04,
            0x08B2DDDC, 0x089F1F18,
            listOf(
                Word(0x089F1F04, 0x3C0508B3),
                Word(0x089F1F08, 0x3C0608B3),
                Word(0x089F1F10, 0x34070002),
                Word(0x089F1F14, 0x24A5DDDC.toInt()),
                Word(0x089F1F18, 0x0E2798CD),
                Word(0x089F1F1C, 0x24C6DE04.toInt()),
            ),
        ),
        Site(
            "named no-argument script function", "CallFunctionNoArg", 0x08B2DE48,
            0x08B2DDDC, 0x089F5830,
            listOf(
                Word(0x089F5818, 0x3C1208B3),
                Word(0x089F581C, 0x2652DDDC),
                Word(0x089F5820, 0x3C0608B3),
                Word(0x089F5824, 0x02602025),
                Word(0x089F5828, 0x02402825),
                Word(0x089F582C, 0x34070002),
                Word(0x089F5830, 0x0E2798CD),
                Word(0x089F5834, 0x24C6DE48.toInt()),
            ),
        ),
        Site(
            "named script function with arguments", "CallFunction", 0x08B2DE04,
            0x08B2DDDC, 0x089F58B0,
            listOf(
                Word(0x089F58A0, 0x3C0608B3),
                Word(0x089F58A4, 0x02602025),
                Word(0x089F58A8, 0x02402825),
                Word(0x089F58AC, 0x34070002),
                Word(0x089F58B0, 0x0E2798CD),
                Word(0x089F58B4, 0x24C6DE04.toInt()),
            ),
        ),
    )
    internal val helperStart = listOf(
        Word(GENERIC_HELPER, 0x27BDFFD0.toInt()),
        Word(0x089E633C, 0x00808025),
        Word(0x089E636C, 0x0E29EB9B),
    )

    data class SiteResult(val name: String, val verified: Boolean)
    data class Report(val sites: List<SiteResult>, val helperMatched: Boolean, val lines: List<String>)

    fun inspect(source: ByteArray, exactSupportedSource: Boolean): Report {
        val helperValid = helperStart.all { checkWord(source, it) }
        val results = sites.map { site ->
            val nameValid = checkStringConstant(source, site.stringVa, site.name) &&
                checkStringConstant(source, site.managerNameVa, "EntityManager")
            val opcodesValid = site.words.all { checkWord(source, it) }
            SiteResult(site.label, nameValid && opcodesValid && helperValid)
        }
        val lines = mutableListOf(
            "Native named-script-call infrastructure (read-only, source-fingerprinted):",
        )
        for ((index, result) in results.withIndex()) {
            val site = sites[index]
            lines += "  ${result.name} @ 0x${site.jalVa.toString(16).uppercase()} " +
                "name=${site.name}: ${if (result.verified) "match" else "MISMATCH"}"
        }
        val matches = results.count { it.verified }
        lines += "Named-call sites: $matches/${sites.size}; helper_signature_matches=$helperValid; " +
            "exact_supported_source=$exactSupportedSource."
        lines += if (exactSupportedSource && matches == sites.size) {
            "STATIC VERIFIED: native code passes EntityManager and CallFunction/CallFunctionNoArg " +
                "string constants to helper 0x089E6334, with a MIPS JAL delay-slot operand. " +
                "No OnHitAttack handler or script callback invocation has been identified."
        } else {
            "STATIC UNVERIFIED: no conclusions about script-call infrastructure may be inferred."
        }
        return Report(results, helperValid, lines)
    }

    private fun checkWord(source: ByteArray, word: Word): Boolean {
        val off = word.va - VA_FILE_DELTA
        return fits(source, off, 4) && source.readIntLe(off) == word.value
    }

    private fun checkStringConstant(source: ByteArray, va: Int, name: String): Boolean {
        val off = va - VA_FILE_DELTA
        val encoded = name.encodeToByteArray()
        val alignedPointerVa = (va.toLong() + encoded.size + 1L + 3L) and -4L
        val pointerOff = alignedPointerVa - VA_FILE_DELTA
        return fits(source, off, encoded.size + 1) &&
            encoded.indices.all { source[off + it] == encoded[it] } &&
            source[off + encoded.size] == 0.toByte() &&
            pointerOff >= Int.MIN_VALUE && pointerOff <= Int.MAX_VALUE &&
            fits(source, pointerOff.toInt(), 4) &&
            source.readIntLe(pointerOff.toInt()) == va
    }

    private fun fits(source: ByteArray, offset: Int, length: Int): Boolean =
        offset >= 0 && length >= 0 && offset <= source.size && length <= source.size - offset
}
