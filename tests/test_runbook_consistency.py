from pathlib import Path
import shutil
import sys
import pytest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from tools.docs.check_runbook import check, smoke, DOCS


@pytest.fixture
def tree(tmp_path):
    paths = list(DOCS) + ['VERSION', 'app/build.gradle.kts', 'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt', 'app/src/main/java/com/realityforge/scanner/engine/DenseWorkload.kt']
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
    return tmp_path


def test_current_runbook():
    report = check(ROOT)
    assert report['status'] == 'PASS', report
    assert report['documents'] == 5 and report['local_links_checked'] >= 10


def test_documented_command_smoke():
    assert all(item['status'] == 'PASS' for item in smoke(ROOT))


def test_stale_version_rejected(tree):
    path = tree / 'README.md'
    path.write_text(path.read_text().replace('0.19.0-m19', '0.17.0-m17'))
    assert check(tree)['status'] == 'FAIL'


def test_broken_local_link_rejected(tree):
    path = tree / 'README.md'
    path.write_text(path.read_text() + '\n[missing](docs/absent.md)\n')
    assert check(tree)['status'] == 'FAIL'


def test_source_limit_drift_rejected(tree):
    path = tree / 'app/src/main/java/com/realityforge/scanner/engine/SparsePipeline.kt'
    path.write_text(path.read_text().replace('.take(12)', '.take(250)'))
    assert check(tree)['status'] == 'FAIL'


def test_missing_command_rejected(tree):
    (tree / 'tools/run-device-tests.sh').unlink()
    assert check(tree)['status'] == 'FAIL'
