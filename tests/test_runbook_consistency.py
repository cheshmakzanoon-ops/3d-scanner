import json
from pathlib import Path
import shutil
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.docs.check_runbook import check, smoke, DOCS


@pytest.fixture
def tree(tmp_path):
    paths = list(DOCS) + ['.github/workflows/verify.yml', 'VERSION', 'app/build.gradle.kts', 'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt', 'app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt',
                            'app/src/main/java/com/realityforge/scanner/engine/pipeline/PipelineContract.kt']
    # Keep linked/script paths real without copying generated/build directories.
    for name in paths:
        destination = tmp_path / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(ROOT / name, destination)
    for directory in ('docs', 'tools'):
        for source in (ROOT / directory).rglob('*'):
            if source.is_file() and '__pycache__' not in source.parts:
                target = tmp_path / source.relative_to(ROOT)
                target.parent.mkdir(parents=True, exist_ok=True)
                if not target.exists():
                    shutil.copyfile(source, target)
    for name in ('AGENTS.md', 'PROJECT_PLAN.md', 'PRODUCTION_100_AGENT_EXECUTION_PLAN.md'):
        shutil.copyfile(ROOT / name, tmp_path / name)
    assert check(tmp_path)['status'] == 'PASS', check(tmp_path)
    return tmp_path


def test_current_runbook():
    report = check(ROOT)
    assert report['status'] == 'PASS', report
    assert report['documents'] == 5 and report['local_links_checked'] >= 10


def test_documented_command_smoke():
    results = smoke(ROOT)
    assert all(item['status'] == 'PASS' for item in results), json.dumps(results, indent=2, sort_keys=True)


def test_stale_version_rejected(tree):
    path = tree / 'README.md'
    path.write_text(path.read_text().replace('0.19.0-m19', '0.17.0-m17'))
    assert check(tree)['status'] == 'FAIL'


def test_broken_local_link_rejected(tree):
    path = tree / 'README.md'
    path.write_text(path.read_text() + '\n[missing](docs/absent.md)\n')
    assert check(tree)['status'] == 'FAIL'


def test_source_limit_drift_rejected(tree):
    path = tree / 'app/src/main/java/com/realityforge/scanner/engine/pipeline/PipelineContract.kt'
    path.write_text(path.read_text().replace('photoCount <= 12', 'photoCount <= 250'))
    assert check(tree)['status'] == 'FAIL'


def test_missing_command_rejected(tree):
    (tree / 'tools/run-device-tests.sh').unlink()
    assert check(tree)['status'] == 'FAIL'


@pytest.mark.parametrize('before,after', [
    ('LEGACY_12(12, true)', 'LEGACY_12(250, true)'),
    ('TARGET_30(30, false)', 'TARGET_30(30, true)'),
    ('TARGET_100(100, false)', 'TARGET_100(100, true)'),
    ('TARGET_250(250, false)', 'TARGET_250(250, true)'),
    ('ABOVE_250(Int.MAX_VALUE, false)', 'ABOVE_250(Int.MAX_VALUE, true)'),
    ('throw UnsupportedPipelineProfileException(admission)', 'admission.profile'),
])
def test_expanded_or_bypassed_capacity_rejected(tree, before, after):
    path = tree / 'app/src/main/java/com/realityforge/scanner/engine/pipeline/PipelineContract.kt'
    source = path.read_text()
    assert before in source
    path.write_text(source.replace(before, after))
    assert check(tree)['status'] == 'FAIL'


def test_pipeline_must_call_capacity_admission(tree):
    path = tree / 'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt'
    source = path.read_text()
    before = 'PipelineCapacityPolicy.requireSupported(photos.size)'
    assert before in source
    path.write_text(source.replace(before, 'PipelineCapacityProfile.LEGACY_12'))
    assert check(tree)['status'] == 'FAIL'
