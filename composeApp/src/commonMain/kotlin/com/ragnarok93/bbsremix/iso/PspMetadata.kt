package com.ragnarok93.bbsremix.iso

data class PspGameMetadata(
    val title: String?,
    val discId: String?,
    val version: String?,
    val coverArtPng: ByteArray?,
)

internal fun parseParamSfo(bytes: ByteArray): Map<String, String> {
    if (bytes.size < 20 || bytes[0] != 0x00.toByte() || bytes[1] != 0x50.toByte() ||
        bytes[2] != 0x53.toByte() || bytes[3] != 0x46.toByte()
    ) {
        return emptyMap()
    }

    val keyTableOffset = bytes.readU32Le(8)
    val dataTableOffset = bytes.readU32Le(12)
    val entryCount = bytes.readU32Le(16)
    if (keyTableOffset !in 0..bytes.size || dataTableOffset !in 0..bytes.size || entryCount > 4096) {
        return emptyMap()
    }

    val result = linkedMapOf<String, String>()
    for (index in 0 until entryCount) {
        val entryOffset = 20 + index * 16
        if (entryOffset < 0 || entryOffset + 16 > bytes.size) break

        val keyOffset = bytes.readU16Le(entryOffset)
        val dataLength = bytes.readU32Le(entryOffset + 4)
        val dataOffset = bytes.readU32Le(entryOffset + 12)

        val keyStart = keyTableOffset + keyOffset
        val valueStart = dataTableOffset + dataOffset
        if (keyStart !in bytes.indices || valueStart !in 0..bytes.size || dataLength < 0) continue

        val key = bytes.readCString(keyStart, bytes.size - keyStart)
        if (key.isBlank()) continue

        val maxLength = minOf(dataLength, bytes.size - valueStart)
        if (maxLength <= 0) continue
        val value = bytes.readCString(valueStart, maxLength).trim()
        if (value.isNotEmpty()) result[key] = value
    }
    return result
}

private fun ByteArray.readU16Le(offset: Int): Int {
    if (offset < 0 || offset + 2 > size) return -1
    return (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
}

private fun ByteArray.readU32Le(offset: Int): Int {
    if (offset < 0 || offset + 4 > size) return -1
    val value =
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)
    return if (value > Int.MAX_VALUE) -1 else value.toInt()
}

private fun ByteArray.readCString(offset: Int, maxLength: Int): String {
    if (offset !in indices || maxLength <= 0) return ""
    val endLimit = minOf(size, offset + maxLength)
    var end = offset
    while (end < endLimit && this[end] != 0.toByte()) end++
    return buildString(end - offset) {
        for (index in offset until end) {
            val value = this@readCString[index].toInt() and 0xff
            if (value in 0x20..0x7e || value >= 0xa0) append(value.toChar())
        }
    }
}
