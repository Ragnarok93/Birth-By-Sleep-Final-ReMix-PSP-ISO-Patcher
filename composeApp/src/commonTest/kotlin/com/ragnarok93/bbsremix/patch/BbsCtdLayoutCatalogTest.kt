package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BbsCtdLayoutCatalogTest {
    @Test fun verifiedManifestIsUniqueAndSectorAligned() {
        val candidates = BbsCtdLayoutCatalog.verifiedCtds
        assertEquals(7, candidates.size)
        assertEquals(7, candidates.map { it.fileName }.toSet().size)
        assertEquals(7, candidates.map { it.offsetInArchive }.toSet().size)
        assertEquals("CT00000.ctd", BbsCtdLayoutCatalog.subtitleCandidate.fileName)
        for (c in candidates) {
            assertEquals(0L, c.offsetInArchive % 2048L)
            assertEquals(0, c.size % 2048)
            assertTrue(c.offsetInArchive >= 0)
            assertTrue(c.offsetInArchive + c.size <= BbsCtdLayoutCatalog.BBS0_SIZE)
            assertEquals(64, c.sourceSha256.length)
            assertTrue(c.sourceSha256.all { it.isDigit() || it in 'a'..'f' })
        }
    }

    @Test fun noVerifiedCtdOverlapsAnother() {
        val sorted = BbsCtdLayoutCatalog.verifiedCtds.sortedBy { it.offsetInArchive }
        for (i in 1 until sorted.size) {
            assertTrue(sorted[i-1].offsetInArchive + sorted[i-1].size <= sorted[i].offsetInArchive)
        }
    }
}
