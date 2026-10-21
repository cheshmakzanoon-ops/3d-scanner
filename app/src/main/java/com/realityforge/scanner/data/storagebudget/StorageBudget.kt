package com.realityforge.scanner.data.storagebudget

import java.io.File

/** Costs are additional peak bytes. Already stored originals/results are reported separately. */
data class StorageEstimate(
    val originalBytes: Long = 0,
    val analysisBytes: Long = 0,
    val featureBytes: Long = 0,
    val depthBytes: Long = 0,
    val meshBytes: Long = 0,
    val textureBytes: Long = 0,
    val stagingBytes: Long = 0,
    val retainedPreviousBytes: Long = 0,
    val unknownInputSize: Boolean = false,
) {
    val additionalPeakBytes: Long
    init {
        val costs = listOf(originalBytes, analysisBytes, featureBytes, depthBytes,
            meshBytes, textureBytes, stagingBytes, retainedPreviousBytes)
        require(costs.all { it >= 0 }) { "Storage estimates cannot be negative." }
        additionalPeakBytes = costs.fold(0L, Math::addExact)
    }

    companion object {
        const val MAX_PHOTO_BYTES = 150L * 1024 * 1024
        /** Unknown providers reserve the enforced copy limit, never zero bytes. */
        fun photoImport(providerBytes: Long?): StorageEstimate {
            require(providerBytes == null || providerBytes in 1..MAX_PHOTO_BYTES)
            val bytes = providerBytes ?: MAX_PHOTO_BYTES
            return StorageEstimate(originalBytes = bytes, analysisBytes = 16L * 1024 * 1024,
                stagingBytes = bytes, unknownInputSize = providerBytes == null)
        }

        fun export(bytes: Long, localStaging: Boolean = true): StorageEstimate {
            require(bytes >= 0)
            // One staging copy plus one potential local-provider destination. Neither is guaranteed remote.
            return StorageEstimate(stagingBytes = if (localStaging) Math.multiplyExact(bytes, 2) else bytes)
        }
    }
}

data class ReservationToken(val operationId: String, val projectId: String, val revision: Long) {
    init {
        require(operationId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(projectId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
        require(revision >= 0)
    }
}

data class SpaceDecision(
    val admitted: Boolean,
    val requiredBytes: Long,
    val measuredAvailableBytes: Long?,
    val otherReservedBytes: Long,
    val safetyBytes: Long,
    val reclaimableBytes: Long,
    val reason: String,
) {
    /** A cleanup plan is advice only: it does not count as currently available storage. */
    val shortfallBytes: Long? get() = measuredAvailableBytes?.let {
        val effective = (it - minOf(it, otherReservedBytes)).let { free -> free - minOf(free, safetyBytes) }
        (requiredBytes - minOf(requiredBytes, effective)).coerceAtLeast(0)
    }
}

object StorageAccounting {
    const val DEFAULT_SAFETY_BYTES = 64L * 1024 * 1024

    fun decide(requiredBytes: Long, availableBytes: Long?, otherClaims: Iterable<Long>,
        reclaimableBytes: Long = 0, safetyBytes: Long = DEFAULT_SAFETY_BYTES): SpaceDecision {
        require(requiredBytes >= 0 && (availableBytes == null || availableBytes >= 0))
        require(reclaimableBytes >= 0 && safetyBytes >= 0)
        var reserved = 0L
        var overflow = false
        for (claim in otherClaims) {
            require(claim >= 0)
            if (Long.MAX_VALUE - reserved < claim) { reserved = Long.MAX_VALUE; overflow = true }
            else reserved += claim
        }
        // Subtract before comparison; summing estimates near Long.MAX_VALUE would wrap.
        val remaining = availableBytes?.let { (it - minOf(it, reserved)).let { free -> free - minOf(free, safetyBytes) } }
        val admitted = !overflow && remaining != null && requiredBytes <= remaining
        return SpaceDecision(admitted, requiredBytes, availableBytes, reserved, safetyBytes, reclaimableBytes,
            when { availableBytes == null -> "storage_measurement_unavailable"; overflow -> "reservation_overflow"
                admitted -> "estimated_capacity_available"; else -> "insufficient_storage" })
    }
}

fun interface SpaceProbe { fun availableBytes(): Long? }

class FileSpaceProbe(private val root: File) : SpaceProbe {
    override fun availableBytes(): Long? = try {
        if (!root.isDirectory) null else root.usableSpace.takeIf { it >= 0 }
    } catch (_: SecurityException) { null }
}

class InsufficientStorageException(val decision: SpaceDecision) : IllegalStateException(
    "Storage needs ${decision.requiredBytes} bytes; available ${decision.measuredAvailableBytes ?: "unknown"}; " +
        "other reservations ${decision.otherReservedBytes}; reclaimable ${decision.reclaimableBytes}.",
)
