"""Keep workflow YAML tests installable from each declared host test environment."""
from pathlib import Path
import re

import pytest


ROOT = Path(__file__).resolve().parents[1]


def yaml_requirement(relative):
    rows = [line.strip() for line in (ROOT / relative).read_text().splitlines()
            if re.match(r'^pyyaml==', line, re.IGNORECASE)]
    assert len(rows) == 1, f'{relative} must directly declare exactly one PyYAML pin'
    return rows[0]


@pytest.mark.parametrize('direct,locked', [
    ('tools/requirements.txt', 'tools/requirements.lock'),
    ('tools/dependencies/qa-requirements.in', 'tools/dependencies/qa-requirements.lock'),
])
def test_workflow_yaml_parser_is_direct_and_hash_locked(direct, locked):
    trusted = yaml_requirement('tools/dependencies/security-requirements.lock')
    assert re.fullmatch(r'PyYAML==\d+\.\d+\.\d+ --hash=sha256:[0-9a-f]{64}', trusted)
    assert yaml_requirement(direct) == trusted.split()[0]
    assert yaml_requirement(locked) == trusted


def test_standard_native_ci_installs_the_declared_main_lock():
    source = (ROOT / '.github/workflows/verify.yml').read_text()
    native = source.split('  native:\n', 1)[1].split('\n  android:\n', 1)[0]
    assert 'python -m pip install --require-hashes --only-binary=:all: -r tools/requirements.lock' in native
