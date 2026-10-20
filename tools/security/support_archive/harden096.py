from pathlib import Path
p=Path('/workspace/agent-workspaces/096/tools/security/scan.py');s=p.read_text().replace('from datetime import date','from datetime import date\nfrom collections import Counter').replace('import json\n','import json\nimport re\n')
pos=s.index('\ndef apply_suppressions')
s=s[:pos]+'''

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
''' + s[pos:]
s=s.replace("source = Path(finding['path']) if finding['scanner'] == 'bandit' else root / finding['path'] if finding['scanner'] == 'detect-secrets' else None", "source = source_path(root, finding['path']) if finding['scanner'] in ('bandit', 'detect-secrets') else None")
s=s.replace("entry.get('source_sha256') == digest]", "entry.get('source_sha256') == digest and all('sha256:' + hashlib.sha256(source_path(root, name).read_bytes()).hexdigest() == expected for name, expected in entry.get('context_sha256', {}).items())]")
s=s.replace("        if report['checks'].get(check) != 'NOT_RUN':", "        if check in report['checks'] and report['checks'][check] != 'NOT_RUN':")
s=s.replace('def scan(root, python, requirements=None, suppressions=None):','def scan(root, python, requirements=None, suppressions=None, expected_source=None):')
s=s.replace("    try:\n        # Verify exact", "    try:\n        root = root.resolve()\n        if not root.is_dir():\n            raise ValueError('scan scope must be a directory')\n        original = source_identity(root)\n        report.update(original, scan_root=str(root))\n        if expected_source is not None and (not re.fullmatch('[0-9a-f]{40}', expected_source) or original['source_sha'] != expected_source or original['source_dirty'] is not False):\n            raise ValueError('candidate source differs or is dirty')\n        input_hashes = {str(path.resolve()): hashlib.sha256(path.read_bytes()).hexdigest() for path in (requirements, suppressions) if path}\n        if suppressions:\n            report['suppressions_sha256'] = hashlib.sha256(suppressions.read_bytes()).hexdigest()\n        # Verify exact")
s=s.replace("'path': issue['filename']", "'path': source_path(root, issue['filename']).relative_to(root).as_posix()")
s=s.replace("        if suppressions:\n            apply_suppressions", "        report['raw_findings'] = [dict(f) for f in report['findings']]\n        report['suppressed'] = []\n        if suppressions:\n            apply_suppressions")
s=s.replace("        report['status'] = 'FAIL'", "        report['finding_counts'] = finding_counts(report['raw_findings'], report['findings'], report['suppressed'])\n        if source_identity(root) != original or any(hashlib.sha256(Path(name).read_bytes()).hexdigest() != digest for name, digest in input_hashes.items()):\n            raise ValueError('source or policy/requirements changed during scan')\n        report['status'] = 'FAIL'")
s=s.replace("    parser.add_argument('--suppressions', type=Path)", "    parser.add_argument('--suppressions', type=Path)\n    parser.add_argument('--source-sha', help='Require this exact clean Git candidate and stable scanned bytes')")
s=s.replace('args.requirements, args.suppressions)', 'args.requirements, args.suppressions, args.source_sha)')
p.write_text(s)
