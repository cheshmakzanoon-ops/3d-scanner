"""Fail-closed diagnostic classification; never changes production policy."""
import argparse
import json
import hashlib
from pathlib import Path


def classify(folder):
    receipt = json.loads((folder/'receipt.json').read_text())
    expected = {(kind, check) for kind in ('empty', 'numpy', 'leak', 'uaf', 'ub')
                for check in ('exit', 'observe', 'boundary')}
    records = receipt.get('records', [])
    if receipt.get('schema') != 2 or receipt.get('status') != 'COMPLETE' or len(records) != 16:
        raise ValueError('Incomplete or invalid receipt schema')
    if not receipt.get('pins_before') or receipt['pins_before'] != receipt.get('pins_after'):
        raise ValueError('Input hashes changed or missing')
    rows = {}
    for row in records:
        kind, check = row.get('kind'), row.get('check')
        key = (kind, check)
        name = 'compile.log' if key == ('compile', None) else str(kind)+'-'+str(check)+'.log'
        if key in rows or (key not in expected and key != ('compile', None)):
            raise ValueError('Duplicate or unexpected record')
        if row.get('log') != name or Path(name).name != name:
            raise ValueError('Unsafe or incorrect log name')
        path = folder/name
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing or symlink log')
        data = path.read_bytes()
        if len(data) > 4*1024*1024 or len(data) != row.get('log_bytes') or hashlib.sha256(data).hexdigest() != row.get('sha256'):
            raise ValueError('Log integrity failure')
        if type(row.get('exit_code')) is not int or row.get('group_drained') is not True or row.get('reason') is not None:
            raise ValueError('Invalid process outcome or cleanup failure')
        rows[key] = row
    if set(rows) != expected | {('compile', None)} or rows[('compile', None)]['exit_code'] != 0:
        raise ValueError('Compile failed or case matrix incomplete')
    findings = []
    ok = True
    for kind in ('empty', 'numpy', 'leak', 'uaf', 'ub'):
        for check in ('exit', 'observe', 'boundary'):
            row = rows.get((kind, check))
            text = (folder/(kind+'-'+check+'.log')).read_text() if row else ''
            infrastructure = (not row or row['reason'] is not None or
                              'LeakSanitizer has encountered a fatal error' in text or
                              'AddressSanitizer:DEADLYSIGNAL' in text or
                              'Traceback (most recent call last)' in text)
            valid = not infrastructure
            if kind == 'leak':
                valid &= bool(row and row['exit_code'] != 0 and
                              'LeakSanitizer: detected memory leaks' in text and
                              'Direct leak of 12347 byte(s) in 1 object(s)' in text and
                              'lose_native_allocation' in text and 'faults.c:' in text)
            elif kind == 'uaf':
                valid &= bool(row and row['exit_code'] != 0 and
                              'ERROR: AddressSanitizer: heap-use-after-free' in text and
                              'rf_proof_uaf' in text and 'faults.c:' in text)
            elif kind == 'ub':
                valid &= bool(row and row['exit_code'] != 0 and
                              'runtime error: signed integer overflow' in text and
                              'rf_proof_ub' in text and 'faults.c:' in text)
            else:
                # Any unexplained nonzero baseline is an invalid experiment.
                valid &= bool(row and (row['exit_code'] == 0 or
                              'LeakSanitizer: detected memory leaks' in text))
            findings.append(dict(kind=kind, check=check, valid=valid,
                                 infrastructure_failure=infrastructure))
            ok &= valid
    lifetime = {}
    for kind in ('empty', 'numpy'):
        logs = {check: (folder/(kind+'-'+check+'.log')).read_text()
                for check in ('exit', 'observe', 'boundary')}
        lifetime[kind] = bool(ok and
            'LeakSanitizer: detected memory leaks' in logs['exit'] and
            '"prefinalization_leaks": 0' in logs['observe'] and
            'LeakSanitizer: detected memory leaks' in logs['observe'] and
            'boundary_check_clean' in logs['boundary'] and
            rows[(kind, 'boundary')]['exit_code'] == 0)
    result = dict(schema=1, controls_valid=ok,
                  shutdown_only_baseline_reproduced=lifetime,
                  result='DIAGNOSTIC_VALID' if ok else 'INVALID_OR_FAILED_CONTROLS',
                  production_policy_authorized=False,
                  native_engine_tested=False, findings=findings,
                  note='Runtime provenance and stack equivalence require manual review; no baseline subtraction or suppression.')
    (folder/'classification.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result, indent=2))
    return 0 if ok else 1

if __name__ == '__main__':
    p = argparse.ArgumentParser()
    p.add_argument('folder', type=Path)
    folder = p.parse_args().folder
    try:
        result = classify(folder)
    except (OSError, ValueError, KeyError, TypeError) as error:
        result = 1
        folder.mkdir(parents=True, exist_ok=True)
        (folder/'classification.json').write_text(json.dumps(dict(
            schema=1, result='INVALID_EVIDENCE', controls_valid=False,
            error=type(error).__name__, production_policy_authorized=False))+'\n')
    raise SystemExit(result)
