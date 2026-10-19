"""Agent 001 source contracts; Android compilation/runtime remains a separate gate."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "app/src/main/java/com/realityforge/scanner/ScannerViewModel.kt"
DOMAIN = ROOT / "app/src/main/java/com/realityforge/scanner/domain/OperationContext.kt"
COORDINATOR = ROOT / "app/src/main/java/com/realityforge/scanner/application/OperationCoordinator.kt"
APP = ROOT / "app/src/main/java/com/realityforge/scanner/RealityForgeApplication.kt"


def test_external_results_have_bound_begin_and_complete_apis():
    source = MODEL.read_text()
    for symbol in (
        "beginImportRequest",
        "completeImport",
        "beginCaptureRequest",
        "completeCapture",
        "beginCalibrationRequest",
        "completeCalibration",
        "beginCaptureBundleExportRequest",
        "completeCaptureBundleExport",
        "beginArtifactExportRequest",
        "completeArtifactExport",
    ):
        assert f"fun {symbol}" in source
    assert "requireCurrent(request.project)" in source
    assert "pendingId(OperationKind" in source


def test_c1_context_carries_all_required_identity():
    source = DOMAIN.read_text()
    for field in (
        "val operationId: OperationId",
        "val jobId: JobId",
        "val source: ProjectRevision?",
        "val requestId: RequestId?",
        "val sessionId: SessionId?",
        "val selectionGeneration: Long",
    ):
        assert field in source
    assert "BoundExternalRequestCodec" in source


def test_coordinator_never_reads_ui_selection():
    source = COORDINATOR.read_text()
    assert "selectedId" not in source
    assert "ProjectRevision" in source
    assert "claimPending" in source
    assert "pending.isNotEmpty()" in source


def test_application_uses_explicit_composition_root():
    source = APP.read_text()
    assert "ScannerCompositionRoot.create(this)" in source
    assert "Room.databaseBuilder" not in source
