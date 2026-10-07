package com.ragnarok93.bbsremix.texture

import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextureAssetManifestResourceTest {
    @Test
    fun packagedManifestMatchesThePinnedUpstreamTree() = runTest {
        assertPackagedManifestMatchesThePinnedUpstreamTree()
    }
}
