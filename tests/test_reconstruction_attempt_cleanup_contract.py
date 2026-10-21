"""Structural production wiring, separate from executable helper/JNI evidence."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def test_attempt_wrapper_surrounds_existing_failure_handlers():
    source = (ROOT / 'app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionExecutionController.kt').read_text()
    body = source.split('private suspend fun runOne(', 1)[1].split('/** A stale screen', 1)[0]
    wrapper = body.index('withReconstructionAttemptCleanup(')
    work = body.index('emit(acquired.snapshot)')
    cancellation = body.index('catch (cancelled: CancellationException)', work)
    handled = body.index('catch (paused: ResourceCheckpointPause)', cancellation)
    failure = body.index('catch (failure: Throwable)', handled)
    assert wrapper < work < cancellation < handled < failure
    assert 'throw cancelled' in body[cancellation:handled]
    assert 'throw failure' not in body[failure:]
    assert 'heartbeat?.cancelAndJoin()' not in body[failure:]


def test_native_handle_is_cleared_only_after_successful_finish_and_tail_is_independent():
    source = (ROOT / 'app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionExecutionController.kt').read_text()
    wiring = source.split('withReconstructionAttemptCleanup(', 1)[1].split('emit(acquired.snapshot)', 1)[0]
    assert wiring.index('check(NativeCore.finishReconstruction(token))') < wiring.index('nativeToken.compareAndSet(token, 0L)')
    for action in ['releaseReservation =', 'clearProgress =', 'closeDiagnostics =', 'clearRunning =']:
        assert action in wiring
    assert 'runningJobId.compareAndSet(acquired.snapshot.jobId, null)' in wiring


def test_cleanup_scope_preserves_actual_escaping_error_and_live_heartbeat_ownership():
    source = (ROOT / 'app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionAttemptCleanup.kt').read_text()
    assert source.index('return block()') < source.index('primary = failure') < source.index('withContext(NonCancellable)')
    assert source.index('child?.cancelAndJoin()') < source.index('if (child?.isCompleted != false)') < source.index('finishNative()')
    assert source.index('finishNative()') < source.index('releaseReservation()')
    for action in ['clearProgress()', 'closeDiagnostics()', 'clearRunning()']:
        assert 'attempt { ' + action + ' }' in source
    assert 'if (original == null) throw failure' in source
    assert 'if (original !== failure) original.addSuppressed(failure)' in source
