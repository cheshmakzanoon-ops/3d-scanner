package com.realityforge.scanner.engine

/** Heuristics for evidence actually observed by the pipeline. These cannot
 * measure missing object sides, physical dimensions, or ground-truth accuracy.
 * Kept pure so thresholds and false positives can be regression-tested on JVM.
 */
internal data class ScanQualityInputs(
    val importedPhotos: Int,
    val registeredCameras: Int,
    val sparsePoints: Int,
    val medianParallaxDegrees: Double?,
    val medianReprojectionNormalized: Double?,
    val sampledDepthPixels: Int,
    val consistentDepthPixels: Int,
    val meshVertices: Int,
    val meshTriangles: Int,
    val photographicFaceFraction: Double?,
)

internal data class ScanQualityFinding(val code: String, val action: String)
internal data class ScanQualitySummary(
    val status: String,
    val registeredFraction: Double,
    val reciprocalFraction: Double?,
    val photographicFaceFraction: Double?,
    val findings: List<ScanQualityFinding>,
)

internal object ScanQualityEvidence {
    fun assess(data: ScanQualityInputs): ScanQualitySummary {
        require(data.importedPhotos in 2..12 && data.registeredCameras in 2..data.importedPhotos)
        require(data.sparsePoints >= 0 && data.sampledDepthPixels >= 0 &&
            data.consistentDepthPixels in 0..data.sampledDepthPixels &&
            data.meshVertices >= 0 && data.meshTriangles >= 0)
        require(data.medianParallaxDegrees == null ||
            (data.medianParallaxDegrees.isFinite() && data.medianParallaxDegrees >= 0))
        require(data.medianReprojectionNormalized == null ||
            (data.medianReprojectionNormalized.isFinite() && data.medianReprojectionNormalized >= 0))
        require(data.photographicFaceFraction == null ||
            (data.photographicFaceFraction.isFinite() && data.photographicFaceFraction in 0.0..1.0))
        val registeredFraction=data.registeredCameras.toDouble()/data.importedPhotos
        val reciprocalFraction=if(data.sampledDepthPixels>0)
            data.consistentDepthPixels.toDouble()/data.sampledDepthPixels else null
        val findings=ArrayList<ScanQualityFinding>()
        if(data.registeredCameras < 3 && data.importedPhotos >= 3) findings += ScanQualityFinding(
            "FEW_REGISTERED_CAMERAS", "Add overlapping photographs from distinct sideways viewpoints, then reconstruct again.")
        if(registeredFraction < .75) findings += ScanQualityFinding(
            "PHOTOS_NOT_REGISTERED", "Ensure each new photograph overlaps at least two already-aligned views with visible texture.")
        if(data.medianParallaxDegrees!=null && data.medianParallaxDegrees < 1.5) findings += ScanQualityFinding(
            "LOW_PARALLAX", "Move the camera sideways around the object rather than turning it in place.")
        if(data.medianReprojectionNormalized!=null && data.medianReprojectionNormalized > .008) findings += ScanQualityFinding(
            "HIGH_REPROJECTION_ERROR", "Improve calibration, avoid motion blur, and retake views with stable lens zoom.")
        if(reciprocalFraction==null) findings += ScanQualityFinding(
            "DEPTH_UNAVAILABLE", "Use textured, matte rigid objects and sufficient calibrated overlapping viewpoints.")
        else if(reciprocalFraction<.15) findings += ScanQualityFinding(
            "LOW_RECIPROCAL_DEPTH_SUPPORT", "Add textured overlapping photographs and avoid reflective, transparent or textureless surfaces.")
        if(data.meshTriangles<100 || data.meshVertices<30) findings += ScanQualityFinding(
            "INSUFFICIENT_SURFACE_TRIANGLES", "Reconstruct more observed surfaces before relying on triangle-mesh exports.")
        else if(data.photographicFaceFraction==null || data.photographicFaceFraction<.75) findings += ScanQualityFinding(
            "LIMITED_PHOTOGRAPHIC_FACE_COVERAGE", "Capture additional visible sides under steady light for more photo-supported faces.")
        val status = when {
            data.meshTriangles < 100 || data.meshVertices < 30 -> "SPARSE_OR_DEPTH_ONLY"
            data.photographicFaceFraction != null && data.photographicFaceFraction >= .75 -> "PHOTO_TEXTURED_OPEN_SURFACE"
            else -> "PARTIAL_OPEN_MESH"
        }
        return ScanQualitySummary(status,registeredFraction,reciprocalFraction,
            data.photographicFaceFraction,findings)
    }
}
