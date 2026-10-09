package com.ragnarok93.bbsremix.platform

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.NoProgress
import com.ragnarok93.bbsremix.patch.ProgressReporter
import com.ragnarok93.bbsremix.texture.TextureInstallPlan
import com.ragnarok93.bbsremix.texture.TextureInstallProgress
import com.ragnarok93.bbsremix.texture.TextureInstallResult
import okio.Path

class PlatformFileSelection internal constructor(
    val displayName: String,
    val location: String,
    internal val token: Any,
)

class PlatformOutputSelection internal constructor(
    val displayName: String,
    val location: String,
    internal val token: Any,
)

class PlatformDirectorySelection internal constructor(
    val displayName: String,
    val location: String,
    internal val token: Any,
)

interface FileGateway {
    suspend fun pickSource(): PlatformFileSelection?

    /** Select the game's BBS0.DAT for a local, non-patching UI research export. */
    suspend fun pickBbs0Source(): PlatformFileSelection?

    suspend fun pickOutput(suggestedName: String): PlatformOutputSelection?

    suspend fun pickTextureDestination(): PlatformDirectorySelection?

    suspend fun installTexturePack(
        destination: PlatformDirectorySelection,
        plan: TextureInstallPlan,
        cancellation: CancellationToken,
        progress: (TextureInstallProgress) -> Unit,
    ): TextureInstallResult

    suspend fun createTempPath(prefix: String, suffix: String): Path

    suspend fun stageSource(
        source: PlatformFileSelection,
        destination: Path,
        cancellation: CancellationToken,
        progress: ProgressReporter = NoProgress,
    )

    suspend fun commitOutput(
        temporary: Path,
        destination: PlatformOutputSelection,
        cancellation: CancellationToken,
        progress: ProgressReporter = NoProgress,
    )

    /**
     * Called when CreateDocument selected an output but the patch or commit
     * failed. Providers may create an empty placeholder immediately.
     */
    suspend fun discardUncommittedOutput(destination: PlatformOutputSelection): Boolean = false

    suspend fun deleteTemp(path: Path)

    fun isSameSourceAndOutput(source: PlatformFileSelection, output: PlatformOutputSelection): Boolean

    fun shouldShowDonationPrompt(): Boolean

    fun suppressDonationPrompt()

    fun openExternalUrl(url: String)

    /** Current local date in the filename format required for exported logs. */
    fun localDateStamp(): String

    /** Current local time for user-visible operation log entries. */
    fun localTimeStamp(): String
}

class FileGatewayException(message: String) : IllegalStateException(message)
