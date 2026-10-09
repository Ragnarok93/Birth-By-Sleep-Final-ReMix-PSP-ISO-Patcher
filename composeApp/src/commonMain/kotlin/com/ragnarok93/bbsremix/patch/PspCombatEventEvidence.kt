package com.ragnarok93.bbsremix.patch

/**
 * Find event callback *names* in EBOOT read-only data. A matching string is
 * evidence that the native executable references the event name, not proof
 * of an invoked callback or of the data's gameplay semantics.
 */
internal object PspCombatEventEvidence {
    private val names = listOf("OnHitAttack", "OnHitBody", "OnHitAttackBg")
    data class EventName(val name: String, val occurrences: List<Long>)

    fun scan(
        source: ByteArray,
        sections: List<PspCombatStaticAnalysis.Section>,
    ): List<EventName> {
        val readOnly = sections.filter {
            it.name == ".rodata" && !it.executable &&
                it.fileOffset >= 0 && it.length >= 0 &&
                it.fileOffset <= source.size &&
                it.length <= source.size - it.fileOffset
        }
        return names.map { name ->
            val needle = name.encodeToByteArray()
            val found = mutableListOf<Long>()
            for (section in readOnly) {
                if (section.length < needle.size + 1) continue
                // Check exact NUL termination. Names in EBOOT are immediately
                // preceded by non-text bytes from script metadata, not
                // necessarily by another NUL. Exclude ASCII continuations
                // to avoid counting longer names as event prefixes.
                for (offset in 0..section.length - needle.size - 1) {
                    val absolute = section.fileOffset + offset
                    if (offset > 0 && source[absolute - 1].toInt() in 0x21..0x7e) continue
                    if (source[absolute + needle.size] != 0.toByte()) continue
                    if (needle.indices.all { source[absolute + it] == needle[it] }) {
                        found += section.address + offset
                    }
                }
            }
            EventName(name, found)
        }
    }

    fun inspect(source: ByteArray): List<String> {
        val results = scan(source, PspCombatStaticAnalysis.sectionMap(source))
        val lines = mutableListOf(
            "Script hit-event strings (.rodata only, read-only). Names are NOT hit-confirm signals:",
        )
        results.forEach { evidence ->
            val addresses = evidence.occurrences.joinToString(", ") {
                "0x" + it.toString(16).uppercase().padStart(8, '0')
            }
            lines += "  ${evidence.name}: ${evidence.occurrences.size} exact NUL-terminated occurrence(s)" +
                if (addresses.isEmpty()) "" else " at $addresses"
        }
        lines += "Unverified: which executable invokes the callbacks, the event's actor/target ownership, " +
            "and whether a hit changes player+0x23C."
        return lines
    }
}
