package com.ragnarok93.bbsremix.patch

/**
 * Proof of *static* overlap between historical code-injection addresses
 * and declared executable PT_LOAD ranges of separately loaded PSP modules.
 *
 * Overlap does not imply simultaneous module residency, but it does rule out
 * treating either address as permanently owned unallocated RAM.
 */
internal object PspOverlayConflictAudit {
    internal val historicalSites = listOf(
        0x08B70000L to "historical Stage 4",
        0x08B71280L to "historical Stage 5",
    )

    data class Finding(
        val module: String,
        val site: Long,
        val purpose: String,
        val segmentStart: Long,
        val segmentEndExclusive: Long,
    )

    fun evaluate(module: String, report: PspElfModuleMap.Report): List<Finding> =
        buildList {
            if (report.malformed) return@buildList
            for (segment in report.segments) {
                if (segment.type != 1L || !segment.inSource || segment.flags and 1L == 0L ||
                    segment.virtualAddress > 0xffff_ffffL ||
                    segment.memoryBytes > 0x1_0000_0000L - segment.virtualAddress
                ) continue
                val end = segment.virtualAddress + segment.memoryBytes
                for ((address, purpose) in historicalSites) {
                    if (address >= segment.virtualAddress && address < end) {
                        add(Finding(module, address, purpose, segment.virtualAddress, end))
                    }
                }
            }
        }

    fun summarize(findings: List<Finding>, modulesScanned: Int): List<String> {
        val lines = mutableListOf(
            "Historical combat injection vs auxiliary ELF module PT_LOAD overlap audit " +
                "(STATIC, not module residency): modules_scanned=$modulesScanned",
        )
        for ((site, label) in historicalSites) {
            val hits = findings.filter { it.site == site }
            lines += "  $label @ 0x${site.toString(16).uppercase()}: " +
                "overlapping_executable_modules=${hits.size}/$modulesScanned"
            for (finding in hits.take(12)) {
                lines += "    ${finding.module} executable PT_LOAD " +
                    "0x${finding.segmentStart.toString(16).uppercase()}.." +
                    "0x${finding.segmentEndExclusive.toString(16).uppercase()} (exclusive)"
            }
        }
        lines += "STORAGE UNSAFE: overlaps are not safe permanent code caves; " +
            "no claim that every overlay is resident at once."
        return lines
    }
}
