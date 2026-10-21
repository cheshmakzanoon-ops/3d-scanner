"""Source wiring only; actual early controller+Room behavior belongs to HOST integration tests."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionExecutionController.kt'


def test_production_operation_identity_rejected_before_recovery_or_durable_enqueue():
    source = SOURCE.read_text().split('suspend fun enqueue(', 1)[1].split('/** START_NOT_STICKY', 1)[0]
    validation = source.index('requireCanonicalUuid(operationId, "operation identifier")')
    assert validation < source.index('awaitRecovery()') < source.index('jobs.enqueue(')
    assert validation < source.index('ContextCompat.startForegroundService(')


def test_throwing_execution_constructors_are_inside_handled_cleanup_scope():
    source = SOURCE.read_text().split('private suspend fun runOne(', 1)[1].split('/** A stale screen', 1)[0]
    scope = source.index('withReconstructionAttemptCleanup(')
    body = source.index('try {', scope)
    emitted = source.index('emit(acquired.snapshot)', body)
    for constructor in ['ProjectOperationRequest(', 'ProjectJobIdentity(', 'ReservationToken(', 'ResourceCheckpointGate(']:
        assert body < source.index(constructor) < emitted
    assert 'var cleanupReservation: ReservationToken? = null' in source[:scope]
    assert 'cleanupReservation?.let { repository.storageBudget?.release(it) }' in source
    assert '.also { cleanupReservation = it }' in source
