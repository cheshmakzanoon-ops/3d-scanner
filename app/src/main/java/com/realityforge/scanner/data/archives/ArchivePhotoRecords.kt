package com.realityforge.scanner.data.archives

import com.realityforge.scanner.capture.metadata.CaptureMetadataCodec
import com.realityforge.scanner.capture.metadata.CaptureSource
import com.realityforge.scanner.data.curation.CuratedPhotoState
import com.realityforge.scanner.data.curation.PhotoCurationRecordCodec
import com.realityforge.scanner.data.importing.CaptureSettingsFields
import com.realityforge.scanner.data.reopening.BoundedJson
import org.json.JSONObject

/** A separate bounded member avoids loading every photo's canonical metadata into session.json. */
internal data class ArchivePhotoRecords(
    val captureMetadataRecord: String? = null,
    val opticalIdentitySha256: String? = null,
    val curationRecord: String? = null,
    val captureSettingsRecord: String? = null,
) {
    fun validate(id: String, sha256: String, included: Boolean, selectionOrder: Long?) {
        require((captureMetadataRecord == null) == (opticalIdentitySha256 == null)) {
            "Capture metadata and its optical identity must be supplied together."
        }
        val metadata = captureMetadataRecord?.let { record ->
            require(record.length <= 64 * 1024)
            CaptureMetadataCodec.decode(record.toByteArray(Charsets.UTF_8)).also {
                require(CaptureMetadataCodec.opticalIdentitySha256(it) == opticalIdentitySha256) {
                    "Archive optical identity differs from the canonical capture record."
                }
            }
        }
        curationRecord?.let { record ->
            val curated = PhotoCurationRecordCodec.decode(record)
            require(curated.id == id && curated.sha256 == sha256 &&
                curated.position.toLong() == selectionOrder &&
                (curated.state == CuratedPhotoState.INCLUDED) == included) {
                "Archive curation differs from its original identity or selection projection."
            }
        }
        captureSettingsRecord?.let { record ->
            val json = BoundedJson.read(record.byteInputStream(Charsets.UTF_8), MAX_SETTINGS_BYTES)
            val fields = json.keys().asSequence().associateWith { key ->
                json.get(key) as? String ?: error("Observed camera settings must contain string values.")
            }
            CaptureSettingsFields.validate(fields)
            require(fields.isEmpty() || metadata?.captureSources?.contains(CaptureSource.CAMERAX_CAPTURE) == true) {
                "Observed capture settings require their camera metadata record."
            }
        }
    }

    fun encode(): ByteArray? {
        if (captureMetadataRecord == null && opticalIdentitySha256 == null && curationRecord == null && captureSettingsRecord == null)
            return null
        return JSONObject().put("schema", 1)
            .put("captureMetadataRecord", captureMetadataRecord ?: JSONObject.NULL)
            .put("opticalIdentitySha256", opticalIdentitySha256 ?: JSONObject.NULL)
            .put("curationRecord", curationRecord ?: JSONObject.NULL)
            .put("captureSettingsRecord", captureSettingsRecord ?: JSONObject.NULL)
            .toString().toByteArray(Charsets.UTF_8).also { require(it.size <= MAX_RECORD_MEMBER_BYTES) }
    }

    companion object {
        const val MAX_RECORD_MEMBER_BYTES = 128 * 1024
        private const val MAX_SETTINGS_BYTES = 16 * 1024
        private val KEYS = setOf("schema", "captureMetadataRecord", "opticalIdentitySha256", "curationRecord", "captureSettingsRecord")

        fun decode(bytes: ByteArray): ArchivePhotoRecords {
            val json = BoundedJson.read(bytes.inputStream(), MAX_RECORD_MEMBER_BYTES)
            require(json.keys().asSequence().toSet() == KEYS && json.get("schema") == 1) {
                "Unsupported archive photo metadata schema or fields."
            }
            fun record(key: String, limit: Int): String? = if (json.isNull(key)) null else
                (json.get(key) as? String)?.also { require(it.length in 1..limit) }
                    ?: error("Archive $key must be a bounded string.")
            return ArchivePhotoRecords(record("captureMetadataRecord", 64 * 1024), record("opticalIdentitySha256", 64),
                record("curationRecord", PhotoCurationRecordCodec.MAX_RECORD_CHARS), record("captureSettingsRecord", MAX_SETTINGS_BYTES))
        }
    }
}
