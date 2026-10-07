package com.ragnarok93.bbsremix.texture

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextureAssetManifestTest {
    @Test
    fun packagedManifestMatchesThePinnedUpstreamTree() = runTest {
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

    @Test
    fun parsesOnlySafeManifestRows() {
        val records = TextureAssetManifest.parse(
            "kind\tpath\tgitBlobSha1\tsize\n" +
                "core\tUI/sample.png\t0000000000000000000000000000000000000000\t12\n" +
                "metadata\t.nomedia\t1111111111111111111111111111111111111111\t1\n",
        )

        assertEquals(2, records.size)
        assertEquals("UI/sample.png", records.first().installPath)
        assertEquals(TextureAssetKind.METADATA, records.last().kind)
    }

    @Test
    fun rejectsTraversalAndUnapprovedOptionalPaths() {
        assertFailsWith<IllegalArgumentException> {
            TextureAssetManifest.parse(
                "kind\tpath\tgitBlobSha1\tsize\n" +
                    "core\t../outside.png\t0000000000000000000000000000000000000000\t12\n",
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextureAssetManifest.parse(
                "kind\tpath\tgitBlobSha1\tsize\n" +
                    "portraits\tOptional/Uncensored Aqua/model.png\t0000000000000000000000000000000000000000\t12\n",
            )
        }
    }

    @Test
    fun stripsOnlyThePinnedArchiveRootAndRejectsUnsafeEntries() {
        assertEquals(
            "Birth-by-Sleep-HD-ReMix-b858f34debbd7bd5b17e989cc194ab05a836c2b5/",
            TextureAssetManifest.archiveRootPrefix,
        )
        val valid = TextureAssetManifest.archiveRootPrefix + "Worlds/Test/sample.png"
        assertEquals("Worlds/Test/sample.png", TextureAssetManifest.normalizeArchiveEntry(valid, false))
        assertEquals("", TextureAssetManifest.normalizeArchiveEntry(TextureAssetManifest.archiveRootPrefix, true))
        assertFailsWith<IllegalArgumentException> {
            TextureAssetManifest.normalizeArchiveEntry(
                TextureAssetManifest.archiveRootPrefix + "../outside.png",
                false,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            TextureAssetManifest.normalizeArchiveEntry("other-repo/Worlds/Test/sample.png", false)
        }
        assertFailsWith<IllegalArgumentException> {
            TextureAssetManifest.normalizeArchiveEntry(
                TextureAssetManifest.archiveRootPrefix + "UI\\outside.png",
                false,
            )
        }
    }
}
