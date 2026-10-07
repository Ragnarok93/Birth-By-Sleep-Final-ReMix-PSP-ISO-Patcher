package com.ragnarok93.bbsremix.ui

private val DISC_SERIAL_PATTERN = Regex("[A-Z0-9-]{5,16}")
private val LOG_DATE_PATTERN = Regex("[0-9]{8}")

internal fun logExportFileName(discSerial: String?, localDate: String): String {
    require(LOG_DATE_PATTERN.matches(localDate)) { "Date must use MMDDYYYY format." }
    val serial = discSerial
        ?.uppercase()
        ?.takeIf(DISC_SERIAL_PATTERN::matches)
        ?: "DISC-SERIAL-UNAVAILABLE"
    return "${serial}_$localDate.log"
}
