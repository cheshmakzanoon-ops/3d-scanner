#!/usr/bin/env python3
"""Read-only delivery audit; only the JSON report outside checkouts is written."""
from __future__ import annotations

import collections
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys

CAMPAIGN = Path('/workspace/campaign-061-080')
REPOSITORY = Path('/workspace/3d-scanner')
OUTPUT = CAMPAIGN / 'final-branch-audit.json'
os.environ['GIT_OPTIONAL_LOCKS'] = '0'
sys.dont_write_bytecode = True


def utc():
    return datetime.now(timezone.utc).isoformat()


def digest(data):
    return hashlib.sha256(data).hexdigest()


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


CANONICAL_100 = CAMPAIGN / 'dependencies/100/campaign.py'
CANONICAL_094 = CAMPAIGN / 'dependencies/094/source_manifest.py'
campaign = module('canonical_campaign', CANONICAL_100)
manifest = module('canonical_manifest', CANONICAL_094)


def git(root, *args, required=True):
    result = subprocess.run(['git', '-C', str(root), *args], capture_output=True,
                            check=False, timeout=60)
    if required and result.returncode:
        raise RuntimeError(result.stderr.decode('utf-8', 'replace').strip())
    return result


def git_text(root, *args):
    return git(root, *args).stdout.decode('utf-8').strip()


def remote_heads():
    output = git_text(REPOSITORY, 'ls-remote', '--heads', 'origin')
    return {ref.removeprefix('refs/heads/'): sha
            for sha, ref in (line.split() for line in output.splitlines())}


def status(root):
    raw = git(root, 'status', '--porcelain=v1', '-z', '--untracked-files=all').stdout
    records = raw.split(b'\0')
    paths = []
    i = 0
    while i < len(records):
        record = records[i]
        i += 1
        if not record:
            continue
        code, path = record[:2].decode(), record[3:].decode('utf-8')
        row = {'status': code, 'path': path}
        if 'R' in code or 'C' in code:
            row['original_path'] = records[i].decode('utf-8')
            i += 1
        paths.append(row)
    return {'clean': not paths, 'paths': paths, 'porcelain_sha256': digest(raw)}


def snapshot(root):
    return {'head': git_text(root, 'rev-parse', 'HEAD^{commit}'),
            'branch': git_text(root, 'symbolic-ref', '--short', 'HEAD'),
            'index_entries_sha256': digest(git(root, 'ls-files', '--stage', '-z').stdout),
            'worktree': status(root)}


def verify_manifest(root, source):
    try:
        return manifest.verify(root, source)
    except Exception as exc:
        return {'source': source, 'status': 'FAIL', 'error': str(exc)}


def committed_file(root, revision, path):
    result = git(root, 'show', f'{revision}:{path}', required=False)
    if result.returncode:
        return {'path': path, 'exists': False}
    return {'path': path, 'exists': True,
            'blob_oid': git_text(root, 'rev-parse', f'{revision}:{path}'),
            'sha256': digest(result.stdout),
            'text': result.stdout.decode('utf-8')}


def receipt_fields(text):
    # Read claims, never infer that an unrecorded gate passed.
    structured = None
    parser = 'canonical-state-only'
    try:
        structured = json.loads(text)
        parser = 'json'
    except ValueError:
        fenced = re.search(r'^```json\s*\n(.*?)^```\s*$', text, re.M | re.S)
        if fenced:
            try:
                structured = json.loads(fenced.group(1))
                parser = 'fenced-json-claims-only-canonical-state-unchanged'
            except ValueError:
                pass
        if structured is None:
            try:
                import yaml
                source = text
                if text.startswith('---\n'):
                    source = text.split('---\n', 2)[1]
                structured = yaml.safe_load(source)
                parser = 'yaml-safe-load'
            except Exception:
                pass
    state = campaign.receipt_state(text)
    result = {'state': state, 'parser': parser, 'claimed_complete': state == 'DONE'}
    if isinstance(structured, dict):
        result['document_claimed_state'] = structured.get('state')
        for key in ('acceptance_results', 'remaining_work', 'external_blockers',
                    'next_action', 'source_candidate', 'source_base', 'campaign_base',
                    'candidate_commit', 'native_candidate_commit', 'production_implementation',
                    'implementation_commits', 'integration_requests', 'interfaces_published'):
            if key in structured:
                result[key] = structured[key]
    else:
        result['recorded_result_tokens'] = dict(collections.Counter(re.findall(
            r'\b(PASS|FAIL|NOT_RUN|BLOCKED_EXTERNAL|INTEGRATED_PENDING_VERIFICATION)\b', text)))
        result['narrative_receipt_requires_reading'] = True
    return result


def plan_section(plan, agent):
    block = re.split(rf'^## Agent {agent}\s*$', plan, maxsplit=1, flags=re.M)[1]
    block = re.split(r'^## Agent \d{3}\s*$', block, maxsplit=1, flags=re.M)[0]
    result = {}
    for field in ('Mission', 'Success criteria', 'Required verification'):
        marker = f'**{field}:**'
        section = block.split(marker, 1)[1]
        section = section.split('\n**', 1)[0].strip()
        result[field.lower().replace(' ', '_')] = section
    return result


def audit_row(agent, entry, plan, rows, remote_start, combined_inputs):
    root = Path(entry['path'])
    row = {'agent': agent, **entry, 'started_at': utc(), 'failures': [], 'pending': []}
    try:
        before = snapshot(root)
        revision = before['head']
        row['initial_checkout'] = before
        row['remote_head_start'] = remote_start.get(entry['branch'])
        row['ownership_unowned_paths'] = campaign.ownership(root, rows, agent, entry['base'], revision)
        row['ownership_status'] = 'FAIL' if row['ownership_unowned_paths'] else 'PASS'
        row['manifest_commit'] = verify_manifest(root, revision)
        row['manifest_index'] = verify_manifest(root, 'index')
        row['manifest_worktree'] = verify_manifest(root, 'worktree')
        contract = committed_file(root, revision, f'docs/agents/contracts/{agent}.md')
        contract.pop('text', None)
        row['contract'] = contract
        receipt = committed_file(root, revision, f'docs/agents/status/{agent}.md')
        if receipt['exists']:
            row['contract_status'] = receipt_fields(receipt.pop('text'))
        else:
            row['contract_status'] = {'state': 'MISSING_RECEIPT', 'claimed_complete': False}
        row['receipt'] = receipt
        row['requirements_from_plan'] = plan_section(plan, agent)
        paths = git_text(root, 'ls-tree', '-r', '--name-only', revision, 'docs/agents/requests').splitlines()
        row['outgoing_request_paths'] = [p for p in paths if p.startswith(f'docs/agents/requests/{agent}-to-')]
        if agent in combined_inputs:
            supplied = combined_inputs[agent]
            changed = git(root, 'diff', '--no-renames', '--name-only', '-z', supplied, revision).stdout
            changed = [p.decode('utf-8') for p in changed.split(b'\0') if p]
            implementation = [p for p in changed if p != 'SOURCE_MANIFEST.sha256' and not p.startswith('docs/')]
            row['frozen_combined_input'] = {
                'source_sha': supplied, 'paths_changed_since_input': changed,
                'implementation_paths_changed_since_input': implementation,
                'implementation_matches_frozen_input': not implementation,
            }
            if implementation:
                row['pending'].append('IMPLEMENTATION_DIFFERS_FROM_FROZEN_COMBINED_INPUT')
        row['final_checkout'] = snapshot(root)
        row['checkout_changed_during_audit'] = before != row['final_checkout']
        if before['branch'] != entry['branch']:
            row['failures'].append('WRONG_CHECKOUT_BRANCH')
        if row['ownership_status'] != 'PASS':
            row['failures'].append('COMMITTED_OWNERSHIP_VIOLATION')
        if row['manifest_commit']['status'] != 'PASS':
            row['failures'].append('COMMITTED_MANIFEST_INVALID')
        if not contract['exists']:
            row['failures'].append('MISSING_COMMITTED_CONTRACT')
        if row['contract_status']['state'] in ('MISSING_RECEIPT', 'INVALID_RECEIPT'):
            row['failures'].append(row['contract_status']['state'])
        if row['checkout_changed_during_audit']:
            row['pending'].append('CHECKOUT_CHANGED_DURING_AUDIT')
        if not row['final_checkout']['worktree']['clean']:
            row['pending'].append('UNCOMMITTED_WORKTREE_CHANGES')
        for key in ('manifest_index', 'manifest_worktree'):
            if row[key]['status'] != 'PASS':
                row['pending'].append(key.upper() + '_NOT_VALID_AT_SNAPSHOT')
    except Exception as exc:
        row['failures'].append('AUDIT_ERROR')
        row['error'] = str(exc)
    row['finished_at'] = utc()
    print(json.dumps({'agent': agent, 'failures': row['failures'], 'pending': row['pending']}), flush=True)
    return row


def main():
    started = utc()
    roster = json.loads((CAMPAIGN / 'roster.json').read_text())
    if set(roster) != {f'{number:03d}' for number in range(61, 81)}:
        raise ValueError('Campaign roster is not exactly agents 061–080')
    bases = {entry['base'] for entry in roster.values()}
    if len(bases) != 1:
        raise ValueError('Campaign bases differ')
    base = next(iter(bases))
    plan_bytes = git(REPOSITORY, 'show', f'{base}:{campaign.PLAN}').stdout
    plan = plan_bytes.decode('utf-8')
    rows = campaign.roster(plan)
    for agent, entry in roster.items():
        if next(r for r in rows if r['agent'] == agent)['branch'] != entry['branch']:
            raise ValueError(f'Roster and canonical plan disagree: {agent}')
    remote_start = remote_heads()
    combined_path = CAMPAIGN / 'combined-final-inputs.json'
    combined_bytes = combined_path.read_bytes() if combined_path.is_file() else b'[]'
    combined_inputs = {r['agent']: r['source_sha'] for r in json.loads(combined_bytes)}
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(audit_row, agent, entry, plan, rows, remote_start, combined_inputs)
                   for agent, entry in sorted(roster.items())]
        deliveries = [future.result() for future in futures]
    remote_end = remote_heads()
    for row in deliveries:
        row['remote_head_end'] = remote_end.get(row['branch'])
        row['remote_changed_during_audit'] = row['remote_head_start'] != row['remote_head_end']
        final = row.get('final_checkout', {})
        row['local_remote_head_equal'] = bool(final.get('head')) and final.get('head') == row['remote_head_end']
        if row['remote_changed_during_audit']:
            row['pending'].append('REMOTE_HEAD_CHANGED_DURING_AUDIT')
        if not row['remote_head_end']:
            row['pending'].append('REMOTE_BRANCH_MISSING')
        elif not row['local_remote_head_equal']:
            row['pending'].append('LOCAL_HEAD_NOT_EQUAL_TO_REMOTE')
        row['delivery_status'] = 'FAIL' if row['failures'] else 'PENDING' if row['pending'] else 'PUBLISHED_CLEAN'
    summary = {
        'branches': len(deliveries),
        'delivery_status_counts': dict(collections.Counter(r['delivery_status'] for r in deliveries)),
        'contract_state_counts': dict(collections.Counter(r.get('contract_status', {}).get('state', 'AUDIT_ERROR') for r in deliveries)),
        'local_remote_equal': sum(r['local_remote_head_equal'] for r in deliveries),
        'committed_manifest_pass': sum(r.get('manifest_commit', {}).get('status') == 'PASS' for r in deliveries),
        'ownership_pass': sum(r.get('ownership_status') == 'PASS' for r in deliveries),
        'clean_worktrees': sum(r.get('final_checkout', {}).get('worktree', {}).get('clean', False) for r in deliveries),
        'committed_contracts_present': sum(r.get('contract', {}).get('exists', False) for r in deliveries),
        'implementation_matches_frozen_input': sum(r.get('frozen_combined_input', {}).get('implementation_matches_frozen_input', False) for r in deliveries),
        'failures': {r['agent']: r['failures'] for r in deliveries if r['failures']},
        'pending': {r['agent']: r['pending'] for r in deliveries if r['pending']},
        'contract_completion_claimed': all(r.get('contract_status', {}).get('state') == 'DONE' for r in deliveries),
    }
    result = {
        'schema': 1, 'started_at': started, 'finished_at': utc(),
        'campaign_base': base, 'roster_path': str(CAMPAIGN / 'roster.json'),
        'roster_sha256': digest((CAMPAIGN / 'roster.json').read_bytes()),
        'canonical_plan_sha256': digest(plan_bytes),
        'frozen_combined_inputs_sha256': digest(combined_bytes),
        'remote_main_start': remote_start.get('main'),
        'remote_main_end': remote_end.get('main'),
        'remote_main_matches_campaign_base': remote_end.get('main') == base,
        'audit_tools': [{'path': str(p), 'sha256': digest(p.read_bytes())}
                        for p in (CANONICAL_100, CANONICAL_094)],
        'scope': 'Read-only delivery and recorded contract status audit; no builds, tests, device runs, merges or acceptance claims are inferred.',
        'claim_limit': 'Receipt states and acceptance results are author claims from exact audited commit blobs. READY_FOR_INTEGRATION and WORKING leave contract requirements open.',
        'summary': summary, 'deliveries': deliveries,
    }
    temporary = OUTPUT.with_suffix('.json.partial')
    temporary.write_text(json.dumps(result, indent=2, default=str) + '\n')
    temporary.replace(OUTPUT)
    markdown = [f'Audit snapshot: {result["finished_at"]}', '', result['scope'], '',
                '| Agent | Delivery | Receipt state | Committed HEAD | Remote equal | Ownership | Manifest |',
                '|---|---|---|---|---|---|---|']
    for row in deliveries:
        markdown.append('| ' + ' | '.join(str(value) for value in (
            row['agent'], row['delivery_status'], row.get('contract_status', {}).get('state'),
            row.get('initial_checkout', {}).get('head'), row['local_remote_head_equal'],
            row.get('ownership_status'), row.get('manifest_commit', {}).get('status'))) + ' |')
    markdown.extend(['', 'Failures:', ''])
    markdown.extend(f'- {agent}: {", ".join(reasons)}' for agent, reasons in summary['failures'].items())
    if not summary['failures']:
        markdown.append('None.')
    markdown.extend(['', 'Pending snapshot items:', ''])
    markdown.extend(f'- {agent}: {", ".join(reasons)}' for agent, reasons in summary['pending'].items())
    if not summary['pending']:
        markdown.append('None.')
    markdown.extend(['', result['claim_limit'], ''])
    OUTPUT.with_suffix('.md').write_text('\n'.join(markdown))
    print(json.dumps({'output': str(OUTPUT), 'summary': summary}, indent=2), flush=True)


if __name__ == '__main__':
    main()
