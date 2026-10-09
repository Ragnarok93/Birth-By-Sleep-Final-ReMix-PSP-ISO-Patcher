package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BbsUiLayoutCatalogTest {
    @Test
    fun supported_layouts_have_unique_nonoverlapping_source_ranges() {
        val items = BbsUiLayoutCatalog.suppliedCandidates
        assertEquals(101, items.size) // 21 originals + 11 duplicates + 69 scene variants
        assertTrue(items.size >= 25, "Expected the verified multi-category research catalog.")
        assertEquals(items.size, items.map { it.archive to it.offsetInArchive }.distinct().size)
        val expectedArchiveSizes = mapOf(
            "BBS0.DAT" to 754655232L,
            "BBS1.DAT" to 206092288L,
            "BBS2.DAT" to 206399488L,
            "BBS3.DAT" to 206391296L,
        )
        items.forEach { item ->
            assertEquals(expectedArchiveSizes[item.archive], item.archiveSize)
            assertTrue(item.offsetInArchive >= 2048L)
            assertTrue(item.layoutSize > 0)
            assertTrue(item.offsetInArchive + item.layoutSize <= item.archiveSize)
            assertEquals(64, item.originalSha256.length)
            assertTrue(item.originalSha256.all { it in '0'..'9' || it in 'a'..'f' })
        }
        items.groupBy { it.archive }.forEach { (_, group) ->
            group.sortedBy { it.offsetInArchive }.zipWithNext().forEach { (a, b) ->
                assertTrue(a.offsetInArchive + a.layoutSize <= b.offsetInArchive)
            }
        }
        for (element in UiScaleElement.entries.filterNot { it == UiScaleElement.SUBTITLES }) {
            assertTrue(items.any { it.element == element }, "Missing " + element.title)
        }
        assertFalse(items.any { it.element == UiScaleElement.SUBTITLES })
        assertFalse(items.any {
            it.layout in setOf("camp.l2d", "pause.l2d", "t_menu.l2d",
                "info_00.l2d", "c_help.l2d", "c_icon_00.l2d")
        }, "No discontinued pause/main-menu layout may be patched.")
    }
}
