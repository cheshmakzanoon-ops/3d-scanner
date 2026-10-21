#!/usr/bin/env python3
"""Merge an explicitly reviewed immutable delivery, preserving semantic ownership."""
import argparse
import json
from pathlib import Path
import shlex
import subprocess
import sys

from campaign import ROOT, frozen_base, git, ownership, roster


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--agent', required=True)
    p.add_argument('--source-sha', required=True)
    p.add_argument('--base', required=True)
    p.add_argument('--check', action='append', default=[], help='Quoted command, parsed into argv without a shell')
    a = p.parse_args()
    rows = roster((ROOT / 'PRODUCTION_100_AGENT_EXECUTION_PLAN.md').read_text())
    if a.base != frozen_base(ROOT):
        p.error('Base must equal the frozen campaign base, not an arbitrary comparison commit')
    agent = a.agent.zfill(3)
    if agent == '100' or not any(r['agent'] == agent for r in rows):
        p.error('Select the supplying semantic owner, not100')
    sha = git(ROOT, 'rev-parse', '--verify', a.source_sha + '^{commit}')
    if sha != a.source_sha:
        p.error('Use the full immutable commit SHA')
    if subprocess.run(['git', 'merge-base', '--is-ancestor', a.base, sha], cwd=ROOT).returncode:
        p.error('Delivery does not descend from the recorded campaign base')
    if git(ROOT, 'branch', '--show-current') != 'agents/100-integration-lead':
        p.error('Run only in the isolated integration branch')
    if (git(ROOT, 'diff', '--name-only') or git(ROOT, 'diff', '--cached', '--name-only')
            or git(ROOT, 'ls-files', '--others', '--exclude-standard')):
        p.error('Commit or resolve current integration work, including untracked inputs, first; no automatic stash/reset')
    errors = ownership(ROOT, rows, agent, a.base, sha)
    if errors:
        print(json.dumps({'status': 'FAIL', 'unowned_paths': errors}, indent=2))
        return 1
    merged = subprocess.run(['git', 'merge', '--no-ff', '--no-commit', sha], cwd=ROOT)
    conflicts = git(ROOT, 'diff', '--name-only', '--diff-filter=U').splitlines()
    if any(path != 'SOURCE_MANIFEST.sha256' for path in conflicts):
        print('Semantic conflicts retained. Route changes through their source owners.', file=sys.stderr)
        return 1
    if merged.returncode not in (0, 1):
        return merged.returncode
    if not (ROOT / '.git').exists():
        raise RuntimeError('Missing Git metadata')
    # A fresh already-merged receipt can still be indexed without changing source.
    ledger = ROOT / 'docs/agents/integration/deliveries.json'
    record = json.loads(ledger.read_text()) if ledger.exists() else {'schema': 1, 'deliveries': []}
    if not any(r['source_sha'] == sha for r in record['deliveries']):
        record['deliveries'].append({'agent': agent, 'source_sha': sha,
                                     'branch': next(r['branch'] for r in rows if r['agent'] == agent),
                                     'campaign_base': a.base, 'ownership': 'PASS'})
    ledger.write_text(json.dumps(record, indent=2) + '\n')
    subprocess.run(['git', 'add', 'SOURCE_MANIFEST.sha256', str(ledger)], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'tools/source_manifest.py', 'generate', '--source', 'index'], cwd=ROOT, check=True)
    subprocess.run(['git', 'add', 'SOURCE_MANIFEST.sha256'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'tools/source_manifest.py', 'verify', '--source', 'index'], cwd=ROOT, check=True)
    subprocess.run(['git', 'diff', '--check', '--cached'], cwd=ROOT, check=True)
    reviewed_tree = git(ROOT, 'write-tree')
    for text in a.check:
        command = shlex.split(text)
        if not command:
            raise ValueError('Empty verification command')
        # No shell interpretation; keep supplied argv and subprocess exit status.
        completed = subprocess.run(command, cwd=ROOT)
        if completed.returncode:
            print('Check failed; staged semantic result is retained for diagnosis.', file=sys.stderr)
            return completed.returncode
    # Checks may be auto-fixing. Never commit a different staged tree from the one tested.
    if git(ROOT, 'diff', '--name-only'):
        print('Verification modified tracked source after staging; review, regenerate and rerun before commit.', file=sys.stderr)
        return 1
    if git(ROOT, 'write-tree') != reviewed_tree:
        print('Verification changed the reviewed staged tree; rerun on the new candidate before commit.', file=sys.stderr)
        return 1
    if git(ROOT, 'ls-files', '--others', '--exclude-standard'):
        print('Verification created untracked inputs absent from the reviewed tree; review and rerun before commit.', file=sys.stderr)
        return 1
    subprocess.run([sys.executable, 'tools/source_manifest.py', 'verify', '--source', 'index'], cwd=ROOT, check=True)
    subprocess.run([sys.executable, 'tools/source_manifest.py', 'verify', '--source', 'worktree'], cwd=ROOT, check=True)
    subprocess.run(['git', 'diff', '--check', '--cached'], cwd=ROOT, check=True)
    subprocess.run(['git', 'commit', '-m', f'merge(agent-100): integrate reviewed Agent{agent} delivery {sha[:12]}'],
                   cwd=ROOT, check=True)
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
