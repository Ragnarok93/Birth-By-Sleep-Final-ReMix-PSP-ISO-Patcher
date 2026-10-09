package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import okio.Path

/**
 * Bounded read-only check of sampled ARC v1 directory candidates.
 *
 * The outer 16-byte header and 32-byte entries follow the existing BBS0
 * research/exporter layout. A valid directory is structural evidence ONLY:
 * hashes/links/record offsets do not establish combat behavior, and payload
 * bytes are never extracted.
 */
internal object IsoArcMetadataProbe {
    private const val MAX_ENTRIES = 1024
    private const val ENTRY_BYTES = 32
    private const val HEADER_BYTES = 16
    private const val MAX_SAMPLES = 12

    internal data class Entry(
        val name: String,
        val isExternalLink: Boolean,
        val reference: Long,
        val payloadOffset: Long,
        val payloadSize: Long,
    )

    data class Result(
        val valid: Boolean,
        val entryCount: Int,
        val validatedEntries: Int,
        val externalLinks: Int,
        val payloadRecords: Int,
        val sampledNames: List<String>,
        val observations: List<String>,
        // Populated only when all records pass structural checks. At most
        // 1024 records are stored; no game asset payloads are read.
        val entries: List<Entry> = emptyList(),
    )

    fun inspect(
        source: Path,
        archive: IsoDirectoryEntry,
        archiveRelativeOffset: Long,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Result {
        cancellation.throwIfCancelled()
        val remaining = archive.size - archiveRelativeOffset
        if (archiveRelativeOffset < 0 || remaining < HEADER_BYTES) return rejected("ARC header outside archive")
        val head = reader.readAt(source, archive.dataOffset + archiveRelativeOffset, HEADER_BYTES)
        if (!hasArcMagic(head)) return rejected("No ARC v1 magic at this location")
        val version = u16(head, 4)
        val count = u16(head, 6)
        if (version != 1 || count !in 1..MAX_ENTRIES) {
            return rejected("Unsupported ARC version/count: version=$version entries=$count")
        }
        val tableBytes = HEADER_BYTES + count * ENTRY_BYTES
        if (tableBytes.toLong() > remaining) return rejected("ARC directory extends outside archive")
        cancellation.throwIfCancelled()
        val table = reader.readAt(source, archive.dataOffset + archiveRelativeOffset, tableBytes)
        cancellation.throwIfCancelled()
        return inspectTable(table, remaining)
    }

    /** Pure decoder for exactly one ARC directory header+table. */
    internal fun inspectTable(table: ByteArray, availableArchiveBytes: Long): Result {
        if (table.size < HEADER_BYTES || !hasArcMagic(table) || u16(table, 4) != 1) {
            return rejected("Not a supported ARC v1 directory")
        }
        val count = u16(table, 6)
        if (count !in 1..MAX_ENTRIES) return rejected("ARC entry count outside bounded range")
        val headerLength = HEADER_BYTES + count * ENTRY_BYTES
        if (headerLength > table.size || headerLength.toLong() > availableArchiveBytes) {
            return rejected("ARC directory length exceeds source bounds")
        }

        val entries = ArrayList<Entry>(count)
        var errors = 0
        for (i in 0 until count) {
            val at = HEADER_BYTES + i * ENTRY_BYTES
            val rawName = table.copyOfRange(at + 16, at + 32)
            val length = rawName.indexOf(0.toByte()).let { if (it < 0) rawName.size else it }
            val text = rawName.copyOfRange(0, length)
            // The existing BBS0 scanner accepts 7-bit printable, slash-free
            // names. Do not interpret arbitrary payload bytes as a filename.
            val validName = text.isNotEmpty() && text.all {
                val c = it.toInt() and 0xff
                c in 32..126 && c != '/'.code && c != '\\'.code
            }
            val name = if (validName) text.decodeToString() else "<invalid>"
            val hashOrLink = u32(table, at)
            val offset = u32(table, at + 4)
            val size = u32(table, at + 8)
            val external = hashOrLink != 0L
            val validPayload = external ||
                (offset >= headerLength.toLong() && size > 0L &&
                    offset <= availableArchiveBytes &&
                    size <= availableArchiveBytes - offset)
            if (!validName || name == "." || name == ".." || !validPayload) errors++
            entries += Entry(name, external, hashOrLink, offset, size)
        }
        val good = count - errors
        val valid = good == count
        val links = entries.count { it.isExternalLink }
        val payloads = entries.size - links
        val names = entries.take(MAX_SAMPLES).map {
            it.name + if (it.isExternalLink) " [link]" else " [payload]"
        }
        val observations = mutableListOf(
            "ARC v1 table: declared=$count valid_records=$good external_links=$links " +
                "payload_records=$payloads structural_status=${if (valid) "VALID" else "UNVERIFIED"}",
        )
        observations += "Name examples: " + names.joinToString(", ")
        if (valid) {
            // Directory metadata is actionable only with its actual link
            // identifier or bounded archive-relative payload address.
            // Do not label a "lua" name as a confirmed script file.
            entries.take(MAX_SAMPLES).forEach { entry ->
                observations += if (entry.isExternalLink) {
                    "  external_ref ${entry.name}: raw_id=0x" +
                        entry.reference.toString(16).uppercase().padStart(8, '0') +
                        (if (entry.name.contains("lua", ignoreCase = true)) {
                            " (script-name candidate ONLY; destination unresolved)"
                        } else "")
                } else {
                    "  local_resource ${entry.name}: arc_relative_offset=${entry.payloadOffset}" +
                        " size=${entry.payloadSize} (bytes not extracted)"
                }
            }
        }
        if (count > MAX_SAMPLES) observations += "  ...${count - MAX_SAMPLES} additional entry names not displayed."
        observations += if (valid)
            "ARC table structural bounds passed; resource identity/meaning NOT proven."
        else
            "ARC table has $errors invalid name/range record(s); treat magic as a candidate only."
        return Result(valid, count, good, links, payloads, names, observations,
            if (valid) entries else emptyList())
    }

    private fun hasArcMagic(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_BYTES &&
            bytes[0] == 'A'.code.toByte() && bytes[1] == 'R'.code.toByte() &&
            bytes[2] == 'C'.code.toByte() && bytes[3] == 0.toByte()

    private fun rejected(reason: String) =
        Result(false, 0, 0, 0, 0, emptyList(), listOf("ARC candidate rejected: $reason"))

    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)

    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
