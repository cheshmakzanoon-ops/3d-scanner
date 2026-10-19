package com.realityforge.scanner.capture.metadata

import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Base64

/** Bounded canonical wire format for durable storage and exact artifact identity input. */
object CaptureMetadataCodec {
    const val MEDIA_TYPE = "application/vnd.realityforge.capture-metadata.v1+text"
    private const val FULL_HEADER = "RF_CAPTURE_METADATA_V1"
    private const val IDENTITY_HEADER = "RF_CAPTURE_OPTICAL_IDENTITY_V1"
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_LINE = 16 * 1024

    fun encode(metadata: CaptureMetadata): ByteArray =
        canonicalLines(metadata, FULL_HEADER, includeRetention = true).toByteArray(Charsets.UTF_8)

    /**
     * The identity excludes export/privacy choices, but includes evidence status and provenance because
     * observed and assumed optical values are not interchangeable reconstruction inputs.
     */
    fun encodeOpticalIdentity(metadata: CaptureMetadata): ByteArray =
        canonicalLines(metadata, IDENTITY_HEADER, includeRetention = false).toByteArray(Charsets.UTF_8)

    fun opticalIdentitySha256(metadata: CaptureMetadata): String = sha256Hex(encodeOpticalIdentity(metadata))

    fun decode(bytes: ByteArray): CaptureMetadata {
        require(bytes.size in 1..MAX_BYTES) { "Capture metadata is empty or exceeds $MAX_BYTES bytes" }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        require('\u0000' !in text && '\r' !in text) { "Capture metadata contains forbidden characters" }
        require(text.endsWith('\n')) { "Capture metadata must end with a newline" }
        val lines = text.dropLast(1).split('\n')
        require(lines.isNotEmpty() && lines.first() == FULL_HEADER) { "Unsupported capture metadata header" }
        require(lines.all { it.length <= MAX_LINE }) { "Capture metadata line exceeds bounded size" }
        val values = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val separator = line.indexOf('=')
            require(separator > 0) { "Malformed capture metadata record" }
            val key = line.substring(0, separator)
            val value = line.substring(separator + 1)
            require(key.matches(Regex("[a-zA-Z0-9_.]+"))) { "Malformed capture metadata key" }
            require(values.put(key, value) == null) { "Duplicate capture metadata key: $key" }
        }

        val schema = take(values, "schema").toIntStrict()
        require(schema == CaptureMetadata.SCHEMA_VERSION) { "Unsupported capture metadata schema: $schema" }
        val captureSources = parseEnumSet<CaptureSource>(take(values, "captureSources"))
        val issues = parseEnumSet<CaptureMetadataIssue>(take(values, "issues"))
        val retained = parseBoolean(take(values, "originalMetadataRetained"))
        val metadata = CaptureMetadata(
            schemaVersion = schema,
            captureSources = captureSources,
            imageSize = readEvidence(values, "imageSize", ::parsePixelSize),
            orientation = readEvidence(values, "orientation", ::parseOrientation),
            logicalCameraId = readEvidence(values, "logicalCameraId", ::identityString),
            physicalCameraId = readEvidence(values, "physicalCameraId", ::identityString),
            reportedCameraMake = readEvidence(values, "reportedCameraMake", ::identityString),
            reportedCameraModel = readEvidence(values, "reportedCameraModel", ::identityString),
            reportedLensMake = readEvidence(values, "reportedLensMake", ::identityString),
            reportedLensModel = readEvidence(values, "reportedLensModel", ::identityString),
            zoomRatio = readEvidence(values, "zoomRatio", ::parseDouble),
            cropRegion = readEvidence(values, "cropRegion", ::parseCrop),
            sensorSizeMm = readEvidence(values, "sensorSizeMm", ::parseSensorSize),
            sensorOrientationDegrees = readEvidence(values, "sensorOrientationDegrees") { it.toIntStrict() },
            focalLengthMm = readEvidence(values, "focalLengthMm", ::parseDouble),
            focal35mmEquivalent = readEvidence(values, "focal35mmEquivalent", ::parseDouble),
            originalMetadataRetained = retained,
            issues = issues,
        )
        require(values.isEmpty()) { "Unknown capture metadata keys: ${values.keys.sorted().joinToString()}" }
        require(encode(metadata).contentEquals(bytes)) { "Capture metadata is valid but not canonical" }
        return metadata
    }

    private fun canonicalLines(metadata: CaptureMetadata, header: String, includeRetention: Boolean): String {
        val lines = ArrayList<String>(90)
        lines += header
        lines += "schema=${metadata.schemaVersion}"
        lines += "captureSources=${metadata.captureSources.sortedBy { it.ordinal }.joinToString(",") { it.name }}"
        lines += "issues=${metadata.issues.sortedBy { it.ordinal }.joinToString(",") { it.name }}"
        if (includeRetention) lines += "originalMetadataRetained=${metadata.originalMetadataRetained}"
        appendEvidence(lines, "imageSize", metadata.imageSize) { "${it.width},${it.height}" }
        appendEvidence(lines, "orientation", metadata.orientation) { it.name }
        appendEvidence(lines, "logicalCameraId", metadata.logicalCameraId, ::identityString)
        appendEvidence(lines, "physicalCameraId", metadata.physicalCameraId, ::identityString)
        appendEvidence(lines, "reportedCameraMake", metadata.reportedCameraMake, ::identityString)
        appendEvidence(lines, "reportedCameraModel", metadata.reportedCameraModel, ::identityString)
        appendEvidence(lines, "reportedLensMake", metadata.reportedLensMake, ::identityString)
        appendEvidence(lines, "reportedLensModel", metadata.reportedLensModel, ::identityString)
        appendEvidence(lines, "zoomRatio", metadata.zoomRatio, ::canonicalDouble)
        appendEvidence(lines, "cropRegion", metadata.cropRegion) {
            listOf(it.left, it.top, it.right, it.bottom, it.referenceLeft, it.referenceTop, it.referenceRight, it.referenceBottom)
                .joinToString(",")
        }
        appendEvidence(lines, "sensorSizeMm", metadata.sensorSizeMm) {
            "${canonicalDouble(it.width)},${canonicalDouble(it.height)}"
        }
        appendEvidence(lines, "sensorOrientationDegrees", metadata.sensorOrientationDegrees) { it.toString() }
        appendEvidence(lines, "focalLengthMm", metadata.focalLengthMm, ::canonicalDouble)
        appendEvidence(lines, "focal35mmEquivalent", metadata.focal35mmEquivalent, ::canonicalDouble)
        val result = lines.joinToString(separator = "\n", postfix = "\n")
        require(result.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "Capture metadata exceeds canonical size bound" }
        return result
    }

    private fun <T : Any> appendEvidence(
        lines: MutableList<String>,
        name: String,
        evidence: FieldEvidence<T>,
        encodeValue: (T) -> String,
    ) {
        lines += "$name.status=${evidence.status.name}"
        lines += "$name.checked=${evidence.checkedOrigins.sortedBy { it.ordinal }.joinToString(",") { it.name }}"
        lines += "$name.uncertainty=${evidence.relativeUncertainty?.let(::canonicalDouble) ?: "-"}"
        lines += "$name.value=${evidence.value?.let { encodeOpaque(encodeValue(it)) } ?: "-"}"
        val observations = evidence.observations.sortedWith(
            compareBy<EvidenceObservation<T>>({ it.origin.ordinal }, { encodeValue(it.value) }),
        )
        lines += "$name.observations=${observations.joinToString(";") { "${it.origin.name}:${encodeOpaque(encodeValue(it.value))}" }}"
    }

    private fun <T : Any> readEvidence(
        values: MutableMap<String, String>,
        name: String,
        parseValue: (String) -> T,
    ): FieldEvidence<T> {
        val status = enumValueOf<EvidenceStatus>(take(values, "$name.status"))
        val checked = parseEnumSet<EvidenceOrigin>(take(values, "$name.checked"))
        val uncertaintyText = take(values, "$name.uncertainty")
        val uncertainty = if (uncertaintyText == "-") null else parseDouble(uncertaintyText)
        val valueText = take(values, "$name.value")
        val value = if (valueText == "-") null else parseValue(decodeOpaque(valueText))
        val observationText = take(values, "$name.observations")
        val observations = if (observationText.isEmpty()) emptyList() else observationText.split(';').map { record ->
            val separator = record.indexOf(':')
            require(separator > 0) { "Malformed $name observation" }
            val origin = enumValueOf<EvidenceOrigin>(record.substring(0, separator))
            val observationValue = parseValue(decodeOpaque(record.substring(separator + 1)))
            EvidenceObservation(observationValue, origin)
        }
        return FieldEvidence(status, value, observations, checked, uncertainty)
    }

    private inline fun <reified E : Enum<E>> parseEnumSet(text: String): Set<E> {
        if (text.isEmpty()) return emptySet()
        val parsed = text.split(',').map { enumValueOf<E>(it) }
        require(parsed.distinct().size == parsed.size) { "Duplicate enum value" }
        require(parsed == parsed.sortedBy { it.ordinal }) { "Enum set is not canonical" }
        return parsed.toSet()
    }

    private fun encodeOpaque(value: String): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(Charsets.UTF_8))

    private fun decodeOpaque(value: String): String {
        require(value.length <= MAX_LINE)
        val decoded = runCatching { Base64.getUrlDecoder().decode(value) }
            .getOrElse { throw IllegalArgumentException("Malformed capture metadata base64", it) }
        require(decoded.size <= MAX_LINE)
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(decoded)).toString()
        require(encodeOpaque(text) == value) { "Opaque capture metadata value is not canonical" }
        return text
    }

    private fun parsePixelSize(text: String): PixelSize {
        val parts = text.split(',')
        require(parts.size == 2)
        return PixelSize(parts[0].toIntStrict(), parts[1].toIntStrict())
    }

    private fun parseOrientation(text: String): ImageOrientation = enumValueOf(text)

    private fun parseCrop(text: String): SensorCrop {
        val values = text.split(',')
        require(values.size == 8)
        val n = values.map { it.toIntStrict() }
        return SensorCrop(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7])
    }

    private fun parseSensorSize(text: String): SensorSizeMm {
        val values = text.split(',')
        require(values.size == 2)
        return SensorSizeMm(parseDouble(values[0]), parseDouble(values[1]))
    }

    private fun parseDouble(text: String): Double {
        val value = runCatching { BigDecimal(text).toDouble() }
            .getOrElse { throw IllegalArgumentException("Malformed decimal", it) }
        require(value.isFinite() && canonicalDouble(value) == text) { "Decimal is not finite or canonical" }
        return value
    }

    private fun canonicalDouble(value: Double): String {
        require(value.isFinite())
        val normalized = BigDecimal.valueOf(if (value == -0.0) 0.0 else value).stripTrailingZeros()
        return normalized.toPlainString()
    }

    private fun String.toIntStrict(): Int {
        val result = toInt()
        require(result.toString() == this) { "Integer is not canonical" }
        return result
    }

    private fun identityString(value: String): String = value

    private fun parseBoolean(text: String): Boolean = when (text) {
        "true" -> true
        "false" -> false
        else -> throw IllegalArgumentException("Malformed boolean")
    }

    private fun take(values: MutableMap<String, String>, key: String): String =
        requireNotNull(values.remove(key)) { "Missing capture metadata key: $key" }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
