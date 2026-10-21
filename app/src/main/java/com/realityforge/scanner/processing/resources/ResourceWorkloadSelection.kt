package com.realityforge.scanner.processing.resources

import com.realityforge.scanner.engine.DenseWorkload
import com.realityforge.scanner.engine.pipeline.sha256Utf8
import org.json.JSONObject

/** Implemented allocation tiers only. The immutable selection is part of job/stage/checkpoint identity. */
enum class ResourceWorkloadSelection(
    val memoryTier: String,
    val depthEdge: Int,
    val textureEdgeLimit: Int,
    val maximumViews: Int,
    val maximumGridSamples: Long,
) {
    COMPACT("compact", 256, 512, 6, 50_000),
    STANDARD("standard", 384, 768, 8, 100_000);

    val version: Int get() = 1
    val nativeThreads: Int get() = 1
    val identityText: String get() = "resource-selection-v$version|$memoryTier|depth=$depthEdge|texture=$textureEdgeLimit|views=$maximumViews|samples=$maximumGridSamples|native-threads=$nativeThreads"
    val identitySha256: String get() = sha256Utf8(identityText)
    // The complete persisted selection is immutable for a resume. Stage reuse uses only the
    // parameters that affect that stage, so an atlas policy change cannot invalidate depth.
    internal val stageIdentity: ResourceStageIdentity get() = ResourceStageIdentity(
        depthEdge, maximumViews, maximumGridSamples, nativeThreads, textureEdgeLimit,
    )
    val geometryIdentityText: String get() = stageIdentity.geometryText
    val geometryIdentitySha256: String get() = sha256Utf8(geometryIdentityText)
    val appearanceIdentityText: String get() = stageIdentity.appearanceText
    val appearanceIdentitySha256: String get() = sha256Utf8(appearanceIdentityText)

    fun encode(): JSONObject = JSONObject().put("schema", version).put("tier", memoryTier)
        .put("identitySha256", identitySha256)

    internal fun resourcePlan(views: Int = maximumViews): WorkloadPlan =
        DenseWorkload.plan(depthEdge, depthEdge, views.coerceIn(2, maximumViews), this).resourcePlan()

    companion object {
        fun decode(json: JSONObject): ResourceWorkloadSelection {
            require(json.get("schema") == 1) { "Unsupported resource selection version." }
            val tier = json.get("tier") as? String ?: error("Resource tier must be explicit.")
            val selection = entries.singleOrNull { it.memoryTier == tier } ?: error("Unsupported resource tier.")
            require(json.get("identitySha256") == selection.identitySha256) { "Resource selection identity mismatch." }
            return selection
        }
    }
}

/** Versions describe the implemented sampling/allocation policy, independently of persisted tier labels. */
internal data class ResourceStageIdentity(
    val depthEdge: Int,
    val maximumViews: Int,
    val maximumGridSamples: Long,
    val nativeThreads: Int,
    val textureEdgeLimit: Int,
    val maximumAtlasPixels: Long = DenseWorkload.MAX_ATLAS_PIXELS,
) {
    val geometryText: String get() = "resource-geometry-v1|depth=$depthEdge|views=$maximumViews|samples=$maximumGridSamples" +
        "|native-threads=$nativeThreads|min-edge=32|grid-border=8|strides=3:8|depth-bytes=4|luminance-bytes=4|luminance-views=4|camera-subset=anchors-distributed-v1"
    val appearanceText: String get() = "resource-appearance-v1|texture-edge=$textureEdgeLimit|atlas-pixels=$maximumAtlasPixels" +
        "|many-views-edge=640|few-views-edge=768|view-threshold=4|atlas-padding=6|atlas-columns=2:3"
}

/**
 * Selection happens before the job is persisted. A resume keeps this exact tier; reduced capacity causes
 * a durable checkpoint pause, never a silent switch to differently sampled inputs or buffers.
 */
internal object ResourceWorkloadScheduler {
    private const val MIB = 1024L * 1024
    fun select(snapshot: DeviceResourceSnapshot): ResourceWorkloadSelection {
        val remainingManaged = snapshot.managedHeapBytes - snapshot.managedUsedBytes
        val standard = snapshot.managedHeapBytes >= DenseWorkload.STANDARD_HEAP_BYTES &&
            remainingManaged >= 192L * MIB && snapshot.availableSystemBytes != null &&
            snapshot.availableSystemBytes >= 512L * MIB && snapshot.thermalStatus != null &&
            snapshot.thermalStatus < 2 && !snapshot.lowMemory && snapshot.powerSave == false &&
            snapshot.batteryPercent != null && (snapshot.batteryPercent > 20 || snapshot.charging == true)
        return if (standard) ResourceWorkloadSelection.STANDARD else ResourceWorkloadSelection.COMPACT
    }
}
