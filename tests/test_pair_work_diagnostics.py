"""Source wiring contracts; actual timing semantics are covered by JVM tests."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def read(name):
    return (ROOT / name).read_text()

def test_diagnostics_do_not_change_exact_matching_or_timeout():
    matcher = read('app/src/main/java/com/realityforge/scanner/engine/matching/FeatureMatcher.kt')
    assert 'ratio:Double=.67,val blockSize:Int=256' in matcher
    assert 'knnMatch(queryBlock,train,rows,2)' in matcher
    assert 'PairMatchDirection.REVERSE' in matcher and 'PairMatchDirection.FORWARD' in matcher
    assert 'finally { preservePairFailure(nativeFailure) { report(direction,start,end,queryCount,false) } }' in matcher
    assert 'catch (_: RuntimeException)' in matcher
    test = read('app/src/androidTest/java/com/realityforge/scanner/SessionReliabilityTest.kt')
    assert 'initialWait = { awaitReconstruction(model,id,revision,180000) }' in test
    assert 'continuedWait = { limit -> awaitReconstruction(model,id,revision,limit) }' in test
    assert 'featureWork=${latest?.featureWork}; pairWork=${latest?.pairWork}' in test

def test_pair_progress_owns_cancellation_and_reaches_attempt_fenced_observer():
    pipeline = read('app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt')
    assert 'PairCancellationSignal(coroutineContext[kotlinx.coroutines.Job], timing)' in pipeline
    assert 'timing.frameLoaded(true, a.cacheHit, a.features.count)' in pipeline
    assert 'timing.frameLoaded(false, b.cacheHit, b.features.count)' in pipeline
    assert 'timing.matchCache(savedMatches.containsKey(pair))' in pipeline
    assert 'timing.phase(PairWorkPhase.GEOMETRY)' in pipeline
    assert 'update.copy(pairWork = timing.snapshot())' in pipeline
    assert 'signal.close()\n                preservePairFailure(pairFailure) { timing.finish(pairOutcome) }' in pipeline
    controller = read('app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionExecutionController.kt')
    observer = controller.split('onPairWork = { sample ->', 1)[1].split('onFeatureWork', 1)[0]
    assert 'runningJobId.get()' in observer and 'runningAttempt.get()' in observer
    assert 'pairWork = sample' in observer


def test_sparse_reverse_keeps_complete_train_sets_and_bounded_copies():
    matcher = read('app/src/main/java/com/realityforge/scanner/engine/matching/FeatureMatcher.kt')
    assert 'eachAccepted(a,b,PairMatchDirection.FORWARD)' in matcher
    assert 'eachAccepted(b,a,PairMatchDirection.REVERSE,selected)' in matcher
    assert 'Mat(end-start,query.cols(),query.type())' in matcher
    assert 'selected?.get(start+x.queryIdx)' in matcher
    assert 'finally { destination.release() }' in matcher
    assert 'finally { source.release() }' in matcher
    assert 'for(i in forward.indices)' in matcher


def test_matcher_float_endpoint_matches_inclusive_cache_domain():
    matcher = read('app/src/main/java/com/realityforge/scanner/engine/matching/FeatureMatcher.kt')
    cache = read('app/src/main/java/com/realityforge/scanner/engine/features/FeatureCacheFormat.kt')
    assert 'Math.nextUp(512f).toDouble()' in matcher
    assert 'Math.nextUp(512.0)' not in matcher
    assert 'it.isFinite() && it>=0f && it<=512f' in cache
    differential = read('app/src/androidTest/java/com/realityforge/scanner/engine/matching/SparseReverseMatcherDifferentialTest.kt')
    assert 'listOf(0f,1f,512f)' in differential


def test_real_opencv_float_upper_bound_preserves_exact_descriptor_domain():
    """Real host OpenCV checkRange, not evidence of Android execution."""
    import pytest
    cv2 = pytest.importorskip('cv2', reason='Pinned optional QA OpenCV environment required')
    np = pytest.importorskip('numpy', reason='Pinned optional QA NumPy environment required')
    float_upper = float(np.nextafter(np.float32(512), np.float32(np.inf)))
    double_upper = float(np.nextafter(np.float64(512), np.float64(np.inf)))
    values = [
        np.float32(0), np.nextafter(np.float32(512), np.float32(0)), np.float32(512),
        np.nextafter(np.float32(512), np.float32(np.inf)),
        -np.nextafter(np.float32(0), np.float32(1)),
        np.float32(np.nan), np.float32(np.inf), np.float32(-np.inf),
    ]
    for index, value in enumerate(values):
        matrix = np.full((4, 128), value, dtype=np.float32)
        old_valid = cv2.checkRange(matrix, quiet=True, minVal=0, maxVal=double_upper)[0]
        new_valid = cv2.checkRange(matrix, quiet=True, minVal=0, maxVal=float_upper)[0]
        assert new_valid == (index < 3), (index, str(value))
        assert old_valid == (index < 2), (index, str(value))
    assert float_upper == float(np.float32(float_upper))
    assert float(np.float32(double_upper)) == 512.0
