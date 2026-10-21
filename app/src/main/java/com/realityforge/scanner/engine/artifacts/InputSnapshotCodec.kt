package com.realityforge.scanner.engine.artifacts

object InputSnapshotCodec {
    private const val MAGIC = "RFSN"
    private const val WIRE_VERSION = 1
    private const val SCHEMA_VERSION = 1

    fun encode(snapshot: InputSnapshot): ByteArray {
        val payload = CanonicalWriter(InputSnapshot.MAX_SNAPSHOT_BYTES).apply {
            int(SCHEMA_VERSION)
            binding(snapshot.binding)
            count(snapshot.selectedPhotos.size, InputSnapshot.MAX_PHOTOS, "selected photo")
            snapshot.selectedPhotos.forEach { photo ->
                string(photo.photoId, 160)
                hash(photo.contentHash)
                hash(photo.captureMetadataHash)
                int(photo.width)
                int(photo.height)
            }
            hash(snapshot.calibrationHash)
            string(snapshot.coordinateConvention, 192)
            string(snapshot.engineCompatibilityVersion, 96)
        }.toByteArray()
        return WireEnvelope.encode(MAGIC, WIRE_VERSION, payload, InputSnapshot.MAX_SNAPSHOT_BYTES)
    }

    fun decode(encoded: ByteArray): InputSnapshot = decodeArtifactContract("input snapshot") {
        val payload = WireEnvelope.decode(encoded, MAGIC, WIRE_VERSION, InputSnapshot.MAX_SNAPSHOT_BYTES)
        val reader = CanonicalReader(payload, InputSnapshot.MAX_SNAPSHOT_BYTES)
        val schema = reader.int("input snapshot schema")
        if (schema != SCHEMA_VERSION) {
            throw ArtifactFormatException("Unsupported input snapshot schema: $schema")
        }
        val binding = reader.binding()
        val count = reader.count(InputSnapshot.MAX_PHOTOS, 76, "selected photo")
        val photos = ArrayList<SelectedPhotoInput>(count)
        repeat(count) {
            photos += SelectedPhotoInput(
                photoId = reader.string(160, "photo ID"),
                contentHash = reader.hash("photo content SHA-256"),
                captureMetadataHash = reader.hash("photo metadata SHA-256"),
                width = reader.int("photo width"),
                height = reader.int("photo height"),
            )
        }
        val snapshot = InputSnapshot(
            binding = binding,
            selectedPhotos = photos,
            calibrationHash = reader.hash("calibration SHA-256"),
            coordinateConvention = reader.string(192, "coordinate convention"),
            engineCompatibilityVersion = reader.string(96, "engine compatibility version"),
        )
        reader.finish("input snapshot")
        return snapshot
    }
}
