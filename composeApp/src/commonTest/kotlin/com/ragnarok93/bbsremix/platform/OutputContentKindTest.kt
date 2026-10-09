package com.ragnarok93.bbsremix.platform

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutputContentKindTest {
    @Test
    fun short_text_logs_need_not_be_whole_iso_sectors() {
        assertEquals(17L, validatedOutputLength(17L, OutputContentKind.LOG))
        assertEquals(2050L, validatedOutputLength(2050L, OutputContentKind.LOG))
    }

    @Test
    fun zip_exports_can_have_non_sector_aligned_lengths() {
        assertEquals(113L, validatedOutputLength(113L, OutputContentKind.ZIP))
        assertEquals(1234567L, validatedOutputLength(1234567L, OutputContentKind.ZIP))
    }

    @Test
    fun iso_outputs_still_require_whole_2048_byte_sectors() {
        assertEquals(2048L, validatedOutputLength(2048L, OutputContentKind.ISO))
        assertEquals(4096L, validatedOutputLength(4096L, OutputContentKind.ISO))
        val error = assertFailsWith<FileGatewayException> {
            validatedOutputLength(2050L, OutputContentKind.ISO)
        }
        assertContains(error.message ?: "", "completed ISO has an invalid length")
    }

    @Test
    fun empty_negative_and_unknown_length_are_invalid_for_every_type() {
        for (kind in OutputContentKind.entries) {
            for (size in listOf(null, 0L, -2048L)) {
                assertFailsWith<FileGatewayException> {
                    validatedOutputLength(size, kind)
                }
            }
        }
    }
}
