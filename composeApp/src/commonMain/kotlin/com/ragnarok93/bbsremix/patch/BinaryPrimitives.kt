package com.ragnarok93.bbsremix.patch

internal fun ByteArray.readIntLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8) or
        ((this[offset + 2].toInt() and 0xff) shl 16) or
        ((this[offset + 3].toInt() and 0xff) shl 24)

internal fun ByteArray.readShortLe(offset: Int): Int =
    (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

internal fun ByteArray.readFloatLe(offset: Int): Float =
    Float.fromBits(readIntLe(offset))

internal fun ByteArray.writeIntLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

internal fun ByteArray.writeShortLe(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
}

internal fun ByteArray.writeFloatLe(offset: Int, value: Float) =
    writeIntLe(offset, value.toBits())

internal fun ByteArray.isZero(start: Int, endExclusive: Int): Boolean =
    slice(start until endExclusive).all { it == 0.toByte() }

internal fun jal(target: Int): Int = 0x0c000000 or ((target ushr 2) and 0x03ffffff)

internal fun ByteArray.copyAt(offset: Int, value: ByteArray) {
    value.copyInto(this, destinationOffset = offset)
}
