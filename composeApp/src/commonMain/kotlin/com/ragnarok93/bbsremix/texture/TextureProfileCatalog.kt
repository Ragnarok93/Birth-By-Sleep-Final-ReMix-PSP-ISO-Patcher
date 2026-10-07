package com.ragnarok93.bbsremix.texture

enum class TextureGameVariant {
    FINAL_MIX_ENGLISH_PATCHED,
    EUROPEAN_ORIGINAL,
    NORTH_AMERICAN_ORIGINAL,
}

enum class TextureCoverage {
    PRIMARY,
    PARTIAL,
}

enum class AquaModelDeltaGate {
    REQUIRES_VERIFIED_FULL_ISO,
    NOT_APPLICABLE,
}

data class TextureProfile(
    val serial: String,
    val title: String,
    val variant: TextureGameVariant,
    val coverage: TextureCoverage,
    val regionalButtonSwapAvailable: Boolean,
    val extraHdPortraitsAvailable: Boolean,
    val aquaModelDeltaGate: AquaModelDeltaGate,
) {
    val installPath: String
        get() = "PSP/TEXTURES/$serial"
}

object TextureProfileCatalog {
    const val UPSTREAM_REPOSITORY = "https://github.com/AkiraJkr/Birth-by-Sleep-HD-ReMix"
    const val UPSTREAM_COMMIT = "b858f34debbd7bd5b17e989cc194ab05a836c2b5"
    const val UPSTREAM_VERSION = "1.5.1"

    const val UNIQUE_MAPPED_ASSET_COUNT = 4_921
    const val AVAILABLE_MAPPED_ASSET_COUNT = 4_844
    const val MISSING_MAPPED_ASSET_COUNT = 77
    const val CORE_PNG_ASSET_COUNT = 4_903
    const val REGIONAL_BUTTON_SWAP_ASSET_COUNT = 15
    const val EXTRA_HD_PORTRAIT_ASSET_COUNT = 3

    val profiles = listOf(
        TextureProfile(
            serial = "ULJM05775",
            title = "Final Mix (English-patched, Japan)",
            variant = TextureGameVariant.FINAL_MIX_ENGLISH_PATCHED,
            coverage = TextureCoverage.PRIMARY,
            regionalButtonSwapAvailable = false,
            extraHdPortraitsAvailable = true,
            aquaModelDeltaGate = AquaModelDeltaGate.REQUIRES_VERIFIED_FULL_ISO,
        ),
        TextureProfile(
            serial = "ULES01441",
            title = "Europe original",
            variant = TextureGameVariant.EUROPEAN_ORIGINAL,
            coverage = TextureCoverage.PARTIAL,
            regionalButtonSwapAvailable = true,
            extraHdPortraitsAvailable = true,
            aquaModelDeltaGate = AquaModelDeltaGate.NOT_APPLICABLE,
        ),
        TextureProfile(
            serial = "ULUS10505",
            title = "North America original",
            variant = TextureGameVariant.NORTH_AMERICAN_ORIGINAL,
            coverage = TextureCoverage.PARTIAL,
            regionalButtonSwapAvailable = true,
            extraHdPortraitsAvailable = true,
            aquaModelDeltaGate = AquaModelDeltaGate.NOT_APPLICABLE,
        ),
    ).associateBy(TextureProfile::serial)

    fun find(serial: String?): TextureProfile? =
        serial?.trim()?.uppercase()?.let(profiles::get)
}
