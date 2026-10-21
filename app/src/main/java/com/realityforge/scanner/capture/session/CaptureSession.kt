package com.realityforge.scanner.capture.session

import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Immutable destination and concurrency identity for one camera session. */
data class CaptureSessionIdentity(
    val projectId: String,
    val initialRevision: Long,
    val sessionToken: String,
) {
    init {
        require(projectId.isNotBlank()) { "projectId must not be blank" }
        require(initialRevision >= 0L) { "initialRevision must be non-negative" }
        require(sessionToken.isNotBlank()) { "sessionToken must not be blank" }
        require(sessionToken.length <= 256) { "sessionToken is too long" }
    }
}

/**
 * Metadata observed by the capture owner. Agent 013 can enrich [fields] without changing the
 * session/publication identity. Unknown values stay absent instead of being inferred.
 */
data class CaptureMetadataSnapshot(
    val schemaVersion: Int = 1,
    val targetRotation: Int? = null,
    val capturedAtEpochMillis: Long,
    val fields: Map<String, String> = emptyMap(),
    val captureMetadata: com.realityforge.scanner.capture.metadata.CaptureMetadata? = null,
) {
    init {
        require(schemaVersion > 0) { "schemaVersion must be positive" }
        require(targetRotation == null || targetRotation in 0..3) {
            "targetRotation must be an Android Surface rotation or null"
        }
        require(capturedAtEpochMillis >= 0L) { "capturedAtEpochMillis must be non-negative" }
        require(fields.size <= 64) { "metadata field count is unbounded" }
        fields.forEach { (key, value) ->
            require(key.isNotBlank() && key.length <= 128) { "invalid metadata key" }
            require(value.length <= 1024) { "metadata value is too long" }
        }
    }
}

/** A saved private staging file bound to the destination that initiated the capture. */
data class CapturedPhoto(
    val projectId: String,
    val revision: Long,
    val sessionToken: String,
    val captureToken: String,
    val stagedFile: File,
    val metadata: CaptureMetadataSnapshot,
)

/** Result returned only after the publication owner has committed or rejected the photo. */
enum class CaptureStagingDisposition { PUBLISHER_OWNED, COPIED_TO_PRIVATE_ORIGINAL }

sealed interface CaptureCommitOutcome {
    /** [committedRevision] is the project revision after the photo transaction commits. */
    data class Accepted(val committedRevision: Long,
        val stagingDisposition: CaptureStagingDisposition = CaptureStagingDisposition.PUBLISHER_OWNED) : CaptureCommitOutcome

    /** Already listed, not a newly saved shot. A database duplicate is not file-integrity proof. */
    data class Duplicate(val revision: Long) : CaptureCommitOutcome

    /** Publication could have committed. Keep temporary bytes, stop this session and reconcile. */
    data class Uncertain(val reason: String) : CaptureCommitOutcome {
        init { require(reason.isNotBlank()) }
    }

    data class Rejected(val reason: String) : CaptureCommitOutcome {
        init {
            require(reason.isNotBlank()) { "A rejected capture needs an actionable reason" }
        }
    }
}

enum class CaptureSessionPhase {
    WAITING_FOR_PERMISSION,
    BINDING,
    READY,
    CAPTURING,
    COMMITTING,
    PAUSED,
    UNAVAILABLE,
    CLOSED,
}

enum class CaptureCloseReason {
    USER_BACK,
    NAVIGATION_CHANGED,
    DISPOSED,
    PROCESS_RECREATED,
}

data class CaptureSessionCheckpoint(
    val expectedRevision: Long,
    val savedCount: Int,
    val requiresRecovery: Boolean = false,
) {
    init {
        require(expectedRevision >= 0L) { "expectedRevision must be non-negative" }
        require(savedCount >= 0) { "savedCount must be non-negative" }
    }
}

data class CaptureSessionState(
    val identity: CaptureSessionIdentity,
    val phase: CaptureSessionPhase = CaptureSessionPhase.WAITING_FOR_PERMISSION,
    val expectedRevision: Long = identity.initialRevision,
    val savedCount: Int = 0,
    val inFlightCaptureToken: String? = null,
    val message: String? = null,
    val closeReason: CaptureCloseReason? = null,
    val requiresRecovery: Boolean = false,
) {
    fun checkpoint(): CaptureSessionCheckpoint = CaptureSessionCheckpoint(
        expectedRevision = expectedRevision,
        savedCount = savedCount,
        requiresRecovery = requiresRecovery,
    )
}

data class CaptureAttempt internal constructor(
    val projectId: String,
    val expectedRevision: Long,
    val sessionToken: String,
    val captureToken: String,
    val stagedFile: File,
)

sealed interface SavedImageDecision {
    data class Deliver(val photo: CapturedPhoto) : SavedImageDecision
    data class Discarded(val reason: String) : SavedImageDecision
}

/**
 * Owns only files below a dedicated cache root. Directory names are hashes of opaque tokens so
 * untrusted identifiers cannot become paths. Old session directories can be pruned on relaunch.
 */
class CaptureStagingStore(private val root: File) {
    private val canonicalRoot: File
        get() = root.canonicalFile

    init {
        check(root.exists() || root.mkdirs()) { "Cannot create capture staging root: $root" }
        check(root.isDirectory) { "Capture staging root is not a directory: $root" }
    }

    @Synchronized
    fun create(sessionToken: String, captureToken: String): File {
        val directory = sessionDirectory(sessionToken)
        check(directory.exists() || directory.mkdirs()) {
            "Cannot create capture session staging directory"
        }
        check(isOwned(directory)) { "Capture session directory escaped the staging root" }
        directory.setLastModified(System.currentTimeMillis())
        val file = File(directory, "capture-${safeSegment(captureToken)}.jpg")
        check(isOwned(file)) { "Capture file escaped the staging root" }
        check(file.createNewFile()) { "Capture staging file already exists" }
        return file
    }

    @Synchronized
    fun discard(file: File): Boolean {
        if (!isOwned(file)) return false
        val deleted = !file.exists() || file.delete()
        removeEmptyParents(file.parentFile)
        return deleted
    }

    @Synchronized
    fun discardSession(sessionToken: String): Boolean =
        deleteOwnedTree(sessionDirectory(sessionToken))

    /**
     * Removes old private staging left by prior, no-longer-active sessions. A recently closed
     * session may still have a CameraX writer or callback in flight, so another token is not proof
     * that its directory is safe to delete. Only age-qualified directories are pruned.
     */
    @Synchronized
    fun discardSessionsExcept(
        activeSessionToken: String,
        minimumAgeMillis: Long = DEFAULT_ABANDONED_SESSION_AGE_MILLIS,
        nowEpochMillis: Long = System.currentTimeMillis(),
    ): Int {
        require(minimumAgeMillis >= 0L) { "minimumAgeMillis must be non-negative" }
        require(nowEpochMillis >= 0L) { "nowEpochMillis must be non-negative" }
        val activeName = sessionDirectory(activeSessionToken).name
        val children = root.listFiles().orEmpty()
        var removed = 0
        for (child in children) {
            val ageMillis = nowEpochMillis - child.lastModified()
            val oldEnough = child.lastModified() >= 0L && ageMillis >= minimumAgeMillis
            if (
                child.name != activeName &&
                child.name.startsWith(SESSION_PREFIX) &&
                oldEnough &&
                deleteOwnedTree(child)
            ) {
                removed++
            }
        }
        return removed
    }

    fun isOwned(file: File): Boolean {
        val rootPath = canonicalRoot.path
        val candidatePath = runCatching { file.canonicalFile.path }.getOrElse { return false }
        return candidatePath == rootPath || candidatePath.startsWith(rootPath + File.separator)
    }

    private fun sessionDirectory(sessionToken: String): File =
        File(root, "$SESSION_PREFIX${safeSegment(sessionToken)}")

    private fun removeEmptyParents(start: File?) {
        var current = start
        while (current != null) {
            val candidate = current
            if (runCatching { candidate.canonicalFile == canonicalRoot }.getOrDefault(true)) return
            if (!isOwned(candidate)) return
            if (candidate.list().orEmpty().isNotEmpty() || !candidate.delete()) return
            current = candidate.parentFile
        }
    }

    private fun deleteOwnedTree(file: File): Boolean {
        if (!file.exists()) return true
        if (!isOwned(file) || runCatching { file.canonicalFile == canonicalRoot }.getOrDefault(true)) {
            return false
        }
        if (file.isDirectory) {
            for (child in file.listFiles().orEmpty()) {
                if (!deleteOwnedTree(child)) return false
            }
        }
        return file.delete()
    }

    private fun safeSegment(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val SESSION_PREFIX = "session-"
        const val DEFAULT_ABANDONED_SESSION_AGE_MILLIS = 24L * 60L * 60L * 1000L
    }
}

/**
 * Thread-safe state machine that rejects callbacks from a closed, replaced, or different session.
 * It never infers a destination from mutable UI selection.
 */
class CaptureSessionController(
    val identity: CaptureSessionIdentity,
    private val stagingStore: CaptureStagingStore,
    restoredCheckpoint: CaptureSessionCheckpoint? = null,
    private val captureTokenFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private data class PendingCapture(
        val attempt: CaptureAttempt,
        var deliveredToPublisher: Boolean = false,
    )

    private val lock = Any()
    private val initialCheckpoint = restoredCheckpoint ?: CaptureSessionCheckpoint(
        expectedRevision = identity.initialRevision,
        savedCount = 0,
    )
    private val mutableState = MutableStateFlow(
        CaptureSessionState(
            identity = identity,
            expectedRevision = initialCheckpoint.expectedRevision,
            savedCount = initialCheckpoint.savedCount,
            requiresRecovery = initialCheckpoint.requiresRecovery,
            phase = if (initialCheckpoint.requiresRecovery) CaptureSessionPhase.UNAVAILABLE else CaptureSessionPhase.WAITING_FOR_PERMISSION,
        ),
    )
    private var pending: PendingCapture? = null
    // In-flight capture/publication retains ownership while availability may change independently.
    private var idlePhase = mutableState.value.phase
    private var idleMessage: String? = null

    init {
        require(initialCheckpoint.expectedRevision >= identity.initialRevision) {
            "Restored capture revision predates the initiating project revision"
        }
    }

    val state: StateFlow<CaptureSessionState> = mutableState.asStateFlow()

    fun markWaitingForPermission(message: String? = null) = transitionIdle(
        phase = CaptureSessionPhase.WAITING_FOR_PERMISSION,
        message = message,
    )

    fun markBinding() = transitionIdle(phase = CaptureSessionPhase.BINDING, message = null)

    fun markReady() = transitionIdle(phase = CaptureSessionPhase.READY, message = null)

    fun markPaused() = synchronized(lock) {
        if (idlePhase in setOf(CaptureSessionPhase.READY, CaptureSessionPhase.BINDING)) {
            transitionIdle(CaptureSessionPhase.PAUSED, null)
        }
    }

    fun markUnavailable(message: String) = transitionIdle(
        phase = CaptureSessionPhase.UNAVAILABLE,
        message = message,
    )

    fun reportNonFatal(message: String) = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != CaptureSessionPhase.CLOSED) {
            mutableState.value = current.copy(message = message)
        }
    }

    fun beginCapture(): CaptureAttempt? = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != CaptureSessionPhase.READY || pending != null) return null
        val token = captureTokenFactory().also {
            require(it.isNotBlank()) { "captureTokenFactory returned a blank token" }
        }
        val stagedFile = try {
            stagingStore.create(identity.sessionToken, token)
        } catch (failure: Exception) {
            mutableState.value = current.copy(
                message = failure.message ?: "Cannot create private capture staging file.",
            )
            return null
        }
        val attempt = CaptureAttempt(
            projectId = identity.projectId,
            expectedRevision = current.expectedRevision,
            sessionToken = identity.sessionToken,
            captureToken = token,
            stagedFile = stagedFile,
        )
        pending = PendingCapture(attempt)
        mutableState.value = current.copy(
            phase = CaptureSessionPhase.CAPTURING,
            inFlightCaptureToken = token,
            message = null,
        )
        attempt
    }

    fun onImageSaved(
        attempt: CaptureAttempt,
        metadata: CaptureMetadataSnapshot,
    ): SavedImageDecision = synchronized(lock) {
        val current = mutableState.value
        val active = pending
        if (active == null) {
            stagingStore.discard(attempt.stagedFile)
            return SavedImageDecision.Discarded("Capture callback does not belong to the active session.")
        }
        if (!matches(active.attempt, attempt)) {
            if (active.attempt.stagedFile.absoluteFile != attempt.stagedFile.absoluteFile) {
                stagingStore.discard(attempt.stagedFile)
            }
            return SavedImageDecision.Discarded("Capture callback does not belong to the active session.")
        }
        if (active.deliveredToPublisher) {
            // A duplicate callback must not remove a file the publication owner is reading.
            return SavedImageDecision.Discarded("Duplicate capture callback ignored.")
        }
        if (current.phase == CaptureSessionPhase.CLOSED) {
            stagingStore.discard(active.attempt.stagedFile)
            pending = null
            mutableState.value = current.copy(inFlightCaptureToken = null)
            return SavedImageDecision.Discarded("Capture session is closed.")
        }
        active.deliveredToPublisher = true
        mutableState.value = current.copy(
            phase = CaptureSessionPhase.COMMITTING,
            message = null,
        )
        SavedImageDecision.Deliver(
            CapturedPhoto(
                projectId = attempt.projectId,
                revision = attempt.expectedRevision,
                sessionToken = attempt.sessionToken,
                captureToken = attempt.captureToken,
                stagedFile = attempt.stagedFile,
                metadata = metadata,
            ),
        )
    }

    fun onCaptureError(attempt: CaptureAttempt, message: String): Boolean = synchronized(lock) {
        val active = pending ?: run {
            stagingStore.discard(attempt.stagedFile)
            return false
        }
        if (!matches(active.attempt, attempt)) {
            if (active.attempt.stagedFile.absoluteFile != attempt.stagedFile.absoluteFile) {
                stagingStore.discard(attempt.stagedFile)
            }
            return false
        }
        if (active.deliveredToPublisher) return false
        stagingStore.discard(active.attempt.stagedFile)
        pending = null
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = if (current.phase == CaptureSessionPhase.CLOSED) {
                CaptureSessionPhase.CLOSED
            } else {
                idlePhase
            },
            inFlightCaptureToken = null,
            message = idleMessage ?: message.ifBlank { "The photograph could not be saved." },
        )
        true
    }

    /** Returns false for stale publisher completion and deletes a rejected staging file. */
    fun onCommit(captureToken: String, outcome: CaptureCommitOutcome, expectedPhoto: CapturedPhoto? = null): Boolean = synchronized(lock) {
        val active = pending
        if (active == null || !active.deliveredToPublisher || active.attempt.captureToken != captureToken) {
            return false
        }
        val current = mutableState.value
        if (expectedPhoto != null && (expectedPhoto.projectId != identity.projectId ||
            expectedPhoto.sessionToken != identity.sessionToken || expectedPhoto.captureToken != captureToken ||
            expectedPhoto.revision != active.attempt.expectedRevision ||
            expectedPhoto.stagedFile.absoluteFile != active.attempt.stagedFile.absoluteFile)) return false
        when (outcome) {
            is CaptureCommitOutcome.Accepted -> {
                if (outcome.committedRevision <= active.attempt.expectedRevision) {
                    // Incoherent acceptance is not proof that another durable copy exists.
                    pending = null
                    mutableState.value = current.copy(
                        phase = if (current.phase == CaptureSessionPhase.CLOSED) CaptureSessionPhase.CLOSED else CaptureSessionPhase.UNAVAILABLE,
                        requiresRecovery = true, inFlightCaptureToken = null,
                        message = "Capture publication could not be verified. Reopen the project before capturing again.",
                    )
                    return false
                }
                pending = null
                mutableState.value = current.copy(
                    phase = if (current.phase == CaptureSessionPhase.CLOSED) {
                        CaptureSessionPhase.CLOSED
                    } else {
                        idlePhase
                    },
                    expectedRevision = outcome.committedRevision,
                    savedCount = current.savedCount + 1,
                    inFlightCaptureToken = null,
                    message = idleMessage,
                )
            }

            is CaptureCommitOutcome.Duplicate -> {
                pending = null
                val coherent = outcome.revision == active.attempt.expectedRevision
                mutableState.value = current.copy(
                    phase = if (current.phase == CaptureSessionPhase.CLOSED) CaptureSessionPhase.CLOSED
                        else if (coherent) idlePhase else CaptureSessionPhase.UNAVAILABLE,
                    requiresRecovery = !coherent, inFlightCaptureToken = null,
                    message = if (coherent) idleMessage ?: "Photograph already listed in this project; no new photo was added."
                        else "Duplicate publication revision changed. Reopen the project before capturing again.",
                )
                // Preserve the source: duplicate detection does not verify the existing file bytes.
                return coherent
            }
            is CaptureCommitOutcome.Uncertain -> {
                pending = null
                mutableState.value = current.copy(
                    phase = if (current.phase == CaptureSessionPhase.CLOSED) CaptureSessionPhase.CLOSED else CaptureSessionPhase.UNAVAILABLE,
                    requiresRecovery = true, inFlightCaptureToken = null, message = outcome.reason,
                )
            }

            is CaptureCommitOutcome.Rejected -> {
                stagingStore.discard(active.attempt.stagedFile)
                pending = null
                mutableState.value = current.copy(
                    phase = if (current.phase == CaptureSessionPhase.CLOSED) {
                        CaptureSessionPhase.CLOSED
                    } else {
                        idlePhase
                    },
                    inFlightCaptureToken = null,
                    message = idleMessage ?: outcome.reason,
                )
            }
        }
        true
    }

    /**
     * Closing does not race-delete a file CameraX may still be writing. A later callback is
     * rejected and deletes it; process-death leftovers are pruned by the next session.
     */
    fun close(reason: CaptureCloseReason) = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != CaptureSessionPhase.CLOSED) {
            mutableState.value = current.copy(
                phase = CaptureSessionPhase.CLOSED,
                closeReason = reason,
                message = null,
            )
        }
    }

    /** Use only when the camera adapter guarantees that no callback can still write the file. */
    fun abandonPending(message: String): Boolean = synchronized(lock) {
        val active = pending ?: return false
        if (active.deliveredToPublisher) return false
        stagingStore.discard(active.attempt.stagedFile)
        pending = null
        val current = mutableState.value
        mutableState.value = current.copy(
            phase = if (current.phase == CaptureSessionPhase.CLOSED) {
                CaptureSessionPhase.CLOSED
            } else {
                idlePhase
            },
            inFlightCaptureToken = null,
            message = idleMessage ?: message,
        )
        true
    }

    private fun transitionIdle(phase: CaptureSessionPhase, message: String?) = synchronized(lock) {
        val current = mutableState.value
        if (current.phase != CaptureSessionPhase.CLOSED && !current.requiresRecovery) {
            idlePhase = phase
            idleMessage = message
            if (pending == null) mutableState.value = current.copy(phase = phase, message = message)
        }
    }

    private fun matches(expected: CaptureAttempt, actual: CaptureAttempt): Boolean =
        expected.projectId == actual.projectId &&
            expected.expectedRevision == actual.expectedRevision &&
            expected.sessionToken == actual.sessionToken &&
            expected.captureToken == actual.captureToken &&
            expected.stagedFile.absoluteFile == actual.stagedFile.absoluteFile
}
