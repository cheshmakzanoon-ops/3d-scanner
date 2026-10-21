package com.realityforge.scanner.agents.a067

import com.realityforge.scanner.engine.artifacts.*
import com.realityforge.scanner.export.*
import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Synthetic on-disk assets, real009 codecs/acceptance/probes, injected shape-validator contract. */
class ManifestExportSourceTest {
    private val sparse = ManifestExportPair(RepresentationKind.SPARSE_POINT_CLOUD, "application/ply")
    private val validPly = "ply\nformat ascii 1.0\nelement vertex 1\nproperty float x\nproperty float y\nproperty float z\nend_header\n0 0 0\n".toByteArray()
    private val validator = ManifestArtifactValidator { reference, file, checkpoint ->
        checkpoint()
        require(reference.mediaType == "application/ply" && file.readText().startsWith("ply\nformat ascii 1.0\n")) { "INVALID_PLY" }
    }
    private inner class Fixture {
        val root = Files.createTempDirectory("manifest-export-synthetic-").toFile()
        val staging = Files.createTempDirectory("manifest-export-staging-").toFile()
        val binding = ArtifactBinding(UUID.randomUUID().toString(), 7, "synthetic-job", 2)
        val snapshot = InputSnapshot(binding, listOf(SelectedPhotoInput("photo", Sha256.digestUtf8("photo"), Sha256.digestUtf8("metadata"), 8, 8)), Sha256.ZERO, "right-handed-arbitrary", "synthetic-v1")
        val identity = stage(PipelineStage.ALIGNMENT)
        var current: ManifestExportLease? = null
        val source = ManifestExportSource(ManifestExportLeaseProvider { id -> current?.takeIf { it.expectedBinding.projectId == id } }, mapOf(sparse to validator), 4096)
        fun stage(kind: PipelineStage, parents: List<StageParent> = emptyList()) = StageIdentity(kind,
            AlgorithmSpec("synthetic.algorithm", "1", Sha256.digestUtf8("implementation")), CanonicalParameters.EMPTY,
            mapOf(InputComponent.ENGINE to snapshot.componentHashes.getValue(InputComponent.ENGINE)), parents)
        fun reference(path: String = "misleading-dense-name.bin", representation: RepresentationKind = sparse.representation,
                      mime: String = sparse.mediaType, stage: StageIdentity = identity, bytes: ByteArray = validPly,
                      required: Boolean = true): ArtifactRef {
            val file = File(root, path); file.parentFile.mkdirs(); file.writeBytes(bytes)
            return ArtifactRef(path, representation, mime, bytes.size.toLong(), Sha256.digest(bytes), stage.identityHash, CompletionState.COMPLETE, required)
        }
        fun manifest(stage: StageIdentity = identity, refs: List<ArtifactRef> = listOf(reference(stage = stage)),
                     completion: CompletionState = CompletionState.COMPLETE, accepted: Boolean = true,
                     dependencies: List<ManifestDependency> = emptyList()): StageManifest = StageManifest(binding,
            snapshot.recordHash, stage, completion,
            if (accepted) StageAcceptance(StageManifest.requiredAcceptance(stage.stage), "synthetic.validator", "1", Sha256.digestUtf8("accepted evidence"), true) else null,
            dependencies, refs)
        fun lease(manifest: StageManifest = manifest(), expected: StageIdentity = manifest.identity,
                  snapshot: InputSnapshot = this.snapshot, expectedBinding: ArtifactBinding = binding,
                  parents: Map<PipelineStage, ByteArray> = emptyMap(), bytes: ByteArray = StageManifestCodec.encode(manifest),
                  hash: Sha256 = Sha256.digest(bytes), publication: String = "published-2", root: File = this.root) = ManifestExportLease(
            publication, root, expectedBinding, snapshot, expected, bytes, hash, parents)
        fun publish(manifest: StageManifest = manifest()): ArtifactIdentity {
            current = lease(manifest)
            return requireNotNull(source.identity(binding.projectId))
        }
        suspend fun request(id: ArtifactIdentity = publish()): ExportRequest = ExportRequest(UUID.randomUUID().toString(), id, source.available(id).single())
        fun close() { root.deleteRecursively(); staging.deleteRecursively() }
    }
    private fun test(block: suspend Fixture.() -> Unit) = runBlocking { val fixture = Fixture(); try { fixture.block() } finally { fixture.close() } }
    private fun rejects(block: () -> Unit) { try { block(); fail("Expected rejection") } catch (_: IllegalArgumentException) {} catch (_: IllegalStateException) {} }

    @Test fun acceptedCanonicalManifestProvidesContentBoundSparseOptionAndPrivateCopy() = test {
        val request = request()
        assertTrue(request.artifact.artifactKey.startsWith("c8:"))
        assertEquals(AssetRepresentation.SPARSE_POINTS, request.option.representation)
        assertEquals(ExportFormat.PLY, request.option.format)
        assertEquals(Sha256.digest(validPly).hex, request.option.contentSha256)
        assertEquals(current!!.publishedManifestHash.hex, request.artifact.sha256)
        val target = File(staging, "copy.ply")
        source.materialize(request, target) {}
        source.validate(request, target)
        assertArrayEquals(validPly, target.readBytes())
        assertArrayEquals(validPly, File(root, "misleading-dense-name.bin").readBytes())
    }
    @Test fun leaseCopiesEncodedRecordsDefensively() = test {
        val manifest = manifest(); val wire = StageManifestCodec.encode(manifest)
        current = lease(manifest, bytes = wire)
        wire.fill(0)
        assertNotNull(source.identity(binding.projectId))
    }
    @Test fun wireChecksumTamperEvenWithNewPublishedHashFailsCodec() = test {
        val manifest = manifest(); val wire = StageManifestCodec.encode(manifest); wire[wire.lastIndex] = (wire.last().toInt() xor 1).toByte()
        current = lease(manifest, bytes = wire)
        assertNull(source.identity(binding.projectId))
    }
    @Test fun publishedHashMismatchAndTrailingWireBytesRejected() = test {
        val manifest = manifest()
        current = lease(manifest, hash = Sha256.ZERO); assertNull(source.identity(binding.projectId))
        current = lease(manifest, bytes = StageManifestCodec.encode(manifest) + byteArrayOf(1)); assertNull(source.identity(binding.projectId))
    }
    @Test fun exactRevisionAndSnapshotBindingAreRequired() = test {
        val manifest = manifest()
        val nextBinding = binding.copy(projectRevision = 8)
        val nextSnapshot = InputSnapshot(nextBinding, snapshot.selectedPhotos, snapshot.calibrationHash, snapshot.coordinateConvention, snapshot.engineCompatibilityVersion)
        current = lease(manifest, expectedBinding = nextBinding, snapshot = nextSnapshot)
        assertNull(source.identity(binding.projectId))
        val changedSnapshot = InputSnapshot(binding, snapshot.selectedPhotos, Sha256.digestUtf8("changed calibration"), snapshot.coordinateConvention, snapshot.engineCompatibilityVersion)
        current = lease(manifest, snapshot = changedSnapshot)
        assertNull(source.identity(binding.projectId))
    }
    @Test fun independentlyExpectedAlgorithmIdentityRequired() = test {
        val manifest = manifest()
        val changed = StageIdentity(identity.stage, identity.algorithm.copy(version = "2"), identity.parameters, identity.inputComponents, identity.parents)
        current = lease(manifest, expected = changed)
        assertNull(source.identity(binding.projectId))
    }
    @Test fun incompleteAndMissingAcceptanceNeverPublishOptions() = test {
        val incomplete = manifest(completion = CompletionState.INCOMPLETE, accepted = false)
        current = lease(incomplete)
        assertNull(source.identity(binding.projectId))
        rejects { manifest(accepted = false) } // Actual009 complete-manifest constructor requires accepted evidence.
    }
    @Test fun acceptedParentHashesAndTheirActualBytesAreRequired() = test {
        val parentIdentity = stage(PipelineStage.MATCHES)
        val parentRef = reference("matches.json", RepresentationKind.DIAGNOSTIC_REPORT, "application/json", parentIdentity, "{}".toByteArray())
        val parent = manifest(parentIdentity, listOf(parentRef))
        val childIdentity = stage(PipelineStage.ALIGNMENT, listOf(StageParent(PipelineStage.MATCHES, parentIdentity.identityHash)))
        val child = manifest(childIdentity, listOf(reference(stage = childIdentity)), dependencies = listOf(ManifestDependency(PipelineStage.MATCHES, parentIdentity.identityHash, StageManifestCodec.hash(parent))))
        current = lease(child)
        assertNull(source.identity(binding.projectId))
        val parentWire = StageManifestCodec.encode(parent)
        current = lease(child, parents = mapOf(PipelineStage.MATCHES to parentWire))
        val id = requireNotNull(source.identity(binding.projectId))
        assertEquals(1, source.available(id).size)
        parentWire.fill(0) // Lease retained its own immutable bytes.
        assertTrue(source.isCurrent(id))
        File(root, parentRef.relativePath).writeText("[]")
        assertFalse(source.isCurrent(id))
    }
    @Test fun rejectedParentOrWrongParentCanonicalHashCannotBeReused() = test {
        val parentIdentity = stage(PipelineStage.MATCHES)
        val parent = manifest(parentIdentity, listOf(reference("matches.json", RepresentationKind.DIAGNOSTIC_REPORT, "application/json", parentIdentity, "{}".toByteArray())), CompletionState.INCOMPLETE, false)
        val childIdentity = stage(PipelineStage.ALIGNMENT, listOf(StageParent(PipelineStage.MATCHES, parentIdentity.identityHash)))
        val child = manifest(childIdentity, listOf(reference(stage = childIdentity)), dependencies = listOf(ManifestDependency(PipelineStage.MATCHES, parentIdentity.identityHash, StageManifestCodec.hash(parent))))
        current = lease(child, parents = mapOf(PipelineStage.MATCHES to StageManifestCodec.encode(parent)))
        assertNull(source.identity(binding.projectId))
    }
    @Test fun sameSizeReplacementInvalidatesCurrentAndStaging() = test {
        val request = request()
        val original = File(root, "misleading-dense-name.bin")
        original.writeBytes(validPly.copyOf().apply { this[lastIndex - 1] = '1'.code.toByte() })
        assertFalse(source.isCurrent(request.artifact))
        assertTrue(source.available(request.artifact).isEmpty())
        val target = File(staging, "copy")
        rejects { source.materialize(request, target) {} }
        assertFalse(target.exists())
    }
    @Test fun stalePublicationCannotRebindIdenticalBytes() = test {
        val request = request(); val manifest = manifest()
        current = lease(manifest, publication = "new-publication")
        assertFalse(source.isCurrent(request.artifact))
        current = null
        assertTrue(source.available(request.artifact).isEmpty())
    }
    @Test fun symlinkFileAndParentDirectoryRejected() = test {
        val manifest = manifest(); val original = File(root, "misleading-dense-name.bin")
        val outside = File(staging, "outside.ply").apply { writeBytes(validPly) }
        original.delete(); Files.createSymbolicLink(original.toPath(), outside.toPath())
        current = lease(manifest); assertNull(source.identity(binding.projectId))
        original.delete()
        val nested = manifest(refs = listOf(reference("nested/points.ply")))
        File(root, "nested").deleteRecursively()
        Files.createSymbolicLink(File(root, "nested").toPath(), staging.toPath())
        File(staging, "points.ply").writeBytes(validPly)
        current = lease(nested); assertNull(source.identity(binding.projectId))
    }
    @Test fun symlinkRootAndRelativeTraversalRejected() = test {
        val manifest = manifest()
        val link = File(staging, "linked-root"); Files.createSymbolicLink(link.toPath(), root.toPath())
        current = lease(manifest, root = link); assertNull(source.identity(binding.projectId))
        for (path in listOf("../escape", "/absolute", "nested/../escape", "nested\\escape")) {
            rejects { ArtifactRef(path, sparse.representation, sparse.mediaType, 1, Sha256.ZERO, identity.identityHash, CompletionState.COMPLETE) }
        }
    }
    @Test fun unsupportedPairsAndAbsentValidatorsAreUnavailable() = test {
        val manifest = manifest(refs = listOf(reference(mime = "application/json")))
        current = lease(manifest)
        val id = requireNotNull(source.identity(binding.projectId))
        assertTrue(source.available(id).isEmpty())
        val unregistered = ManifestExportSource(ManifestExportLeaseProvider { current }, emptyMap())
        current = lease(manifest())
        assertTrue(unregistered.available(requireNotNull(unregistered.identity(binding.projectId))).isEmpty())
    }
    @Test fun actualShapeValidationFailureWithCorrectHashIsUnavailable() = test {
        val manifest = manifest(refs = listOf(reference(bytes = "invalid shape".toByteArray())))
        current = lease(manifest)
        val id = requireNotNull(source.identity(binding.projectId))
        assertTrue(source.available(id).isEmpty())
    }
    @Test fun forgedOptionAndUnmaterializedEditCannotOpenSource() = test {
        val request = request()
        rejects { source.materialize(request.copy(option = request.option.copy(contentSha256 = "0".repeat(64))), File(staging, "copy")) {} }
        rejects { source.materialize(request.copy(options = ExportOptions(editIdentity = "a".repeat(64))), File(staging, "copy")) {} }
    }
    @Test fun stagedTamperFailsValidationWithoutChangingOriginals() = test {
        val request = request(); val target = File(staging, "copy")
        source.materialize(request, target) {}
        target.writeBytes(validPly.copyOf().apply { this[lastIndex - 1] = '1'.code.toByte() })
        rejects { source.validate(request, target) }
        assertArrayEquals(validPly, File(root, "misleading-dense-name.bin").readBytes())
    }
    @Test fun cancelledCopyDeletesOnlyPrivatePartial() = test {
        val request = request(); val target = File(staging, "partial")
        var calls = 0
        try { source.materialize(request, target) { if (++calls == 3) throw ExportCancelled() }; fail("Expected cancellation") } catch (_: ExportCancelled) {}
        assertFalse(target.exists())
        assertArrayEquals(validPly, File(root, "misleading-dense-name.bin").readBytes())
    }
    @Test fun leaseRevokedDuringCopyRemovesPartial() = test {
        val request = request(); val target = File(staging, "partial")
        rejects { source.materialize(request, target) { current = null } }
        assertFalse(target.exists())
    }
    @Test fun boundedCatalogRejectsOversizeRequiredArtifact() = test {
        current = lease(manifest())
        val small = ManifestExportSource(ManifestExportLeaseProvider { current }, mapOf(sparse to validator), 8)
        assertNull(small.identity(binding.projectId))
    }
    @Test fun targetInsideProjectAndTargetSymlinkPreserveOriginals() = test {
        val request = request(); val original = File(root, "misleading-dense-name.bin")
        rejects { source.materialize(request, original) {} }
        val link = File(staging, "link"); Files.createSymbolicLink(link.toPath(), original.toPath())
        rejects { source.materialize(request, link) {} }
        assertArrayEquals(validPly, original.readBytes())
    }
    @Test fun hardLinkedPrivateTargetCannotTruncateAnAcceptedSource() = test {
        val request = request(); val original = File(root, "misleading-dense-name.bin")
        val linked = File(staging, "linked-target")
        Files.createLink(linked.toPath(), original.toPath())
        rejects { source.materialize(request, linked) {} }
        assertArrayEquals(validPly, original.readBytes()); assertArrayEquals(validPly, linked.readBytes())
    }
    @Test fun preexistingNonemptyPrivateTargetIsNeverOverwrittenOrDeleted() = test {
        val request = request(); val target = File(staging, "previous-export").apply { writeText("preserve previous bytes") }
        rejects { source.materialize(request, target) {} }
        assertEquals("preserve previous bytes", target.readText())
        assertArrayEquals(validPly, File(root, "misleading-dense-name.bin").readBytes())
    }
    @Test fun optionalBadArtifactIsNeverOffered() = test {
        val good = reference()
        val optional = reference("optional.ply", required = false)
        val manifest = manifest(refs = listOf(good, optional)); current = lease(manifest)
        val id = requireNotNull(source.identity(binding.projectId))
        File(root, optional.relativePath).writeBytes(validPly.copyOf().apply { this[lastIndex - 1] = '1'.code.toByte() })
        assertTrue(source.available(id).isEmpty())
    }
}
