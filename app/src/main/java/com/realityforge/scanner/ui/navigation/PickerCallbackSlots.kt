package com.realityforge.scanner.ui.navigation

import com.realityforge.scanner.export.ExportRequest
import java.util.Base64

/** One Android result per launch. Cancel retains its immutable origin until that callback drains. */
data class PickerCallbackSlot(val request: PendingRequest, val cancelled: Boolean = false)
private enum class PickerChannel { IMPORT, CALIBRATION, EXPORT }
class PickerCallbackSlots private constructor(
    private val slots: Map<PickerChannel, PickerCallbackSlot>,
    val blocked: Boolean = false,
    private val consumedExport: PendingRequest? = null,
) {
    val awaitingCancelledResult: Boolean get() = blocked || slots.values.any { it.cancelled }
    fun canLaunch(kind: PendingKind): Boolean = channel(kind)?.let { !blocked && it !in slots } ?: true
    fun permitsRestore(request: PendingRequest): Boolean {
        if (blocked || consumedExport?.requestId == request.requestId) return false
        val channel = channel(request.kind) ?: return false
        val slot = slots[channel] ?: return true // A durable pre-launch intent can still be explicitly cancelled.
        return !slot.cancelled && slot.request == request
    }
    fun launched(request: PendingRequest): PickerCallbackSlots {
        val channel = requireNotNull(channel(request.kind))
        require(canLaunch(request.kind)) { "PICKER_CALLBACK_OUTSTANDING" }
        require(request.encode().toByteArray(Charsets.UTF_8).size <= 8192)
        return PickerCallbackSlots(slots + (channel to PickerCallbackSlot(request)), consumedExport = consumedExport)
    }
    fun cancel(requestId: String): PickerCallbackSlots = PickerCallbackSlots(slots.mapValues { (_, slot) ->
        if (slot.request.requestId == requestId) slot.copy(cancelled = true) else slot
    }, blocked, consumedExport)
    /** Only for synchronous launch failure, when Android has not accepted the launch. */
    fun launchFailed(requestId: String): PickerCallbackSlots = PickerCallbackSlots(
        slots.filterValues { it.request.requestId != requestId }, blocked, consumedExport)
    fun take(kind: PendingKind): Pair<PickerCallbackSlot?, PickerCallbackSlots> {
        val channel = channel(kind) ?: return null to this
        val slot = slots[channel] ?: return null to this
        // Retain the last consumed model-export origin across recreation before async disk cleanup.
        // A later actual model-export result can replace it: the private store admits only one intent.
        val consumed = if (slot.request.kind == PendingKind.EXPORT) slot.request else consumedExport
        return slot to PickerCallbackSlots(slots - channel, blocked, consumed)
    }
    fun encode(): String = if (blocked) "blocked" else buildList {
        add("2")
        for ((channel, slot) in slots.toSortedMap(compareBy { it.ordinal })) {
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(slot.request.encode().toByteArray(Charsets.UTF_8))
            add("${channel.name}:${if (slot.cancelled) 1 else 0}:$encoded")
        }
        consumedExport?.let { request ->
            val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(request.encode().toByteArray(Charsets.UTF_8))
            add("CONSUMED_EXPORT:0:$encoded")
        }
    }.joinToString("\n")
    companion object {
        private fun channel(kind: PendingKind): PickerChannel? = when (kind) {
            PendingKind.IMPORT -> PickerChannel.IMPORT
            PendingKind.CALIBRATE -> PickerChannel.CALIBRATION
            PendingKind.EXPORT, PendingKind.CAPTURE_ARCHIVE -> PickerChannel.EXPORT
            PendingKind.CAMERA -> null // The in-app camera captures its callback's exact request instead.
        }
        fun empty(): PickerCallbackSlots = PickerCallbackSlots(emptyMap())
        fun restore(encoded: String?): PickerCallbackSlots = if (encoded == null) empty() else runCatching {
            require(encoded.length <= 65536)
            val lines = encoded.split('\n')
            require(lines.size in 1..5 && lines.first() == "2")
            val slots = linkedMapOf<PickerChannel, PickerCallbackSlot>()
            var consumed: PendingRequest? = null
            for (line in lines.drop(1)) {
                val parts = line.split(':', limit = 3)
                require(parts.size == 3 && parts[1] in setOf("0", "1"))
                val bytes = Base64.getUrlDecoder().decode(parts[2])
                require(bytes.size <= 8192)
                val request = requireNotNull(PendingRequest.restore(bytes.toString(Charsets.UTF_8)))
                if (parts[0] == "CONSUMED_EXPORT") {
                    require(parts[1] == "0" && request.kind == PendingKind.EXPORT && consumed == null)
                    consumed = request
                } else {
                    val channel = PickerChannel.valueOf(parts[0])
                    require(channel(request.kind) == channel && channel !in slots)
                    slots[channel] = PickerCallbackSlot(request, parts[1] == "1")
                }
            }
            PickerCallbackSlots(slots, consumedExport = consumed)
        }.getOrElse { PickerCallbackSlots(emptyMap(), blocked = true) }
    }
}

data class PickerConsumption(val accepted: PendingRequest?, val pendingAfter: PendingRequest?, val cancelRequestId: String?)
data class ExportRestoreSnapshot(val pendingEpoch: Long, val launchEpoch: Long, val pendingAtStart: PendingRequest?)
object PickerCallbackBinding {
    /** A callback may consume only its launch's fixed request, never a newer pending request. */
    fun consume(slot: PickerCallbackSlot, current: PendingRequest?, busy: Boolean): PickerConsumption {
        val same = slot.request == current
        val accepted = slot.request.takeIf { same && !slot.cancelled && !busy }
        return PickerConsumption(accepted, if (same) null else current, if (accepted == null) slot.request.requestId else null)
    }
    fun matchesStoredExport(request: PendingRequest, stored: ExportRequest?): Boolean = stored != null &&
        request.kind == PendingKind.EXPORT && request.requestId == stored.requestId &&
        request.projectId == stored.artifact.projectId && request.revision == stored.artifact.revision &&
        request.artifactKey == stored.artifact.artifactKey && request.extension == stored.option.id
    fun epochsMatch(snapshot: ExportRestoreSnapshot, pendingEpoch: Long, launchEpoch: Long): Boolean =
        snapshot.pendingEpoch == pendingEpoch && snapshot.launchEpoch == launchEpoch
    fun mayRestore(snapshot: ExportRestoreSnapshot, pendingEpoch: Long, launchEpoch: Long,
                   current: PendingRequest?, exportInFlight: Boolean): Boolean =
        snapshot.pendingAtStart == null && current == null && !exportInFlight &&
            epochsMatch(snapshot, pendingEpoch, launchEpoch)
}
