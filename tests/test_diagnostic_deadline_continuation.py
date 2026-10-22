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
    assert 'initialWait = { awaitReconstruction(model,id,revision,180000) }' in session
    assert 'continuedWait = { limit -> awaitReconstruction(model,id,revision,limit) }' in session
    begin = session.index('diagnostic.run(initialWait')
    end = session.index('}, event = { marker ->', begin)
    checks = session[begin:end]
    for token in ('pointCount', 'triangleCount', 'currentPreview', 'FaultDocumentProvider.hash',
                  'hash(repository.photoFile(photo))', 'awaitPhotographicFrame()', 'signature'):
        assert token in checks
    assert 'cleanupPreservingFailure(primaryFailure' in session
    assert 'execution.awaitWorkerStopped()' in session
    assert 'repository.delete(id)' in session
    wait = session[session.index('private suspend fun awaitReconstruction('):]
    for token in (
        'withTimeout(maxMillis) {',
        'job?.takeIf { it.input.projectId == projectId && it.input.projectRevision == revision }',
        'val started = requireNotNull(admission.first)',
        'execution.jobs.observe(started.jobId).filterNotNull().first',
        'it.state.isTerminal || it.state == ReconstructionJobState.PAUSED',
        'ReconstructionJobState.COMPLETED, finished.state)',
        'assertEquals(projectId, finished.input.projectId)',
        'assertEquals(revision, finished.input.projectRevision)',
        'assertEquals(revision, row.resultRevision)',
        'val key = requireNotNull(row.resultKey)',
        'model.preview.filterNotNull().first { it.optLong("revision",-1) == revision && it.optString("artifactKey") == key }',
        'model.busy.first { !it }',
        'catch (deadline: TimeoutCancellationException)',
        'throw OperationDeadlineExceeded(maxMillis,',
        '.also { it.addSuppressed(deadline) }',
    ):
        assert token in wait
    assert wait.index('ReconstructionJobState.COMPLETED, finished.state)') < wait.index('execution.awaitWorkerStopped()')
    assert wait.index('execution.awaitWorkerStopped()') < wait.index('model.repository.dao.project(projectId)')
    assert wait.index('assertEquals(revision, row.resultRevision)') < wait.index('model.preview.filterNotNull()')
    cleanup = session[session.index('cleanupPreservingFailure(primaryFailure,'):]
    assert 'if (job?.input?.projectId == id)' in cleanup
    assert 'execution.cancel(job.jobId)' in cleanup
    assert cleanup.index('execution.awaitWorkerStopped()') < cleanup.index('repository.delete(id)')
    helper = (BASE / 'DiagnosticDeadlineContinuation.kt').read_text()
    assert helper.index('continuedWait(ADDITIONAL_WAIT_MILLIS)') < helper.index('event("DIAGNOSTIC_FUNCTIONAL_COMPLETION")')
    assert 'functionalChecks()\n            // The caller' in helper
