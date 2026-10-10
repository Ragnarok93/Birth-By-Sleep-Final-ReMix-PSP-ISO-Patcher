package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import okio.Path

/**
 * Sample-only, read-only reconnaissance of the BBSA Lua directory category.
 *
 * The demonstrated g01.lub chunk has gimmick-oriented OnInit/OnUpdate
 * constants, with no exact hit-event/native-combat API constants.
 * This survey deliberately pivots to DIFFERENT Lua-index entries; it does
 * not extrapolate one gimmick script to all game scripts.
 *
 * It reads at most 24 files of <= 8 KiB, selected deterministically from
 * the bounded verified BBSA 12-byte directory index. It DOES NOT extract
 * files, execute bytecode, infer runtime dispatch or modify the ISO.
 */
internal object IsoBbsaLuaCategorySurvey {
    private const val HEADER_BYTES = 0x30
    private const val RECORD_BYTES = 12L
    private const val MAX_DIRECTORY_RECORDS = 32768
    private const val MAX_INDEX_BYTES = 4 * 1024 * 1024
    private const val LUA_DIRECTORY_ID = 0xC0000000L
    private const val SECTOR_BYTES = 2048L
    private const val MAX_SAMPLES = 24
    private const val MAX_SUMMARY_NAMES = 8

    data class Entry(
        val indexOffset: Int,
        val fileHash: Long,
        val logicalSector: Long,
        val sectors: Int,
    )

    data class Sample(
        val record: Entry,
        val location: IsoBbsaIndexedPayloadProbe.Location?,
        val result: String,
        val callbacks: List<String> = emptyList(),
        val hitEvents: List<String> = emptyList(),
        val combatApis: List<String> = emptyList(),
    )

    data class Report(
        val indexValid: Boolean,
        val luaRecords: Int,
        val eligibleRecords: Int,
        val samples: List<Sample>,
        val lines: List<String>,
    )

    /**
     * This pure index decoder checks the filename and path-ID fields in their
     * documented order and never confuses an invalid table with zero Lua.
     */
    internal fun entries(index: ByteArray): List<Entry>? {
        if (index.size !in HEADER_BYTES..MAX_INDEX_BYTES ||
            index[0] != 'b'.code.toByte() ||
            index[1] != 'b'.code.toByte() ||
            index[2] != 's'.code.toByte() ||
            index[3] != 'a'.code.toByte() ||
            u32(index, 4) !in 5L..6L
        ) return null
        val count = u16(index, 0x0e)
        val base = u32(index, 0x14)
        if (count !in 1..MAX_DIRECTORY_RECORDS ||
            base < HEADER_BYTES || base > index.size ||
            count.toLong() > (index.size - base) / RECORD_BYTES
        ) return null
        val records = mutableListOf<Entry>()
        for (i in 0 until count) {
            val offset = base.toInt() + i * RECORD_BYTES.toInt()
            if (u32(index, offset + 8) != LUA_DIRECTORY_ID) continue
            val packed = u32(index, offset + 4)
            records += Entry(offset, u32(index, offset),
                packed ushr 12, (packed and 0xfff).toInt())
        }
        return records
    }

    /**
     * Spread probes across the ENTIRE eligible index range, including first
     * and last, without making promises about unvisited entries.
     */
    internal fun select(samples: List<Entry>, limit: Int = MAX_SAMPLES): List<Entry> {
        if (samples.isEmpty() || limit <= 0) return emptyList()
        if (samples.size <= limit) return samples
        if (limit == 1) return listOf(samples[samples.size / 2])
        return (0 until limit).map { k ->
            samples[(k.toLong() * (samples.size - 1) / (limit - 1)).toInt()]
        }.distinctBy { it.indexOffset }
    }

    fun inspect(
        source: Path,
        index: ByteArray,
        archiveFiles: Map<Int, IsoDirectoryEntry>,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "BBSA LUA CATEGORY SURVEY — READ ONLY; deterministic sample, NOT exhaustive:",
        )
        val indexed = entries(index) ?: return Report(false, 0, 0, emptyList(),
            lines + "  UNVERIFIED BBSA 12-byte directory table; no Lua-category census.")
        cancellation.throwIfCancelled()
        // Skip already-inspected gimmick script G01 so the bounded budget
        // explores other Lua filename hashes; retain it in the census.
        val alreadyProbedG01 = IsoBbsaDirectoryEvidence.fileNameHash("G01")
        val eligible = indexed.filter {
            it.fileHash != alreadyProbedG01 &&
                it.sectors in 1..(IsoLua51MetadataInspector.MAX_INPUT_BYTES / SECTOR_BYTES).toInt()
        }
        val chosen = select(eligible)
        val samples = mutableListOf<Sample>()
        var validChunks = 0
        var hitCandidates = 0
        var apiCandidates = 0
        var rejected = 0
        val exampleNames = mutableListOf<String>()

        for (entry in chosen) {
            cancellation.throwIfCancelled()
            val location = IsoBbsaIndexedPayloadProbe.map(index,
                entry.logicalSector, entry.sectors)
            val dat = location?.let { archiveFiles[it.archiveIndex] }
            val len = entry.sectors.toLong() * SECTOR_BYTES
            if (location == null || dat == null || dat.isDirectory ||
                location.archiveRelativeByteOffset > dat.size ||
                len > dat.size - location.archiveRelativeByteOffset
            ) {
                rejected++
                samples += Sample(entry, location, "UNVERIFIED archive mapping/extent")
                continue
            }
            // Exact validated logical mapping, DAT extent, and max input bound.
            val bytes = reader.readAt(source,
                dat.dataOffset + location.archiveRelativeByteOffset, len.toInt())
            cancellation.throwIfCancelled()
            val metadata = IsoLua51MetadataInspector.inspect(bytes)
            if (!metadata.valid) {
                rejected++
                samples += Sample(entry, location,
                    "UNVERIFIED Lua 5.1 chunk: ${metadata.reason}")
                continue
            }
            validChunks++
            val callbacks = metadata.callbackNameConstants
            val hits = metadata.hitEventNameConstants
            val apis = metadata.nativeApiNameConstants
            if (hits.isNotEmpty()) hitCandidates++
            if (apis.isNotEmpty()) apiCandidates++
            if (exampleNames.size < MAX_SUMMARY_NAMES) {
                exampleNames += "name_hash=${hex(entry.fileHash)} " +
                    "functions=${metadata.functions} instructions=${metadata.instructions} " +
                    "callbacks=${callbacks.joinToString(",").ifEmpty { "-" }}"
            }
            samples += Sample(entry, location, "VALID Lua 5.1 structure",
                callbacks, hits, apis)
        }
        lines += "  BBSA directory lua-category entries=${indexed.size}; " +
            "eligible_other_small_chunks=${eligible.size} " +
            "(excluding G01, allocated 1..4 sectors); sampled=${samples.size}/${eligible.size} " +
            "(max=$MAX_SAMPLES, evenly spaced by index order)."
        lines += "  Among sampled: valid_Lua51=$validChunks " +
            "unverified_format_or_mapping=$rejected " +
            "chunks_with_exact_hit_event_name_constants=$hitCandidates " +
            "chunks_with_exact_known_native_combat_API_name_constants=$apiCandidates."
        for (sample in samples) {
            if (sample.hitEvents.isEmpty() && sample.combatApis.isEmpty()) continue
            lines += "  COMBAT STRING CANDIDATE filename_hash=${hex(sample.record.fileHash)} " +
                "index_at=${sample.record.indexOffset} " +
                "sector=${sample.record.logicalSector} sectors=${sample.record.sectors} " +
                "physical=${sample.location?.let {
                    "BBS${it.archiveIndex}.DAT:${it.physicalSector}"
                } ?: "UNVERIFIED"} " +
                "hit_event_constants=${sample.hitEvents.joinToString(",").ifEmpty { "-" }} " +
                "native_API_constants=${sample.combatApis.joinToString(",").ifEmpty { "-" }} " +
                "callback_constants=${sample.callbacks.take(8).joinToString(",").ifEmpty { "-" }}"
        }
        for (line in exampleNames) lines += "  LUA example $line"
        if (samples.isEmpty()) lines += "  No eligible non-G01 Lua records for bounded preview."
        lines += "  LIMIT: This is only an evenly spaced subset of short indexed Lua " +
            "chunks, not a full-Lua-index scan. No-match in sampled files does NOT " +
            "imply absent combat scripts elsewhere. Constant-table names alone " +
            "do NOT prove callback definitions or execution. No game files modified."
        return Report(true, indexed.size, eligible.size, samples, lines)
    }

    private fun hex(value: Long): String =
        "0x" + value.toString(16).uppercase().padStart(8, '0')
    private fun u16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8)
    private fun u32(bytes: ByteArray, at: Int): Long =
        u16(bytes, at).toLong() or (u16(bytes, at + 2).toLong() shl 16)
}
