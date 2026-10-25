"""Presentation wiring checks; these do not claim rendered Android validation."""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / 'app/src/main/java/com/realityforge/scanner'


def strings(name):
    return {node.attrib['name']: node.text for node in ET.parse(ROOT / 'app/src/main/res/values' / name).getroot() if node.tag == 'string'}


def test_finished_processing_does_not_claim_a_finished_surface_model():
    copy = strings('agent_074.xml')
    assert copy['a074_completed'] == 'Processing finished'
    assert 'only points' in copy['a074_completed_scope']
    assert 'surface mesh or photographic model' in copy['a074_completed_scope']
    screen = (JAVA / 'ui/processing/ProcessingScreen.kt').read_text()
    assert 'if (state.phase == JobPhase.COMPLETED) Text(stringResource(R.string.a074_completed_scope))' in screen


def test_point_only_notice_uses_validated_studio_geometry_not_job_completion():
    screen = (JAVA / 'ui/navigation/ResultRoutes.kt').read_text()
    assert 'if (geometry.mesh == null) Text(stringResource(R.string.a071_points_only))' in screen
    copy = strings('agent_071.xml')['a071_points_only']
    assert 'Point-cloud result only' in copy
    assert 'No surface mesh or photographic model is available' in copy
    assert 'Sparse points' == strings('agent_071.xml')['a071_sparse']
    assert 'if (geometry.mesh != null) FilterChip' in screen
    assert 'if (current.photographic != null) Button' in screen


def test_existing_export_choices_remain_bytes_validated_and_representation_specific():
    source = (JAVA / 'export/RepositoryExportSource.kt').read_text()
    assert 'validate(request(identity, candidate), file)' in source
    assert 'add("ply", ExportFormat.PLY, AssetRepresentation.SPARSE_POINTS)' in source
    assert '(metadata.optJSONObject("meshPreview")?.optInt("triangleCount") ?: 0) > 0' in source
    assert '(metadata.optJSONObject("texturePreview")?.optInt("triangleCount") ?: 0) > 0' in source
    assert 'return choices.takeIf { isCurrent(identity) } ?: emptyList()' in source


def test_point_mode_on_full_result_cannot_be_mistaken_for_missing_mesh():
    screen = (JAVA / 'ui/navigation/ResultRoutes.kt').read_text()
    studio = screen.split('fun StudioRoute(', 1)[1].split('private data class ExportCatalog', 1)[0]
    loading = studio.split('LaunchedEffect(', 1)[1].split('val current = loaded?', 1)[0]
    # Load authority is the reopened whole result, not the selected renderer. Switching
    # sparse/dense mode must neither reload a mode-filtered model nor clear its mesh.
    assert 'representation' not in loading
    assert 'preparePreviewGeometry(reopened.model)' in loading
    assert 'StudioPreview(source.id, source.revision, source.resultKey, geometry, photo)' in loading
    assert 'val geometry = current.geometry' in studio
    assert 'if (geometry.mesh == null) Text(stringResource(R.string.a071_points_only))' in studio
    for mode in ('sparse', 'dense', 'surface'):
        assert f'FilterChip(representation == "{mode}", {{ representation = "{mode}" }}' in studio
    assert 'geometry.mesh =' not in studio.replace('geometry.mesh ==', '')
    render_switch = studio.split('when (representation) {', 1)[1].split('if (geometry.mesh != null) SurfaceTopologyQualification', 1)[0]
    assert 'a071_points_only' not in render_switch
    assert 'geometry.dense?.let' in render_switch and 'geometry.mesh?.let' in render_switch
    assert 'PointCloudViewer(geometry.sparse' in render_switch


def test_preparation_retains_full_mesh_alongside_point_modes_but_not_for_point_only_result():
    source = (JAVA / 'data/reopening/PreparedPreviewGeometry.kt').read_text()
    prepare = source.split('suspend fun preparePreviewGeometry(', 1)[1].split('private fun normalize', 1)[0]
    assert 'representation' not in prepare
    assert 'val sparse = normalize(model.sparsePoints, checkCancelled)' in prepare
    assert 'val mesh = if (model.meshVertices.isEmpty())' in prepare
    assert 'require(model.triangles.isEmpty())\n        null' in prepare
    assert 'PreparedSurface(vertices, indices, model.triangles.size)' in prepare
    assert 'sparse, dense, mesh,' in prepare
    # The authoritative prepared result stores all three together, independently
    # of the viewer's sparse/dense/surface selection.
    declaration = source.split('data class PreparedPreviewGeometry(', 1)[1].split('/**', 1)[0]
    assert 'val sparse: PreparedPointCloud' in declaration
    assert 'val dense: PreparedPointCloud?' in declaration
    assert 'val mesh: PreparedSurface?' in declaration
