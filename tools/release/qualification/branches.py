"""Read-only index of all100 existing remote agent refs and immutable receipts."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess


def index(root):
    lines = subprocess.check_output(['git', '-C', str(root), 'for-each-ref', '--format=%(refname) %(objectname)', 'refs/remotes/origin/agents/'], text=True).splitlines()
    found = {}
    for line in lines:
        ref, sha = line.split()
        match = re.fullmatch(r'refs/remotes/origin/agents/(\d{3})-[a-z0-9-]+', ref)
        if match and 1 <= int(match[1]) <= 100:
            found.setdefault(match[1], []).append((ref, sha))
    result = []
    for number in range(1, 101):
        agent = f'{number:03}'
        refs = found.get(agent, [])
        row = {'agent': agent, 'status': 'NOT_RUN', 'refs': []}
        for ref, sha in refs:
            receipt = subprocess.run(['git', '-C', str(root), 'show', sha + ':docs/agents/status/' + agent + '.md'], capture_output=True)
            row['refs'].append({'ref': ref, 'source_sha': sha, 'receipt_sha256': hashlib.sha256(receipt.stdout).hexdigest() if receipt.returncode == 0 else None})
        row['presence'] = 'PRESENT_UNVERIFIED' if len(refs) == 1 else 'AMBIGUOUS' if refs else 'MISSING'
        result.append(row)
    return {'schema': 1, 'agents': result, 'qualification': 'NOT_RUN', 'note': 'Remote presence and Markdown receipts are not integration/runtime evidence.'}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path.cwd())
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = index(args.root)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps({'agents': len(report['agents']), 'present': sum(r['presence'] == 'PRESENT_UNVERIFIED' for r in report['agents']), 'qualification': 'NOT_RUN'}))
