#!/usr/bin/env python3
"""Inspect actual packaged privacy policy; never infer runtime evidence from source."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as E

DOMAINS = {'root', 'file', 'database', 'sharedpref', 'external', 'device_root',
           'device_file', 'device_database', 'device_sharedpref'}
FORBIDDEN_PERMISSIONS = {'android.permission.INTERNET', 'android.permission.ACCESS_NETWORK_STATE',
                         'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE',
                         'android.permission.READ_MEDIA_IMAGES', 'android.permission.READ_MEDIA_VIDEO'}


def aapt_tree(text: str) -> E.Element:
    """Decode aapt2's bounded XML-tree report, retaining element/attribute scope."""
    if len(text) > 2_000_000:
        raise ValueError('XML report too large')
    stack = []
    root = None
    for line in text.splitlines():
        element = re.fullmatch(r'(\s*)E: ([\w-]+) \(line=\d+\)', line)
        if element:
            depth = len(element.group(1))
            while stack and stack[-1][0] >= depth:
                stack.pop()
            node = E.Element(element.group(2))
            if stack:
                stack[-1][1].append(node)
            elif root is None:
                root = node
            else:
                raise ValueError('Multiple XML roots')
            stack.append((depth, node))
            continue
        attr = re.fullmatch(r'\s*A: ([^=]+)=(.*)', line)
        if attr:
            if not stack:
                raise ValueError('Attribute outside element')
            name = re.sub(r'\(0x[0-9a-fA-F]+\)$', '', attr.group(1)).rsplit(':', 1)[-1]
            value = attr.group(2).split(' (Raw:', 1)[0]
            if value.startswith('"'):
                try:
                    value = json.loads(value)
                except json.JSONDecodeError as exc:
                    raise ValueError('Malformed quoted XML value') from exc
            if name in stack[-1][1].attrib:
                raise ValueError('Duplicate XML attribute')
            stack[-1][1].set(name, value)
        elif line.strip() and not line.lstrip().startswith('N:'):
            raise ValueError('Unrecognized XML report line')
    if root is None:
        raise ValueError('Missing XML root')
    return root


def check_rules(root: E.Element) -> list[str]:
    errors = []
    if root.tag != 'data-extraction-rules':
        return ['Wrong extraction rules root']
    if sorted(x.tag for x in root) != ['cloud-backup', 'device-transfer']:
        errors.append('Both backup and transfer policies must occur exactly once')
    for tag in ('cloud-backup', 'device-transfer'):
        section = root.find(tag)
        if section is None:
            continue
        excluded = {x.get('domain') for x in section if x.tag == 'exclude' and x.get('path') == '.'}
        if DOMAINS - excluded:
            errors.append(f'{tag}: missing full-domain exclusions: {sorted(DOMAINS-excluded)}')
        if any(x.tag != 'exclude' or x.get('path') != '.' or x.get('domain') not in DOMAINS for x in section):
            errors.append(f'{tag}: unexpected include or partial-domain rule')
    return errors


def check_manifest(root: E.Element) -> list[str]:
    errors = []
    if root.tag != 'manifest' or root.get('package') != 'com.realityforge.scanner':
        errors.append('Unexpected candidate package identity')
    permissions = {x.get('name') for x in root if x.tag.startswith('uses-permission')}
    if permissions & FORBIDDEN_PERMISSIONS:
        errors.append('Forbidden network or broad library permission')
    apps = root.findall('application')
    if len(apps) != 1:
        return errors + ['Application must occur exactly once']
    app = apps[0]
    for flag in ('allowBackup', 'fullBackupContent', 'usesCleartextTraffic'):
        if app.get(flag) != 'false':
            errors.append(f'Application {flag} must be explicitly false')
    if not app.get('dataExtractionRules', '').startswith('@'):
        errors.append('Missing packaged extraction policy reference')
    camera = [x for x in root.findall('uses-feature') if x.get('name', '').startswith('android.hardware.camera')]
    if not camera or any(x.get('required') != 'false' for x in camera):
        errors.append('Camera hardware must remain optional')
    return errors


def package_report(apk: Path, sdk: Path, source_sha: str) -> dict:
    if not re.fullmatch('[0-9a-f]{40}', source_sha):
        raise ValueError('Exact source SHA required')
    aapt = sdk / 'build-tools/36.0.0/aapt2'
    def dump(name):
        return aapt_tree(subprocess.check_output([str(aapt), 'dump', 'xmltree', str(apk), '--file', name],
                                                text=True, timeout=45))
    manifest, rules = dump('AndroidManifest.xml'), dump('res/xml/data_extraction_rules.xml')
    errors = check_manifest(manifest) + check_rules(rules)
    return {'schema': 1, 'agent': '089', 'source_sha': source_sha,
            'artifact_sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
            'gate': 'packaged_privacy_policy', 'status': 'FAIL' if errors else 'PASS',
            'errors': errors, 'runtime_offline_journey': 'NOT_RUN',
            'physical_backup_transfer': 'NOT_RUN'}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--source-sha', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = package_report(args.apk, args.sdk, args.source_sha)
    except (ValueError, OSError, subprocess.SubprocessError) as exc:
        result = {'schema': 1, 'agent': '089', 'status': 'FAIL', 'error': str(exc),
                  'runtime_offline_journey': 'NOT_RUN'}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
