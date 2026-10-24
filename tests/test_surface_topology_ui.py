"""Source wiring checks only; these do not qualify device UI or physical geometry."""
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/java/com/realityforge/scanner"

def test_workspace_studio_and_export_share_independent_qualification_display():
    workspace = (ROOT / "ui/ScannerApp.kt").read_text()
    routes = (ROOT / "ui/navigation/ResultRoutes.kt").read_text()
    assert 'indexed triangles' in workspace and 'verified triangles' not in workspace
    assert 'SurfaceTopologyQualification(requireNotNull(preparedGeometry).surfaceTopology)' in workspace
    assert 'if (geometry.mesh != null) SurfaceTopologyQualification(geometry.surfaceTopology)' in routes
    assert 'if (catalog.options.any { it.isSurfaceMesh() })' in routes
    assert 'catalog.topology?.forArtifact(it)' in routes
    assert 'OutlinedButton(enabled=!busy' in routes
    assert 'catch (_: Exception) { null }' in routes
    assert 'SurfaceTopologyQualification(geometry.surfaceTopology)' in workspace
    assert 'SurfaceTopologyQualification' not in routes.split('catalog.options.forEach')[1]

def test_qualification_is_read_only_and_reopened_under_exact_export_identity():
    source = (ROOT / "export/RepositoryExportSource.kt").read_text()
    body = source.split('suspend fun surfaceTopology(')[1].split('override suspend fun available')[0]
    assert 'if (!isCurrent(identity)) return null' in body
    assert 'repository.reopen(identity.projectId, false)' in body
    assert 'return evidence.takeIf { isCurrent(identity) }' in body
    assert not any(token in body for token in ('writeText', 'delete(', 'renameTo', 'materialize('))
    display = (ROOT / "ui/SurfaceTopologyQualification.kt").read_text()
    assert 'surfaceTopologyQualification(summary).forEach' in display
    assert 'recommendations' not in display or 'recommendation caps' in display
    assert 'Exported verified open surface' not in (ROOT / 'ScannerViewModel.kt').read_text()


def test_export_display_fences_current_selection_and_preserves_cancellation():
    routes = (ROOT / "ui/navigation/ResultRoutes.kt").read_text()
    export = routes.split("fun ExportRoute(")[1]
    assert "expectedRevision: Long?, expectedArtifactKey: String?" in export
    assert "produceState(ExportCatalog(selection),selection,source,busy)" in export
    assert "value = ExportCatalog(selection)" in export
    assert "it.selection == selection" in export and "selection.matches(it.identity)" in export
    assert "runCatching" not in export
    assert export.count("catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }") == 2
    caller = (ROOT / "ui/ScannerNavigationApp.kt").read_text()
    assert "expectedRevision = project?.takeIf { it.id == id }?.revision" in caller
    assert "expectedArtifactKey = project?.takeIf { it.id == id }?.resultKey" in caller
