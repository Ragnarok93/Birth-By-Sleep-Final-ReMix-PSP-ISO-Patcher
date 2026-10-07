package com.ragnarok93.bbsremix.iso

private const val SFO_HEADER_SIZE = 20
private const val SFO_ENTRY_SIZE = 16
private const val MAX_SFO_ENTRIES = 4096
private val DISC_ID_PATTERN = Regex("[A-Z0-9-]{5,16}")

/** Reads the PSP disc identifier from a bounded PARAM.SFO byte array. */
internal fun parseDiscId(paramSfo: ByteArray): String? {
    if (paramSfo.size < SFO_HEADER_SIZE ||
        paramSfo[0] != 0x00.toByte() || paramSfo[1] != 'P'.code.toByte() ||
        paramSfo[2] != 'S'.code.toByte() || paramSfo[3] != 'F'.code.toByte()
    ) return null

    val keyTable = readU32(paramSfo, 8) ?: return null
    val dataTable = readU32(paramSfo, 12) ?: return null
    val entryCount = readU32(paramSfo, 16) ?: return null
    if (entryCount !in 1..MAX_SFO_ENTRIES.toLong()) return null
    val entriesEnd = SFO_HEADER_SIZE.toLong() + entryCount * SFO_ENTRY_SIZE
    if (entriesEnd > paramSfo.size || keyTable !in entriesEnd..paramSfo.size.toLong() ||
        dataTable !in keyTable..paramSfo.size.toLong()
    ) return null

    repeat(entryCount.toInt()) { index ->
        val entry = SFO_HEADER_SIZE + index * SFO_ENTRY_SIZE
        val keyOffset = readU16(paramSfo, entry)?.toInt() ?: return null
        val format = readU16(paramSfo, entry + 2) ?: return null
        val dataLength = readU32(paramSfo, entry + 4) ?: return null
        val dataOffset = readU32(paramSfo, entry + 12) ?: return null
        val keyStart = keyTable + keyOffset
        if (keyStart !in keyTable until paramSfo.size.toLong()) return null
        val key = readCString(paramSfo, keyStart.toInt(), paramSfo.size) ?: return null
        if (key != "DISC_ID") return@repeat
        if (format != 0x0204 || dataLength !in 1L..32L) return null
        val valueStart = dataTable + dataOffset
        val valueEnd = valueStart + dataLength
        if (valueStart < dataTable || valueEnd > paramSfo.size || valueEnd < valueStart) return null
        val value = buildString {
            for (byte in paramSfo.copyOfRange(valueStart.toInt(), valueEnd.toInt())) {
                if (byte == 0.toByte()) break
                val char = (byte.toInt() and 0xff).toChar()
                if (char.code !in 0x21..0x7e) return null
                append(char)
            }
        }.uppercase()
        return value.takeIf(DISC_ID_PATTERN::matches)
    }
    return null
}

private fun readCString(bytes: ByteArray, start: Int, limit: Int): String? {
    if (start !in 0 until limit) return null
    val end = (start until limit).firstOrNull { bytes[it] == 0.toByte() } ?: return null
    if (end == start || end - start > 128) return null
    return buildString(end - start) {
        for (index in start until end) {
            val value = bytes[index].toInt() and 0xff
            if (value !in 0x20..0x7e) return null
            append(value.toChar())
        }
    }
}

private fun readU16(bytes: ByteArray, offset: Int): Int? {
    if (offset < 0 || offset + 2 > bytes.size) return null
    return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}

private fun readU32(bytes: ByteArray, offset: Int): Long? {
    if (offset < 0 || offset + 4 > bytes.size) return null
    return (bytes[offset].toLong() and 0xff) or
        ((bytes[offset + 1].toLong() and 0xff) shl 8) or
        ((bytes[offset + 2].toLong() and 0xff) shl 16) or
        ((bytes[offset + 3].toLong() and 0xff) shl 24)
}
