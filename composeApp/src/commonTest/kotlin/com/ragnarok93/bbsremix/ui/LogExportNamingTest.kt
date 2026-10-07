package com.ragnarok93.bbsremix.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LogExportNamingTest {
    @Test
    fun detectedSerialAndLocalDateFormTheExportName() {
        assertEquals("ULJM05775_10072026.log", logExportFileName("uljm05775", "10072026"))
    }

    @Test
    fun missingOrInvalidSerialIsExplicit() {
        assertEquals(
            "DISC-SERIAL-UNAVAILABLE_10072026.log",
            logExportFileName(null, "10072026"),
        )
        assertEquals(
            "DISC-SERIAL-UNAVAILABLE_10072026.log",
            logExportFileName("not a serial", "10072026"),
        )
    }

    @Test
    fun malformedDateIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            logExportFileName("ULJM05775", "2026-10-07")
        }
    }
}
