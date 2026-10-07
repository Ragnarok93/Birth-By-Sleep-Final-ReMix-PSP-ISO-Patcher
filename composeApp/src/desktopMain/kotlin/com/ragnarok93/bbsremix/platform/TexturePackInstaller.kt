package com.ragnarok93.bbsremix.platform

import com.ragnarok93.bbsremix.patch.CancellationToken
import com.ragnarok93.bbsremix.patch.PatchCancelledException
import com.ragnarok93.bbsremix.texture.TextureAssetKind
import com.ragnarok93.bbsremix.texture.TextureAssetManifest
import com.ragnarok93.bbsremix.texture.TextureInstallPhase
import com.ragnarok93.bbsremix.texture.TextureInstallPlan
import com.ragnarok93.bbsremix.texture.TextureInstallProgress
import com.ragnarok93.bbsremix.texture.TextureInstallResult
import com.ragnarok93.bbsremix.texture.TextureProfileCatalog
import kotlinx.coroutines.CancellationException
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipInputStream

internal fun installTexturePackOnDesktop(
    destination: PlatformDirectorySelection,
    plan: TextureInstallPlan,
    cancellation: CancellationToken,
    progress: (TextureInstallProgress) -> Unit,
): TextureInstallResult {
    require(TextureProfileCatalog.profiles[plan.profile.serial] == plan.profile) {
        "The selected texture profile is not in the pinned catalog."
    }
    val selectedPath = destination.token as? okio.Path
        ?: throw FileGatewayException("The selected desktop texture folder is invalid.")
    val root = try {
        File(selectedPath.toString()).canonicalFile.toPath()
    } catch (_: Exception) {
        throw FileGatewayException("The selected desktop texture folder is unavailable.")
    }
    if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
        throw FileGatewayException("Choose an existing PPSSPP TEXTURES folder.")
    }
    val finalName = plan.profile.serial
    val finalPath = root.resolve(finalName)
    if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
        throw FileGatewayException("A texture folder for ${plan.profile.serial} already exists. Choose an empty PPSSPP TEXTURES folder.")
    }

    var stagePath: java.nio.file.Path? = null
    var connection: HttpURLConnection? = null
    var committed = false
    try {
        stagePath = Files.createTempDirectory(root, ".${finalName}.installing-${UUID.randomUUID()}-")
        if (stagePath.parent != root || !stagePath.toRealPath().startsWith(root.toRealPath())) {
            throw FileGatewayException("The selected texture folder could not safely contain a staging folder.")
        }
        connection = openPinnedArchiveConnection()
        val archiveLength = connection.contentLengthLong.takeIf { it >= 0L } ?: 0L
        if (archiveLength > TextureAssetManifest.MAX_ARCHIVE_BYTES) {
            throw FileGatewayException("The upstream texture archive is larger than the supported download limit.")
        }

        val assetsByPath = plan.assets.associateBy { it.sourcePath }
        val found = mutableSetOf<String>()
        val seenArchivePaths = mutableSetOf<String>()
        val writtenTargets = mutableSetOf<String>()
        val optionBuffers = linkedMapOf<String, ByteArray>()
        var optionBytes = 0L
        var expandedBytes = 0L
        var verifiedBytes = 0L
        var verifiedFiles = 0
        val limitedInput = LimitedTextureInputStream(
            connection.inputStream,
            TextureAssetManifest.MAX_ARCHIVE_BYTES,
        ) { received ->
            progress(TextureInstallProgress(TextureInstallPhase.DOWNLOADING, received, archiveLength, 0, plan.assets.size))
        }

        ZipInputStream(BufferedInputStream(limitedInput, ZIP_BUFFER_SIZE)).use { zip ->
            var entryCount = 0
            var entry = zip.nextEntry
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (entry != null) {
                cancellation.throwIfCancelled()
                entryCount += 1
                if (entryCount > TextureAssetManifest.MAX_ARCHIVE_ENTRIES) {
                    throw FileGatewayException("The upstream texture archive contains too many entries.")
                }
                val sourcePath = try {
                    TextureAssetManifest.normalizeArchiveEntry(entry.name, entry.isDirectory)
                } catch (error: IllegalArgumentException) {
                    throw FileGatewayException(error.message ?: "The upstream texture archive contains an unsafe path.")
                }
                if (!seenArchivePaths.add(sourcePath)) {
                    throw FileGatewayException("The upstream texture archive contains a duplicate path.")
                }
                if (sourcePath.isEmpty() || entry.isDirectory) {
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        expandedBytes += read
                        if (expandedBytes > TextureAssetManifest.MAX_UNCOMPRESSED_BYTES) {
                            throw FileGatewayException("The upstream texture archive exceeds the supported expanded size.")
                        }
                        throw FileGatewayException("The upstream texture archive contains data in a directory entry.")
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                    continue
                }
                val record = assetsByPath[sourcePath]
                if (entry.size > TextureAssetManifest.MAX_SINGLE_FILE_BYTES) {
                    throw FileGatewayException("The upstream texture archive contains an oversized file.")
                }
                if (record != null) {
                    if (entry.size >= 0L && entry.size != record.size) {
                        throw FileGatewayException("An upstream texture file has an unexpected size.")
                    }
                    if (!found.add(sourcePath)) {
                        throw FileGatewayException("The upstream texture archive contains a duplicate asset.")
                    }
                }

                val digest = record?.let {
                    MessageDigest.getInstance("SHA-1").apply {
                        update("blob ${it.size}\u0000".toByteArray(Charsets.UTF_8))
                    }
                }
                val directOutput = if (
                    record != null &&
                    record.kind != TextureAssetKind.REGIONAL_BUTTON_SWAP &&
                    record.kind != TextureAssetKind.EXTRA_HD_PORTRAIT
                ) {
                    openDesktopStageOutput(stagePath!!, record.installPath, writtenTargets, replace = false)
                } else {
                    null
                }
                val optionalBuffer = if (
                    record != null &&
                    record.kind != TextureAssetKind.CORE &&
                    record.kind != TextureAssetKind.METADATA
                ) {
                    ByteArrayOutputStream(record.size.toInt())
                } else {
                    null
                }

                var entryBytes = 0L
                try {
                    while (true) {
                        cancellation.throwIfCancelled()
                        val read = zip.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        entryBytes += read
                        expandedBytes += read
                        if (expandedBytes > TextureAssetManifest.MAX_UNCOMPRESSED_BYTES) {
                            throw FileGatewayException("The upstream texture archive exceeds the supported expanded size.")
                        }
                        if (entryBytes > TextureAssetManifest.MAX_SINGLE_FILE_BYTES) {
                            throw FileGatewayException("The upstream texture archive contains an oversized file.")
                        }
                        if (record != null && entryBytes > record.size) {
                            throw FileGatewayException("An upstream texture file is larger than the pinned manifest allows.")
                        }
                        digest?.update(buffer, 0, read)
                        directOutput?.write(buffer, 0, read)
                        optionalBuffer?.write(buffer, 0, read)
                    }
                } finally {
                    directOutput?.close()
                }
                zip.closeEntry()

                if (record != null) {
                    if (entryBytes != record.size || digest!!.digest().toHex() != record.gitBlobSha1) {
                        throw FileGatewayException("An upstream texture file failed its pinned integrity check.")
                    }
                    if (optionalBuffer != null) {
                        optionBytes += entryBytes
                        if (optionBytes > TextureAssetManifest.MAX_OPTIONAL_BUFFER_BYTES) {
                            throw FileGatewayException("The selected optional textures exceed the safe staging limit.")
                        }
                        optionBuffers[sourcePath] = optionalBuffer.toByteArray()
                    } else {
                        writtenTargets += record.installPath
                    }
                    verifiedFiles += 1
                    verifiedBytes += entryBytes
                    progress(
                        TextureInstallProgress(
                            TextureInstallPhase.VERIFYING,
                            verifiedBytes,
                            plan.totalBytes,
                            verifiedFiles,
                            plan.assets.size,
                        ),
                    )
                }
                entry = zip.nextEntry
            }
        }

        if (found != assetsByPath.keys) {
            throw FileGatewayException("The upstream texture archive is missing one or more pinned assets.")
        }
        if (optionBuffers.isNotEmpty()) {
            progress(TextureInstallProgress(TextureInstallPhase.APPLYING_OPTIONS, verifiedBytes, plan.totalBytes, verifiedFiles, plan.assets.size))
            for ((sourcePath, bytes) in optionBuffers) {
                cancellation.throwIfCancelled()
                val record = assetsByPath.getValue(sourcePath)
                openDesktopStageOutput(stagePath!!, record.installPath, writtenTargets, replace = true).use { it.write(bytes) }
                writtenTargets += record.installPath
            }
        }

        cancellation.throwIfCancelled()
        if (Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS)) {
            throw FileGatewayException("A texture folder for ${plan.profile.serial} appeared during installation; no files were replaced.")
        }
        if (File(root.toFile().canonicalPath).toPath() != root || stagePath!!.parent != root) {
            throw FileGatewayException("The selected texture folder changed during installation.")
        }
        progress(TextureInstallProgress(TextureInstallPhase.COMMITTING, plan.totalBytes, plan.totalBytes, plan.assets.size, plan.assets.size))
        try {
            Files.move(stagePath, finalPath)
        } catch (_: Exception) {
            throw FileGatewayException("The staged texture folder could not be committed without overwriting an existing path.")
        }
        stagePath = null
        committed = true
        return TextureInstallResult(
            installedLocation = destination.displayName + "/" + finalName,
            filesInstalled = plan.assets.size,
            bytesInstalled = plan.totalBytes,
        )
    } catch (error: PatchCancelledException) {
        throw error
    } catch (error: CancellationException) {
        throw error
    } catch (error: FileGatewayException) {
        throw error
    } catch (_: Exception) {
        throw FileGatewayException("Texture installation failed before commit. The temporary folder was discarded.")
    } finally {
        connection?.disconnect()
        if (!committed) stagePath?.let(::deleteStageTree)
    }
}

private fun openDesktopStageOutput(
    stagePath: java.nio.file.Path,
    relativePath: String,
    writtenTargets: Set<String>,
    replace: Boolean,
): OutputStream {
    if (relativePath.isEmpty() || relativePath.startsWith("/") || '\\' in relativePath) {
        throw FileGatewayException("The texture manifest contains an unsafe installation path.")
    }
    val parts = relativePath.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." || ':' in it }) {
        throw FileGatewayException("The texture manifest contains an unsafe installation path.")
    }
    val stageReal = stagePath.toRealPath()
    var parent = stagePath
    for (part in parts.dropLast(1)) {
        val next = parent.resolve(part)
        if (Files.exists(next, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(next) || !Files.isDirectory(next, LinkOption.NOFOLLOW_LINKS)) {
                throw FileGatewayException("The staging path contains an unexpected filesystem entry.")
            }
        } else {
            Files.createDirectory(next)
        }
        parent = next
        if (!parent.toRealPath().startsWith(stageReal)) {
            throw FileGatewayException("The texture installation path escaped its temporary folder.")
        }
    }
    val target = parent.resolve(parts.last())
    val exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS)
    if (replace) {
        if (relativePath in writtenTargets) {
            if (!exists || Files.isSymbolicLink(target) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw FileGatewayException("The verified base texture could not be replaced safely.")
            }
            Files.delete(target)
        } else if (exists) {
            throw FileGatewayException("The texture staging folder contains an unexpected destination collision.")
        }
    } else if (exists) {
        throw FileGatewayException("The texture staging folder contains an unexpected destination collision.")
    }
    return Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
}

private fun deleteStageTree(root: java.nio.file.Path) {
    if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attributes: BasicFileAttributes): FileVisitResult {
                Files.deleteIfExists(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): FileVisitResult {
                if (error != null) throw error
                Files.deleteIfExists(dir)
                return FileVisitResult.CONTINUE
            }
        },
    )
}

private fun openPinnedArchiveConnection(): HttpURLConnection {
    var currentUrl = URL(TextureAssetManifest.UPSTREAM_ARCHIVE_URL)
    repeat(MAX_REDIRECTS + 1) { redirectIndex ->
        if (currentUrl.protocol != "https" || currentUrl.host.lowercase() !in ALLOWED_ARCHIVE_HOSTS) {
            throw FileGatewayException("The pinned texture archive redirected to an unsupported host.")
        }
        val connection = currentUrl.openConnection() as? HttpURLConnection
            ?: throw FileGatewayException("Unable to open the pinned texture archive.")
        connection.instanceFollowRedirects = false
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", "BirthBySleepFinalReMixPatcher")
        val code = connection.responseCode
        if (code in 300..399) {
            val location = connection.getHeaderField("Location")
            connection.disconnect()
            if (location.isNullOrBlank() || redirectIndex == MAX_REDIRECTS) {
                throw FileGatewayException("The pinned texture archive redirected too many times.")
            }
            currentUrl = URL(currentUrl, location)
        } else {
            if (code != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                throw FileGatewayException("The pinned texture archive could not be downloaded (HTTP $code).")
            }
            if (connection.contentLengthLong > TextureAssetManifest.MAX_ARCHIVE_BYTES) {
                connection.disconnect()
                throw FileGatewayException("The pinned texture archive is larger than the supported download limit.")
            }
            return connection
        }
    }
    throw FileGatewayException("The pinned texture archive redirected too many times.")
}

private class LimitedTextureInputStream(
    input: InputStream,
    private val limit: Long,
    private val onProgress: (Long) -> Unit,
) : FilterInputStream(input) {
    var bytesRead: Long = 0L
        private set
    private var lastReport = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) increment(1L)
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        val count = super.read(buffer, offset, length)
        if (count > 0) increment(count.toLong())
        return count
    }

    private fun increment(count: Long) {
        bytesRead += count
        if (bytesRead > limit) throw FileGatewayException("The pinned texture archive exceeds the download limit.")
        if (bytesRead - lastReport >= REPORT_INTERVAL_BYTES) {
            lastReport = bytesRead
            onProgress(bytesRead)
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

private const val COPY_BUFFER_SIZE = 32 * 1024
private const val ZIP_BUFFER_SIZE = 128 * 1024
private const val REPORT_INTERVAL_BYTES = 1_048_576L
private const val CONNECT_TIMEOUT_MS = 20_000
private const val READ_TIMEOUT_MS = 60_000
private const val MAX_REDIRECTS = 5
private val ALLOWED_ARCHIVE_HOSTS = setOf("github.com", "codeload.github.com")
