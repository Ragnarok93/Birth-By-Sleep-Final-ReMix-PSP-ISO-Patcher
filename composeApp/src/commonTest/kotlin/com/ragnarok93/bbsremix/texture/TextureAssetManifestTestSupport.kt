package com.ragnarok93.bbsremix.texture

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

internal suspend fun assertPackagedManifestMatchesThePinnedUpstreamTree() {
    val records = TextureAssetManifest.load()

    assertEquals(4_903, records.count { it.kind == TextureAssetKind.CORE })
    assertEquals(15, records.count { it.kind == TextureAssetKind.REGIONAL_BUTTON_SWAP })
    assertEquals(3, records.count { it.kind == TextureAssetKind.EXTRA_HD_PORTRAIT })
    assertEquals(4_923, records.size)

    val europe = TextureProfileCatalog.find("ULES01441")!!
    val plan = TextureAssetManifest.buildPlan(
        europe,
        records,
        TextureInstallOptions(
            includeRegionalButtonSwaps = true,
            includeExtraHdPortraits = true,
        ),
    )
    assertEquals(557_373_270L, plan.totalBytes)
    assertEquals(4_923, plan.assets.size)
    assertFailsWith<IllegalArgumentException> {
        TextureAssetManifest.buildPlan(
            TextureProfileCatalog.find("ULJM05775")!!,
            records,
            TextureInstallOptions(includeRegionalButtonSwaps = true),
        )
    }
}
