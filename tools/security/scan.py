"""Pinned external scanners, redacted output and fail-closed scanner execution.

Exit 0 PASS, 1 findings/FAIL, 2 unavailable/NOT_RUN. Never print scanned source.
"""
import argparse
from datetime import date
from collections import Counter
import hashlib
import json
import re
from pathlib import Path
import subprocess
import tempfile

PINS = {'bandit': '1.8.6', 'detect-secrets': '1.5.0', 'pip-audit': '2.9.0'}


def run_json(argv, accepted=(0, 1), timeout=180, cwd=None):
    run = subprocess.run(argv, capture_output=True, text=True, timeout=timeout, cwd=cwd)
    if run.returncode not in accepted:
        raise RuntimeError('scanner failed, exit ' + str(run.returncode))
    return json.loads(run.stdout)



def source_path(root, name):
    candidate = Path(name)
    candidate = candidate if candidate.is_absolute() else root / candidate
    resolved = candidate.resolve()
    if candidate.is_symlink() or not resolved.is_relative_to(root.resolve()) or not resolved.is_file():
        raise ValueError('finding source escapes scan scope or is not regular')
    return resolved


def source_identity(root):
    """Bind actual selected file bytes; cache files are never source inputs."""
    rows = []
    for path in sorted(root.rglob('*')):
        if any(part in ('.git', '__pycache__') for part in path.relative_to(root).parts):
            continue
        if path.is_symlink():
            raise ValueError('scan scope contains a symlink')
        if path.is_file():
            rows.append([path.relative_to(root).as_posix(), hashlib.sha256(path.read_bytes()).hexdigest()])
    revision = subprocess.run(['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True, timeout=10)
    dirty = None
    if revision.returncode == 0:
        dirty = bool(subprocess.check_output(['git', '-C', str(root), 'status', '--porcelain'], text=True, timeout=10))
    return {'source_sha': revision.stdout.strip() if revision.returncode == 0 else None,
            'source_dirty': dirty, 'source_files_sha256': hashlib.sha256(json.dumps(rows, separators=(',', ':')).encode()).hexdigest(),
            'source_files_count': len(rows)}


def finding_counts(raw, active, suppressed):
    def counts(rows):
        return dict(sorted(Counter(f["scanner"] + ':' + f['rule'] + ':' + f.get('severity', 'UNCLASSIFIED') for f in rows).items()))
    return {'raw': len(raw), 'active': len(active), 'suppressed': len(suppressed),
            'raw_by_rule': counts(raw), 'active_by_rule': counts(active), 'suppressed_by_rule': counts(suppressed)}

def apply_suppressions(report, root, path):
    entries = json.loads(path.read_text())
    if entries.get('schema') != 1 or not isinstance(entries.get('entries'), list):
        raise ValueError('invalid suppression policy')
    active, suppressed = [], []
    for finding in report['findings']:
        source = source_path(root, finding['path']) if finding['scanner'] in ('bandit', 'detect-secrets') else None
        digest = 'sha256:' + hashlib.sha256(source.read_bytes()).hexdigest() if source else None
        matches = [entry for entry in entries['entries'] if all(entry.get(key) == finding.get(key) for key in ('scanner', 'path', 'line', 'rule')) and entry.get('source_sha256') == digest and all('sha256:' + hashlib.sha256(source_path(root, name).read_bytes()).hexdigest() == expected for name, expected in entry.get('context_sha256', {}).items())]
        if matches:
            if len(matches) != 1 or not isinstance(matches[0].get('justification'), str) or len(matches[0]['justification']) < 30 or date.fromisoformat(matches[0]['expires']) < date.today():
                raise ValueError('invalid/expired suppression')
            suppressed.append({**finding, 'justification': matches[0]['justification'], 'expires': matches[0]['expires']})
        else:
            active.append(finding)
    report['findings'], report['suppressed'] = active, suppressed
    for check, scanner in [('static', 'bandit'), ('secrets', 'detect-secrets'), ('dependencies', 'pip-audit')]:
        if check in report['checks'] and report['checks'][check] != 'NOT_RUN':
            report['checks'][check] = 'FAIL' if any(f['scanner'] == scanner for f in active) else 'PASS'


def scan(root, python, requirements=None, suppressions=None, expected_source=None):
    report = {'schema': 1, 'status': 'NOT_RUN', 'tools': PINS, 'findings': [], 'checks': {}}
    try:
        root = root.resolve()
        if not root.is_dir():
            raise ValueError('scan scope must be a directory')
        original = source_identity(root)
        report.update(original, scan_root=str(root))
        if expected_source is not None and (not re.fullmatch('[0-9a-f]{40}', expected_source) or original['source_sha'] != expected_source or original['source_dirty'] is not False):
            raise ValueError('candidate source differs or is dirty')
        input_hashes = {str(path.resolve()): hashlib.sha256(path.read_bytes()).hexdigest() for path in (requirements, suppressions) if path}
        if suppressions:
            report['suppressions_sha256'] = hashlib.sha256(suppressions.read_bytes()).hexdigest()
        # Verify exact installed distributions through the selected isolated Python.
        code = 'import importlib.metadata,json; print(json.dumps({n:importlib.metadata.version(n) for n in '+repr(list(PINS))+'}))'
        if run_json([str(python), '-c', code], accepted=(0,)) != PINS:
            raise RuntimeError('unexpected scanner version')
        bandit = run_json([str(python), '-m', 'bandit', '-r', str(root), '-f', 'json', '-q'])
        if bandit.get('errors'):
            raise RuntimeError('Bandit could not analyze all selected files')
        for issue in bandit.get('results', []):
            report['findings'].append({'scanner': 'bandit', 'path': source_path(root, issue['filename']).relative_to(root).as_posix(), 'line': issue['line_number'], 'rule': issue['test_id'], 'severity': issue['issue_severity']})
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
        report['raw_findings'] = [dict(f) for f in report['findings']]
        report['suppressed'] = []
        if suppressions:
            apply_suppressions(report, root, suppressions)
        report['finding_counts'] = finding_counts(report['raw_findings'], report['findings'], report['suppressed'])
        if source_identity(root) != original or any(hashlib.sha256(Path(name).read_bytes()).hexdigest() != digest for name, digest in input_hashes.items()):
            raise ValueError('source or policy/requirements changed during scan')
        report['status'] = 'FAIL' if report['findings'] else 'PASS' if requirements else 'NOT_RUN'
    except (OSError, ValueError, KeyError, RuntimeError, subprocess.SubprocessError) as error:
        report['error'] = type(error).__name__ + ': scanner unavailable, failed, or incomplete'
    raw = report.get('raw_findings', report['findings'])
    report['finding_counts'] = finding_counts(raw, report['findings'], report.get('suppressed', []))
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--scanner-python', type=Path, required=True)
    parser.add_argument('--requirements', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--suppressions', type=Path)
    parser.add_argument('--source-sha', help='Require this exact clean Git candidate and stable scanned bytes')
    args = parser.parse_args()
    report = scan(args.root, args.scanner_python, args.requirements, args.suppressions, args.source_sha)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'status': report['status'], 'checks': report['checks'], 'finding_count': len(report['findings'])}))
    return {'PASS': 0, 'FAIL': 1, 'NOT_RUN': 2}[report['status']]


if __name__ == '__main__':
    raise SystemExit(main())
