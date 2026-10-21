package com.realityforge.scanner.ui.studio

/** C15. View-only camera state in degrees / normalized view coordinates; never model edits. */
data class StudioPose(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val zoom: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    init {
        require(listOf(yaw,pitch,zoom,panX,panY).all { it.isFinite() })
        require(pitch in -89f..89f && zoom in .35f..5f && panX in -2f..2f && panY in -2f..2f)
    }
    fun orbit(horizontal: Float, vertical: Float): StudioPose {
        require(horizontal.isFinite() && vertical.isFinite())
        return copy(yaw=(yaw+horizontal)%360f,pitch=(pitch+vertical).coerceIn(-89f,89f))
    }
    fun magnify(factor: Float): StudioPose {
        require(factor.isFinite() && factor>0)
        return copy(zoom=(zoom*factor).coerceIn(.35f,5f))
    }
    fun pan(horizontal: Float, vertical: Float): StudioPose {
        require(horizontal.isFinite() && vertical.isFinite())
        return copy(panX=(panX+horizontal).coerceIn(-2f,2f),panY=(panY+vertical).coerceIn(-2f,2f))
    }
}

enum class ScanRepresentation { SPARSE_POINTS, DENSE_POINTS, OBSERVED_SURFACE, PHOTOGRAPHIC_SURFACE }

/** Additive action seam. The editor/export owners validate recipe/revision and materialize output. */
data class StudioFeatureActions(
    val edit: (() -> Unit)? = null,
    val measure: (() -> Unit)? = null,
    val chooseLod: (() -> Unit)? = null,
    val export: (() -> Unit)? = null,
)
