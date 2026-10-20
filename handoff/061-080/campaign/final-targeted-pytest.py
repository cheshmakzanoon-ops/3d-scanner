#!/usr/bin/env python3
"""Run changed080 runner tests and all seven retained source gates, without hiding failures."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

BASE = Path('/workspace/campaign-061-080')
SPEC = importlib.util.spec_from_file_location('final_android_recipe', BASE/'final-validation.py')
RECIPE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECIPE)
PRIOR = BASE/'final-validation/a18aa6eb6d8f-buildscript-relative'
LITERALS = dict(RECIPE.STALE_LITERAL_TESTS)
LITERALS['tests/test_project_contracts.py::test_m18_depth_resume_is_input_identified_atomic_and_native_scoped'] = (
    'M18 inline-domain spelling moved into the explicitly validated compatibility-domain constant; '
    'independent legacy-hash parity has a separate actual JVM test, while this Python literal still fails.')


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--validation-dir', type=Path, required=True)
    parser.add_argument('--allow-native-build-configuration-change', action='store_true',
                        help='Permit CMakeLists.txt-only change; require a separate fresh assertion-enabled native proof')
    args = parser.parse_args()
    directory = args.validation_dir.resolve()
    source = directory/'source'
    snapshot = json.loads((directory/'tracked-source.json').read_text())
    commit = snapshot['commit']
    if RECIPE.source_snapshot(source, commit) != snapshot:
        raise RuntimeError('Frozen source changed before affected Python checks')
    prior_snapshot = json.loads((PRIOR/'tracked-source.json').read_text())
    prior_result = json.loads((PRIOR/'pytest-result.json').read_text())
    native = lambda files: {path: entry['sha256'] for path, entry in files.items()
                            if path.startswith('reconstruction/') or path == 'CMakeLists.txt'}
    native_before = native(prior_snapshot['files'])
    native_now = native(snapshot['files'])
    changed_native = sorted(path for path in native_before.keys() | native_now.keys()
                            if native_before.get(path) != native_now.get(path))
    if changed_native and not (args.allow_native_build_configuration_change and changed_native == ['CMakeLists.txt']):
        raise RuntimeError('Native tracked inputs changed beyond an explicitly permitted build-only change; affected-only validation is insufficient')
    record = {'schema': 1, 'source_commit': commit, 'source_directory': str(source),
              'tracked_source_digest_sha256': snapshot['tracked_source_digest_sha256'],
              'started_at': RECIPE.utc(), 'state': 'WORKING',
              'scope': 'AFFECTED_ONLY: all Agent080 runner behavioral cases and all seven retained source-literal gates; full native/numerical pytest suite is not rerun.',
              'scope_is_full_pytest_suite': False,
              'native_input_equivalence': {
                  'prior_source_commit': prior_snapshot['commit'],
                  'prior_full_pytest_result': str(PRIOR/'pytest-result.json'),
                  'prior_full_pytest_result_sha256': digest(PRIOR/'pytest-result.json'),
                  'prior_full_pytest_totals': prior_result['totals'],
                  'prior_host_library': prior_result['host_library'],
                  'prior_17_ctest_log': prior_result['host_build_and_17_ctest_log'],
                  'current_native_input_sha256': native_now,
                  'entire_native_tracked_set_and_bytes_unchanged': not changed_native,
                  'reconstruction_implementation_and_test_bytes_unchanged': True,
                  'changed_native_build_paths': changed_native,
                  'separate_fresh_assertion_enabled_native_proof_required': bool(changed_native),
                  'historical_baseline_ctest_limitation': 'Old Release baseline CTest executables lacked -UNDEBUG; their assertion outcomes are not reused. Final candidate requires owner004 corrected assertion-enabled native proof.',
              },
              'known_retained_literal_gates': LITERALS,
              'limitations': 'No test is excluded/xfail-marked within the selected affected scope. Every selected gate is run; failing checks remain FAIL. Unchanged native bytes support reuse of historical numerical evidence, not a claim of newly executing the full suite or any device test.'}
    result = directory/'pytest-result.json'
    if result.exists():
        raise RuntimeError('Refusing to overwrite an existing Python result')
    RECIPE.write_json(result, record)
    environment = RECIPE.build_environment()
    environment['PYTHONDONTWRITEBYTECODE'] = '1'
    xml = directory/'targeted-pytest.xml'
    command = [str(RECIPE.PYTHON), '-m', 'pytest', 'tests/test_agent_080_ui_evidence.py',
               *LITERALS, '-q', '--junitxml='+str(xml), '--basetemp='+str(directory/'pytest-temp')]
    completed = RECIPE.run(command, source, directory/'targeted-pytest.log', environment, timeout=1200)
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
            nodeid = case.attrib.get('classname', '').replace('.', '/')+'.py::'+case.attrib.get('name', '')
            failed.append({'nodeid': nodeid, 'message': error.attrib.get('message'),
                           'known_retained_literal_gate': nodeid in LITERALS,
                           'recorded_mapping': LITERALS.get(nodeid)})
    record['failed_cases'] = failed
    record['passed'] = record['totals']['tests']-record['totals']['failures']-record['totals']['errors']-record['totals']['skipped']
    record['new_or_unclassified_failures'] = [case for case in failed if not case['known_retained_literal_gate']]
    if RECIPE.source_snapshot(source, commit) != snapshot:
        raise RuntimeError('Tracked source changed during affected Python checks')
    record['tracked_source_unchanged'] = True
    record['xml'] = {'path': str(xml), 'sha256': digest(xml)}
    record['tools'] = {str(path): digest(path) for path in (Path(__file__), BASE/'final-validation.py')}
    record['state'] = 'PASS' if completed['exit_code'] == 0 else 'FAIL'
    record['finished_at'] = RECIPE.utc()
    RECIPE.write_json(result, record)
    print(json.dumps({'result': str(result), 'scope': record['scope'], 'state': record['state'],
                      'totals': record['totals'], 'new_or_unclassified_failures': record['new_or_unclassified_failures']}, indent=2))
    return completed['exit_code']


if __name__ == '__main__':
    raise SystemExit(main())
