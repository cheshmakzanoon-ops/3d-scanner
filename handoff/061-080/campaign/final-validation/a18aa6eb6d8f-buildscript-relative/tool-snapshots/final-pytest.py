#!/usr/bin/env python3
"""Run all pytest checks on the same frozen Android source; retain every failure."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

BASE = Path('/workspace/campaign-061-080')
SPEC = importlib.util.spec_from_file_location('final_android_recipe', BASE/'final-validation.py')
RECIPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECIPE)


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-dir', type=Path, required=True)
    args = parser.parse_args()
    directory = args.validation_dir.resolve()
    source = directory/'source'
    snapshot = json.loads((directory/'tracked-source.json').read_text())
    commit = snapshot['commit']
    if RECIPE.source_snapshot(source, commit) != snapshot:
        raise RuntimeError('Frozen Android source changed before host regression run')
    # Root already built all seven new modules plus baseline in this real shared
    # library and passed17 CTests. Prove current native inputs match the frozen
    # worktree, copy the exact .so, and record its independent artifact digest.
    native_files = {}
    for relative, entry in snapshot['files'].items():
        if relative.startswith('reconstruction/') or relative == 'CMakeLists.txt':
            current = BASE/'combined'/relative
            if digest(current) != entry['sha256']:
                raise RuntimeError('Root host build inputs no longer match frozen source: '+relative)
            native_files[relative] = entry['sha256']
    original_library = BASE/'combined/build/host/librealityforge.so'
    library = directory/'host-library/librealityforge.so'
    library.parent.mkdir(exist_ok=False)
    shutil.copyfile(original_library, library)
    symbols = subprocess.check_output(['nm', '-D', '--demangle', str(library)], text=True)
    if any(symbol not in symbols for symbol in RECIPE.EXPECTED_SYMBOLS):
        raise RuntimeError('Actual host library lacks the seven module entry-point families')
    root_log = BASE/'combined-final-native.log'
    root_ctest_log = root_log.read_text()
    if '100% tests passed, 0 tests failed out of 17' not in root_ctest_log:
        raise RuntimeError('Root combined17 CTest log is not a recorded pass')
    record = {'schema': 1, 'source_commit': commit, 'source_directory': str(source),
              'tracked_source_digest_sha256': snapshot['tracked_source_digest_sha256'],
              'host_library': {'original_path': str(original_library), 'copied_path': str(library),
                               'sha256': digest(library), 'bytes': library.stat().st_size},
              'native_input_sha256': native_files,
              'host_build_and_17_ctest_log': {'path': str(root_log), 'sha256': digest(root_log)},
              'started_at': RECIPE.utc(), 'state': 'WORKING',
              'known_prior_literal_failures': RECIPE.STALE_LITERAL_TESTS,
              'limitations': 'No failures are deselected, xfailed, hidden or converted to success. Prior stale-literal mappings are classification only; application runtime coverage is independent.'}
    result = directory/'pytest-result.json'
    RECIPE.write_json(result, record)
    environment = RECIPE.build_environment()
    environment['RF_NATIVE_LIBRARY'] = str(library)
    environment['PYTHONDONTWRITEBYTECODE'] = '1'
    xml = directory/'full-pytest.xml'
    command = [str(RECIPE.PYTHON), '-m', 'pytest', 'tests', '-q', '--junitxml='+str(xml),
               '--basetemp='+str(directory/'pytest-temp')]
    completed = RECIPE.run(command, source, directory/'full-pytest.log', environment, timeout=2400)
    record['command'] = completed
    root = ET.parse(xml).getroot()
    suites = root.findall('testsuite') if root.tag == 'testsuites' else [root]
    record['totals'] = {key: sum(int(s.attrib.get(key, 0)) for s in suites)
                        for key in ('tests', 'failures', 'errors', 'skipped')}
    failed = []
    for suite in suites:
        for case in suite.findall('testcase'):
            error = case.find('failure')
            if error is None:
                error = case.find('error')
            if error is None:
                continue
            module = case.attrib.get('classname', '').replace('.', '/')+'.py'
            nodeid = module+'::'+case.attrib.get('name', '')
            failed.append({'nodeid': nodeid, 'message': error.attrib.get('message'),
                           'known_prior_literal_failure': nodeid in RECIPE.STALE_LITERAL_TESTS,
                           'recorded_mapping': RECIPE.STALE_LITERAL_TESTS.get(nodeid)})
    record['failed_cases'] = failed
    record['passed'] = record['totals']['tests']-record['totals']['failures']-record['totals']['errors']-record['totals']['skipped']
    record['new_or_unclassified_failures'] = [case for case in failed if not case['known_prior_literal_failure']]
    if RECIPE.source_snapshot(source, commit) != snapshot:
        raise RuntimeError('Tracked source changed during pytest run')
    if digest(library) != record['host_library']['sha256']:
        raise RuntimeError('Copied host library bytes changed during pytest run')
    record['tracked_source_and_host_library_unchanged'] = True
    record['xml'] = {'path': str(xml), 'sha256': digest(xml)}
    record['state'] = 'PASS' if completed['exit_code'] == 0 else 'FAIL'
    record['finished_at'] = RECIPE.utc()
    RECIPE.write_json(result, record)
    print(json.dumps({'result': str(result), 'state': record['state'], 'totals': record['totals'],
                      'new_or_unclassified_failures': record['new_or_unclassified_failures']}, indent=2))
    return completed['exit_code']


if __name__ == '__main__':
    raise SystemExit(main())
