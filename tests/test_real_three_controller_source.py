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
    start = source.index('private suspend fun assertCompletePersistedPairGraph')
    method = source[start:source.index('\n    private suspend fun ', start + 1)]
    for forbidden in ('NativeCore.', '.save(', '.store(', 'captureImmutable', '.coordinates()', '.descriptors()'):
        assert forbidden not in method


def test_partial_tail_is_closed_and_cannot_complete():
    source = SOURCE.read_text()
    before = source.index('assertStrictPreview(fixture, requireNotNull(')
    caught = source.index('} catch (original: AssertionError)', before)
    end = source.index('            val before = assertPublishedAndExport', caught)
    tail = source[caught:end]
    assert 'catch (original: Throwable)' not in source
    assert tail.index('assertEligiblePartialPreview(fixture,') < tail.index('partialDiagnostic = true')
    assert tail.count('partialDiagnostic = true') == 2
    assert tail.index('fixture.reopen()') < tail.index('"after-reopen", finished, partialDiagnostic = true')
    assert tail.index('assertEquals(ReconstructionStage.PUBLISHING, reopenedJob.cursor.acceptedStage)') < tail.index('"partial-roundtrip.json"')
    assert 'preserveStrictFailure(original) {' in tail
    helper = source[source.index('private suspend fun preserveStrictFailure'):source.index('/** Qualification is unchanged;')]
    assert '): Nothing' in helper
    assert 'if (secondary !== original) original.addSuppressed(secondary)' in helper
    assert helper.index('catch (secondary: Throwable)') < helper.index('throw original')
    assert 'complete-result.json' not in tail and 'return' not in tail
    assert 'withTimeout' not in tail and 'NonCancellable' not in tail
    assert 'for (suffix in listOf("preview.json", "publication.json", "artifacts.json", "exports.json"))' in tail
    assert 'before-reopen-$suffix' in tail and 'after-reopen-$suffix' in tail
    assert 'original.javaClass == AssertionError::class.java && marker.size <= 4096' in tail
    for token in ('put("schema", 1)', 'put("classification", "NOT_FULL_THREE")',
                  'put("profile", "real-licensed-three-complete")', 'put("requiredCameraCount", 3)',
                  'put("registeredCameraCount", 2)', 'put("finalSparseOutcome", "verified_anchor_fallback")',
                  'put("roundTripVerified", true)', 'put("originalFailureType", original.javaClass.name)'):
        assert token in tail


def test_partial_gate_cannot_admit_other_failures_or_invented_registration():
    source = SOURCE.read_text()
    gate = source[source.index('private suspend fun assertEligiblePartialPreview'):source.index('private suspend fun assertPublishedAndExport')]
    for token in ('importedIds.size == 3 && cameras.length() == 2 && registeredIds.toSet().size == 2',
                  'require(importedIds.containsAll(registeredIds))',
                  'preview.getInt("registeredCameras") == 2',
                  'preview.getString("finalSparseOutcome") == "verified_anchor_fallback"',
                  'listOf("denseStatus", "meshStatus", "textureStatus").all { preview.optString(it) == "ready" }'):
        assert token in gate
    assert 'captureImmutable' not in gate
    assertion = source[source.index('private suspend fun assertStrictPreview'):source.index('private suspend fun assertEligiblePartialPreview')]
    for token in ('assertEquals(3, importedIds.size); assertEquals(3, cameras.length())',
                  'assertEquals(importedIds, registeredIds.toSet())',
                  'assertEquals(3, preview.getInt("registeredCameras"))',
                  'assertEquals("global_typed_ba_admitted12", preview.getString("finalSparseOutcome"))',
                  'assertTrue(preview.has("bundleAdjustment"))',
                  'getInt("consistentPixels") >= 60', 'getInt("vertexCount") >= 30',
                  'getInt("triangleCount") >= 40', 'getDouble("faceCoverage") > 0.0'):
        assert token in assertion
    assert 'partialDiagnostic' not in assertion and 'verified_anchor_fallback' not in assertion
    assert 'if (partialDiagnostic) assertEligiblePartialPreview(f, preview) else assertStrictPreview(f, preview)' in source


def test_partial_retains_all_numeric_support_gates():
    source = SOURCE.read_text()
    strict = source[source.index('private suspend fun assertStrictPreview'):source.index('private suspend fun assertEligiblePartialPreview')]
    gate = source[source.index('private suspend fun assertEligiblePartialPreview'):source.index('private suspend fun assertPublishedAndExport')]
    quality = strict[strict.index('        assertTrue(preview.getInt("pointCount") > 0)'):strict.index('    }')]
    assert quality in gate


def test_executable_kotlin_preserves_original_assertion_on_every_diagnostic_outcome(tmp_path):
    """Executes the exact isolated helper; NOT app/controller/native reconstruction proof."""
    import os
    import shutil
    import subprocess
    import xml.etree.ElementTree as ET
    import pytest
    lib = Path(os.environ.get('RF_KOTLIN_TEST_LIB', '/workspace/shared/viewer-quality-qa/lib'))
    java = shutil.which('java')
    if not java or not (lib / 'kotlin-compiler-embeddable-2.1.21.jar').is_file():
        pytest.skip('Kotlin compiler/JVM unavailable for isolated failure-precedence helper')
    compiler = [lib / name for name in (
        'kotlin-compiler-embeddable-2.1.21.jar', 'kotlin-daemon-embeddable-2.1.21.jar',
        'kotlin-reflect-1.6.10.jar', 'kotlin-script-runtime-2.1.21.jar', 'kotlin-stdlib-2.1.21.jar',
        'kotlinx-coroutines-core-jvm-1.8.0.jar', 'trove4j-1.0.20200330.jar', 'annotations-13.0.jar')]
    metadata = ET.parse(ADAPTER.parents[3] / 'gradle/verification-metadata.xml').getroot()
    pins = {a.get('name'): h.get('value') for c in metadata.iter() if c.tag.endswith('component')
            for a in c if a.tag.endswith('artifact') for h in a if h.tag.endswith('sha256')}
    for jar in compiler:
        assert jar.is_file() and hashlib.sha256(jar.read_bytes()).hexdigest() == pins[jar.name], jar.name
    source = SOURCE.read_text()
    helper = source[source.index('    private suspend fun preserveStrictFailure'):source.index('    /** Qualification is unchanged;')]
    harness = tmp_path / 'FailureHarness.kt'
    harness.write_text('import kotlinx.coroutines.*\n' + helper + r'''
fun main() = runBlocking {
    for (kind in 0..4) {
        val original = AssertionError("original-strict-failure")
        val secondary: Throwable = when (kind) {
            1 -> IllegalStateException("diagnostic-failed")
            2 -> java.util.concurrent.CancellationException("budget-exhausted")
            3 -> AssertionError("diagnostic-assertion")
            else -> original
        }
        var entered = false
        var caught: Throwable? = null
        try {
            preserveStrictFailure(original) {
                entered = true
                if (kind != 0) throw secondary
            }
        } catch (failure: Throwable) { caught = failure }
        check(entered && caught === original)
        if (kind in 1..3) check(original.suppressed.size == 1 && original.suppressed[0] === secondary)
        else check(original.suppressed.isEmpty())
    }
    // Synthetic diagnostic control-flow adversaries, NOT real app/export/Room execution.
    for (failedStep in listOf("EXPORT_BEFORE", "REOPEN", "EXPORT_AFTER")) {
        val original = AssertionError("original-before-synthetic-step")
        val secondary = IllegalStateException("synthetic-step-failed")
        val visited = mutableListOf<String>()
        var marker = false
        var caught: Throwable? = null
        try {
            preserveStrictFailure(original) {
                for (step in listOf("EXPORT_BEFORE", "REOPEN", "EXPORT_AFTER", "VERIFY_REOPEN")) {
                    visited.add(step)
                    if (step == failedStep) throw secondary
                }
                marker = true
            }
        } catch (failure: Throwable) { caught = failure }
        check(caught === original && original.suppressed.single() === secondary && !marker)
        check(visited.last() == failedStep && "VERIFY_REOPEN" !in visited)
    }
    val timeoutOriginal = AssertionError("original-before-timeout")
    var timeoutCaught: Throwable? = null
    var timeoutMarker = false
    try {
        preserveStrictFailure(timeoutOriginal) {
            withTimeout(10) { awaitCancellation() }
            timeoutMarker = true
        }
    } catch (failure: Throwable) { timeoutCaught = failure }
    check(timeoutCaught === timeoutOriginal && timeoutOriginal.suppressed.size == 1 && !timeoutMarker)
    check(timeoutOriginal.suppressed[0] is TimeoutCancellationException)
    println("EXACT_HELPER_NINE_CASES_SYNTHETIC_FLOW_NOT_CONTROLLER_PROOF")
}
''')
    runtime = [lib / 'kotlin-stdlib-2.1.21.jar', lib / 'kotlinx-coroutines-core-jvm-1.8.0.jar']
    cp = lambda paths: os.pathsep.join(map(str, paths))
    classes = tmp_path / 'classes'
    compile_result = subprocess.run([java, '-Xmx512m', '-XX:ActiveProcessorCount=2', '-cp', cp(compiler),
        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-jvm-target', '17',
        '-classpath', cp(runtime), '-d', str(classes), str(harness)], capture_output=True, text=True, timeout=60)
    assert compile_result.returncode == 0, compile_result.stderr
    result = subprocess.run([java, '-Xmx128m', '-cp', cp([classes, *runtime]), 'FailureHarnessKt'],
        capture_output=True, text=True, timeout=30)
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == 'EXACT_HELPER_NINE_CASES_SYNTHETIC_FLOW_NOT_CONTROLLER_PROOF'


def test_partial_failure_status_is_closed_and_budget_cancellation_prevents_marker():
    source = SOURCE.read_text()
    tail = source[source.index('} catch (original: AssertionError)'):source.index('            val before = assertPublishedAndExport')]
    assert tail.index('currentCoroutineContext().ensureActive()') < tail.index('captureImmutable(evidence, "partial-roundtrip.json", marker)')
    assert 'is TimeoutCancellationException -> "TIMED_OUT"' in tail
    assert 'is CancellationException -> "CANCELLED"' in tail
    assert 'else -> "FAILED"' in tail and 'throw diagnostic' in tail
    for stage in ('ELIGIBILITY', 'EXPORT_BEFORE', 'REOPEN', 'EXPORT_AFTER', 'VERIFY_REOPEN'):
        assert f'partialDiagnosticStage = "{stage}"' in tail
    for forbidden in ('diagnostic.message', 'diagnostic.toString()', 'diagnostic.javaClass', 'withTimeout', 'NonCancellable'):
        assert forbidden not in tail
    assert '.put("partialDiagnosticStatus", partialDiagnosticStatus)' in source
    assert '.put("partialDiagnosticStage", partialDiagnosticStage)' in source
