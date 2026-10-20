"""Pinned external scanners, redacted output and fail-closed scanner execution.

Exit 0 PASS, 1 findings/FAIL, 2 unavailable/NOT_RUN. Never print scanned source.
"""
import argparse
from datetime import date
import hashlib
import json
from pathlib import Path
import subprocess
import tempfile

PINS = {'bandit': '1.8.6', 'detect-secrets': '1.5.0', 'pip-audit': '2.9.0'}


def run_json(argv, accepted=(0, 1), timeout=180, cwd=None):
    run = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd)
    if run.returncode not in accepted:
        raise RuntimeError('scanner failed, exit ' + str(run.returncode))
    return json.loads(run.stdout)


def apply_suppressions(report, root, path):
    entries = json.loads(path.read_text())
    if entries.get('schema') != 1 or not isinstance(entries.get('entries'), list):
        raise ValueError('invalid suppression policy')
    active, suppressed = [], []
    for finding in report['findings']:
        source = Path(finding['path']) if finding['scanner'] == 'bandit' else root / finding['path'] if finding['scanner'] == 'detect-secrets' else None
        digest = 'sha256:' + hashlib.sha256(source.read_bytes()).hexdigest() if source else None
        matches = [entry for entry in entries['entries'] if all(entry.get(key) == finding.get(key) for key in ('scanner', 'path', 'line', 'rule')) and entry.get('source_sha256') == digest]
        if matches:
            if len(matches) != 1 or not isinstance(matches[0].get('justification'), str) or len(matches[0]['justification']) < 30 or date.fromisoformat(matches[0]['expires']) < date.today():
                raise ValueError('invalid/expired suppression')
            suppressed.append({**finding, 'justification': matches[0]['justification'], 'expires': matches[0]['expires']})
        else:
            active.append(finding)
    report['findings'], report['suppressed'] = active, suppressed
    for check, scanner in [('static', 'bandit'), ('secrets', 'detect-secrets'), ('dependencies', 'pip-audit')]:
        if report['checks'].get(check) != 'NOT_RUN':
            report['checks'][check] = 'FAIL' if any(f['scanner'] == scanner for f in active) else 'PASS'


def scan(root, python, requirements=None, suppressions=None):
    report = {'schema': 1, 'status': 'NOT_RUN', 'tools': PINS, 'findings': [], 'checks': {}}
    try:
        # Verify exact installed distributions through the selected isolated Python.
        code = 'import importlib.metadata,json; print(json.dumps({n:importlib.metadata.version(n) for n in '+repr(list(PINS))+'}))'
        if run_json([str(python), '-c', code], accepted=(0,)) != PINS:
            raise RuntimeError('unexpected scanner version')
        bandit = run_json([str(python), '-m', 'bandit', '-r', str(root), '-f', 'json', '-q'])
        if bandit.get('errors'):
            raise RuntimeError('Bandit could not analyze all selected files')
        for issue in bandit.get('results', []):
            report['findings'].append({'scanner': 'bandit', 'path': issue['filename'], 'line': issue['line_number'], 'rule': issue['test_id'], 'severity': issue['issue_severity']})
        report['checks']['static'] = 'FAIL' if bandit.get('results') else 'PASS'
        secrets = run_json([str(python), '-m', 'detect_secrets', 'scan', '--all-files', '--no-verify', '.'], accepted=(0,), cwd=root.resolve())
        secret_count = 0
        for path, issues in secrets.get('results', {}).items():
            for issue in issues:
                secret_count += 1
                report['findings'].append({'scanner': 'detect-secrets', 'path': path, 'line': issue['line_number'], 'rule': issue['type']})
        report['checks']['secrets'] = 'FAIL' if secret_count else 'PASS'
        report['checks']['dependencies'] = 'NOT_RUN'
        if requirements:
            report['requirements_sha256'] = hashlib.sha256(requirements.read_bytes()).hexdigest()
            # Pinned requirements only: --disable-pip prevents environment mutation.
            with tempfile.TemporaryDirectory(prefix='rf-audit-cache-') as cache:
                dependencies = run_json([str(python), '-m', 'pip_audit', '--disable-pip', '--no-deps', '--cache-dir', cache, '--progress-spinner', 'off', '-r', str(requirements), '-f', 'json'])
            count = 0
            for dep in dependencies['dependencies']:
                if 'skip_reason' in dep:
                    raise RuntimeError('dependency was not audited')
                for vuln in dep['vulns']:
                    count += 1
                    report['findings'].append({'scanner': 'pip-audit', 'package': dep['name'], 'version': dep['version'], 'rule': vuln['id'], 'fix_versions': vuln.get('fix_versions', [])})
            report['checks']['dependencies'] = 'FAIL' if count else 'PASS'
        if suppressions:
            apply_suppressions(report, root, suppressions)
        report['status'] = 'FAIL' if report['findings'] else 'PASS' if requirements else 'NOT_RUN'
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        report['error'] = type(error).__name__ + ': scanner unavailable, failed, or incomplete'
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--scanner-python', type=Path, required=True)
    parser.add_argument('--requirements', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--suppressions', type=Path)
    args = parser.parse_args()
    report = scan(args.root, args.scanner_python, args.requirements, args.suppressions)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': report['checks'], 'finding_count': len(report['findings'])}))
    return {'PASS': 0, 'FAIL': 1, 'NOT_RUN': 2}[report['status']]


if __name__ == '__main__':
    raise SystemExit(main())
