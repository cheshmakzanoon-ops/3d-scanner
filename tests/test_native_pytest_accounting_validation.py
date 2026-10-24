"""Real pytest events reconcile native-only counts; raw general JUnit stays strict."""
import copy
import hashlib
import json
from pathlib import Path
import shutil

import pytest
import test_native_pytest_accounting_plugin as observer
import test_ci_evidence_validation as fixtures
from tools.ci.validation import evidence as ci
from tools.ci.validation import native_matrix as matrix
from tools.ci.validation.pytest_accounting import validate, AccountingError

SOURCE = '''import pytest, unittest
@pytest.mark.parametrize("value", ["a::b[x]", "second"])
def test_parts(subtests, value):
    with subtests.test(part=1): assert True
    with subtests.test(part=2): pytest.skip("optional subtest")
@pytest.mark.skip(reason="ordinary skip")
def test_skip(): pass
class Cases(unittest.TestCase):
    def test_good(self):
        with self.subTest(part=1): self.assertTrue(True)
'''


def check(folder, **overrides):
    identity = dict(source_sha='a' * 40, profile='gcc-release', repository=None, run_id=None)
    identity.update(overrides)
    return validate(folder / 'raw.xml', folder / 'receipt.json', **identity)


def test_actual_pass_skip_parameterized_subtests_have_explicit_counts(tmp_path):
    result, data = observer.execute(tmp_path, SOURCE, extra=('--junit-prefix=pref',))
    assert result.returncode == 0, result.stdout
    assert check(tmp_path) == dict(tests=9, passed=6, skipped=3, failed=0)
    with pytest.raises(ci.EvidenceError):
        ci.junit(tmp_path / 'raw.xml')


@pytest.mark.parametrize('source', [
    'def test_failure(subtests):\n    with subtests.test(): assert False\n',
    'import unittest\nclass C(unittest.TestCase):\n    def test_bad(self):\n        with self.subTest(): self.assertTrue(False)\n',
    'import pytest\n@pytest.fixture\ndef f():\n    assert False\ndef test_bad(f): pass\n',
    'import pytest\n@pytest.fixture\ndef f():\n    yield\n    assert False\ndef test_bad(f): pass\n',
])
def test_actual_failed_subtests_and_setup_teardown_never_accept(tmp_path, source):
    result, data = observer.execute(tmp_path, source)
    assert result.returncode != 0 and data is not None
    with pytest.raises(AccountingError):
        check(tmp_path)
    # Even falsifying the session summary cannot hide actual report failures.
    data['session_exitstatus'] = 0
    fixtures.dump(tmp_path / 'receipt.json', data)
    with pytest.raises(AccountingError):
        check(tmp_path)


@pytest.fixture
def successful(tmp_path):
    result, data = observer.execute(tmp_path, SOURCE)
    assert result.returncode == 0, result.stdout
    return tmp_path, data


@pytest.mark.parametrize('mutation', ['source', 'profile', 'repository', 'run', 'version', 'missing', 'incomplete',
    'truncated', 'xml-hash', 'xml-size', 'sequence', 'dropped-event', 'duplicate-event', 'ordinal',
    'classname', 'nodeid', 'unknown-event', 'failure', 'skip-as-pass', 'missing-parent', 'reordered'])
def test_receipt_mutations_fail_closed(successful, mutation):
    folder, data = successful
    if mutation == 'source': data['source_sha'] = 'b' * 40
    elif mutation == 'profile': data['profile'] = 'clang-release'
    elif mutation == 'repository': data['repository'] = 'other/repo'
    elif mutation == 'run': data['run_id'] = '1:1'
    elif mutation == 'version': data['pytest_version'] = '9.0.4'
    elif mutation == 'incomplete': data['completed'] = False
    elif mutation == 'xml-hash': data['raw_xml_sha256'] = '0' * 64
    elif mutation == 'xml-size': data['raw_xml_size'] += 1
    elif mutation == 'sequence': data['events'][0]['sequence'] = 1
    elif mutation == 'dropped-event': data['events'].pop(1)
    elif mutation == 'duplicate-event': data['events'].insert(1, copy.deepcopy(data['events'][1]))
    elif mutation == 'ordinal': next(e for e in data['events'] if e['subtest'])['ordinal'] = 99
    elif mutation == 'classname': data['events'][0]['classname'] = 'different'
    elif mutation == 'nodeid': data['events'][0]['nodeid'] += '_other'
    elif mutation == 'unknown-event': data['events'][0]['unrecognized'] = True
    elif mutation == 'failure': data['events'][0]['outcome'] = 'failed'
    elif mutation == 'skip-as-pass': next(e for e in data['events'] if e['subtest'] and e['outcome'] == 'skipped')['outcome'] = 'passed'
    elif mutation == 'missing-parent': data['events'] = data['events'][1:]
    elif mutation == 'reordered': data['events'][0], data['events'][1] = data['events'][1], data['events'][0]
    fixtures.dump(folder / 'receipt.json', data)
    if mutation == 'missing': (folder / 'receipt.json').unlink()
    elif mutation == 'truncated': (folder / 'receipt.json').write_text('{')
    with pytest.raises((AccountingError, ValueError)):
        check(folder)


@pytest.mark.parametrize('mutation', ['tests', 'skips', 'parent', 'duplicate-case', 'failure'])
def test_rehashed_raw_xml_must_still_match_observed_events(successful, mutation):
    import xml.etree.ElementTree as ET
    folder, data = successful
    root = ET.parse(folder / 'raw.xml').getroot()
    suite = root[0]
    if mutation == 'tests': suite.set('tests', str(int(suite.get('tests')) + 1))
    elif mutation == 'skips': suite.set('skipped', '0')
    elif mutation == 'parent': suite[0].set('name', 'different')
    elif mutation == 'duplicate-case': suite.append(copy.deepcopy(suite[0]))
    else: ET.SubElement(suite[0], 'failure').text = 'hidden failure'
    raw = ET.tostring(root)
    (folder / 'raw.xml').write_bytes(raw)
    data.update(raw_xml_sha256=hashlib.sha256(raw).hexdigest(), raw_xml_size=len(raw))
    fixtures.dump(folder / 'receipt.json', data)
    with pytest.raises(AccountingError):
        check(folder)


def test_local_null_identity_never_matches_hosted_expected(successful):
    folder, _ = successful
    with pytest.raises(AccountingError):
        check(folder, repository='owner/repo', run_id='123:2')


@pytest.mark.parametrize('mutation', ['unknown-field', 'nested-error', 'unknown-tag'])
def test_unknown_receipt_or_xml_shapes_rejected(successful, mutation):
    import xml.etree.ElementTree as ET
    folder, data = successful
    if mutation == 'unknown-field':
        data['unknown'] = True
    else:
        root = ET.parse(folder / 'raw.xml').getroot()
        wrapper = ET.SubElement(root[0][0], 'unknown')
        if mutation == 'nested-error': ET.SubElement(wrapper, 'error').text = 'hidden'
        raw = ET.tostring(root)
        (folder / 'raw.xml').write_bytes(raw)
        data.update(raw_xml_sha256=hashlib.sha256(raw).hexdigest(), raw_xml_size=len(raw))
    fixtures.dump(folder / 'receipt.json', data)
    with pytest.raises(AccountingError): check(folder)


candidate = fixtures.candidate


@pytest.fixture
def accounted_matrix(candidate):
    markers = {}
    for profile in matrix.PROFILES:
        markers[profile] = candidate[2] / profile / 'start.json'
        matrix.begin(candidate[0], candidate[1], '123:2', profile, markers[profile])
    base = ci.load(fixtures.native(candidate))
    incoming = candidate[2] / 'incoming'
    incoming.mkdir()
    original_xml = {}
    for profile in matrix.PROFILES:
        run_folder = candidate[2] / profile / 'actual-pytest'
        run_folder.mkdir()
        result, receipt = observer.execute(run_folder, SOURCE, hosted=True, env_extra={
            'RF_PYTEST_SOURCE_SHA': candidate[1], 'RF_NATIVE_CONFIG': profile,
            'RF_PYTEST_REPOSITORY': matrix.PROJECT, 'RF_PYTEST_RUN_ID': '123:2'})
        assert result.returncode == 0, result.stdout
        record = copy.deepcopy(base)
        record['configurations'] = [row for row in record['configurations'] if row['name'] == profile]
        record['comparisons'] = []
        config = record['configurations'][0]
        command = next(row for row in config['commands'] if Path(row['log']).stem == 'pytest')
        log = Path(command['log'])
        log.write_text(result.stdout)
        command['log_sha256'] = ci.sha(log)
        xml = log.with_name('pytest.xml')
        raw = (run_folder / 'raw.xml').read_bytes()
        xml.write_bytes(raw)
        original_xml[profile] = raw
        proof = xml.parent / 'pytest-accounting/receipt.json'
        proof.parent.mkdir()
        shutil.copyfile(run_folder / 'receipt.json', proof)
        config.update(pytest_accounting_required=True, pytest_accounting=dict(path=str(proof), sha256=ci.sha(proof)))
        numeric = next(row for row in config['artifacts'] if Path(row['path']).name == 'numerical.json')
        fixtures.dump(Path(numeric['path']), dict(scenes=[dict(seed=1, noise=0., outliers=0, point_count=180,
            rotation_error=0., median_position_error=0., points=[[0., 1., 2.]], rotation=[1., 0., 0.], translation=[-1., 0., 0.])]))
        numeric['sha256'] = ci.sha(Path(numeric['path']))
        record_path = fixtures.dump(candidate[2] / profile / 'runner.json', record)
        qa = [fixtures.write(candidate[2] / profile / 'qa' / name, 'synthetic tool evidence')
              for name in ('installation.json', 'blender-publisher.sha256', 'blender-install.log', 'blender-check.log')]
        matrix.seal(markers[profile], record_path, incoming / profile, list(map(str, qa)))
    output = candidate[3] / 'native'
    matrix.aggregate(incoming, candidate[1], '123:2', output, root=candidate[0])
    result = ci.verify_bundle(candidate[3], candidate[1], '123:2', required=('native',))
    assert result['gates']['native']['tests'] == dict(tests=36, passed=24, skipped=12, failed=0)
    for profile in matrix.PROFILES:
        folder = output / 'files' / profile
        envelope = ci.load(folder / 'shard.json')
        raw = next(row for row in envelope['artifacts'] if row['role'] == 'junit')
        assert (folder / raw['path']).read_bytes() == original_xml[profile]
        proof = next(row for row in envelope['artifacts'] if row['role'] == 'pytest-accounting')
        assert ci.sha(folder / proof['path']) == proof['sha256']
    return candidate, output


def test_actual_pytest_subtests_seal_transport_and_final_matrix(accounted_matrix):
    candidate, output = accounted_matrix
    assert matrix.verify_matrix(output, candidate[1], '123:2')['tests']['tests'] == 36


def test_actual_nested_observer_receipt_path(tmp_path):
    result, _ = observer.execute(tmp_path, 'def test_good(subtests):\n    with subtests.test(): assert True\n',
                                 extra=('--rf-pytest-accounting=pytest-accounting/receipt.json',))
    assert result.returncode == 0, result.stdout
    assert validate(tmp_path / 'raw.xml', tmp_path / 'pytest-accounting/receipt.json',
                    source_sha='a' * 40, profile='gcc-release', repository=None, run_id=None) == dict(tests=2, passed=2, skipped=0, failed=0)



def test_accounting_missing_and_rehashed_tampering_never_qualifies(accounted_matrix, tmp_path):
    candidate, original = accounted_matrix
    for mutation in ('missing', 'remove-reference', 'drop-contract', 'source', 'profile', 'run', 'incomplete', 'skip-as-pass'):
        output = tmp_path / mutation
        shutil.copytree(original, output)
        folder = output / 'files/gcc-release'
        envelope = ci.load(folder / 'shard.json')
        proof_artifact = next(row for row in envelope['artifacts'] if row['role'] == 'pytest-accounting')
        proof_path = folder / proof_artifact['path']
        record_artifact = next(row for row in envelope['artifacts'] if row['role'] == 'native-record')
        record_path = folder / record_artifact['path']
        record = ci.load(record_path)
        config = record['configurations'][0]
        proof = ci.load(proof_path)
        if mutation == 'missing': proof_path.unlink()
        elif mutation in ('remove-reference', 'drop-contract'):
            config.pop('pytest_accounting')
            if mutation == 'drop-contract': config.pop('pytest_accounting_required')
            envelope['artifacts'].remove(proof_artifact)
            proof_path.unlink()
        else:
            if mutation == 'source': proof['source_sha'] = 'b' * 40
            elif mutation == 'profile': proof['profile'] = 'clang-release'
            elif mutation == 'run': proof['run_id'] = '123:3'
            elif mutation == 'incomplete': proof['completed'] = False
            else: next(event for event in proof['events'] if event['subtest'] and event['outcome'] == 'skipped')['outcome'] = 'passed'
            fixtures.dump(proof_path, proof)
            config['pytest_accounting']['sha256'] = ci.sha(proof_path)
            proof_artifact.update(sha256=ci.sha(proof_path), size_bytes=proof_path.stat().st_size)
        fixtures.dump(record_path, record)
        record_artifact.update(sha256=ci.sha(record_path), size_bytes=record_path.stat().st_size)
        fixtures.dump(folder / 'shard.json', envelope)
        manifest = ci.load(output / 'matrix.json')
        manifest['shards'][0]['sha256'] = ci.sha(folder / 'shard.json')
        fixtures.dump(output / 'matrix.json', manifest)
        outer = ci.load(output / 'evidence.json')
        outer['artifacts'] = [matrix.identity(output / 'matrix.json', 'matrix.json', 'matrix.json', 'native-matrix')]
        fixtures.dump(output / 'evidence.json', outer)
        with pytest.raises(ValueError):
            matrix.verify_matrix(output, candidate[1], '123:2')


def test_multiple_skipped_subtests_remain_individual_outcomes(tmp_path):
    result, _ = observer.execute(tmp_path, 'import pytest\ndef test_parts(subtests):\n    with subtests.test(i=0): pytest.skip("one")\n    with subtests.test(i=1): pytest.skip("two")\ndef test_clean(): pass\n')
    assert result.returncode == 0, result.stdout
    assert check(tmp_path) == dict(tests=4, passed=2, skipped=2, failed=0)



def test_mixed_only_parents_retain_legacy_all_skipped_rejection(tmp_path):
    result, data = observer.execute(tmp_path, 'import pytest\ndef test_parts(subtests):\n    with subtests.test(i=0): assert True\n    with subtests.test(i=1): pytest.skip("optional")\n')
    assert result.returncode == 0, result.stdout
    # Parent/subtest events are preserved honestly, but qualification keeps the
    # previous floor: at least one parent with no skipped outcome is required.
    with pytest.raises(AccountingError, match='entirely unskipped'):
        check(tmp_path)
    with pytest.raises(ci.EvidenceError):
        ci.junit(tmp_path / 'raw.xml')
