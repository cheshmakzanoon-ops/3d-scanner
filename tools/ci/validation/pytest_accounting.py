"""Strict native-only reconciliation of raw pytest XML with observed report events."""
from __future__ import annotations
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

SCHEMA = 'realityforge.native-pytest-accounting.v1'
VERSION = '9.0.3'
MAX_BYTES = 32 * 1024**2
MAX_XML_BYTES = 64 * 1024**2
MAX_EVENTS = 100000
MAX_ID_BYTES = 8192
PROFILES = {'gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize'}


class AccountingError(ValueError):
    pass


def bounded(path, limit):
    path = Path(path)
    if path.is_symlink() or not path.is_file() or not 0 < path.stat().st_size <= limit:
        raise AccountingError('Missing/invalid pytest accounting input')
    with path.open('rb') as stream:
        data = stream.read(limit + 1)
    if len(data) > limit:
        raise AccountingError('Pytest accounting input exceeds bound')
    return data


def identifier(value):
    if (not isinstance(value, str) or not value or len(value.encode('utf-8')) > MAX_ID_BYTES
            or any(ord(char) < 32 or ord(char) == 127 for char in value)):
        raise AccountingError('Invalid pytest report identity')
    return value


def identity(nodeid, prefix):
    import pytest
    from _pytest.junitxml import mangle_test_address, bin_xml_escape
    if pytest.__version__ != VERSION:
        raise AccountingError('Unsupported pytest accounting validator version')
    address = mangle_test_address(nodeid)
    classnames = address[:-1]
    if prefix:
        classnames.insert(0, prefix)
    return '.'.join(classnames), bin_xml_escape(address[-1])


def validate(xml_path, receipt_path, *, source_sha, profile, repository, run_id):
    """No console totals, no XML-count subtraction, no general JUnit relaxation."""
    if not re.fullmatch(r'[0-9a-f]{40}', source_sha) or profile not in PROFILES:
        raise AccountingError('Invalid expected native identity')
    if (repository is None) != (run_id is None):
        raise AccountingError('Incomplete expected hosted identity')
    if repository is not None and (not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository)
                                   or not re.fullmatch(r'[1-9][0-9]*:[1-9][0-9]*', run_id)):
        raise AccountingError('Invalid expected hosted identity')
    raw = bounded(xml_path, MAX_XML_BYTES)
    receipt = json.loads(bounded(receipt_path, MAX_BYTES))
    if not isinstance(receipt, dict) or set(receipt) != {'schema', 'source_sha', 'profile', 'repository', 'run_id', 'pytest_version', 'junit_prefix', 'junit_family', 'raw_xml_sha256', 'raw_xml_size', 'session_exitstatus', 'completed', 'events'}:
        raise AccountingError('Unknown/incomplete pytest accounting schema')
    if (receipt.get('schema') != SCHEMA or receipt.get('pytest_version') != VERSION
            or receipt.get('source_sha') != source_sha or receipt.get('profile') != profile
            or receipt.get('repository') != repository or receipt.get('run_id') != run_id
            or receipt.get('completed') is not True or type(receipt.get('session_exitstatus')) is not int
            or receipt['session_exitstatus'] != 0 or receipt.get('raw_xml_sha256') != hashlib.sha256(raw).hexdigest()
            or type(receipt.get('raw_xml_size')) is not int or receipt['raw_xml_size'] != len(raw)):
        raise AccountingError('Incomplete/foreign pytest accounting receipt or raw XML mismatch')
    prefix = receipt.get('junit_prefix')
    if prefix is not None:
        identifier(prefix)
    if receipt.get('junit_family') != 'xunit2':
        raise AccountingError('Unsupported pytest JUnit family')
    events = receipt.get('events')
    if not isinstance(events, list) or not 0 < len(events) <= MAX_EVENTS:
        raise AccountingError('Missing/oversized pytest event sequence')
    parents, identities = {}, set()
    current = None
    for sequence, event in enumerate(events):
        if (set(event) != {'sequence', 'nodeid', 'classname', 'name', 'when', 'outcome', 'subtest', 'ordinal'}
                or type(event.get('sequence')) is not int or event['sequence'] != sequence
                or type(event.get('subtest')) is not bool
                or event.get('when') not in ('setup', 'call', 'teardown')
                or event.get('outcome') not in ('passed', 'skipped')):
            raise AccountingError('Failed/unknown/reordered pytest report event')
        nodeid = identifier(event['nodeid'])
        key = (identifier(event['classname']), identifier(event['name']))
        if key != identity(nodeid, prefix):
            raise AccountingError('Pytest parent JUnit identity mismatch')
        phase = event['when']
        if not event['subtest'] and phase == 'setup':
            if nodeid in parents or key in identities or current is not None or event['ordinal'] is not None:
                raise AccountingError('Duplicate/interleaved pytest parent')
            current = nodeid
            parents[nodeid] = dict(key=key, setup=event['outcome'], call=None, closed=False, subtests=[])
            identities.add(key)
        else:
            if current != nodeid or nodeid not in parents:
                raise AccountingError('Pytest report has no active parent')
            parent = parents[nodeid]
            if event['subtest']:
                if (phase != 'call' or parent['setup'] != 'passed' or parent['call'] is not None
                        or type(event['ordinal']) is not int or event['ordinal'] != len(parent['subtests'])):
                    raise AccountingError('Invalid/reordered subtest event')
                parent['subtests'].append(event['outcome'])
            else:
                if event['ordinal'] is not None:
                    raise AccountingError('Ordinary report has subtest ordinal')
                if phase == 'call':
                    if parent['setup'] != 'passed' or parent['call'] is not None:
                        raise AccountingError('Invalid/duplicate parent call')
                    parent['call'] = event['outcome']
                elif phase == 'teardown':
                    if event['outcome'] != 'passed' or (parent['setup'] == 'passed' and parent['call'] is None):
                        raise AccountingError('Incomplete/failed parent teardown')
                    parent['closed'] = True
                    current = None
                else:
                    raise AccountingError('Unexpected report phase')
    if current is not None or not parents or not all(parent['closed'] for parent in parents.values()):
        raise AccountingError('Incomplete pytest parent sequence')
    root = ET.fromstring(raw)
    if root.tag != 'testsuites' or len(root) != 1 or root[0].tag != 'testsuite':
        raise AccountingError('Unsupported raw pytest suite shape')
    suite = root[0]
    if list(root.iter('failure')) or list(root.iter('error')):
        raise AccountingError('Raw pytest failure/error cannot be waived')
    for child in suite:
        if child.tag not in ('testcase', 'properties', 'system-out', 'system-err'):
            raise AccountingError('Unknown raw pytest suite child')
    for element in root.iter():
        if element.tag == 'testcase' and any(child.tag not in ('skipped', 'properties', 'system-out', 'system-err') for child in element):
            raise AccountingError('Unknown raw pytest testcase child')
        if element.tag == 'properties' and any(child.tag != 'property' or len(child) for child in element):
            raise AccountingError('Unknown raw pytest property shape')
        if element.tag in ('skipped', 'property', 'system-out', 'system-err') and len(element):
            raise AccountingError('Nested raw pytest outcome/data shape')
    cases = list(suite.findall('testcase'))
    if len(cases) != len(parents) or len(list(root.iter('testcase'))) != len(cases):
        raise AccountingError('Missing/extra raw pytest parent cases')
    by_key = {parent['key']: parent for parent in parents.values()}
    observed = set()
    total_skips, total_subtests, clean_parents = 0, 0, 0
    for case in cases:
        key = (case.get('classname'), case.get('name'))
        if key not in by_key or key in observed:
            raise AccountingError('Missing/duplicate raw pytest parent identity')
        observed.add(key)
        parent = by_key[key]
        parent_skip = parent['setup'] == 'skipped' or parent['call'] == 'skipped'
        expected_skips = int(parent_skip) + parent['subtests'].count('skipped')
        if case.findall('failure') or case.findall('error') or len(case.findall('skipped')) != expected_skips:
            raise AccountingError('Raw pytest failure/skip outcomes disagree with events')
        clean_parents += int(expected_skips == 0)
        total_skips += expected_skips
        total_subtests += len(parent['subtests'])
    tests = len(parents) + total_subtests
    if (int(suite.get('tests', '-1')) != tests or int(suite.get('skipped', '-1')) != total_skips
            or int(suite.get('failures', '-1')) != 0 or int(suite.get('errors', '-1')) != 0):
        raise AccountingError('Raw pytest declarations disagree with observed events')
    if tests <= total_skips or clean_parents == 0:
        raise AccountingError('No entirely unskipped successful native pytest parent')
    return dict(tests=tests, passed=tests - total_skips, skipped=total_skips, failed=0)
