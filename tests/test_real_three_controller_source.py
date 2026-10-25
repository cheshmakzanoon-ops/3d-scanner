"""Source-contract guards only; these do not claim execution of the real controller."""
import hashlib
import importlib.util
from pathlib import Path

ADAPTER = Path(__file__).parents[1] / 'docs/development/experiments/eight-view-sparse-runtime-successor'
SOURCE = ADAPTER / 'RealLicensedThreeCompleteControllerHostTest.kt'


def test_three_controller_keeps_actual_journey_and_resource_bounds():
    source = SOURCE.read_text()
    for token in (
        '@Test(timeout = 120_000)', 'withTimeout(90_000)',
        'ReconstructionExecutionController(fixture.context, fixture.database, fixture.repository)',
        'actual.enqueue(fixture.project, revision, UUID.randomUUID().toString(), 88.0)',
        'actual.awaitRecovery()', 'actual.start(onUpdate = {}, onIdle = {})',
        'DurableJobState.COMPLETED, finished.state)', 'actual.awaitWorkerStopped()',
        'fixture.reopen()', 'DurableJobState.COMPLETED, persisted.state)',
        'ReconstructionStage.PUBLISHING, persisted.cursor.acceptedStage)',
        'PublicationReadResult.Verified', 'source.materialize(request, target)',
        'source.validate(request, target)', 'assertTrue(source.isCurrent(identity))',
        'assertEquals(artifactHashes.getValue(exportedNames.getValue(extension)), exportHash)',
        'assertEquals(importedIds, registeredIds.toSet())',
        'assertEquals(3, importedIds.size); assertEquals(3, cameras.length())',
        'assertEquals(3, preview.getInt("registeredCameras"))',
        'assertEquals(3L, revision)', 'assertEquals(3, result.importedCount)',
        'assertEquals(0, result.rejectedCount)', 'assertEquals(3L, result.finalRevision)',
        'assertEquals(3, photos.size)', 'assertEquals(3, optics.getJSONArray("images").length())',
        'assertFalse(optics.getBoolean("synthetic"))', 'assertNull(photo.focal35mm)',
        'assertFalse(LensCalibration.file(fixture.repository, fixture.project).exists())',
        'REAL_LICENSED_THREE_PARTIAL_ARC_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY',
        'require(bytes.size <= 16 * 1024 * 1024)',
        'global_typed_ba_admitted12',
        'require(matches.keys == setOf(0 to 1, 0 to 2, 1 to 2))',
        'InputPhotoIdentity.snapshotSha256(input) == job.input.inputSha256',
        'checksumBefore.contentEquals(boundedBytes(checksum))',
        'graphBefore.contentEquals(boundedBytes(owned(cache.file)))',
    ):
        assert token in source, token
    for forbidden in ('captureFailedMatchReplay', 'match-replay.json', 'reconstructCalibratedCancellable',
                      '.coordinates()', '.descriptors()', 'lastProgress', 'finished.toString()'):
        assert forbidden not in source


def test_three_controller_closed_manifest_and_original_checks():
    spec = importlib.util.spec_from_file_location('three_inputs_source_guard', ADAPTER / 'real_three_inputs.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    source = SOURCE.read_text()
    assert hashlib.sha256(module.MANIFEST_BYTES).hexdigest() in source
    assert len(module.HASHES) == 3
    for name, digest in module.HASHES.items():
        assert f'"images/{name}" to "{digest}"' in source
    assert 'HASHES.forEach { (name, hash) -> assertEquals(hash, sha(File(fixtureDir, name))) }' in source
    assert 'assertEquals(photo.bytes, file.length()); assertEquals(photo.sha256, sha(file))' in source
    assert 'assertEquals(setOf("observation-inputs.json", "images"), fixtureDir.list()!!.toSet())' in source
    assert 'assertEquals(HASHES.keys.map { File(it).name }.toSet(), File(fixtureDir, "images").list()!!.toSet())' in source


def test_three_pair_proof_follows_join_and_completed_job_without_reconstruction():
    source = SOURCE.read_text()
    assert source.index('actual.awaitWorkerStopped()') < source.index('DurableJobState.COMPLETED, finished.state)') < source.index('assertCompletePersistedPairGraph(fixture, finished)')
    method = source[source.index('private suspend fun assertCompletePersistedPairGraph'):source.index('private suspend fun stopController')]
    for forbidden in ('NativeCore.', '.save(', '.store(', 'captureImmutable', '.coordinates()', '.descriptors()'):
        assert forbidden not in method
