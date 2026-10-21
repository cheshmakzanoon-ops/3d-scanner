"""Wiring contracts; pure Kotlin helper also has executable host/device JUnit tests."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'app/src/androidTest/java/com/realityforge/scanner'


def test_continuation_is_test_only_explicit_and_default_off():
    helper = (BASE / 'DiagnosticDeadlineContinuation.kt').read_text()
    assert 'null, "false" -> DiagnosticDeadlineContinuation(false)' in helper
    assert '"true" -> DiagnosticDeadlineContinuation(true)' in helper
    assert 'else -> throw IllegalArgumentException' in helper
    assert 'catch (deadline: OperationDeadlineExceeded)' in helper
    assert 'deadline.deadlineMillis != NORMAL_WAIT_MILLIS' in helper
    assert 'const val NORMAL_WAIT_MILLIS = 180_000L' in helper
    assert 'const val ADDITIONAL_WAIT_MILLIS = 600_000L' in helper
    assert 'if (later !== original) original.addSuppressed(later)' in helper
    assert helper.rstrip().endswith('throw original\n    }\n}')
    assert not list((ROOT / 'app/src/main').rglob('DiagnosticDeadlineContinuation*'))


def test_same_full_checks_precede_completion_and_cleanup_remains_owned():
    session = (BASE / 'SessionReliabilityTest.kt').read_text()
    assert 'initialWait = { awaitIdle(model,180000) }' in session
    assert 'continuedWait = { limit -> awaitIdle(model,limit) }' in session
    begin = session.index('diagnostic.run(initialWait')
    end = session.index('}, event = { marker ->', begin)
    checks = session[begin:end]
    for token in ('pointCount', 'triangleCount', 'currentPreview', 'FaultDocumentProvider.hash',
                  'hash(repository.photoFile(photo))', 'awaitPhotographicFrame()', 'signature'):
        assert token in checks
    assert 'cleanupPreservingFailure(primaryFailure' in session
    assert 'execution.awaitWorkerStopped()' in session
    assert 'repository.delete(id)' in session
    assert 'if (model.busy.value) throw OperationDeadlineExceeded(maxMillis' in session
    helper = (BASE / 'DiagnosticDeadlineContinuation.kt').read_text()
    assert helper.index('continuedWait(ADDITIONAL_WAIT_MILLIS)') < helper.index('event("DIAGNOSTIC_FUNCTIONAL_COMPLETION")')
    assert 'functionalChecks()\n            // The caller' in helper
