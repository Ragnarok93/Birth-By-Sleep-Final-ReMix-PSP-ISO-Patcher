package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.PspCombatDeepStaticInspector
import com.ragnarok93.bbsremix.patch.PspCombatEventEvidence
import com.ragnarok93.bbsremix.patch.PspCombatStaticAnalysis
import com.ragnarok93.bbsremix.patch.PspElfModuleMap
import com.ragnarok93.bbsremix.patch.PspOverlayConflictAudit
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Bounded read-only inventory of the *selected* PSP ISO, not just EBOOT.
 *
 * Reads ISO9660 directory records with the existing validated ISO reader,
 * samples known candidate file headers, and runs executable-section static
 * analysis only on reasonably small, file-backed, unencrypted ELF modules.
 * No unpacking, archive decryption, mutation, or external tool is attempted.
 */
internal class IsoCombatResearchService(
    private val reader: Iso9660Reader,
) {
    private companion object {
        const val MAX_ENTRIES = 8192
        const val MAX_DIRECTORY_DEPTH = 12
        const val MAX_DIRECTORY_BYTES = 4L * 1024L * 1024L
        const val MAX_FILE_HEADER_PROBES = 512
        const val MAX_REPORTED_FILES = 48
        const val MAX_ELF_FILES = 24
        const val MAX_ELF_SIZE = 12L * 1024L * 1024L
        const val MAX_TOTAL_ELF_BYTES = 64L * 1024L * 1024L
        const val MAX_MODULE_LINES = 9
        const val MAX_ARCHIVE_HEADER = 64
        const val MAX_BBSA_INDEX_BYTES = 4 * 1024 * 1024
        val EXECUTABLE_EXTENSIONS = setOf("ELF", "PRX", "BIN", "SELF")
        val RESEARCH_EXTENSIONS = setOf(
            "DAT", "ARC", "PAK", "CPK", "BBS", "PBD", "REL", "LUA", "LUB",
        )
    }

    data class FileFinding(
        val path: String,
        val bytes: Long,
        val kind: String,
        val digest: String? = null,
    )
    data class Report(
        val fileCount: Int,
        val directories: Int,
        val scannedModules: Int,
        val sampledHeaders: Int,
        val truncated: Boolean,
        val findings: List<FileFinding>,
        val lines: List<String>,
    )

    fun inspect(
        source: Path,
        image: IsoImageInfo,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "ISO COMBAT RESEARCH — READ ONLY",
            "ISO9660 inventory: size=${image.sourceSize} sector=${image.sectorSize} " +
                "disc_serial=${image.discSerial ?: "unknown"}",
        )
        val queue = mutableListOf(image.root to 0)
        val seenDirectories = mutableSetOf<Long>()
        val all = mutableListOf<IsoDirectoryEntry>()
        var directoryCount = 0
        var cursor = 0
        var truncated = false

        while (cursor < queue.size) {
            cancellation.throwIfCancelled()
            val (directory, depth) = queue[cursor++]
            if (!seenDirectories.add(directory.dataOffset)) continue
            directoryCount++
            if (directoryCount > MAX_ENTRIES) {
                truncated = true
                break
            }
            if (directory.size > MAX_DIRECTORY_BYTES) {
                lines += "  Oversized directory ${directory.path.ifEmpty { "/" }}: " +
                    "${directory.size} bytes; skipped by 4 MiB research limit."
                truncated = true
                continue
            }
            // Malformed ISO records fail the analysis with an explicit
            // error; treating malformed directories as empty would lie.
            for (entry in reader.readDirectory(source, directory)) {
                cancellation.throwIfCancelled()
                val special = entry.name == "\u0000" || entry.name == "\u0001" ||
                    entry.path.endsWith("/\u0000") || entry.path.endsWith("/\u0001")
                if (special) continue
                if (all.size + queue.size > MAX_ENTRIES) {
                    truncated = true
                    break
                }
                if (!entry.isDirectory) {
                    all += entry
                } else if (depth < MAX_DIRECTORY_DEPTH) {
                    queue += entry to depth + 1
                } else {
                    truncated = true
                }
            }
            if (truncated) break
        }

        val files = all.sortedBy { it.path }
        lines += "Directory traversal: directories=$directoryCount files=${files.size}; " +
            "truncated_by_limit=$truncated; ISO directory contents are NOT extracted."
        val extensions = files.groupingBy { extensionOf(it.name) }.eachCount()
        lines += "File extension counts: " + extensions.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(20).joinToString(", ") { "${it.key}=${it.value}" }

        val relevant = files.filter {
            val ext = extensionOf(it.name)
            ext in EXECUTABLE_EXTENSIONS || ext in RESEARCH_EXTENSIONS ||
                it.name.uppercase().contains("MODULE") ||
                it.name.uppercase().contains("SCRIPT")
        }
        val findings = mutableListOf<FileFinding>()
        var probes = 0
        var staged = 0L
        var elfCount = 0
        var discoveredElf = 0
        var encrypted = 0
        var skipped = 0
        val overlapFindings = mutableListOf<PspOverlayConflictAudit.Finding>()
        for (entry in relevant) {
            cancellation.throwIfCancelled()
            if (!validExtent(entry, image)) {
                if (findings.size < MAX_REPORTED_FILES) {
                    findings += FileFinding(entry.path, entry.size, "INVALID ISO EXTENT; not read")
                }
                continue
            }
            if (probes >= MAX_FILE_HEADER_PROBES) {
                truncated = true
                break
            }
            probes++
            val header = reader.readAt(
                source, entry.dataOffset, minOf(entry.size, MAX_ARCHIVE_HEADER.toLong()).toInt(),
            )
            val kind = classify(header)
            if (extensionOf(entry.name) == "DAT") {
                // Examine a bounded, reproducible sample of sectors, even
                // when the archive header is unrecognized (BBS1..BBS4).
                lines += IsoArchiveSectorResearch.inspect(
                    source, entry, reader, cancellation,
                ).lines
            }
            if (kind == "encrypted PSP ~PSP") encrypted++
            if (kind == "BBSA game archive") {
                lines += inspectBbsaIndex(source, entry, header, cancellation)
            }
            val isMainEboot = normalize(entry.path) == "PSP_GAME/SYSDIR/EBOOT.BIN"
            val candidate = kind == "MIPS ELF32"
            if (candidate) discoveredElf++
            if (findings.size < MAX_REPORTED_FILES) {
                findings += FileFinding(entry.path, entry.size, kind)
            }
            if (!candidate || isMainEboot) continue
            if (elfCount >= MAX_ELF_FILES || entry.size > MAX_ELF_SIZE ||
                entry.size > MAX_TOTAL_ELF_BYTES - staged || entry.size > Int.MAX_VALUE
            ) {
                skipped++
                continue
            }
            // ByteArray is limited to 12 MiB for each additional module.
            // Never stage large DAT/CPK archives just to look for ELF magic.
            val module = reader.readEntry(source, entry)
            cancellation.throwIfCancelled()
            staged += module.size
            elfCount++
            val deep = PspCombatDeepStaticInspector.inspect(module, entry.path)
            val sections = PspCombatStaticAnalysis.sectionMap(module)
            val events = PspCombatEventEvidence.scan(module, sections)
                .filter { it.occurrences.isNotEmpty() }
            lines += "MODULE ${entry.path}: ${entry.size} bytes sha256=${sha256Hex(module)} " +
                "ELF sections=${sections.size} executable_instructions=${deep.executableInstructions} " +
                "JAL=${deep.directCalls} JALR=${deep.jalrCalls}"
            val loadMap = PspElfModuleMap.inspect(module, entry.path)
            lines += loadMap.lines
            overlapFindings += PspOverlayConflictAudit.evaluate(entry.path, loadMap)
            lines += "  File-backed sections: " + if (sections.isEmpty()) "none decoded" else
                sections.take(8).joinToString("; ") {
                    "${it.name}@0x${it.address.toString(16).uppercase()} " +
                        "bytes=${it.length} exec=${it.executable}"
                }
            for (event in events.take(MAX_MODULE_LINES)) {
                lines += "  event-name constant ${event.name}: count=${event.occurrences.size}; " +
                    "handler invocation NOT proven."
            }
            val accesses = deep.offsetResults
                .filter { it.loads > 0 || it.stores > 0 }
                .sortedByDescending { it.loads + it.stores }.take(4)
            if (accesses.isNotEmpty()) {
                lines += "  offset-only opcode candidates (ALL object types): " +
                    accesses.joinToString("; ") {
                        "+0x${it.offset.toString(16)} read=${it.loads} write=${it.stores}"
                    }
            }
        }
        lines += PspOverlayConflictAudit.summarize(overlapFindings, elfCount)
        lines += "ISO candidate file inventory: ${relevant.size} likely module/archive/script file(s), " +
            "sampled_headers=$probes; ELF_magic=$discoveredElf; encrypted_PSP=$encrypted; " +
            "additional_ELF_staged=$elfCount; skipped_ELF_by_budget=$skipped."
        for (finding in findings) {
            lines += "  FILE ${finding.path}: size=${finding.bytes} type=${finding.kind}"
        }
        if (relevant.size > findings.size) {
            lines += "  ...${relevant.size - findings.size} additional candidate path(s) " +
                "not individually logged (bounded output)."
        }
        lines += "COVERAGE LIMITS: ISO has been inventoried only through directory entries and " +
            "selected headers. Packed DAT/ARC/CPK internals, encrypted PRX modules, " +
            "dynamically loaded overlay relocation, script bytecode, and runtime actor " +
            "ownership have NOT been decoded."
        lines += "Safety: no ISO files or game modules were modified. " +
            "No combat patch, code cave or executable hook was generated."
        return Report(files.size, directoryCount, elfCount, probes, truncated, findings, lines)
    }

    private fun inspectBbsaIndex(
        iso: Path,
        entry: IsoDirectoryEntry,
        header: ByteArray,
        cancellation: CancellationToken,
    ): String {
        if (header.size < 0x1c) return "  BBSA ${entry.path}: incomplete header."
        val version = u32(header, 4)
        val indexBytes = u16(header, 0x1a).toLong() * 2048L
        if (version !in 5L..6L || indexBytes < 2048L ||
            indexBytes > MAX_BBSA_INDEX_BYTES || indexBytes > entry.size
        ) {
            return "  BBSA ${entry.path}: version=$version declared_index=$indexBytes " +
                "(invalid/unsupported; index NOT decoded)."
        }
        cancellation.throwIfCancelled()
        val index = reader.readAt(iso, entry.dataOffset, indexBytes.toInt())
        cancellation.throwIfCancelled()
        return "  BBSA ${entry.path}: version=$version index_bytes=$indexBytes " +
            "index_sha256=${sha256Hex(index)} (header/index metadata only)."
    }

    private fun validExtent(entry: IsoDirectoryEntry, image: IsoImageInfo): Boolean =
        !entry.isDirectory && entry.size >= 0L && entry.dataOffset >= 0L &&
            entry.dataOffset <= image.sourceSize &&
            entry.size <= image.sourceSize - entry.dataOffset

    private fun extensionOf(name: String): String {
        val withoutVersion = name.substringBefore(';')
        return withoutVersion.substringAfterLast('.', "").uppercase().ifEmpty { "<none>" }
    }

    private fun normalize(path: String): String =
        path.split('/').joinToString("/") { it.substringBefore(';').uppercase() }

    internal fun classify(header: ByteArray): String = when {
        header.size >= 20 && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() && header[4] == 1.toByte() &&
            header[5] == 1.toByte() && u16(header, 18) == 8 -> "MIPS ELF32"
        header.size >= 4 && header[0] == '~'.code.toByte() &&
            header[1] == 'P'.code.toByte() && header[2] == 'S'.code.toByte() &&
            header[3] == 'P'.code.toByte() -> "encrypted PSP ~PSP"
        header.size >= 4 && header[0] == 'b'.code.toByte() &&
            header[1] == 'b'.code.toByte() && header[2] == 's'.code.toByte() &&
            header[3] == 'a'.code.toByte() -> "BBSA game archive"
        header.size >= 4 && header[0] == 'P'.code.toByte() &&
            header[1] == 'S'.code.toByte() && header[2] == 'A'.code.toByte() &&
            header[3] == 'R'.code.toByte() -> "PSAR container (not decoded)"
        header.size >= 4 && header[0] == 'C'.code.toByte() &&
            header[1] == 'P'.code.toByte() && header[2] == 'K'.code.toByte() &&
            header[3] == ' '.code.toByte() -> "CPK archive (not decoded)"
        header.size >= 4 && header[0] == 'P'.code.toByte() &&
            header[1] == 'K'.code.toByte() && header[2] == 3.toByte() &&
            header[3] == 4.toByte() -> "ZIP archive (not decoded)"
        header.size >= 4 && header[0] == 0x7f.toByte() &&
            header[1] == 'E'.code.toByte() && header[2] == 'L'.code.toByte() &&
            header[3] == 'F'.code.toByte() -> "ELF other/unsupported; not disassembled"
        header.isEmpty() -> "empty file"
        else -> "unrecognized raw or packed data"
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)
}
