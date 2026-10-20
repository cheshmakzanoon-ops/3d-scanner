import importlib.util
from pathlib import Path
import struct
import sys
import zlib

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('fuzzrun', ROOT / 'tools/qa/fuzz/run.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


def test_checkpoint_seed_has_real_format_and_crc_and_bad_variant(tmp_path):
    result = module.corpus(tmp_path)
    assert result['synthetic']
    data = (tmp_path / 'checkpoint/valid32').read_bytes()
    assert data[:8] == b'RFDPT018'
    assert struct.unpack('<IIIII', data[8:28]) == (1, 32, 32, 0, 8192)
    assert struct.unpack('<I', data[92:96])[0] == zlib.crc32(data[96:])
    assert (tmp_path / 'checkpoint/bad_crc').read_bytes() != data
    before = result
    assert module.corpus(tmp_path) == before


def test_failing_subprocess_preserves_nonzero_status_and_log(tmp_path):
    log = tmp_path / 'failure.log'
    r = module.invoke([sys.executable, '-c', 'print("diagnostic"); raise SystemExit(17)'], log, 5)
    assert r['status'] == 'FAIL' and r['exit_code'] == 17
    assert log.read_text().strip() == 'diagnostic'
    assert len(r['log_sha256']) == 64


def test_timeout_is_failure_with_diagnostic_log(tmp_path):
    r = module.invoke([sys.executable, '-c', 'import time; time.sleep(5)'], tmp_path / 'timeout.log', 1)
    assert r['status'] == 'FAIL' and r['exit_code'] is None
    assert 'incomplete run' in (tmp_path / 'timeout.log').read_text()
