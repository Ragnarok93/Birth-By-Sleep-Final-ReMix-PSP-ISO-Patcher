package com.ragnarok93.bbsremix.texture

import com.ragnarok93.bbsremix.resources.Res
import org.jetbrains.compose.resources.readBytes

enum class TextureAssetKind {
    CORE,
    METADATA,
    REGIONAL_BUTTON_SWAP,
    EXTRA_HD_PORTRAIT,
}

data class TextureAssetRecord(
    val kind: TextureAssetKind,
    val sourcePath: String,
    val installPath: String,
    val gitBlobSha1: String,
    val size: Long,
)

data class TextureInstallOptions(
    val includeRegionalButtonSwaps: Boolean = false,
    val includeExtraHdPortraits: Boolean = false,
)

data class TextureInstallPlan(
    val profile: TextureProfile,
    val assets: List<TextureAssetRecord>,
) {
    val totalBytes: Long
        get() = assets.sumOf(TextureAssetRecord::size)
}

enum class TextureInstallPhase {
    DOWNLOADING,
    VERIFYING,
    APPLYING_OPTIONS,
    COMMITTING,
}

data class TextureInstallProgress(
    val phase: TextureInstallPhase,
    val completedBytes: Long,
    val totalBytes: Long,
    val completedFiles: Int,
    val totalFiles: Int,
)

data class TextureInstallResult(
    val installedLocation: String,
    val filesInstalled: Int,
    val bytesInstalled: Long,
)

object TextureAssetManifest {
    const val RESOURCE_PATH = "files/texture-assets.tsv"
    const val MAX_ARCHIVE_BYTES = 650_000_000L
    const val MAX_UNCOMPRESSED_BYTES = 600_000_000L
    const val MAX_ARCHIVE_ENTRIES = 6_000
    const val MAX_SINGLE_FILE_BYTES = 16_000_000L
    const val MAX_OPTIONAL_BUFFER_BYTES = 2_000_000L

    private const val HEADER = "kind\tpath\tgitBlobSha1\tsize"
    private const val BUTTON_PREFIX = "Optional/Button Swaps/"
    private const val PORTRAIT_PREFIX = "Optional/Extra HD Textures/"

    private val buttonSwapTargets = setOf(
        "UI/Shotlock 2.png",
        "UI/Command Menu.png",
        "UI/Command Styles/Blade Charge.png",
        "UI/Command Styles/Critical Impact.png",
        "UI/Command Styles/Cyclone.png",
        "UI/Command Styles/Dark Impulse.png",
        "UI/Command Styles/Fever Pitch.png",
        "UI/Command Styles/Firestorm.png",
        "UI/Command Styles/Frozen Fortune.png",
        "UI/Command Styles/Ghost Drive.png",
        "UI/Command Styles/Rhythm Mixer.png",
        "UI/Command Styles/Rockbreaker.png",
        "UI/Command Styles/Spell Weaver.png",
        "UI/Command Styles/Thunder Bolt.png",
        "UI/Command Styles/Wingblade.png",
    )
    private val portraitTargets = setOf(
        "UI/Aqua/Normal Portrait.png",
        "UI/Terra/Normal Portrait.png",
        "UI/Ventus/Normal Portrait.png",
    )

    val archiveRootPrefix: String
        get() = "Birth-by-Sleep-HD-ReMix-\${TextureProfileCatalog.UPSTREAM_COMMIT}/"

    suspend fun load(): List<TextureAssetRecord> {
        val bytes = Res.readBytes(RESOURCE_PATH)
        val content = bytes.decodeToString()
        val lines = content.lineSequence().toList()
        require(lines.size >= 4) { "The pinned texture asset manifest is incomplete." }
        require(lines[0] == "# repository=\${TextureProfileCatalog.UPSTREAM_REPOSITORY}") {
            "The texture asset manifest repository does not match the configured upstream."
        }
        require(lines[1] == "# commit=\${TextureProfileCatalog.UPSTREAM_COMMIT}") {
            "The texture asset manifest commit does not match the configured upstream."
        }
        val records = parse(lines.drop(2).joinToString("\n"))
        validatePinnedCatalog(records)
        return records
    }

    fun parse(contents: String): List<TextureAssetRecord> {
        val rows = contents.lineSequence().filter { it.isNotEmpty() }.toList()
        require(rows.firstOrNull() == HEADER) { "The texture asset manifest header is invalid." }
        val records = rows.drop(1).mapIndexed { index, row ->
            parseRow(row, index + 2)
        }
        require(records.map(TextureAssetRecord::sourcePath).distinct().size == records.size) {
            "The texture asset manifest contains duplicate source paths."
        }
        return records
    }

    fun buildPlan(
        profile: TextureProfile,
        records: List<TextureAssetRecord>,
        options: TextureInstallOptions,
    ): TextureInstallPlan {
        validatePinnedCatalog(records)
        require(TextureProfileCatalog.profiles[profile.serial] == profile) {
            "The selected texture profile is not in the pinned catalog."
        }
        require(!options.includeRegionalButtonSwaps || profile.regionalButtonSwapAvailable) {
            "Regional button swaps are only available for the European and North American profiles."
        }
        require(!options.includeExtraHdPortraits || profile.extraHdPortraitsAvailable) {
            "Extra HD portraits are not available for the selected profile."
        }
        val selected = records.filter { record ->
            when (record.kind) {
                TextureAssetKind.CORE, TextureAssetKind.METADATA -> true
                TextureAssetKind.REGIONAL_BUTTON_SWAP -> options.includeRegionalButtonSwaps
                TextureAssetKind.EXTRA_HD_PORTRAIT -> options.includeExtraHdPortraits
            }
        }
        val collisions = selected.groupBy(TextureAssetRecord::installPath).values.filter { it.size > 1 }
        require(collisions.all { group ->
            group.size == 2 &&
                group.count { it.kind == TextureAssetKind.CORE } == 1 &&
                group.count { it.kind != TextureAssetKind.CORE && it.kind != TextureAssetKind.METADATA } == 1
        }) {
            "The texture asset plan contains an unexpected destination collision."
        }
        return TextureInstallPlan(profile, selected)
    }

    fun normalizeArchiveEntry(path: String, isDirectory: Boolean): String {
        require(path.isNotEmpty() && '\u0000' !in path && '\\' !in path) {
            "The upstream archive contains an invalid path."
        }
        require(!path.startsWith("/") && !Regex("^[A-Za-z]:").containsMatchIn(path)) {
            "The upstream archive contains an absolute path."
        }
        val entryPath = if (isDirectory && path.endsWith("/")) path.dropLast(1) else path
        val root = archiveRootPrefix.dropLast(1)
        require(entryPath == root || entryPath.startsWith(archiveRootPrefix)) {
            "The upstream archive has an unexpected root folder."
        }
        val relative = if (entryPath == root) "" else entryPath.removePrefix(archiveRootPrefix)
        if (relative.isEmpty()) {
            require(isDirectory) { "The upstream archive contains a file at its root." }
            return ""
        }
        val components = relative.split('/')
        require(components.all { component ->
            component.isNotEmpty() && component != "." && component != ".." && ':' !in component
        }) {
            "The upstream archive contains a path traversal entry."
        }
        require(isDirectory || !relative.endsWith("/")) {
            "The upstream archive contains a malformed file path."
        }
        return relative
    }

    private fun validatePinnedCatalog(records: List<TextureAssetRecord>) {
        require(records.count { it.kind == TextureAssetKind.CORE } == TextureProfileCatalog.CORE_PNG_ASSET_COUNT) {
            "The pinned texture asset manifest has an unexpected core asset count."
        }
        require(records.count { it.kind == TextureAssetKind.REGIONAL_BUTTON_SWAP } == TextureProfileCatalog.REGIONAL_BUTTON_SWAP_ASSET_COUNT) {
            "The pinned texture asset manifest has an unexpected button swap count."
        }
        require(records.count { it.kind == TextureAssetKind.EXTRA_HD_PORTRAIT } == TextureProfileCatalog.EXTRA_HD_PORTRAIT_ASSET_COUNT) {
            "The pinned texture asset manifest has an unexpected portrait count."
        }
        val metadata = records.filter { it.kind == TextureAssetKind.METADATA }.associateBy(TextureAssetRecord::sourcePath)
        require(metadata.keys == setOf(".nomedia", "textures.ini")) {
            "The pinned texture asset manifest is missing required metadata files."
        }
        require(metadata[".nomedia"]?.gitBlobSha1 == "8b137891791fe96927ad78e64b0aad7bded08bdc") {
            "The pinned texture asset manifest has an unexpected .nomedia fingerprint."
        }
        require(metadata["textures.ini"]?.gitBlobSha1 == "e2d40c62b42e74d44b443cc29a22f0ba2273ec47") {
            "The pinned texture asset manifest has an unexpected textures.ini fingerprint."
        }
    }

    private fun parseRow(row: String, lineNumber: Int): TextureAssetRecord {
        val columns = row.split('\t')
        require(columns.size == 4) { "The texture asset manifest row $lineNumber is malformed." }
        val kind = when (columns[0]) {
            "core" -> TextureAssetKind.CORE
            "metadata" -> TextureAssetKind.METADATA
            "buttons" -> TextureAssetKind.REGIONAL_BUTTON_SWAP
            "portraits" -> TextureAssetKind.EXTRA_HD_PORTRAIT
            else -> throw IllegalArgumentException("The texture asset manifest row $lineNumber has an unknown asset kind.")
        }
        val sourcePath = columns[1]
        require(isSafeRelativePath(sourcePath)) { "The texture asset manifest row $lineNumber has an unsafe path." }
        val targetPath = when (kind) {
            TextureAssetKind.CORE -> {
                require(!sourcePath.startsWith("Optional/") && sourcePath.endsWith(".png", ignoreCase = true)) {
                    "The texture asset manifest row $lineNumber contains an invalid core path."
                }
                sourcePath
            }
            TextureAssetKind.METADATA -> {
                require(sourcePath == ".nomedia" || sourcePath == "textures.ini") {
                    "The texture asset manifest row $lineNumber contains unexpected metadata."
                }
                sourcePath
            }
            TextureAssetKind.REGIONAL_BUTTON_SWAP -> {
                require(sourcePath.startsWith(BUTTON_PREFIX)) {
                    "The texture asset manifest row $lineNumber contains an invalid button swap path."
                }
                sourcePath.removePrefix(BUTTON_PREFIX).also { target ->
                    require(target in buttonSwapTargets) {
                        "The texture asset manifest row $lineNumber contains an unsupported button swap."
                    }
                }
            }
            TextureAssetKind.EXTRA_HD_PORTRAIT -> {
                require(sourcePath.startsWith(PORTRAIT_PREFIX)) {
                    "The texture asset manifest row $lineNumber contains an invalid portrait path."
                }
                sourcePath.removePrefix(PORTRAIT_PREFIX).also { target ->
                    require(target in portraitTargets) {
                        "The texture asset manifest row $lineNumber contains an unsupported portrait."
                    }
                }
            }
        }
        require(columns[2].length == 40 && columns[2].all { it in "0123456789abcdef" }) {
            "The texture asset manifest row $lineNumber has an invalid Git blob fingerprint."
        }
        val size = columns[3].toLongOrNull()
        require(size != null && size in 1..MAX_SINGLE_FILE_BYTES) {
            "The texture asset manifest row $lineNumber has an invalid file size."
        }
        return TextureAssetRecord(kind, sourcePath, targetPath, columns[2], size)
    }

    private fun isSafeRelativePath(path: String): Boolean =
        path.isNotEmpty() &&
            !path.startsWith("/") &&
            '\\' !in path &&
            '\u0000' !in path &&
            !Regex("^[A-Za-z]:").containsMatchIn(path) &&
            path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && ':' !in it }
}
