package com.ragnarok93.bbsremix.iso

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NeverCancelled
import com.ragnarok93.bbsremix.patch.sha256Hex
import okio.Path

/**
 * Read-only full census of every indexed BBSA Lua-category record.
 *
 * Walk all records, including malformed and duplicate entries. Stage just one
 * script at a time; no code execution, game extraction or ISO mutation.
 * An indexed census is not proof that every script used by the game is indexed.
 */
internal object IsoBbsaExhaustiveCombatSurvey {
    private const val SECTOR = 2048L
    private val MAX_SCRIPT = IsoLua51MetadataInspector.MAX_INPUT_BYTES.toLong()
    private const val MAX_TOTAL_READ = 32L * 1024L * 1024L

    data class Decision(val reason: String?, val absoluteOffset: Long?, val length: Int) {
        val permitted: Boolean get() = reason == null
    }

    internal fun decide(
        entry: IsoBbsaLuaCategorySurvey.Entry,
        archive: IsoDirectoryEntry?,
        location: IsoBbsaIndexedPayloadProbe.Location?,
        bytesAlreadyRead: Long,
    ): Decision {
        val sectors = entry.sectors.toLong()
        if (sectors !in 1..0xffe) return Decision("ZERO_OR_SENTINEL_SECTORS", null, 0)
        if (sectors > MAX_SCRIPT / SECTOR)
            return Decision("SCRIPT_EXCEEDS_${MAX_SCRIPT}_BYTES", null, 0)
        if (bytesAlreadyRead < 0 || bytesAlreadyRead > MAX_TOTAL_READ)
            return Decision("TOTAL_READ_BUDGET_EXCEEDED", null, 0)
        val length = sectors * SECTOR
        if (length > MAX_TOTAL_READ - bytesAlreadyRead)
            return Decision("TOTAL_READ_BUDGET_EXCEEDED", null, 0)
        if (location == null || archive == null || archive.isDirectory)
            return Decision("UNMAPPED_ARCHIVE_OR_MISSING_DAT", null, 0)
        val offset = location.archiveRelativeByteOffset
        if (offset < 0 || archive.dataOffset < 0 || archive.size < 0 ||
            offset > archive.size || length > archive.size - offset ||
            archive.dataOffset > Long.MAX_VALUE - offset)
            return Decision("OUTSIDE_PHYSICAL_DAT_EXTENT", null, 0)
        return Decision(null, archive.dataOffset + offset, length.toInt())
    }

    /** Only structurally detected symbol names, not executed callback paths. */
    internal data class ScriptCrossReference(
        val hash: Long,
        val filenameHint: String,
        val nativeApiNames: List<String>,
        val hitEventNames: List<String>,
        val callbackNames: List<String>,
    )

    /** Explicit common-feature overlaps for triaging actor-local Lua scripts. */
    internal fun candidateLines(candidates: List<ScriptCrossReference>): List<String> {
        val lines = mutableListOf(
            "COMBAT SCRIPT CANDIDATE MATRIX — name/constant co-occurrence only, NOT player ownership:"
        )
        for (api in listOf(
            "SetTrgFlagCancel", "IsTrgFlagCancel", "IsAttacking",
            "GetPlayerState", "GetCommandCategory", "GetCommandKind",
            "EnableInvincible", "SetPlayerFlagInvincible", "GetMotionNowFrame",
        )) {
            val matching = candidates.filter { api in it.nativeApiNames }
            lines += "  API_MATRIX $api script_count=${matching.size}"
            for (item in matching) {
                lines += "    SCRIPT name_hint=${item.filenameHint} " +
                    "hash=0x${item.hash.toString(16).uppercase().padStart(8, '0')} " +
                    "has_OnHitAttack=${"OnHitAttack" in item.hitEventNames} " +
                    "has_OnUpdate=${"OnUpdate" in item.callbackNames} " +
                    "has_OnCommand=${"OnCommand" in item.callbackNames} " +
                    "other_apis=${item.nativeApiNames.filter { it != api }.joinToString(",").ifEmpty { "-" }}"
            }
        }
        val overlaps = candidates.filter {
            "OnHitAttack" in it.hitEventNames &&
                "GetPlayerState" in it.nativeApiNames &&
                "IsAttacking" in it.nativeApiNames
        }
        lines += "  HIT_PLAYER_ATTACK_NAME_OVERLAP scripts=${overlaps.size}; " +
            "potential investigative leads, not proof of player hit-confirm."
        for (item in overlaps) {
            lines += "    OVERLAP name_hint=${item.filenameHint} hash=0x${item.hash.toString(16)}"
        }
        return lines
    }

    data class Report(
        val indexed: Int,
        val parsed: Int,
        val rejected: Int,
        val totalReadBytes: Long,
        val lines: List<String>,
    )

    fun inspect(
        source: Path,
        index: ByteArray,
        archives: Map<Int, IsoDirectoryEntry>,
        reader: Iso9660Reader,
        cancellation: CancellationToken = NeverCancelled,
    ): Report {
        val lines = mutableListOf(
            "EXHAUSTIVE INDEXED BBSA LUA COMBAT SURVEY — READ ONLY",
            "  All indexed category 0xC0000000 records, not a 24-entry sample.",
            "  Per-script cap=$MAX_SCRIPT total byte-read cap=$MAX_TOTAL_READ; invalid records are reported.",
        )
        val entries = IsoBbsaLuaCategorySurvey.entries(index)
            ?: return Report(0, 0, 1, 0, lines + "  INVALID BBSA INDEX; survey refused.")
        var parsed = 0
        var rejected = 0
        var byteCount = 0L
        var hitStrings = 0
        var hitWrites = 0
        var nativeApiNames = 0
        var scriptsWithCallbacks = 0
        val seenChunks = mutableMapOf<String, MutableList<Int>>()
        val byApi = mutableMapOf<String, Int>()
        val byEvent = mutableMapOf<String, Int>()
        val byCallback = mutableMapOf<String, Int>()
        val scriptCrossReferences = mutableListOf<ScriptCrossReference>()
        for ((ordinal, entry) in entries.withIndex()) {
            cancellation.throwIfCancelled()
            val location = IsoBbsaIndexedPayloadProbe.map(
                index, entry.logicalSector, entry.sectors
            )
            val dat = location?.let { archives[it.archiveIndex] }
            val decision = decide(entry, dat, location, byteCount)
            val prefix = "  LUA[${ordinal + 1}/${entries.size}] index_off=0x${entry.indexOffset.toString(16)} " +
                "hash=0x${entry.fileHash.toString(16).uppercase().padStart(8, '0')} " +
                "name_hint=${IsoBbsaLuaCategorySurvey.nameHint(entry.fileHash)} " +
                "logical_sector=${entry.logicalSector} sectors=${entry.sectors} " +
                "physical=${location?.let { "BBS${it.archiveIndex}.DAT sector=${it.physicalSector}" } ?: "unmapped"}"
            if (!decision.permitted) {
                rejected++
                lines += "$prefix REJECTED=${decision.reason}"
                continue
            }
            val content = reader.readAt(source, decision.absoluteOffset!!, decision.length)
            cancellation.throwIfCancelled()
            byteCount += decision.length
            val digest = sha256Hex(content)
            val previous = seenChunks.getOrPut(digest) { mutableListOf() }
            val duplicates = previous.toList()
            previous += entry.indexOffset
            val report = IsoLua51MetadataInspector.inspect(content)
            if (!report.valid) {
                rejected++
                lines += "$prefix sha256=$digest UNPARSED=${report.reason}"
                continue
            }
            parsed++
            val hits = report.hitEventNameConstants
            val apis = report.nativeApiNameConstants
            val callbacks = report.callbackNameConstants
            if (hits.isNotEmpty()) hitStrings++
            if (apis.isNotEmpty()) nativeApiNames++
            if (callbacks.isNotEmpty()) scriptsWithCallbacks++
            hitWrites += report.hitEventTableWrites.size
            hits.forEach { byEvent[it] = (byEvent[it] ?: 0) + 1 }
            apis.forEach { byApi[it] = (byApi[it] ?: 0) + 1 }
            callbacks.forEach { byCallback[it] = (byCallback[it] ?: 0) + 1 }
            scriptCrossReferences += ScriptCrossReference(
                entry.fileHash, IsoBbsaLuaCategorySurvey.nameHint(entry.fileHash),
                apis, hits, callbacks,
            )
            lines += "$prefix sha256=$digest duplicate_of_indexes=${duplicates.joinToString(",") { "0x" + it.toString(16) }.ifEmpty { "-" }} " +
                "LUA51_VALID functions=${report.functions} instructions=${report.instructions} " +
                "constants=${report.constantStrings} consumed=${report.consumedBytes}/${content.size} " +
                "hit_event_names=${hits.joinToString(",").ifEmpty { "-" }} " +
                "native_apis=${apis.joinToString(",").ifEmpty { "-" }} " +
                "callbacks=${callbacks.joinToString(",").ifEmpty { "-" }} " +
                "table_writes=${report.hitEventTableWrites.size}"
            for (proto in report.prototypeSummaries) {
                lines += "    PROTO ordinal=${proto.ordinal} depth=${proto.depth} " +
                    "line=${proto.lineStart}..${proto.lineEnd} params=${proto.parameters} " +
                    "instructions=${proto.instructions} upvalues=${proto.upvalueCount} " +
                    "combat=${proto.combatKeywordConstants.joinToString(",").ifEmpty { "-" }} " +
                    "api=${proto.nativeApiNameConstants.joinToString(",").ifEmpty { "-" }} " +
                    "callbacks=${proto.callbackNameConstants.joinToString(",").ifEmpty { "-" }}"
            }
            for (ref in report.opcodeSymbolReferences) {
                lines += "    LUA_XREF proto=${ref.ordinal} pc=${ref.pc} opcode=${ref.opcode} " +
                    "symbol=${ref.constantName} role=${ref.role}"
            }
            for (write in report.hitEventTableWrites) {
                lines += "    HIT_WRITE proto=${write.ordinal} pc=${write.pc} " +
                    "event=${write.eventName} table_reg=${write.tableRegister} " +
                    "value=${write.valueOperand} closure=${write.closureProvenance} " +
                    "child=${write.adjacentClosureFunctionOrdinal ?: "UNRESOLVED"} " +
                    "child_instructions=${write.adjacentClosureInstructionCount ?: "UNRESOLVED"} " +
                    "child_apis=${write.adjacentClosureNativeApis.joinToString(",").ifEmpty { "-" }}"
                for (window in write.handlerOpcodeWindows) {
                    lines += "      HANDLER_XREF pc=${window.pc} op=${window.opcode} symbol=${window.constantName}"
                    lines += window.context.map { "        $it" }
                }
                lines += write.unresolvedPriorInstructions.map { "      UNRESOLVED_PRIOR $it" }
            }
        }
        lines += "LUA FULL CENSUS: indexed=${entries.size} structurally_parsed=$parsed rejected=$rejected " +
            "hashed_bytes=$byteCount scripts_with_hit_name=$hitStrings " +
            "scripts_with_native_api_name=$nativeApiNames " +
            "scripts_with_callback_names=$scriptsWithCallbacks hit_table_writes=$hitWrites " +
            "unique_chunk_digests=${seenChunks.size}"
        for ((label, counts) in listOf(
            "HIT_EVENT" to byEvent,
            "NATIVE_API" to byApi,
            "CALLBACK" to byCallback,
        )) {
            for ((name, count) in counts.toSortedMap()) lines += "  $label $name scripts=$count"
        }
        lines += candidateLines(scriptCrossReferences)
        lines += "FULL CENSUS LIMITS: per-script format validity, names, tables, raw opcode operands " +
            "and structural child closures do not prove player-owned callbacks, script load, " +
            "dispatch, hit-confirm state, safe executable hooks, or animation timing."
        return Report(entries.size, parsed, rejected, byteCount, lines)
    }
}
