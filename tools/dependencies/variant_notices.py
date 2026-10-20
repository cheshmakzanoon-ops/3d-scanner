#!/usr/bin/env python3
"""Build complete debug attribution from actual resolved bytes and reviewed common texts."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
from inventory import extract_notices, verify_resolution


def generate(resolution, inventory, release_inventory, base, output):
    if inventory.get('variant') != 'debug':
        raise ValueError('Actual debug runtime inventory required')
    verify_resolution(resolution, inventory)
    common = json.loads((base / 'NOTICE_INDEX.json').read_text())
    if common.get('variant', 'release') != 'release' or common.get('unresolved'):
        raise ValueError('Reviewed complete release notices required')
    runtime = [m for m in inventory['modules'] if 'android-runtime' in m['scopes']]
    known = set(common['runtimeCoordinates'])
    reviewed = {m['coordinate']: m for m in release_inventory['modules'] if 'android-runtime' in m['scopes']}
    if known != set(reviewed):
        raise ValueError('Common notices do not match the reviewed release inventory')
    if base.resolve() == output.resolve():
        raise ValueError('Debug notices must not overwrite release notices')
    if not known <= {m['coordinate'] for m in runtime}:
        raise ValueError('Debug graph removed reviewed runtime coordinates; explicit review required')
    for module in runtime:
        if module['coordinate'] in reviewed:
            hashes = lambda entry: {(a['name'], a['sha256']) for a in entry['artifacts']}
            if hashes(module) != hashes(reviewed[module['coordinate']]):
                raise ValueError('Common runtime artifact identity changed: ' + module['coordinate'])
    output.mkdir(parents=True, exist_ok=True)
    for name, expected in common['files'].items():
        if Path(name).name != name or hashlib.sha256((base / name).read_bytes()).hexdigest() != expected:
            raise ValueError('Common notice integrity/path mismatch: ' + name)
        shutil.copy2(base / name, output / name)
    # Preserve the narrowly scoped attributes for verbatim upstream legal text.
    if (base / '.gitattributes').exists():
        shutil.copy2(base / '.gitattributes', output / '.gitattributes')
    extracted = extract_notices(resolution, output)
    additional = [m for m in runtime if m['coordinate'] not in known]
    for module in additional:
        if not module['licenses'] or not all('apache' in (l.get('name') or '').lower() and '2.0' in (l.get('name') or '') for l in module['licenses']):
            raise ValueError('Additional runtime license needs explicit full-text review: ' + module['coordinate'])
    if 'Apache-2.0.txt' not in common['files']:
        raise ValueError('Verified full Apache2.0 text required for additional modules')
    rows = ['Runtime dependency attribution, resolved debug graph. Metadata-only platforms are labelled.',
            'First-party source distribution license requires an explicit owner decision.',
            'Complete upstream license texts and preserved notices accompany this inventory.', '']
    for module in runtime:
        rows.append(module['coordinate'] + (' [metadata only]' if module['metadataOnly'] else ''))
        rows.extend('  ' + license['name'] + ' ' + license['url'] for license in module['licenses'])
    (output / 'DEPENDENCIES.txt').write_text('\n'.join(rows) + '\n')
    files = set(common['files']) | {r['path'] for r in extracted} | {'DEPENDENCIES.txt'}
    index = {**common, 'variant': 'debug', 'runtimeCoordinates': sorted(m['coordinate'] for m in runtime),
             'files': {name: hashlib.sha256((output / name).read_bytes()).hexdigest() for name in sorted(files)}}
    (output / 'NOTICE_INDEX.json').write_text(json.dumps(index, indent=2) + '\n')
    return {'schemaVersion': 1, 'variant': 'debug', 'runtimeModules': len(runtime), 'additionalRuntimeCoordinates': sorted(m['coordinate'] for m in additional),
            'commonNoticeIndexSha256': hashlib.sha256((base / 'NOTICE_INDEX.json').read_bytes()).hexdigest(),
            'noticeIndexSha256': hashlib.sha256((output / 'NOTICE_INDEX.json').read_bytes()).hexdigest(), 'noticeFiles': len(files),
            'additionalLicenseEvidence': [{'coordinate': m['coordinate'], 'licenses': m['licenses'], 'pomEvidence': m['licenseEvidence'],
                 'fullLicenseText': 'Apache-2.0.txt', 'preservedArtifactNotices': [r for r in extracted if r['coordinate'] == m['coordinate']]} for m in additional]}


def main():
    parser = argparse.ArgumentParser()
    for name in ('resolution', 'inventory', 'release-inventory', 'base-notices', 'output', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    result = generate(json.loads(args.resolution.read_text()), json.loads(args.inventory.read_text()), json.loads(args.release_inventory.read_text()), args.base_notices, args.output)
    args.report.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
