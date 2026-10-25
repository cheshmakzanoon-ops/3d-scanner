"""Source wiring checks supplement actual JVM tests of admission and diagnostics."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ENGINE = ROOT / "app/src/main/java/com/realityforge/scanner/engine"


def test_sparse_live_and_persisted_consumers_share_typed_recovery_policy():
    pipeline = (ENGINE / "SparsePipeline.kt").read_text()
    report = (ENGINE / "ScanQualityReport.kt").read_text()
    assert "SparseFinalizationDiagnostics.record(output, failure)" in pipeline
    assert 'if (output.has("bundleAdjustment") || output.has("bundleAdjustmentFailure"))' in pipeline
    assert 'SparseFinalizationDiagnostics.recovery(output), "scan.failure.sparse_refinement_unavailable"' in pipeline
    assert 'if (result.has("bundleAdjustmentFailure"))' in report
    assert "SparseFinalizationDiagnostics.recovery(result)" in report


def test_mesh_publication_uses_shared_gate_and_preserves_reason_through_preview():
    dense = (ENGINE / "DensePreview.kt").read_text()
    pipeline = (ENGINE / "SparsePipeline.kt").read_text()
    assert "MeshPublicationDecision.rejection(result, glbPartial, plyPartial)" in dense
    assert 'composite.put("meshError",meshRejection.message)' in dense
    assert '.put("meshErrorCode",meshRejection.code)' in dense
    assert 'surface.optString("meshErrorCode")' in pipeline
    assert 'output.put("meshFailureCode",it)' in pipeline
    assert 'output.put("meshFailure",surface.optString("meshError"' in pipeline
    assert "Surface confidence/coverage gate rejected geometry" not in dense
