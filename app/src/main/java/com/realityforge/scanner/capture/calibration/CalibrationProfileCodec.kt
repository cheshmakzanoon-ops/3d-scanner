package com.realityforge.scanner.capture.calibration

import com.realityforge.scanner.engine.LensProfile
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.max

/** Versioned, bounded local calibration profile codec. Schema 1 is imported as legacy-unverified. */
object CalibrationProfileCodec {
    const val LEGACY_SCHEMA_VERSION = 1
    const val SCHEMA_VERSION = 2
    const val MODEL = "brown-conrady-5"
    const val MAX_PROFILE_BYTES = 10_000

    fun fromSession(
        profile: LensProfile,
        coverage: CalibrationCoverageEvidence,
        views: List<CalibrationViewEvidence>,
        createdAtEpochMs: Long = System.currentTimeMillis(),
        boardColumns: Int = CalibrationSetEvaluator.BOARD_COLUMNS,
        boardRows: Int = CalibrationSetEvaluator.BOARD_ROWS,
    ): CalibrationProfileRecord {
        val accepted = views.filter { it.accepted }.sortedBy { it.index }
        require(accepted.size == profile.acceptedViews) {
            "Accepted profile count does not match per-view evidence"
        }
        val evidenceSources = accepted.map { it.optical.evidenceSource }.distinct()
        val evidenceSource = evidenceSources.singleOrNull()
            ?: CalibrationEvidenceSource.USER_SELECTED_UNVERIFIED
        val optical = CalibrationOpticalMetadata(
            cameraId = accepted.commonString { it.optical.cameraId },
            lensId = accepted.commonString { it.optical.lensId },
            zoomRatio = accepted.commonDouble { it.optical.zoomRatio },
            cropFingerprint = accepted.commonString { it.optical.cropFingerprint },
            orientation = accepted.commonString { it.optical.orientation }
                ?: CalibrationOpticalMetadata.EXIF_UPRIGHT,
            focal35mm = profile.focal35mm ?: accepted.commonDouble { it.optical.focal35mm },
            evidenceSource = evidenceSource,
        )
        val sourceSetSha256 = accepted
            .map { it.sourceSha256 }
            .takeIf { hashes -> hashes.isNotEmpty() && hashes.all { it != null } }
            ?.map { requireNotNull(it) }
            ?.let { hashes ->
                sha256Utf8(hashes.withIndex().joinToString("|") { (index, hash) -> "$index:$hash" })
            }
        val draft = CalibrationProfileRecord(
            profile = profile,
            profileId = "",
            provenance = CalibrationProfileProvenance(
                createdAtEpochMs = createdAtEpochMs,
                evidenceSource = evidenceSource,
                physicalSameLensVerified = evidenceSource == CalibrationEvidenceSource.PHYSICAL_DEVICE &&
                    coverage.metadataComplete,
                sourceSetSha256 = sourceSetSha256,
                boardColumns = boardColumns,
                boardRows = boardRows,
                optical = optical,
                viewEvidenceComplete = true,
            ),
            coverage = coverage,
            views = views.sortedBy { it.index },
        )
        return withComputedProfileId(draft)
    }

    /** Compatibility adapter for the old controller until Agent 001 consumes calculateSession(). */
    fun aggregateOnly(profile: LensProfile): CalibrationProfileRecord {
        val draft = CalibrationProfileRecord(
            profile = profile,
            profileId = "",
            provenance = CalibrationProfileProvenance(
                createdAtEpochMs = 0,
                evidenceSource = CalibrationEvidenceSource.LEGACY_UNVERIFIED,
                physicalSameLensVerified = false,
                sourceSetSha256 = null,
                boardColumns = CalibrationSetEvaluator.BOARD_COLUMNS,
                boardRows = CalibrationSetEvaluator.BOARD_ROWS,
                optical = CalibrationOpticalMetadata(
                    focal35mm = profile.focal35mm,
                    evidenceSource = CalibrationEvidenceSource.LEGACY_UNVERIFIED,
                ),
                viewEvidenceComplete = false,
            ),
            coverage = CalibrationCoverageEvidence(
                acceptedViews = profile.acceptedViews,
                xRangeFraction = null,
                yRangeFraction = null,
                boardAreaRatio = null,
                metadataComplete = false,
            ),
            views = emptyList(),
        )
        return withComputedProfileId(draft)
    }

    fun encode(record: CalibrationProfileRecord): String {
        val normalized = if (record.profileId.isEmpty()) withComputedProfileId(record) else record
        require(normalized.profileId == computedProfileId(normalized)) {
            "Calibration profile identity does not match its content"
        }
        val root = payload(normalized).put("profileId", normalized.profileId)
        val text = root.toString()
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_PROFILE_BYTES) {
            "Calibration profile exceeds the $MAX_PROFILE_BYTES-byte safety limit"
        }
        return text
    }

    fun decode(text: String): CalibrationProfileRecord {
        require(text.toByteArray(Charsets.UTF_8).size in 30..MAX_PROFILE_BYTES) {
            "Calibration profile size is invalid"
        }
        val root = JSONObject(text)
        return when (root.requireInt("schema")) {
            LEGACY_SCHEMA_VERSION -> decodeLegacy(root)
            SCHEMA_VERSION -> decodeV2(root)
            else -> throw IllegalArgumentException("Unrecognized calibration profile schema")
        }
    }

    fun computedProfileId(record: CalibrationProfileRecord): String =
        "cal-v2-${sha256Utf8(canonicalIdentityPayload(record))}"

    private fun withComputedProfileId(record: CalibrationProfileRecord): CalibrationProfileRecord =
        record.copy(profileId = computedProfileId(record))

    private fun canonicalIdentityPayload(record: CalibrationProfileRecord): String = buildString {
        appendToken(SCHEMA_VERSION)
        appendToken(MODEL)
        with(record.profile) {
            appendToken(width); appendToken(height)
            appendToken(fx); appendToken(fy); appendToken(cx); appendToken(cy)
            appendToken(k1); appendToken(k2); appendToken(p1); appendToken(p2); appendToken(k3)
            appendToken(reprojectionRmsPixels); appendToken(acceptedViews); appendToken(focal35mm)
        }
        with(record.provenance) {
            appendToken(createdAtEpochMs); appendToken(evidenceSource.wireName)
            appendToken(physicalSameLensVerified); appendToken(sourceSetSha256)
            appendToken(boardColumns); appendToken(boardRows); appendToken(viewEvidenceComplete)
            with(optical) {
                appendToken(cameraId); appendToken(lensId); appendToken(zoomRatio)
                appendToken(cropFingerprint); appendToken(orientation); appendToken(focal35mm)
                appendToken(evidenceSource.wireName)
            }
        }
        with(record.coverage) {
            appendToken(acceptedViews); appendToken(xRangeFraction); appendToken(yRangeFraction)
            appendToken(boardAreaRatio); appendToken(metadataComplete)
        }
        record.views.sortedBy { it.index }.forEach { view ->
            appendToken(view.index); appendToken(view.sourceId); appendToken(view.sourceSha256)
            appendToken(view.uprightWidth); appendToken(view.uprightHeight)
            appendToken(view.boardCenterXFraction); appendToken(view.boardCenterYFraction)
            appendToken(view.boardAreaFraction); appendToken(view.reprojectionRmsPixels)
            with(view.optical) {
                appendToken(cameraId); appendToken(lensId); appendToken(zoomRatio)
                appendToken(cropFingerprint); appendToken(orientation); appendToken(focal35mm)
                appendToken(evidenceSource.wireName)
            }
            appendToken(view.rejection?.code?.name)
        }
    }

    private fun StringBuilder.appendToken(value: Any?) {
        val encoded = when (value) {
            null -> "~"
            is Double -> java.lang.Double.toString(value)
            is Float -> java.lang.Float.toString(value)
            else -> value.toString()
        }
        append(encoded.length).append(':').append(encoded).append('|')
    }

    private fun decodeLegacy(root: JSONObject): CalibrationProfileRecord {
        require(root.requireString("model") == MODEL) { "Unrecognized calibration model" }
        val orientation = root.optionalString("orientation")
            ?: CalibrationOpticalMetadata.EXIF_UPRIGHT
        require(orientation == CalibrationOpticalMetadata.EXIF_UPRIGHT) {
            "Unsupported calibration coordinate orientation"
        }
        val profile = parseProfile(root)
        val draft = CalibrationProfileRecord(
            profile = profile,
            profileId = "",
            provenance = CalibrationProfileProvenance(
                createdAtEpochMs = 0,
                evidenceSource = CalibrationEvidenceSource.LEGACY_UNVERIFIED,
                physicalSameLensVerified = false,
                sourceSetSha256 = null,
                boardColumns = CalibrationSetEvaluator.BOARD_COLUMNS,
                boardRows = CalibrationSetEvaluator.BOARD_ROWS,
                optical = CalibrationOpticalMetadata(
                    orientation = orientation,
                    focal35mm = profile.focal35mm,
                    evidenceSource = CalibrationEvidenceSource.LEGACY_UNVERIFIED,
                ),
                viewEvidenceComplete = false,
            ),
            coverage = CalibrationCoverageEvidence(
                acceptedViews = profile.acceptedViews,
                xRangeFraction = null,
                yRangeFraction = null,
                boardAreaRatio = null,
                metadataComplete = false,
            ),
            views = emptyList(),
        )
        return withComputedProfileId(draft)
    }

    private fun decodeV2(root: JSONObject): CalibrationProfileRecord {
        require(root.requireString("model") == MODEL) { "Unrecognized calibration model" }
        require(root.requireString("orientation") == CalibrationOpticalMetadata.EXIF_UPRIGHT) {
            "Unsupported calibration coordinate orientation"
        }
        val profile = parseProfile(root)
        val evidenceSource = CalibrationEvidenceSource.fromWireName(root.requireString("evidenceSource"))
        val profileOptical = CalibrationOpticalMetadata(
            cameraId = root.optionalString("cameraId"),
            lensId = root.optionalString("lensId"),
            zoomRatio = root.optionalFiniteDouble("zoomRatio"),
            cropFingerprint = root.optionalString("cropFingerprint"),
            orientation = root.requireString("orientation"),
            focal35mm = profile.focal35mm,
            evidenceSource = evidenceSource,
        )
        val provenance = CalibrationProfileProvenance(
            createdAtEpochMs = root.requireLong("createdAtEpochMs"),
            evidenceSource = evidenceSource,
            physicalSameLensVerified = root.requireBoolean("physicalSameLensVerified"),
            sourceSetSha256 = root.optionalString("sourceSetSha256")?.also(::requireSha256),
            boardColumns = root.requireInt("boardColumns"),
            boardRows = root.requireInt("boardRows"),
            optical = profileOptical,
            viewEvidenceComplete = root.requireBoolean("viewEvidenceComplete"),
        )
        val coverageJson = root.requireObject("coverage")
        val coverage = CalibrationCoverageEvidence(
            acceptedViews = coverageJson.requireInt("acceptedViews"),
            xRangeFraction = coverageJson.optionalFiniteDouble("xRangeFraction"),
            yRangeFraction = coverageJson.optionalFiniteDouble("yRangeFraction"),
            boardAreaRatio = coverageJson.optionalFiniteDouble("boardAreaRatio"),
            metadataComplete = coverageJson.requireBoolean("metadataComplete"),
        )
        val viewArray = root.requireArray("views")
        require(viewArray.length() <= CalibrationSetEvaluator.MAX_IMAGES) { "Too many profile views" }
        val views = List(viewArray.length()) { position ->
            parseView(viewArray.requireObject(position), profile, provenance)
        }.sortedBy { it.index }
        require(views.map { it.index }.distinct().size == views.size) { "Duplicate profile view index" }
        if (provenance.physicalSameLensVerified) {
            require(provenance.viewEvidenceComplete && coverage.metadataComplete) {
                "Physical same-lens verification requires complete view and optical metadata evidence"
            }
            require(views.filter { it.accepted }.all {
                it.optical.evidenceSource == CalibrationEvidenceSource.PHYSICAL_DEVICE
            }) { "Physical same-lens verification contains non-physical accepted evidence" }
        }
        val suppliedId = root.requireString("profileId")
        val record = CalibrationProfileRecord(profile, suppliedId, provenance, coverage, views)
        require(suppliedId == computedProfileId(record)) { "Calibration profile checksum mismatch" }
        return record
    }

    private fun parseProfile(root: JSONObject): LensProfile = LensProfile(
        width = root.requireInt("width"),
        height = root.requireInt("height"),
        fx = root.requireFiniteDouble("fx"),
        fy = root.requireFiniteDouble("fy"),
        cx = root.requireFiniteDouble("cx"),
        cy = root.requireFiniteDouble("cy"),
        k1 = root.requireFiniteDouble("k1"),
        k2 = root.requireFiniteDouble("k2"),
        p1 = root.requireFiniteDouble("p1"),
        p2 = root.requireFiniteDouble("p2"),
        k3 = root.requireFiniteDouble("k3"),
        reprojectionRmsPixels = root.requireFiniteDouble("reprojectionRmsPixels"),
        acceptedViews = root.requireInt("acceptedViews"),
        focal35mm = root.optionalFiniteDouble("focal35mm"),
    )

    private fun parseView(
        json: JSONObject,
        profile: LensProfile,
        provenance: CalibrationProfileProvenance,
    ): CalibrationViewEvidence {
        val accepted = json.requireBoolean("ok")
        val reason = if (accepted) {
            require(!json.has("reason")) { "Accepted calibration view contains a rejection reason" }
            null
        } else {
            val code = runCatching {
                CalibrationViewRejectionCode.valueOf(json.requireString("reason"))
            }.getOrElse { throw IllegalArgumentException("Unknown calibration view rejection code") }
            CalibrationViewRejection(code, "Persisted calibration rejection: ${code.name.lowercase(Locale.ROOT)}")
        }
        val evidenceSource = json.optionalString("e")?.let(CalibrationEvidenceSource::fromWireName)
            ?: provenance.evidenceSource
        val width = json.optionalIntAllowNull("w", profile.width)
        val height = json.optionalIntAllowNull("h", profile.height)
        val optical = CalibrationOpticalMetadata(
            cameraId = json.optionalStringAllowDefault("cam", provenance.optical.cameraId),
            lensId = json.optionalStringAllowDefault("lens", provenance.optical.lensId),
            zoomRatio = json.optionalDoubleAllowDefault("z", provenance.optical.zoomRatio),
            cropFingerprint = json.optionalStringAllowDefault("crop", provenance.optical.cropFingerprint),
            orientation = json.optionalStringAllowDefault("o", provenance.optical.orientation)
                ?: CalibrationOpticalMetadata.EXIF_UPRIGHT,
            focal35mm = json.optionalDoubleAllowDefault("f", profile.focal35mm),
            evidenceSource = evidenceSource,
        )
        return CalibrationViewEvidence(
            index = json.requireInt("i"),
            sourceId = json.requireString("id"),
            sourceSha256 = json.optionalString("sha")?.also(::requireSha256),
            uprightWidth = width,
            uprightHeight = height,
            boardCenterXFraction = json.optionalFiniteDouble("x"),
            boardCenterYFraction = json.optionalFiniteDouble("y"),
            boardAreaFraction = json.optionalFiniteDouble("a"),
            reprojectionRmsPixels = json.optionalFiniteDouble("r"),
            optical = optical,
            rejection = reason,
        )
    }

    private fun payload(record: CalibrationProfileRecord): JSONObject {
        val profile = record.profile
        val provenance = record.provenance
        val root = JSONObject()
            .put("schema", SCHEMA_VERSION)
            .put("model", MODEL)
            .put("orientation", CalibrationOpticalMetadata.EXIF_UPRIGHT)
            .put("width", profile.width)
            .put("height", profile.height)
            .put("fx", profile.fx)
            .put("fy", profile.fy)
            .put("cx", profile.cx)
            .put("cy", profile.cy)
            .put("k1", profile.k1)
            .put("k2", profile.k2)
            .put("p1", profile.p1)
            .put("p2", profile.p2)
            .put("k3", profile.k3)
            .put("reprojectionRmsPixels", profile.reprojectionRmsPixels)
            .put("acceptedViews", profile.acceptedViews)
            .putNullable("focal35mm", profile.focal35mm)
            .put("createdAtEpochMs", provenance.createdAtEpochMs)
            .put("evidenceSource", provenance.evidenceSource.wireName)
            .put("physicalSameLensVerified", provenance.physicalSameLensVerified)
            .putNullable("sourceSetSha256", provenance.sourceSetSha256)
            .put("boardColumns", provenance.boardColumns)
            .put("boardRows", provenance.boardRows)
            .putNullable("cameraId", provenance.optical.cameraId)
            .putNullable("lensId", provenance.optical.lensId)
            .putNullable("zoomRatio", provenance.optical.zoomRatio)
            .putNullable("cropFingerprint", provenance.optical.cropFingerprint)
            .put("viewEvidenceComplete", provenance.viewEvidenceComplete)
            .put(
                "coverage",
                JSONObject()
                    .put("acceptedViews", record.coverage.acceptedViews)
                    .putNullable("xRangeFraction", record.coverage.xRangeFraction)
                    .putNullable("yRangeFraction", record.coverage.yRangeFraction)
                    .putNullable("boardAreaRatio", record.coverage.boardAreaRatio)
                    .put("metadataComplete", record.coverage.metadataComplete),
            )
        val views = JSONArray()
        record.views.sortedBy { it.index }.forEach { view ->
            views.put(encodeView(view, record))
        }
        return root.put("views", views)
    }

    private fun encodeView(view: CalibrationViewEvidence, record: CalibrationProfileRecord): JSONObject {
        val json = JSONObject()
            .put("i", view.index)
            .put("id", view.sourceId)
            .putNullable("sha", view.sourceSha256)
            .put("ok", view.accepted)
            .putNullable("x", view.boardCenterXFraction)
            .putNullable("y", view.boardCenterYFraction)
            .putNullable("a", view.boardAreaFraction)
            .putNullable("r", view.reprojectionRmsPixels)
        if (!view.accepted) json.put("reason", requireNotNull(view.rejection).code.name)
        json.putWhenDifferentOrNull("w", view.uprightWidth, record.profile.width)
        json.putWhenDifferentOrNull("h", view.uprightHeight, record.profile.height)
        json.putWhenDifferentOrNull("cam", view.optical.cameraId, record.provenance.optical.cameraId)
        json.putWhenDifferentOrNull("lens", view.optical.lensId, record.provenance.optical.lensId)
        json.putWhenDifferentOrNull("z", view.optical.zoomRatio, record.provenance.optical.zoomRatio)
        json.putWhenDifferentOrNull("crop", view.optical.cropFingerprint, record.provenance.optical.cropFingerprint)
        json.putWhenDifferentOrNull("o", view.optical.orientation, record.provenance.optical.orientation)
        json.putWhenDifferentOrNull("f", view.optical.focal35mm, record.profile.focal35mm)
        if (view.optical.evidenceSource != record.provenance.evidenceSource) {
            json.put("e", view.optical.evidenceSource.wireName)
        }
        return json
    }

    private fun <T> List<CalibrationViewEvidence>.commonString(
        selector: (CalibrationViewEvidence) -> T?,
    ): T? {
        if (isEmpty()) return null
        val values = map(selector)
        return values.firstOrNull()?.takeIf { value -> values.all { it == value } }
    }

    private fun List<CalibrationViewEvidence>.commonDouble(
        selector: (CalibrationViewEvidence) -> Double?,
    ): Double? {
        if (isEmpty()) return null
        val values = map(selector)
        if (values.any { it == null }) return null
        val sorted = values.map { requireNotNull(it) }.sorted()
        val low = sorted.first()
        val high = sorted.last()
        if (low <= 0.0 || high / low > CalibrationSetEvaluator.MAX_FOCAL_RATIO) return null
        return sorted[sorted.size / 2]
    }

    private fun JSONObject.requireString(name: String): String {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is String && value.length in 1..256 && value.none(Char::isISOControl)) {
            "Invalid $name"
        }
        return value
    }

    private fun JSONObject.optionalString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        val value = get(name)
        require(value is String && value.length in 1..256 && value.none(Char::isISOControl)) {
            "Invalid $name"
        }
        return value
    }

    private fun JSONObject.requireInt(name: String): Int {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is Number) { "Invalid $name" }
        val long = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == long.toDouble() && long in Int.MIN_VALUE..Int.MAX_VALUE) {
            "Invalid $name"
        }
        return long.toInt()
    }

    private fun JSONObject.requireLong(name: String): Long {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name)
        require(value is Number) { "Invalid $name" }
        val long = value.toLong()
        require(value.toDouble().isFinite() && value.toDouble() == long.toDouble()) { "Invalid $name" }
        return long
    }

    private fun JSONObject.requireBoolean(name: String): Boolean {
        require(has(name) && !isNull(name)) { "Missing $name" }
        return get(name) as? Boolean ?: throw IllegalArgumentException("Invalid $name")
    }

    private fun JSONObject.requireFiniteDouble(name: String): Double {
        require(has(name) && !isNull(name)) { "Missing $name" }
        val value = get(name) as? Number ?: throw IllegalArgumentException("Invalid $name")
        return value.toDouble().also { require(it.isFinite()) { "Invalid $name" } }
    }

    private fun JSONObject.optionalFiniteDouble(name: String): Double? {
        if (!has(name) || isNull(name)) return null
        val value = get(name) as? Number ?: throw IllegalArgumentException("Invalid $name")
        return value.toDouble().also { require(it.isFinite()) { "Invalid $name" } }
    }

    private fun JSONObject.requireObject(name: String): JSONObject =
        get(name) as? JSONObject ?: throw IllegalArgumentException("Invalid $name")

    private fun JSONObject.requireArray(name: String): JSONArray =
        get(name) as? JSONArray ?: throw IllegalArgumentException("Invalid $name")

    private fun JSONArray.requireObject(index: Int): JSONObject =
        get(index) as? JSONObject ?: throw IllegalArgumentException("Invalid view at index $index")

    private fun JSONObject.optionalIntAllowNull(name: String, default: Int): Int? {
        if (!has(name)) return default
        if (isNull(name)) return null
        return requireInt(name)
    }

    private fun JSONObject.optionalStringAllowDefault(name: String, default: String?): String? {
        if (!has(name)) return default
        if (isNull(name)) return null
        return optionalString(name)
    }

    private fun JSONObject.optionalDoubleAllowDefault(name: String, default: Double?): Double? {
        if (!has(name)) return default
        if (isNull(name)) return null
        return optionalFiniteDouble(name)
    }

    private fun JSONObject.putNullable(name: String, value: Any?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private fun JSONObject.putWhenDifferentOrNull(name: String, value: Any?, default: Any?): JSONObject {
        if (value != default) put(name, value ?: JSONObject.NULL)
        return this
    }
}
