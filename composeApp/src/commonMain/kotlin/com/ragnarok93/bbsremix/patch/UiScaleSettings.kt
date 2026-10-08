package com.ragnarok93.bbsremix.patch

/**
 * Percentage of the stock PSP 480x272 presentation footprint, not a texture
 * resampling factor. Each component starts at stock (100%).
 *
 * Non-stock values now request experimental source-fingerprinted ISO resource
 * overlays. Renderer behavior still requires real PPSSPP gameplay validation.
 */
enum class UiScaleElement(val title: String) {
    COMBAT_HUD("Combat HUD"),
    COMMAND_DECK("Command Deck"),
    GAUGES("HP / Focus / D-Link"),
    PORTRAITS("Character portraits"),
    SHOTLOCK("Shotlock interface"),
    MENUS("Pause / main menus"),
    SUBTITLES("Subtitles"),
}

data class UiScaleSettings(
    val combatHud: Int = STOCK_PERCENT,
    val commandDeck: Int = STOCK_PERCENT,
    val gauges: Int = STOCK_PERCENT,
    val portraits: Int = STOCK_PERCENT,
    val shotlock: Int = STOCK_PERCENT,
    val menus: Int = STOCK_PERCENT,
    val subtitles: Int = STOCK_PERCENT,
) {
    operator fun get(element: UiScaleElement): Int = when (element) {
        UiScaleElement.COMBAT_HUD -> combatHud
        UiScaleElement.COMMAND_DECK -> commandDeck
        UiScaleElement.GAUGES -> gauges
        UiScaleElement.PORTRAITS -> portraits
        UiScaleElement.SHOTLOCK -> shotlock
        UiScaleElement.MENUS -> menus
        UiScaleElement.SUBTITLES -> subtitles
    }

    fun withPercent(element: UiScaleElement, percent: Int): UiScaleSettings {
        require(isSelectable(percent)) {
            "UI scaling must be 70–100% in 5% increments (received ${percent}%)."
        }
        return when (element) {
            UiScaleElement.COMBAT_HUD -> copy(combatHud = percent)
            UiScaleElement.COMMAND_DECK -> copy(commandDeck = percent)
            UiScaleElement.GAUGES -> copy(gauges = percent)
            UiScaleElement.PORTRAITS -> copy(portraits = percent)
            UiScaleElement.SHOTLOCK -> copy(shotlock = percent)
            UiScaleElement.MENUS -> copy(menus = percent)
            UiScaleElement.SUBTITLES -> copy(subtitles = percent)
        }
    }

    val isStock: Boolean
        get() = UiScaleElement.entries.all { this[it] == STOCK_PERCENT }

    fun invalidSelections(): List<Pair<UiScaleElement, Int>> =
        UiScaleElement.entries.mapNotNull { element ->
            val percent = this[element]
            if (isSelectable(percent)) null else element to percent
        }

    companion object {
        const val MIN_PERCENT: Int = 70
        const val MAX_PERCENT: Int = 100
        const val STOCK_PERCENT: Int = 100
        const val STEP_PERCENT: Int = 5

        fun isSelectable(percent: Int): Boolean =
            percent in MIN_PERCENT..MAX_PERCENT &&
                (percent - MIN_PERCENT) % STEP_PERCENT == 0
    }
}
