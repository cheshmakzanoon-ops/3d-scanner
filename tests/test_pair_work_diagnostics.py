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
    assert 'awaitIdle(model,180000)' in test
    assert 'pairWork=$pair' in test

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
