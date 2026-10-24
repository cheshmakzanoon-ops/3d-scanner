"""Closed-profile source checks only; full Android/HOST compilation and execution remain separate."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / 'docs/development/experiments/eight-view-sparse-runtime-successor'
SOURCE = PACKAGE / 'ReliefThreeCompleteControllerHostTest.kt'
PREFIX = ROOT / 'docs/development/experiments/eight-view-sparse-runtime-successor/ReliefThreeSparsePrefixHostTest.kt'


def test_complete_source_uses_only_observation_inputs():
    source = SOURCE.read_text()
    def input_constants(text):
        start = text.index('private const val MANIFEST_SHA256')
        return text[start:text.index('private fun', start)].strip()
    assert input_constants(source) == input_constants(PREFIX.read_text())
    for guard in ['assertFalse(LensCalibration.file', 'assertNull(LensCalibration.readRecord', 'setOf("observation-inputs.json", "images")', 'setOf("id", "path", "sha256")']:
        assert guard in source
    for forbidden in ['evaluation-only', 'calibration.json', 'NativeCore.beginReconstruction(', 'SparsePipeline(', 'jobs.enqueue(', 'acquireNext(', 'PipelineCheckpointSink']:
        assert forbidden not in source


def test_real_controller_and_publication_barriers_are_not_sparse_fallback_success():
    source = SOURCE.read_text()
    sequence = ['ReconstructionExecutionController(fixture.context', 'actual.awaitRecovery()', 'actual.enqueue(', 'actual.start(', 'actual.jobs.observe(queued.jobId)', 'DurableJobState.COMPLETED, finished.state', 'actual.awaitWorkerStopped()', 'assertPublishedAndExport(fixture, evidence, "before-reopen", finished)', 'stopController(actual)', 'fixture.reopen()', 'assertPublishedAndExport(fixture, evidence, "after-reopen", finished)']
    indices = [source.index(value) for value in sequence]
    assert indices == sorted(indices)
    assert 'ReconstructionStage.PUBLISHING, persisted.cursor.acceptedStage' in source
    assert 'listOf("denseStatus", "meshStatus", "textureStatus")' in source
    assert '"ready", preview.optString(name)' in source
    assert 'publication is PublicationReadResult.Verified' in source
    assert 'val names = listOf("preview", "sparse-glb", "sparse-ply", "dense-glb", "dense-ply", "mesh-glb", "mesh-ply", "textured-glb", "texture-png")' in source
    assert 'PhotographicGlb.decode(' in source
    assert 'assertEquals(importedIds, registeredIds.toSet())' in source
    assert 'assertEquals(3, cameras.length())' in source
    assert 'assertEquals("global_typed_ba_admitted12", preview.getString("finalSparseOutcome"))' in source
    for call in ['source.materialize(', 'source.validate(', 'source.isCurrent(', 'artifactHashes.getValue(exportedNames.getValue(extension))', 'fixture.assertOriginals()']:
        assert call in source


def test_modeled_resources_deadlines_and_safe_cleanup_are_explicit():
    source = SOURCE.read_text()
    for token in ['MODELED_HOST_RESOURCES', '"modeledResources", true', '@Test(timeout = 120_000)', 'withTimeout(90_000)', 'withTimeout(10_000)', 'shadowOf(Looper.getMainLooper()).idle()', 'controller.cancel(requireNotNull(current).jobId)', 'cancelAndJoin()', 'if (stopped)', 'withContext(NonCancellable) { withTimeout(3_000)', 'FAILED_NOT_QUALITY_PASS', 'lastProgress.get()', 'snapshot?.cursor?.acceptedStage?.name']:
        assert token in source
    assert 'resources = ResourceWorkloadSelection.' not in source
    assert 'indices.isNotEmpty()' in source
    assert '904' not in source
    assert source.index('source.validate(') < source.index('captureImmutable(evidence, evidenceName,')


def test_binary_evidence_uses_existing_hash_addressed_inventory_contract():
    source = SOURCE.read_text()
    assert 'val evidenceName = "artifact-$exportHash.bin"' in source
    assert '.put("sha256", exportHash).put("publishedLogicalName", exportedNames.getValue(extension))' in source
    assert 'captureImmutable(evidence, "$phase-exports.json"' in source
    assert 'captureImmutable(evidence, "$phase-export-$extension"' not in source


def test_all_published_bytes_and_exact_preview_are_retained_with_job_binding():
    source = SOURCE.read_text()
    for token in ['file.canonicalFile.toPath() == path', 'Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)', 'path.startsWith(publishedRoot)', 'assertEquals(entry.bytes, bytes.size.toLong())', 'assertEquals(entry.sha256, sha(bytes))', 'captureImmutable(evidence, "artifact-$digest.bin", bytes)', 'captureImmutable(evidence, "$phase-preview.json", publishedPreviewBytes)', 'captureImmutable(evidence, "$phase-publication.json"', '.put("entries", publicationEntries)', '.put("state", persisted.state.name)', '.put("acceptedStage", persisted.cursor.acceptedStage.name)']:
        assert token in source
    assert 'preview.toString(2).toByteArray()' not in source
    assert 'assertEquals(expectedJob.input.inputSha256, identityRecord.inputIdentitySha256)' in source
    assert 'assertEquals(finished.input, persisted.input)' in source
    for field in ['setId', 'createdAtEpochMillis', 'purpose', 'projectId', 'projectRevision', 'operationId', 'attempt', 'inputIdentitySha256', 'settingsIdentitySha256', 'engineVersion', 'logicalName', 'relativePath', 'representation', 'required', 'maxBytes', 'presence', 'bytes', 'sha256']:
        assert '.put("' + field + '"' in source


def test_artifact_reads_are_bounded_and_admitted_bytes_reused():
    source = SOURCE.read_text()
    assert '.readBytes()' not in source
    for token in ['private fun boundedBytes(file: File): ByteArray', 'before.size() in 1..16L * 1024 * 1024', 'Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)', 'require(input.read() == -1)', 'before.fileKey() == after.fileKey()', 'admittedBytes.getOrPut(name)', 'PhotographicGlb.decode(publishedBytes("textured-glb"))', 'val exportedBytes = boundedBytes(target)']:
        assert token in source
