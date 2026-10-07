package com.ragnarok93.bbsremix.texture

import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TextureAssetManifestResourceTest {
    @Test
    fun packagedManifestMatchesThePinnedUpstreamTree() = runTest {
        assertPackagedManifestMatchesThePinnedUpstreamTree()
    }
}
