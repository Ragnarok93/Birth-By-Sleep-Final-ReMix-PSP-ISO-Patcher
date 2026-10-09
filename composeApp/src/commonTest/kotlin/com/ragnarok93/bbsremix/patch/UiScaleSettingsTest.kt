package com.ragnarok93.bbsremix.patch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiScaleSettingsTest {
    @Test
    fun all_categories_accept_only_70_through_100_in_five_percent_steps() {
        val stock = UiScaleSettings()
        assertTrue(stock.isStock)
        assertEquals(6, UiScaleElement.entries.size)
        assertFalse(UiScaleElement.entries.any { it.title.contains("menu", ignoreCase = true) })
        for (element in UiScaleElement.entries) {
            for (percent in 70..100 step 5) {
                val selection = stock.withPercent(element, percent)
                assertEquals(percent, selection[element])
                assertTrue(selection.invalidSelections().isEmpty())
                assertEquals(percent == 100, selection.isStock)
                UiScaleElement.entries.filterNot { it == element }.forEach {
                    assertEquals(100, selection[it])
                }
            }
            for (bad in listOf(-10, 0, 69, 71, 74, 96, 99, 101, 120)) {
                assertFailsWith<IllegalArgumentException> {
                    stock.withPercent(element, bad)
                }
            }
        }
    }

    @Test
    fun each_element_must_be_explicitly_restored_to_stock() {
        val adjusted = UiScaleElement.entries.fold(UiScaleSettings()) { settings, element ->
            settings.withPercent(element, 70)
        }
        assertFalse(adjusted.isStock)
        assertEquals(6, UiScaleElement.entries.count { adjusted[it] == 70 })
        val restored = UiScaleElement.entries.fold(adjusted) { settings, element ->
            settings.withPercent(element, 100)
        }
        assertTrue(restored.isStock)
        assertEquals(UiScaleSettings(), restored)
    }
}
