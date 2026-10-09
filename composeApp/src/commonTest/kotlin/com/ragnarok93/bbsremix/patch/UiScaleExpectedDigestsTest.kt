package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiScaleExpectedDigestsTest {
    @Test
    fun every_selected_resource_has_six_independent_reference_profiles() {
        val layouts = BbsUiLayoutCatalog.suppliedCandidates
        assertEquals(41, layouts.size)
        assertEquals(30, UiScaleExpectedDigests.profileCount)
        for (candidate in layouts) {
            assertTrue(
                UiScaleExpectedDigests.contains(candidate.digestArchive, candidate.digestOffset),
                "Unmapped expected scale hashes for " + candidate.archive + "/" + candidate.layout,
            )
            for (percent in 70..95 step 5) {
                val expected = assertNotNull(
                    UiScaleExpectedDigests.expectedPrefix(
                        candidate.digestArchive, candidate.digestOffset, percent,
                    ),
                )
                assertEquals(32, expected.length)
                assertTrue(expected.all { it in '0'..'9' || it in 'a'..'f' })
                assertNotEquals(candidate.originalSha256.take(32), expected)
            }
        }
        val ctd = BbsCtdLayoutCatalog.subtitleCandidate
        for (percent in 70..95 step 5) {
            val expected = assertNotNull(
                UiScaleExpectedDigests.expectedPrefix("BBS0.DAT", ctd.offsetInArchive, percent),
            )
            assertEquals(32, expected.length)
            assertNotEquals(ctd.sourceSha256.take(32), expected)
        }
    }

    @Test fun stock_and_invalid_values_have_no_scaled_reference() {
        assertNull(UiScaleExpectedDigests.expectedPrefix("BBS0.DAT", 754538496L, 100))
        assertNull(UiScaleExpectedDigests.expectedPrefix("BBS0.DAT", 754538496L, 65))
        assertNull(UiScaleExpectedDigests.expectedPrefix("BBS0.DAT", 754538496L, 101))
        assertNull(UiScaleExpectedDigests.expectedPrefix("unknown.dat", 0, 85))
    }
}
