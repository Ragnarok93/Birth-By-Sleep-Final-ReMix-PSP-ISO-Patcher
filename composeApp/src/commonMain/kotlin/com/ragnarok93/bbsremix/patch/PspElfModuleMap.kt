package com.ragnarok93.bbsremix.patch

/**
 * Read-only PSP ELF32 program-header/layout research. File-backed sections
 * and PT_LOAD virtual address ranges are *static*; relocatable module load
 * addresses and runtime overlay lifetime are not inferred from this map.
 */
internal object PspElfModuleMap {
    private const val MAX_HEADERS = 32
    private const val PH_SIZE = 32

    data class Segment(
        val type: Long,
        val fileOffset: Long,
        val virtualAddress: Long,
        val fileBytes: Long,
        val memoryBytes: Long,
        val flags: Long,
        val inSource: Boolean,
    )
    data class Report(
        val elfType: Int?,
        val entry: Long?,
        val segments: List<Segment>,
        val malformed: Boolean,
        val lines: List<String>,
    )

    fun inspect(bytes: ByteArray, label: String): Report {
        val prefix = "  ELF LOAD MAP $label:"
        val elf32 = bytes.size >= 52 && bytes[0] == 0x7f.toByte() &&
            bytes[1] == 'E'.code.toByte() && bytes[2] == 'L'.code.toByte() &&
            bytes[3] == 'F'.code.toByte() && bytes[4] == 1.toByte() &&
            bytes[5] == 1.toByte()
        if (!elf32) {
            return Report(null, null, emptyList(), true, listOf("$prefix invalid ELF32/LE header"))
        }
        val eType = bytes.readShortLe(16)
        val entry = bytes.readIntLe(24).toUInt().toLong()
        val offset = bytes.readIntLe(28).toUInt().toLong()
        val entrySize = bytes.readShortLe(42)
        val count = bytes.readShortLe(44)
        val basic = "$prefix e_type=$eType e_entry=0x${entry.toString(16).uppercase()} " +
            "program_headers=$count"
        if (count == 0) {
            return Report(eType, entry, emptyList(), false,
                listOf(basic, "  No ELF program headers; module load placement remains unknown."))
        }
        if (count > MAX_HEADERS || entrySize < PH_SIZE ||
            offset > bytes.size.toLong() ||
            count.toLong() * entrySize.toLong() > bytes.size.toLong() - offset
        ) {
            return Report(eType, entry, emptyList(), true,
                listOf(basic, "  Invalid/truncated program header table; no load-map inference."))
        }
        val segments = buildList {
            for (i in 0 until count) {
                val at = (offset + i.toLong() * entrySize).toInt()
                val type = bytes.readIntLe(at).toUInt().toLong()
                val file = bytes.readIntLe(at + 4).toUInt().toLong()
                val va = bytes.readIntLe(at + 8).toUInt().toLong()
                val filesz = bytes.readIntLe(at + 16).toUInt().toLong()
                val memsz = bytes.readIntLe(at + 20).toUInt().toLong()
                val flags = bytes.readIntLe(at + 24).toUInt().toLong()
                val fits = file <= bytes.size.toLong() &&
                    filesz <= bytes.size.toLong() - file && memsz >= filesz
                add(Segment(type, file, va, filesz, memsz, flags, fits))
            }
        }
        val lines = mutableListOf(basic)
        for (segment in segments) {
            lines += "  segment_type=${segment.type} va=0x${segment.virtualAddress.toString(16).uppercase()} " +
                "file_offset=${segment.fileOffset} file_bytes=${segment.fileBytes} " +
                "memory_bytes=${segment.memoryBytes} flags=0x${segment.flags.toString(16)} " +
                "bounds_ok=${segment.inSource}"
        }
        lines += "  PT_LOAD is a static file layout only. No code-cave/overlay lifetime guarantee."
        return Report(eType, entry, segments, segments.any { !it.inSource }, lines)
    }
}
