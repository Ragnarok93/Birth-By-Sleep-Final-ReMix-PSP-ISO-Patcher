package com.ragnarok93.bbsremix.patch

/**
 * Bounded, read-only ELF/MIPS investigation for the PSP combat port.
 *
 * A static JAL XREF or an executable ELF section is NOT proof that a
 * gameplay-state offset or hook is safe at runtime. In particular this
 * analyzer must never produce a patch or declare an option validated.
 */
internal object PspCombatStaticAnalysis {
    private const val ELF_HEADER_SIZE = 0x34
    private const val SECTION_HEADER_SIZE = 0x28
    private const val MAX_SECTIONS = 256
    private const val MAX_SECTION_NAME = 64
    private const val MAX_REPORTED_CALLS = 12
    private const val EXECUTABLE_FLAG = 0x4L
    private const val NO_BITS_SECTION = 8

    data class Section(
        val name: String,
        val address: Long,
        val fileOffset: Int,
        val length: Int,
        val executable: Boolean,
    ) {
        fun contains(va: Long): Boolean = va >= address && va - address < length.toLong()
    }

    private val targets = listOf(
        "controller poll callee" to 0x08B16D38L,
        "legacy stub candidate" to 0x08B16D20L,
        "native camera resource copier (reference only)" to 0x0893DBF4L,
    )

    fun sectionMap(data: ByteArray): List<Section> {
        if (data.size < ELF_HEADER_SIZE ||
            data[0] != 0x7f.toByte() || data[1] != 'E'.code.toByte() ||
            data[2] != 'L'.code.toByte() || data[3] != 'F'.code.toByte() ||
            data[4] != 1.toByte() || data[5] != 1.toByte()
        ) return emptyList()

        val table = data.readIntLe(0x20).toUInt().toLong()
        val entrySize = data.readShortLe(0x2e)
        val count = data.readShortLe(0x30)
        val stringsIndex = data.readShortLe(0x32)
        if (count !in 1..MAX_SECTIONS || entrySize < SECTION_HEADER_SIZE ||
            stringsIndex !in 0 until count || !fits(data, table, count.toLong() * entrySize)
        ) return emptyList()

        fun headerField(index: Int, offset: Int): Long =
            data.readIntLe((table + index.toLong() * entrySize + offset).toInt()).toUInt().toLong()

        val stringOffset = headerField(stringsIndex, 0x10)
        val stringSize = headerField(stringsIndex, 0x14)
        if (!fits(data, stringOffset, stringSize)) return emptyList()

        fun sectionName(index: Long): String {
            if (index >= stringSize) return "<invalid-name>"
            val start = (stringOffset + index).toInt()
            val endLimit = minOf((stringOffset + stringSize).toInt(), start + MAX_SECTION_NAME)
            var end = start
            while (end < endLimit && data[end] != 0.toByte()) end++
            return data.copyOfRange(start, end).decodeToString()
        }

        return buildList {
            for (index in 0 until count) {
                val type = headerField(index, 0x04)
                val flags = headerField(index, 0x08)
                val address = headerField(index, 0x0c)
                val offset = headerField(index, 0x10)
                val length = headerField(index, 0x14)
                val nameIndex = headerField(index, 0x00)
                if (length == 0L || type == NO_BITS_SECTION.toLong() ||
                    length > Int.MAX_VALUE || !fits(data, offset, length)
                ) continue
                add(
                    Section(
                        name = sectionName(nameIndex),
                        address = address,
                        fileOffset = offset.toInt(),
                        length = length.toInt(),
                        executable = flags and EXECUTABLE_FLAG != 0L,
                    ),
                )
            }
        }
    }

    fun inspect(data: ByteArray): List<String> {
        val sections = sectionMap(data)
        if (sections.isEmpty()) {
            return listOf(
                "ELF section map: unavailable or invalid; static JAL XREF scan skipped.",
                "A LOAD segment alone does not distinguish code from data or prove a safe hook.",
            )
        }

        val lines = mutableListOf<String>()
        val executable = sections.filter { it.executable }
        lines += "ELF section map: ${sections.size} file-backed sections; " +
            "${executable.size} executable section(s); static references only."
        for ((label, va) in listOf(
            "input restoration site" to 0x08816904L,
            "controller poll callee" to 0x08B16D38L,
            "legacy stub candidate" to 0x08B16D20L,
            "candidate player-state address" to 0x08B6A490L,
        )) {
            val owner = sections.firstOrNull { it.contains(va) }
            lines += "$label ${hex(va)}: section=${owner?.name ?: "<unmapped>"}; " +
                "executable=${owner?.executable ?: false}"
        }

        val matches = targets.associate { it.second to mutableListOf<Pair<Long, Int>>() }
        var scanned = 0L
        for (section in executable) {
            // Do not scan executable section contents when its VA/file mapping
            // would overflow 32-bit PSP addressing.
            if (section.address + section.length > 0x1_0000_0000L) continue
            for (offset in 0 until section.length - 3 step 4) {
                val pc = section.address + offset
                val word = data.readIntLe(section.fileOffset + offset)
                scanned++
                if (word ushr 26 != 3) continue // only direct MIPS JAL
                val target = ((pc + 4) and 0xF0000000L) or
                    ((word.toLong() and 0x03FF_FFFFL) shl 2)
                val collection = matches[target] ?: continue
                if (collection.size < MAX_REPORTED_CALLS) collection += pc to word
            }
        }
        lines += "MIPS static JAL scan: $scanned executable instructions; " +
            "direct calls only (indirect JALR and runtime overlays not resolved)."
        for ((label, target) in targets) {
            val references = matches.getValue(target)
            lines += "$label ${hex(target)}: ${references.size}" +
                (if (references.size == MAX_REPORTED_CALLS) "+ (report cap)" else "") +
                " sample call site(s)" +
                if (references.isEmpty()) "" else
                    " at " + references.joinToString(", ") { hex(it.first) }
        }
        lines += "Static XREFs and section flags do not prove gameplay semantics, hook reachability, " +
            "state ownership, or runtime safety."
        return lines
    }

    private fun fits(data: ByteArray, offset: Long, length: Long): Boolean =
        offset >= 0 && length >= 0 && offset <= data.size.toLong() &&
            length <= data.size.toLong() - offset

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')
}
