"""Source regression only; installed candidate journey remains a separate device gate."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JOURNEY = ROOT / "app/src/androidTest/java/com/realityforge/scanner/qa/ReleaseJourneyTest.kt"


def test_candidate_journey_uses_bound_document_export_and_verified_receipt():
    source = JOURNEY.read_text()
    start = source.index('val destination = SharedFaultDocumentFixtures.createUri(app)')
    verify = source.index('DeviceOperationAwait.verifiedArtifactExport(model)', start)
    bind = source.index('model.beginArtifactExportRequest("textured.glb")', verify)
    complete = source.index('model.completeArtifactExport(requestId, destination)', bind)
    digest = source.index('val exportHash = SharedFaultDocumentFixtures.hash(app, destination)', complete)
    assert source.index('assertEquals(hash(artifact), exportHash)', digest) > digest
    assert '.put("export_sha256",exportHash)' in source
    assert 'SharedFaultDocumentFixtures.cleanup(app, it)' in source
    assert 'model.export(' not in source
    assert 'Uri.fromFile(destination)' not in source


def test_candidate_journey_waits_for_loaded_revision_and_completed_preview():
    source = JOURNEY.read_text()
    ready = source.index('model.projects.first { rows -> rows.any { it.id == id && it.revision == revision } }')
    reconstruct = source.index('model.reconstruct(32.88)', ready)
    preview = source.index('model.preview.filterNotNull().first { it.optLong("revision", -1) == revision }', reconstruct)
    assert source.index('model.busy.first { !it }', preview) > preview
    assert 'while (model.busy.value' not in source
