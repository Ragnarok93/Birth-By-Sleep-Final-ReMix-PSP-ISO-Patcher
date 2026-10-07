package com.ragnarok93.bbsremix.texture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TextureProfileCatalogTest {
    @Test
    fun resolvesTheThreeUpstreamSerialProfiles() {
        assertEquals(
            TextureGameVariant.FINAL_MIX_ENGLISH_PATCHED,
            TextureProfileCatalog.find("uljm05775")?.variant,
        )
        assertEquals(
            TextureGameVariant.EUROPEAN_ORIGINAL,
            TextureProfileCatalog.find("ULES01441")?.variant,
        )
        assertEquals(
            TextureGameVariant.NORTH_AMERICAN_ORIGINAL,
            TextureProfileCatalog.find("ULUS10505")?.variant,
        )
    }

    @Test
    fun preservesUpstreamCoverageAndOptionalFeatureCompatibility() {
        val finalMix = TextureProfileCatalog.find("ULJM05775")!!
        val europe = TextureProfileCatalog.find("ULES01441")!!
        val northAmerica = TextureProfileCatalog.find("ULUS10505")!!

        assertEquals(TextureCoverage.PRIMARY, finalMix.coverage)
        assertEquals(TextureCoverage.PARTIAL, europe.coverage)
        assertEquals(TextureCoverage.PARTIAL, northAmerica.coverage)
        assertFalse(finalMix.regionalButtonSwapAvailable)
        assertTrue(europe.regionalButtonSwapAvailable)
        assertTrue(northAmerica.regionalButtonSwapAvailable)
        assertTrue(finalMix.extraHdPortraitsAvailable)
        assertTrue(europe.extraHdPortraitsAvailable)
        assertTrue(northAmerica.extraHdPortraitsAvailable)
    }

    @Test
    fun keepsAquaModelDeltaBlockedUntilTheWholeIsoIsVerified() {
        assertEquals(
            AquaModelDeltaGate.REQUIRES_VERIFIED_FULL_ISO,
            TextureProfileCatalog.find("ULJM05775")?.aquaModelDeltaGate,
        )
        assertEquals(
            AquaModelDeltaGate.NOT_APPLICABLE,
            TextureProfileCatalog.find("ULUS10505")?.aquaModelDeltaGate,
        )
    }

    @Test
    fun normalizesSerialWhitespaceAndRejectsUnknownProfiles() {
        assertEquals(
            TextureProfileCatalog.find("ULJM05775"),
            TextureProfileCatalog.find("  uljm05775  "),
        )
        assertNull(TextureProfileCatalog.find(null))
        assertNull(TextureProfileCatalog.find("ULUS00000"))
    }

    @Test
    fun exposesTheVersionedUpstreamAndPpSSppInstallPath() {
        val profile = TextureProfileCatalog.find("ULES01441")!!
        assertEquals("PSP/TEXTURES/ULES01441", profile.installPath)
        assertEquals("1.5.1", TextureProfileCatalog.UPSTREAM_VERSION)
        assertEquals("b858f34debbd7bd5b17e989cc194ab05a836c2b5", TextureProfileCatalog.UPSTREAM_COMMIT)
        assertEquals(4_921, TextureProfileCatalog.UNIQUE_MAPPED_ASSET_COUNT)
        assertEquals(77, TextureProfileCatalog.MISSING_MAPPED_ASSET_COUNT)
    }
}
