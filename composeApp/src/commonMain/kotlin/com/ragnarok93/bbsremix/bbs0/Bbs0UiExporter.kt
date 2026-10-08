package com.ragnarok93.bbsremix.bbs0

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.FileSystem
import okio.Path
import okio.buffer

data class Bbs0UiExportResult(
    val archiveCount: Int,
    val layoutsFound: Int,
    val externalLinks: Int,
    val layoutsExported: Int,
    val zipSize: Long,
    val standaloneCtdLocated: Int = 0,
    val standaloneCtdExported: Int = 0,
)

internal data class Bbs0ZipEntry(val name: String, val bytes: ByteArray)

internal expect fun writeBbs0Zip(
    destination: Path,
    entries: List<Bbs0ZipEntry>,
    cancellation: CancellationToken,
)

/**
 * OpenKh BBSA/ARC sector scan. This is an investigative export, not a
 * production-safe game UI scaling patch. All source bytes are read-only.
 */
object Bbs0UiExporter {
    private const val SECTOR = 2048L
    private const val MAX_INDEX = 4 * 1024 * 1024
    private const val MAX_ASSET = 4 * 1024 * 1024
    private const val MAX_EXPORT = 12 * 1024 * 1024
    private val priorities = listOf(
        "hud", "command", "comm", "deck", "gauge", "face", "portrait",
        "shot", "lock", "focus", "pause", "camp", "menu", "sub", "text", "board",
    )

    private data class Candidate(
        val name: String,
        val kind: String,
        val offset: Long,
        val size: Int,
        val arcOffset: Long,
        val sha: String,
    )

    fun export(
        input: Path,
        output: Path,
        metadataOnly: Boolean = false,
        cancellation: CancellationToken = NeverCancelled,
        progress: (Long, Long) -> Unit = { _, _ -> },
        fs: FileSystem = FileSystem.SYSTEM,
    ): Bbs0UiExportResult {
        require(input != output) { "The BBS0 source cannot be the export destination." }
        require(!fs.exists(output)) { "The ZIP output already exists." }
        var success = false
        try {
            fs.openReadOnly(input).use { handle ->
                val length = handle.size()
                fun read(offset: Long, size: Int): ByteArray {
                    require(offset >= 0 && size >= 0 && offset <= length &&
                        size.toLong() <= length - offset) {
                        "BBS0 has an invalid resource address."
                    }
                    return handle.source(offset).buffer().use { it.readByteArray(size.toLong()) }
                }
                require(length >= SECTOR) { "BBS0.DAT is too small." }
                val header = read(0, 0x30)
                require(header.copyOfRange(0, 4).contentEquals("bbsa".encodeToByteArray())) {
                    "The selected file is not a BBSA BBS0.DAT archive."
                }
                val version = header.u32(4)
                require(version in 5..6) { "Unsupported BBSA version: " + version }
                val indexLength = header.u16(0x1a).toLong() * SECTOR
                require(indexLength in SECTOR..MAX_INDEX.toLong() && indexLength <= length) {
                    "BBS0 has an invalid BBSA index boundary."
                }
                val index = read(0L, indexLength.toInt())
                var arcCount = 0
                val assets = mutableListOf<Candidate>()
                val links = mutableListOf<String>()
                val linkedCtd = mutableListOf<Bbs0IndexedCtd.Reference>()
                var offset = indexLength
                while (offset + 16 <= length) {
                    cancellation.throwIfCancelled()
                    val signature = read(offset, 16)
                    if (signature.copyOfRange(0, 4).contentEquals(byteArrayOf(65, 82, 67, 0)) &&
                        signature.u16(4) == 1
                    ) {
                        val count = signature.u16(6)
                        if (count in 1..1024 && 16L + count * 32L <= length - offset) {
                            val table = read(offset, 16 + count * 32)
                            val pendingAssets = mutableListOf<Candidate>()
                            val pendingLinks = mutableListOf<String>()
                            val pendingCtd = mutableListOf<Bbs0IndexedCtd.Reference>()
                            var valid = true
                            for (i in 0 until count) {
                                val entry = 16 + i * 32
                                val name = parseName(table, entry + 16)
                                if (name == null) {
                                    valid = false
                                    break
                                }
                                val kind = when {
                                    name.endsWith(".l2d", ignoreCase = true) -> "l2d"
                                    name.endsWith(".ctd", ignoreCase = true) -> "ctd"
                                    else -> ""
                                }
                                if (table.u32(entry) != 0) {
                                    if (kind.isNotEmpty()) {
                                        if (kind == "ctd") {
                                            pendingCtd += Bbs0IndexedCtd.Reference(name, table.u32(entry))
                                        }
                                        pendingLinks += jsonObj(
                                            "arc_offset" to offset.toString(),
                                            "name" to quote(name),
                                            "directory_hash" to quote(table.u32(entry).toUInt().toString(16)),
                                        )
                                    }
                                    continue
                                }
                                val rel = table.u32(entry + 4)
                                val size = table.u32(entry + 8)
                                if (rel < 16 + count * 32 || size < 0 ||
                                    rel.toLong() > length - offset ||
                                    size.toLong() > length - offset - rel.toLong()
                                ) {
                                    valid = false
                                    break
                                }
                                if (kind.isEmpty() || size !in 64..MAX_ASSET) continue
                                val assetOffset = offset + rel
                                val magic = read(assetOffset, 64)
                                val matches = if (kind == "l2d") {
                                    magic.copyOfRange(0, 4).contentEquals("L2D@".encodeToByteArray()) &&
                                        magic.u32(0x2c) == size
                                } else {
                                    magic.copyOfRange(0, 4).contentEquals("@CTD".encodeToByteArray())
                                }
                                if (!matches) continue
                                pendingAssets += Candidate(
                                    name, kind, assetOffset, size, offset,
                                    sha256Hex(read(assetOffset, size)),
                                )
                            }
                            if (valid) {
                                arcCount++
                                assets.addAll(pendingAssets)
                                links.addAll(pendingLinks)
                                linkedCtd.addAll(pendingCtd)
                            }
                        }
                    }
                    offset += SECTOR
                    if (offset % (SECTOR * 1024) == 0L) progress(offset.coerceAtMost(length), length)
                }
                progress(length, length)
                val ctd = Bbs0IndexedCtd.resolve(
                    index, length, { offset, count -> read(offset, count) }, linkedCtd, cancellation,
                )
                val entries = mutableListOf(Bbs0ZipEntry("bbs0/index.bin", index))
                val exported = mutableSetOf<Long>()
                val exportedCtd = mutableSetOf<Long>()
                var total = 0
                if (!metadataOnly) {
                    for (item in ctd.resources) {
                        cancellation.throwIfCancelled()
                        if (item.data.size > MAX_EXPORT - total ||
                            item.offset in exportedCtd
                        ) continue
                        val name = item.name.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                        entries += Bbs0ZipEntry(
                            "bbs0/ctd/" + item.offset.toString(16).padStart(8, '0') + "_" + name,
                            item.data,
                        )
                        total += item.data.size
                        exportedCtd += item.offset
                    }
                }
                if (!metadataOnly) {
                    val sorted = assets.sortedWith(
                        compareByDescending<Candidate> { item ->
                            priorities.any { item.name.lowercase().contains(it) }
                        }.thenBy { it.size }.thenBy { it.offset }
                    )
                    for (asset in sorted) {
                        cancellation.throwIfCancelled()
                        if (asset.size > MAX_EXPORT - total) continue
                        val bytes = read(asset.offset, asset.size)
                        check(sha256Hex(bytes) == asset.sha) { "BBS0 changed during export." }
                        val safe = asset.name.map { char ->
                            if (char.isLetterOrDigit() || char in "_.-") char else '_'
                        }.joinToString("")
                        entries += Bbs0ZipEntry(
                            "bbs0/assets/" + asset.offset.toString(16).padStart(8, '0') + "_" + safe, bytes,
                        )
                        total += bytes.size
                        exported += asset.offset
                    }
                }
                val manifest = buildString {
                    append("{\n\"schema\":1,\n\"source\":\"BBS0.DAT\",\n")
                    append("\"source_bytes\":").append(length).append(",\n")
                    append("\"bbsa_version\":").append(version).append(",\n")
                    append("\"index_bytes\":").append(indexLength).append(",\n")
                    append("\"index_sha256\":").append(quote(sha256Hex(index))).append(",\n")
                    append("\"arc_count\":").append(arcCount).append(",\n")
                    append("\"external_link_count\":").append(links.size).append(",\n")
                    append("\"standalone_ctd_count\":").append(ctd.resources.size).append(",\n")
                    append("\"standalone_ctd_exported\":").append(exportedCtd.size).append(",\n")
                    append("\"metadata_only\":").append(metadataOnly).append(",\n")
                    append("\"assets\":[\n")
                    assets.forEachIndexed { i, asset ->
                        if (i != 0) append(",\n")
                        append(jsonObj(
                            "name" to quote(asset.name), "kind" to quote(asset.kind),
                            "arc_offset" to asset.arcOffset.toString(), "offset" to asset.offset.toString(),
                            "size" to asset.size.toString(), "sha256" to quote(asset.sha),
                            "exported" to (asset.offset in exported).toString(),
                        ))
                    }
                    append("],\n\"standalone_ctds\":[\n")
                    ctd.resources.forEachIndexed { i, item ->
                        if (i != 0) append(",\n")
                        append(jsonObj(
                            "name" to quote(item.name),
                            "directory_hash" to quote(item.directoryHash.toUInt().toString(16)),
                            "offset" to item.offset.toString(),
                            "size" to item.data.size.toString(),
                            "sha256" to quote(item.sha256),
                            "exported" to (item.offset in exportedCtd).toString(),
                        ))
                    }
                    append("],\n\"unresolved_ctd_links\":[")
                    ctd.unresolved.forEachIndexed { i, name ->
                        if (i != 0) append(",")
                        append(quote(name))
                    }
                    append("],\n\"links\":[\n")
                    links.forEachIndexed { i, item ->
                        if (i != 0) append(",\n")
                        append(item)
                    }
                    append("]\n}\n")
                }
                entries += Bbs0ZipEntry("bbs0/ui_manifest.json", manifest.encodeToByteArray())
                cancellation.throwIfCancelled()
                writeBbs0Zip(output, entries, cancellation)
                success = true
                return Bbs0UiExportResult(
                    arcCount, assets.size, links.size, exported.size, fs.metadata(output).size ?: 0L,
                    ctd.resources.size, exportedCtd.size,
                )
            }
        } finally {
            if (!success && fs.exists(output)) fs.delete(output, mustExist = false)
        }
    }

    private fun parseName(bytes: ByteArray, offset: Int): String? {
        val end = (offset until offset + 16).firstOrNull { bytes[it] == 0.toByte() }
            ?: offset + 16
        if (end == offset) return null
        val name = bytes.copyOfRange(offset, end).decodeToString()
        return name.takeIf {
            it.isNotBlank() && it != "." && it != ".." &&
                it.all { c -> c.code in 32..126 && c != '/' && c != '\\' }
        }
    }

    private fun ByteArray.u16(offset: Int): Int =
        (this[offset].toInt() and 255) or ((this[offset + 1].toInt() and 255) shl 8)

    private fun ByteArray.u32(offset: Int): Int = u16(offset) or (u16(offset + 2) shl 16)

    private fun quote(s: String): String = buildString {
        append('"')
        s.forEach { c ->
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                else -> append(if (c.code < 32) '?' else c)
            }
        }
        append('"')
    }

    private fun jsonObj(vararg values: Pair<String, String>): String =
        values.joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
            quote(key) + ":" + value
        }
}
