package com.realityforge.scanner.agents.a025

import com.realityforge.scanner.data.archives.*
import com.realityforge.scanner.capture.metadata.*
import com.realityforge.scanner.data.curation.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class CaptureArchiveTest {
    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun boundedCanonicalSidecarsPreserveMetadataAndRejectForgedIdentity() {
        val root = Files.createTempDirectory("rf-archive-records").toFile()
        try {
            val file = File(root, "original").apply { writeBytes(ByteArray(4096) { (it * 37).toByte() }) }
            val id = UUID.randomUUID().toString()
            val metadata = CaptureMetadata.empty(CaptureSource.CAMERAX_CAPTURE)
            val record = CaptureMetadataCodec.encode(metadata).toString(Charsets.UTF_8)
            val curation = PhotoCurationRecordCodec.encode(CuratedPhoto(id, digest(file.readBytes()), 4,
                CuratedPhotoState.EXCLUDED, PhotoCurationReason(PhotoExclusionCode.BLUR, "Retain for review")))
            val settings = JSONObject().put("sensitivityIso", "100").put("confirmationSource", "CAMERA2_CAPTURE_RESULT").toString()
            val photo = CaptureArchivePhoto(id, file, digest(file.readBytes()), file.length(), 20, 15, null, 0,
                included = false, selectionOrder = 4, captureMetadataRecord = record,
                opticalIdentitySha256 = CaptureMetadataCodec.opticalIdentitySha256(metadata), curationRecord = curation,
                captureSettingsRecord = settings)
            val output = ByteArrayOutputStream()
            CaptureArchiveWriter().write(CaptureArchiveSource(UUID.randomUUID().toString(), "Metadata fixture", 7,
                "test-engine", listOf(photo), source = "synthetic"), output)
            val staging = File(root, "staging")
            CaptureArchiveImporter().stage(output.toByteArray().inputStream(), staging).use { archive ->
                val restored = archive.photos.single()
                assertEquals(record, restored.captureMetadataRecord)
                assertEquals(photo.opticalIdentitySha256, restored.opticalIdentitySha256)
                assertEquals(curation, restored.curationRecord)
                assertEquals(settings, restored.captureSettingsRecord)
            }
            val members = linkedMapOf<String, ByteArray>()
            ZipInputStream(output.toByteArray().inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    members[entry.name] = zip.readBytes()
                }
            }
            assertEquals("session.json", members.keys.last())
            val member = "metadata/$id.json"
            val changed = JSONObject(members.getValue(member).toString(Charsets.UTF_8))
                .put("opticalIdentitySha256", "0".repeat(64)).toString().toByteArray(Charsets.UTF_8)
            members[member] = changed
            val manifest = JSONObject(members.getValue("session.json").toString(Charsets.UTF_8))
            manifest.getJSONArray("photos").getJSONObject(0).getJSONObject("metadata")
                .put("bytes", changed.size).put("sha256", digest(changed))
            members["session.json"] = manifest.toString().toByteArray(Charsets.UTF_8)
            val forged = ByteArrayOutputStream()
            ZipOutputStream(forged).use { zip -> members.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
            } }
            assertTrue(runCatching { CaptureArchiveImporter().stage(forged.toByteArray().inputStream(), staging) }.isFailure)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            val oversized = ByteArrayOutputStream()
            ZipOutputStream(oversized).use { zip ->
                zip.putNextEntry(ZipEntry(member)); zip.write(ByteArray(128 * 1024 + 1)); zip.closeEntry()
            }
            assertTrue(runCatching { CaptureArchiveImporter().stage(oversized.toByteArray().inputStream(), staging) }.isFailure)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
        } finally { root.deleteRecursively() }
    }

    @Test fun exactOriginalBytesOrderSelectionAndProvenanceRoundTrip() {
        val root = Files.createTempDirectory("rf-archive-roundtrip").toFile()
        try {
            val photos = (1..3).map { index ->
                val bytes = ByteArray(301 + index) { ((it * 71 + index) % 255).toByte() }
                val file = File(root, "original-$index").apply { writeBytes(bytes) }
                CaptureArchivePhoto(UUID.randomUUID().toString(), file, digest(bytes), bytes.size.toLong(),
                    20, 15, null, 1000L + index, included = index != 2, selectionOrder = 4L - index)
            }
            val out = ByteArrayOutputStream()
            CaptureArchiveWriter().write(CaptureArchiveSource(UUID.randomUUID().toString(), "Synthetic bytes", 3,
                "test-engine", photos, source = "synthetic"), out)
            CaptureArchiveImporter().stage(out.toByteArray().inputStream(), File(root, "staging")).use { restored ->
                assertEquals("synthetic", restored.provenance)
                assertEquals(photos.map { it.id }, restored.photos.map { it.sourceId })
                for (i in photos.indices) {
                    assertArrayEquals(photos[i].file.readBytes(), restored.photos[i].file.readBytes())
                    assertEquals(photos[i].included, restored.photos[i].included)
                    assertEquals(photos[i].selectionOrder, restored.photos[i].selectionOrder)
                }
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun traversalMissingManifestAndUnexpectedMembersNeverEscapeStaging() {
        val root = Files.createTempDirectory("rf-archive-adversarial").toFile()
        try {
            for (member in listOf("../outside.image", "/absolute", "photos/a/../../outside", "unexpected.txt",
                "photos/${UUID.randomUUID()}.image")) {
                val zip = ByteArrayOutputStream()
                ZipOutputStream(zip).use { it.putNextEntry(ZipEntry(member)); it.write(byteArrayOf(1, 2, 3)); it.closeEntry() }
                assertTrue(member, runCatching { CaptureArchiveImporter().stage(zip.toByteArray().inputStream(), root) }.isFailure)
                assertTrue(root.listFiles().orEmpty().isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun changedHashCancellationAndDiskFailureLeaveNoImport() {
        val root = Files.createTempDirectory("rf-archive-fault").toFile()
        try {
            val photo = File(root, "original").apply { writeBytes(ByteArray(2000) { (it % 255).toByte() }) }
            val row = CaptureArchivePhoto(UUID.randomUUID().toString(), photo, digest(photo.readBytes()), photo.length(), 50, 40, 35.0, 1)
            val zip = ByteArrayOutputStream()
            CaptureArchiveWriter().write(CaptureArchiveSource(UUID.randomUUID().toString(), "Fault fixture", 1, "test", listOf(row)), zip)
            val staging = File(root, "staging")
            assertTrue(runCatching { CaptureArchiveImporter().stage(zip.toByteArray().inputStream(), staging,
                reserveAdditional = { throw java.io.IOException("quota exhausted") }) }.isFailure)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertTrue(runCatching { CaptureArchiveImporter().stage(zip.toByteArray().inputStream(), staging,
                checkCancelled = { throw java.util.concurrent.CancellationException("cancel") }) }.isFailure)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            val corrupt = ByteArrayOutputStream()
            ZipOutputStream(corrupt).use { output ->
                ZipInputStream(zip.toByteArray().inputStream()).use { input ->
                    while (true) {
                        val member = input.nextEntry ?: break
                        var bytes = input.readBytes()
                        if (member.name == "session.json") {
                            val manifest = JSONObject(bytes.toString(Charsets.UTF_8))
                            manifest.getJSONArray("photos").getJSONObject(0).put("sha256", "0".repeat(64))
                            bytes = manifest.toString().toByteArray()
                        }
                        output.putNextEntry(ZipEntry(member.name)); output.write(bytes); output.closeEntry()
                    }
                }
            }
            assertTrue(runCatching { CaptureArchiveImporter().stage(corrupt.toByteArray().inputStream(), staging) }.isFailure)
            assertTrue(staging.listFiles().orEmpty().isEmpty())
            assertEquals(row.sha256, digest(photo.readBytes()))
        } finally { root.deleteRecursively() }
    }
}
