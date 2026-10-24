import json
from pathlib import Path
import subprocess
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.qa.export_interoperability.make_fixtures import make
from tools.qa.export_interoperability.validate import validate, glb_document


@pytest.fixture(scope='module')
def corpus(tmp_path_factory):
    root = tmp_path_factory.mktemp('interop')
    make(root)
    return root


@pytest.mark.parametrize('suffix', ['glb', 'obj', 'ply', 'stl'])
def test_valid_independent_readers(corpus, suffix):
    expected = json.loads((corpus / ('textured.expected.json' if suffix in ('glb', 'obj') else 'geometry.expected.json')).read_text())
    report = validate(corpus / ('asymmetric.' + suffix), expected)
    assert report['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)


@pytest.mark.parametrize('name', ['invalid-index.glb', 'flipped-uv.glb', 'reversed-normal.glb', 'unproven-metric.glb', 'missing-sidecar.obj', 'traversal.obj'])
def test_adversarial_rejection(corpus, name):
    expected = json.loads((corpus / 'textured.expected.json').read_text())
    assert validate(corpus / name, expected)['status'] == 'FAIL'


@pytest.mark.parametrize('suffix', ['glb', 'obj', 'ply', 'stl'])
def test_blender_import_export_roundtrip(corpus, suffix):
    expected = json.loads((corpus / ('textured.expected.json' if suffix in ('glb', 'obj') else 'geometry.expected.json')).read_text())
    report = validate(corpus / ('asymmetric.' + suffix), expected, target_tool=True)
    assert report['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)
    assert report['target_tool']['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)


def test_wrong_scale_and_axes(corpus):
    for bounds in ([[0, 0, 0], [2000, 1000, 3000]], [[0, 0, 0], [2, 3, 1]]):
        assert validate(corpus / 'asymmetric.glb', {'bounds': bounds})['status'] == 'FAIL'


def test_tangent_smooth_vertex_normal_is_legal(corpus):
    # A tangent vertex is valid for a smooth/hard-edge transition; the face's
    # average normal still faces outward and no vertex points behind the face.
    report = validate(corpus / 'tangent-normal.glb')
    assert report['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)


def test_real066_plain_pbr_material_remains_untextured_surface():
    asset = ROOT / 'benchmarks/export_fixtures/066-metric-edit.glb'
    report = validate(asset, {'representation':'triangles','triangles':2,'textured_triangles':0,
                              'bounds':[[0,-.4,0],[2.4,0,0]]}, target_tool=True)
    assert report['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)
    assert report['target_tool']['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)


def test_missing_validator_is_not_run(corpus, monkeypatch):
    monkeypatch.delenv('RF_GLTF_VALIDATOR', raising=False)
    assert validate(corpus / 'asymmetric.glb')['status'] == 'NOT_RUN'


def test_bad_glb_length(corpus, tmp_path):
    target = tmp_path / 'short.glb'
    target.write_bytes((corpus / 'asymmetric.glb').read_bytes()[:-4])
    assert validate(target)['status'] == 'FAIL'


def test_baseline_sparse_is_points():
    for suffix in ('glb', 'ply'):
        report = validate(ROOT / 'benchmarks/output' / ('sparse.' + suffix), {'representation': 'points', 'triangles': 0})
        assert report['status'] == 'PASS', json.dumps(report, indent=2, sort_keys=True)
        assert report['geometry']['vertices'] == 579


def test_cli_failure_exit(corpus):
    run = subprocess.run([sys.executable, '-m', 'tools.qa.export_interoperability.validate', str(corpus / 'invalid-index.glb')], cwd=ROOT, capture_output=True, text=True)
    assert run.returncode == 1
    assert json.loads(run.stdout)['status'] == 'FAIL'
