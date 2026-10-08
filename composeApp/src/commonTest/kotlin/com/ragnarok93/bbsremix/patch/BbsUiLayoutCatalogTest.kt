package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BbsUiLayoutCatalogTest {
    @Test
    fun inspected_layouts_have_unique_nonoverlapping_bounded_ranges() {
        val items = BbsUiLayoutCatalog.suppliedCandidates
        assertEquals(8, items.size)
        assertEquals(items.size, items.map { it.archive to it.offsetInArchive }.distinct().size)
        items.forEach { item ->
            assertEquals("BBS1.DAT", item.archive)
            assertTrue(item.offsetInArchive >= 2048L)
            assertTrue(item.layoutSize > 0)
            assertTrue(item.offsetInArchive + item.layoutSize <= item.archiveSize)
            assertEquals(64, item.originalSha256.length)
            assertTrue(item.originalSha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
        items.sortedBy { it.offsetInArchive }.zipWithNext().forEach { (a, b) ->
            assertTrue(a.offsetInArchive + a.layoutSize <= b.offsetInArchive)
        }
        assertTrue(items.any { it.element == UiScaleElement.COMMAND_DECK })
        assertTrue(items.any { it.element == UiScaleElement.SHOTLOCK })
        assertTrue(items.any { it.element == UiScaleElement.MENUS })
        assertFalse(items.any { it.element == UiScaleElement.COMBAT_HUD })
        assertFalse(items.any { it.element == UiScaleElement.SUBTITLES })
    }
}
