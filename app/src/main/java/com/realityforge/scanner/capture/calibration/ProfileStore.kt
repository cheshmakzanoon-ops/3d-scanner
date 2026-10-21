package com.realityforge.scanner.capture.calibration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Project/revision-bound operation identity. A picker callback must not infer a new selected project. */
data class CalibrationOperationKey(
    val projectId: String,
    val expectedRevision: Long,
    val operationId: String,
) {
    init {
        require(runCatching { UUID.fromString(projectId).toString() == projectId }.getOrDefault(false)) {
            "Invalid project identifier"
        }
        require(expectedRevision >= 0) { "Invalid project revision" }
        require(operationId.length in 1..128 && operationId.none(Char::isISOControl)) {
            "Invalid calibration operation identifier"
        }
    }
}

enum class CalibrationProfileMutationKind { SAVE, REMOVE }

data class CalibrationPublicationRequest(
    val key: CalibrationOperationKey,
    val kind: CalibrationProfileMutationKind,
    val encodedProfile: ByteArray?,
    val profileId: String?,
) {
    init {
        when (kind) {
            CalibrationProfileMutationKind.SAVE -> {
                require(encodedProfile != null && encodedProfile.isNotEmpty())
                require(encodedProfile.size <= CalibrationProfileCodec.MAX_PROFILE_BYTES)
                require(profileId?.startsWith("cal-v2-") == true)
            }
            CalibrationProfileMutationKind.REMOVE -> {
                require(encodedProfile == null && profileId == null)
            }
        }
    }
}

/** Result returned only after the owner-023 publication transaction reaches a terminal boundary. */
sealed interface CalibrationProfileStoreResult {
    data class Saved(val profileId: String, val committedRevision: Long) : CalibrationProfileStoreResult {
        init {
            require(profileId.startsWith("cal-v2-"))
            require(committedRevision >= 0)
        }
    }

    data class Removed(val committedRevision: Long) : CalibrationProfileStoreResult {
        init { require(committedRevision >= 0) }
    }

    data class Stale(val expectedRevision: Long, val actualRevision: Long?) : CalibrationProfileStoreResult {
        init {
            require(expectedRevision >= 0)
            require(actualRevision == null || actualRevision >= 0)
        }
    }

    data class Rejected(val code: String, val message: String) : CalibrationProfileStoreResult {
        init {
            require(code.matches(Regex("[A-Z0-9_]{2,80}")))
            require(message.length in 1..500 && message.none { it == '\u0000' })
        }
    }
}

/** Staged publication owned by Agent 023. Abort must be idempotent and safe after partial staging. */
interface CalibrationProfilePublication {
    suspend fun commit(): CalibrationProfileStoreResult
    suspend fun abort()
}

/**
 * Filesystem/database implementation is supplied by publication owner 023.
 * stage() must not expose the staged profile as current.
 */
fun interface CalibrationProfilePublicationPort {
    suspend fun stage(request: CalibrationPublicationRequest): CalibrationProfilePublication
}

interface ProfileStore {
    suspend fun save(
        key: CalibrationOperationKey,
        record: CalibrationProfileRecord,
    ): CalibrationProfileStoreResult

    suspend fun remove(key: CalibrationOperationKey): CalibrationProfileStoreResult
}

/**
 * Cancellation-safe orchestration around a revision-bound staged publication.
 * The port decides atomic filesystem/database mechanics; this class guarantees validation and abort semantics.
 */
class TransactionalProfileStore(
    private val publicationPort: CalibrationProfilePublicationPort,
) : ProfileStore {
    override suspend fun save(
        key: CalibrationOperationKey,
        record: CalibrationProfileRecord,
    ): CalibrationProfileStoreResult {
        val encoded = CalibrationProfileCodec.encode(record)
        val decoded = CalibrationProfileCodec.decode(encoded)
        require(decoded.profileId == record.profileId) { "Profile failed canonical validation" }
        return execute(
            CalibrationPublicationRequest(
                key = key,
                kind = CalibrationProfileMutationKind.SAVE,
                encodedProfile = encoded.toByteArray(Charsets.UTF_8),
                profileId = record.profileId,
            ),
        )
    }

    override suspend fun remove(key: CalibrationOperationKey): CalibrationProfileStoreResult = execute(
        CalibrationPublicationRequest(
            key = key,
            kind = CalibrationProfileMutationKind.REMOVE,
            encodedProfile = null,
            profileId = null,
        ),
    )

    private suspend fun execute(request: CalibrationPublicationRequest): CalibrationProfileStoreResult {
        coroutineContext.ensureActive()
        val publication = publicationPort.stage(request)
        try {
            coroutineContext.ensureActive()
            val result = publication.commit()
            validateResult(request, result)
            return result
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { publication.abort() }
            throw cancelled
        } catch (failure: Throwable) {
            withContext(NonCancellable) { publication.abort() }
            throw failure
        }
    }

    private fun validateResult(
        request: CalibrationPublicationRequest,
        result: CalibrationProfileStoreResult,
    ) {
        when (result) {
            is CalibrationProfileStoreResult.Saved -> {
                require(request.kind == CalibrationProfileMutationKind.SAVE)
                require(result.profileId == request.profileId) {
                    "Publication committed a different calibration profile"
                }
                require(result.committedRevision > request.key.expectedRevision) {
                    "Saving calibration must advance the project revision"
                }
            }
            is CalibrationProfileStoreResult.Removed -> {
                require(request.kind == CalibrationProfileMutationKind.REMOVE)
                require(result.committedRevision > request.key.expectedRevision) {
                    "Removing calibration must advance the project revision"
                }
            }
            is CalibrationProfileStoreResult.Stale -> {
                require(result.expectedRevision == request.key.expectedRevision)
            }
            is CalibrationProfileStoreResult.Rejected -> Unit
        }
    }
}
