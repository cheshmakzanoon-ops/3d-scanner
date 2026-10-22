"""Keep diagnostic shell commands safe as YAML and independent of bytecode caches."""
from pathlib import Path
import re


WORKFLOW = Path(__file__).resolve().parents[1] / '.github/workflows/lsan-boundary-diagnostic.yml'


def test_hashed_install_uses_yaml_literal_block():
    source = WORKFLOW.read_text()
    step = source.split('- name: Install existing hashed dependency resolution\n', 1)[1]
    step = step.split('\n      - ', 1)[0]
    # The trailing colon in --only-binary=:all: is a YAML mapping separator
    # when this command is an unquoted scalar. A literal block preserves it.
    assert re.search(r'^        run: \|\n', step, re.MULTILINE)
    assert '          python -m pip install --require-hashes --only-binary=:all: -r tools/requirements.lock' in step


def test_metadata_hashes_files_not_python_cache_directories():
    source = WORKFLOW.read_text()
    command = next(line.strip() for line in source.splitlines() if line.strip().startswith('sha256sum '))
    assert command == 'sha256sum tools/requirements.lock tools/qa/lsan_diagnostic/*.py tools/qa/lsan_diagnostic/faults.c > "$META/input-sha256.txt"'
