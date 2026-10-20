#!/usr/bin/env python3
"""Validate the numbered plan, source ownership and immutable delivery index."""
from __future__ import annotations

import argparse
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[2]
PLAN = 'PRODUCTION_100_AGENT_EXECUTION_PLAN.md'
STATES = {'WORKING', 'READY_FOR_INTEGRATION', 'INTEGRATED_PENDING_VERIFICATION',
          'DONE', 'OPTIONAL_DISABLED', 'BLOCKED_EXTERNAL'}


def git(root: Path, *args: str) -> str:
    return subprocess.check_output(['git', '-C', str(root), *args], text=True).strip()


def roster(text: str) -> list[dict]:
    sections = re.split(r'^## Agent (\d{3})\s*$', text, flags=re.M)
    if len(sections) != 201:
        raise ValueError('Plan must have exactly 100 individual assignment headings')
    rows = []
    for i in range(1, len(sections), 2):
        agent, block = sections[i], sections[i + 1].split('\n## ', 1)[0]
        if agent != f'{len(rows) + 1:03d}':
            raise ValueError('Agent IDs must occur exactly once in order 001–100')
        def field(name: str) -> str:
            m = re.search(r'^\*\*' + re.escape(name) + r':\*\* (.+)$', block, re.M)
            if not m:
                raise ValueError(f'Agent {agent} missing {name}')
            return m.group(1).strip('`')
        def paths(name: str) -> list[str]:
            marker = f'**{name}:**'
            if marker not in block:
                raise ValueError(f'Agent {agent} missing {name}')
            part = block.split(marker, 1)[1].split('\n**', 1)[0]
            return re.findall(r'^- `([^`]+)`\s*$', part, re.M)
        row = {'agent': agent, 'role': field('Role'), 'branch': field('Branch'),
               'receipt': field('Status receipt'),
               'existing': paths('Exclusive existing files'),
               'reserved': paths('Reserved new files/packages')}
        if row['receipt'] != f'docs/agents/status/{agent}.md' or not row['branch'].startswith(f'agents/{agent}-'):
            raise ValueError(f'Agent {agent} has inconsistent branch or receipt')
        for name in ('Mission', 'Implementation instructions', 'Success criteria', 'Required verification'):
            if f'**{name}:**' not in block:
                raise ValueError(f'Agent {agent} missing {name}')
        for path in row['existing'] + row['reserved']:
            p = PurePosixPath(path)
            if p.is_absolute() or '..' in p.parts or '\\' in path:
                raise ValueError(f'Unsafe ownership path: {path}')
        rows.append(row)
    owners = {}
    for row in rows:
        for path in row['existing']:
            if path in owners:
                raise ValueError(f'Duplicate existing source owner for {path}')
            owners[path] = row['agent']
    if len({r['branch'] for r in rows}) != 100:
        raise ValueError('Duplicate agent branches')
    return rows


def owned(row: dict, path: str) -> bool:
    a = row['agent']
    if path == 'SOURCE_MANIFEST.sha256':
        return True
    common = [row['receipt'], f'docs/agents/contracts/{a}.md']
    if path in common or re.fullmatch(rf'docs/agents/requests/{a}-to-\d{{3}}\.md', path):
        return True
    prefixes = [f'app/src/test/java/com/realityforge/scanner/agents/a{a}/',
                f'app/src/androidTest/java/com/realityforge/scanner/agents/a{a}/']
    if any(path.startswith(p) for p in prefixes):
        return True
    if re.fullmatch(rf'(?:tests/test_agent_{a}_[^/]+\.py|reconstruction/tests/agent_{a}_[^/]+\.cpp)', path):
        return True
    if path == f'app/src/main/res/values/agent_{a}.xml':
        return True
    return any(path.startswith(p) if p.endswith('/') else path == p
               for p in row['existing'] + row['reserved'])


def ownership(root: Path, rows: list[dict], agent: str, base: str, revision: str) -> list[str]:
    row = next((r for r in rows if r['agent'] == agent), None)
    if row is None:
        raise ValueError('Unknown agent')
    # --no-renames exposes both old and new paths, so renames cannot evade ownership.
    raw = subprocess.check_output(['git', '-C', str(root), 'diff', '--no-renames',
                                   '--name-only', '-z', base, revision])
    paths = [p.decode('utf-8') for p in raw.split(b'\0') if p]
    errors = [p for p in paths if not owned(row, p)]
    if agent != '100' or not errors:
        return errors
    # The integration branch intentionally contains other owners' reviewed commits.
    # Allow only recorded ancestor deliveries, and only unchanged delivered blobs.
    saved = subprocess.run(['git', '-C', str(root), 'show',
                            f'{revision}:docs/agents/integration/deliveries.json'],
                           text=True, capture_output=True)
    if saved.returncode:
        return errors
    try:
        deliveries = json.loads(saved.stdout)['deliveries']
    except (ValueError, KeyError, TypeError):
        return errors
    permitted = set()
    for delivery in deliveries:
        a, sha = delivery.get('agent'), delivery.get('source_sha')
        source_row = next((r for r in rows if r['agent'] == a and a != '100'), None)
        if source_row is None or not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{40}', sha):
            continue
        if subprocess.run(['git', '-C', str(root), 'merge-base', '--is-ancestor', sha, revision],
                          stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode:
            continue
        for path in errors:
            if not owned(source_row, path):
                continue
            actual = subprocess.run(['git', '-C', str(root), 'rev-parse', f'{revision}:{path}'],
                                    text=True, capture_output=True)
            supplied = subprocess.run(['git', '-C', str(root), 'rev-parse', f'{sha}:{path}'],
                                      text=True, capture_output=True)
            if actual.returncode == supplied.returncode == 0 and actual.stdout == supplied.stdout:
                permitted.add(path)
            elif actual.returncode != 0 and supplied.returncode != 0:
                permitted.add(path)
    return [p for p in errors if p not in permitted]


def receipt_state(text: str) -> str:
    try:
        mapping = json.loads(text)
    except json.JSONDecodeError:
        mapping = None
    if isinstance(mapping, dict):
        value = mapping.get('state')
    else:
        match = re.search(r'^state:\s*[\"\x27]?([A-Z_]+)[\"\x27]?\s*$', text, re.M)
        value = match.group(1) if match else None
    return value if isinstance(value, str) and value in STATES else 'INVALID_RECEIPT'


def receipt_checks(text: str) -> list[str]:
    """Read the documented JSON or compact YAML subset; unknown evidence fails closed."""
    try:
        record = json.loads(text)
    except json.JSONDecodeError:
        record = None
    if not isinstance(record, dict):
        def block(key):
            match = re.search(r'^' + re.escape(key) + r':(.*)$', text, re.M)
            if not match:
                return ''
            tail = text[match.end():]
            stop = re.search(r'^(?:[a-z_]+:|#)', tail, re.M)
            return match.group(1) + (tail[:stop.start()] if stop else tail)
        results = []
        for piece in re.split(r'^\s+- criterion:', block('acceptance_results'), flags=re.M)[1:]:
            row = {'criterion': piece.splitlines()[0].strip()}
            for key, value in re.findall(r'^\s+([a-z_]+):\s*(.*)$', piece, re.M):
                row[key] = value.strip().strip('"\x27')
            results.append(row)
        record = {'implementation_commits': re.findall(r'\b[0-9a-f]{40}\b', block('implementation_commits')),
                  'acceptance_results': results,
                  'remaining_work': [] if block('remaining_work').strip() == '[]' else ['open'],
                  'external_blockers': [] if block('external_blockers').strip() == '[]' else ['open']}
    errors = []
    implementations = record.get('implementation_commits')
    if not isinstance(implementations, list) or not implementations or any(
            not isinstance(sha, str) or not re.fullmatch('[0-9a-f]{40}', sha) for sha in implementations):
        errors.append('Missing immutable implementation commits')
    results = record.get('acceptance_results')
    if not isinstance(results, list) or not results:
        errors.append('Missing acceptance evidence')
        results = []
    for row in results:
        if not isinstance(row, dict) or not row.get('criterion') or row.get('result') not in {
                'PASS', 'FAIL', 'NOT_RUN', 'BLOCKED_EXTERNAL'}:
            errors.append('Malformed acceptance criterion/status')
            continue
        if row['result'] == 'PASS' and (not row.get('command') or not isinstance(row.get('source_sha'), str)
                or not re.fullmatch('[0-9a-f]{40}', row['source_sha'])):
            errors.append('PASS lacks command or immutable source identity')
    if receipt_state(text) == 'DONE':
        if any(not isinstance(r, dict) or r.get('result') != 'PASS' for r in results):
            errors.append('DONE contradicts unpassed acceptance criteria')
        if record.get('remaining_work') != [] or record.get('external_blockers') != []:
            errors.append('DONE contradicts open work or external blockers')
    return errors


def index(root: Path, rows: list[dict], base: str) -> dict:
    deliveries = []
    requests = []
    for row in rows:
        ref = f'refs/remotes/origin/{row["branch"]}'
        exists = subprocess.run(['git', '-C', str(root), 'show-ref', '--verify', '--quiet', ref],
                                stdout=subprocess.DEVNULL).returncode == 0
        entry = {'agent': row['agent'], 'branch': row['branch'], 'source_sha': None,
                 'state': 'NOT_RUN', 'receipt_path': row['receipt'], 'ownership_errors': [], 'receipt_errors': []}
        if exists:
            sha = git(root, 'rev-parse', f'{ref}^{{commit}}')
            entry['source_sha'] = sha
            entry['ownership_errors'] = ownership(root, rows, row['agent'], base, sha)
            receipt = subprocess.run(['git', '-C', str(root), 'show', f'{sha}:{row["receipt"]}'],
                                     text=True, capture_output=True)
            if receipt.returncode == 0:
                entry['state'] = receipt_state(receipt.stdout)
                entry['receipt_errors'] = receipt_checks(receipt.stdout)
                if entry['state'] == 'DONE' and entry['receipt_errors']:
                    entry['state'] = 'INVALID_RECEIPT'
                entry['receipt_sha'] = git(root, 'rev-parse', f'{sha}:{row["receipt"]}')
            else:
                entry['state'] = 'MISSING_RECEIPT'
            paths = git(root, 'ls-tree', '-r', '--name-only', sha, 'docs/agents/requests').splitlines()
            for path in paths:
                if path.startswith(f'docs/agents/requests/{row["agent"]}-to-'):
                    requests.append({'from_agent': row['agent'], 'to_agent': Path(path).stem[-3:],
                                     'branch': row['branch'], 'source_sha': sha, 'path': path})
        deliveries.append(entry)
    return {'schema': 1, 'campaign_base': base, 'deliveries': deliveries, 'requests': requests,
            'campaign_complete': all(d['state'] == 'DONE' and not d['ownership_errors'] and not d['receipt_errors']
                                     for d in deliveries)}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    sub = parser.add_subparsers(dest='command', required=True)
    sub.add_parser('plan')
    own = sub.add_parser('ownership')
    own.add_argument('--agent', required=True)
    own.add_argument('--base', required=True)
    own.add_argument('--revision', default='HEAD')
    idx = sub.add_parser('index')
    idx.add_argument('--base', required=True)
    idx.add_argument('--output', type=Path, required=True)
    idx.add_argument('--require-complete', action='store_true')
    args = parser.parse_args(argv)
    try:
        rows = roster((args.root / PLAN).read_text())
        if args.command == 'plan':
            print(json.dumps({'schema': 1, 'agents': rows}, indent=2))
        elif args.command == 'ownership':
            errors = ownership(args.root, rows, args.agent.zfill(3), args.base, args.revision)
            print(json.dumps({'agent': args.agent.zfill(3), 'unowned_paths': errors}, indent=2))
            return 1 if errors else 0
        else:
            result = index(args.root, rows, args.base)
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(result, indent=2) + '\n')
            print(json.dumps({'agents': len(rows), 'complete': result['campaign_complete'],
                              'open_requests': len(result['requests'])}))
            return 1 if args.require_complete and not result['campaign_complete'] else 0
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        print(str(exc), file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
