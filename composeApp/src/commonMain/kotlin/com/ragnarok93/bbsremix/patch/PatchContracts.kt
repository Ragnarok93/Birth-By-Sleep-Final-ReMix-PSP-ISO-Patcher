package com.ragnarok93.bbsremix.patch

enum class EbootRepresentation {
    DECRYPTED_ELF,
    ENCRYPTED_PRX,
    UNKNOWN,
}

data class EbootFingerprint(
    val size: Long,
    val sha256: String,
    val representation: EbootRepresentation,
)

data class EbootInspection(
    val fingerprint: EbootFingerprint,
    val supported: Boolean,
    val problems: List<String>,
)

data class PatchVerification(
    val verified: Boolean,
    val problems: List<String> = emptyList(),
    // Byte/ELF/ISO checks cannot certify the runtime ABI or gameplay semantics.
    val runtimeValidation: RuntimeValidation = RuntimeValidation.PENDING,
)

enum class RuntimeValidation { PENDING }

data class PatchedEboot(
    val bytes: ByteArray,
    val input: EbootFingerprint,
    val outputSha256: String,
) {
    override fun equals(other: Any?): Boolean =
        other is PatchedEboot && bytes.contentEquals(other.bytes) && input == other.input && outputSha256 == other.outputSha256

    override fun hashCode(): Int = bytes.contentHashCode()
}

interface EbootPatchEngine {
    fun inspect(data: ByteArray, options: PatchOptions): EbootInspection

    fun patch(data: ByteArray, options: PatchOptions): PatchedEboot

    fun verifyPatched(data: ByteArray, options: PatchOptions): PatchVerification =
        PatchVerification(false, listOf("This patch engine does not expose patched-output verification."))
}

class PatchValidationException(message: String) : IllegalArgumentException(message)

interface CancellationToken {
    val isCancelled: Boolean

    fun throwIfCancelled() {
        if (isCancelled) throw PatchCancelledException()
    }
}

object NeverCancelled : CancellationToken {
    override val isCancelled: Boolean = false
}

class PatchCancelledException : Exception("Patching was cancelled before the output was committed.")

data class PatchProgress(
    val phase: PatchPhase,
    val completed: Long,
    val total: Long,
    val detail: String? = null,
) {
    val fraction: Float
        get() = if (total <= 0L) 0f else (completed.toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)
}

enum class PatchPhase {
    INSPECTING,
    STAGING_EBOOT,
    PATCHING_EBOOT,
    REBUILDING_ISO,
    VALIDATING_OUTPUT,
    COMMITTING_OUTPUT,
}

fun interface ProgressReporter {
    fun report(progress: PatchProgress)
}

val NoProgress: ProgressReporter = ProgressReporter { }

