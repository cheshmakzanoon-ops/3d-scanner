"""Actual pinned pytest subprocesses test observation, not native code execution."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
import xml.etree.ElementTree as ET

import pytest
from _pytest.reports import TestReport

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('accounting_plugin', ROOT / 'tools/qa/native_regression/pytest_accounting.py')
plugin = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(plugin)


def execute(tmp_path, source, *, hosted=False, extra=(), env_extra=None):
    (tmp_path / 'test_sample.py').write_text(source)
    env = os.environ.copy()
    env.update(PYTHONPATH=str(ROOT), PYTEST_DISABLE_PLUGIN_AUTOLOAD='1',
               RF_PYTEST_SOURCE_SHA='a' * 40, RF_NATIVE_CONFIG='gcc-release')
    for key in ('RF_PYTEST_REPOSITORY', 'RF_PYTEST_RUN_ID'):
        env.pop(key, None)
    if hosted:
        env.update(RF_PYTEST_REPOSITORY='owner/repo', RF_PYTEST_RUN_ID='123:2')
    env.update(env_extra or {})
    result = subprocess.run([sys.executable, '-m', 'pytest', '-q', '-p',
                             'tools.qa.native_regression.pytest_accounting',
                             '--junitxml=raw.xml', '--rf-pytest-accounting=receipt.json',
                             *extra, 'test_sample.py'], cwd=tmp_path, env=env,
                            text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
    receipt = tmp_path / 'receipt.json'
    return result, json.loads(receipt.read_text()) if receipt.exists() else None


def test_builtin_subtests_preserve_pass_fail_skip_and_xml_identity(tmp_path):
    result, data = execute(tmp_path, '''import pytest
@pytest.mark.parametrize("value", ["a::b[x]"])
def test_cases(subtests, value):
    with subtests.test(i=0): assert True
    with subtests.test(i=1): assert False
    with subtests.test(i=2): pytest.skip("optional")
''', hosted=True, extra=('--junit-prefix=pref',))
    assert result.returncode == 1, result.stdout
    assert data['session_exitstatus'] == 1 and data['completed'] is True
    assert data['repository'] == 'owner/repo' and data['run_id'] == '123:2'
    assert data['source_sha'] == 'a' * 40 and data['profile'] == 'gcc-release'
    assert data['pytest_version'] == '9.0.3'
    assert data['junit_prefix'] == 'pref' and data['junit_family'] == 'xunit2'
    events = data['events']
    assert [e['sequence'] for e in events] == list(range(len(events)))
    subtests = [e for e in events if e['subtest']]
    assert [e['outcome'] for e in subtests] == ['passed', 'failed', 'skipped']
    assert [e['ordinal'] for e in subtests] == [0, 1, 2]
    assert all(e['ordinal'] is None for e in events if not e['subtest'])
    xml_names = {(e.attrib['classname'], e.attrib['name']) for e in ET.parse(tmp_path / 'raw.xml').iter('testcase')}
    assert {(e['classname'], e['name']) for e in events} == xml_names
    raw = (tmp_path / 'raw.xml').read_bytes()
    assert data['raw_xml_sha256'] == hashlib.sha256(raw).hexdigest()
    assert data['raw_xml_size'] == len(raw)


def test_unittest_subtests_and_ordinary_skip(tmp_path):
    result, data = execute(tmp_path, '''import unittest, pytest
class Cases(unittest.TestCase):
    def test_parts(self):
        with self.subTest(i=0): self.assertTrue(True)
        with self.subTest(i=1): self.skipTest("optional")
        with self.subTest(i=2): self.assertTrue(False)
@pytest.mark.skip(reason="ordinary")
def test_skip(): pass
''')
    assert result.returncode == 1, result.stdout
    assert data['repository'] is None and data['run_id'] is None
    subtests = [e for e in data['events'] if e['subtest']]
    assert [e['outcome'] for e in subtests] == ['passed', 'skipped', 'failed']
    assert [e['ordinal'] for e in subtests] == [0, 1, 2]
    assert any(e['nodeid'].endswith('test_skip') and e['outcome'] == 'skipped' and not e['subtest'] for e in data['events'])


@pytest.mark.parametrize('phase', ['setup', 'teardown'])
def test_fixture_failure_phases_are_retained(tmp_path, phase):
    body = 'assert False\n    yield' if phase == 'setup' else 'yield\n    assert False'
    result, data = execute(tmp_path, 'import pytest\n@pytest.fixture\ndef fixture():\n    '+body+'\ndef test_case(fixture): pass\n')
    assert result.returncode == 1, result.stdout
    assert any(e['when'] == phase and e['outcome'] == 'failed' for e in data['events'])


def test_passing_subtests_and_parameter_ordinal_reset(tmp_path):
    result, data = execute(tmp_path, '''import pytest
@pytest.mark.parametrize("x", [1, 2])
def test_good(subtests, x):
    with subtests.test(x=x): assert x > 0
''')
    assert result.returncode == 0, result.stdout
    assert data['session_exitstatus'] == 0
    assert [e['ordinal'] for e in data['events'] if e['subtest']] == [0, 0]


@pytest.mark.parametrize('environment', [
    {'RF_PYTEST_SOURCE_SHA': 'bad'}, {'RF_NATIVE_CONFIG': 'unknown'},
    {'RF_PYTEST_REPOSITORY': 'owner/repo'}, {'RF_PYTEST_RUN_ID': '1:1'},
    {'RF_PYTEST_REPOSITORY': 'owner/repo', 'RF_PYTEST_RUN_ID': 'fabricated'},
])
def test_bad_identity_never_writes_completed_receipt(tmp_path, environment):
    result, data = execute(tmp_path, 'def test_good(): pass', env_extra=environment)
    assert result.returncode != 0 and data is None


def test_existing_receipt_is_not_overwritten(tmp_path):
    (tmp_path / 'receipt.json').write_text('{"existing":true}')
    result, data = execute(tmp_path, 'def test_good(): pass')
    assert result.returncode != 0 and data == {'existing': True}


def observer(tmp_path, monkeypatch):
    monkeypatch.setenv('RF_PYTEST_SOURCE_SHA', 'a' * 40)
    monkeypatch.setenv('RF_NATIVE_CONFIG', 'gcc-release')
    monkeypatch.delenv('RF_PYTEST_REPOSITORY', raising=False)
    monkeypatch.delenv('RF_PYTEST_RUN_ID', raising=False)
    config = SimpleNamespace(option=SimpleNamespace(xmlpath=str(tmp_path / 'raw.xml'), junitprefix=None),
                             pluginmanager=SimpleNamespace(hasplugin=lambda name: False),
                             getini=lambda name: 'xunit2')
    return plugin.Observer(config, tmp_path / 'receipt.json')


def report(**overrides):
    return TestReport(nodeid='test_sample.py::test_good', location=('test_sample.py', 1, 'test_good'),
                      keywords={}, outcome=overrides.get('outcome', 'passed'), longrepr=None,
                      when=overrides.get('when', 'call'), sections=(), duration=0)


@pytest.mark.parametrize('bad', ['type', 'phase', 'outcome', 'events', 'bytes', 'name'])
def test_observer_rejects_unknown_reports_and_overflow(tmp_path, monkeypatch, bad):
    obj = observer(tmp_path, monkeypatch)
    value = report()
    if bad == 'type': value = SimpleNamespace()
    elif bad == 'phase': value.when = 'unknown'
    elif bad == 'outcome': value.outcome = 'unknown'
    elif bad == 'events': monkeypatch.setattr(plugin, 'MAX_EVENTS', 0)
    elif bad == 'bytes': monkeypatch.setattr(plugin, 'MAX_BYTES', 1)
    elif bad == 'name': value.nodeid = 'x' * 8193
    with pytest.raises(ValueError): obj.pytest_runtest_logreport(value)
    assert obj.failed is True and not obj.output.exists()


def test_unpinned_pytest_version_rejected(tmp_path, monkeypatch):
    monkeypatch.setattr(plugin.pytest, '__version__', '9.0.4')
    with pytest.raises(pytest.UsageError, match='exact pytest'):
        observer(tmp_path, monkeypatch)


@pytest.mark.parametrize('extra', [('--junit-family=xunit1',), ('-o', 'junit_family=xunit1')])
def test_unsupported_junit_configuration_does_not_pass(tmp_path, extra):
    result, data = execute(tmp_path, 'def test_good(): pass', extra=extra)
    assert result.returncode != 0 and data is None


def test_symlink_xml_rejected_before_pytest_writes_it(tmp_path):
    target = tmp_path / 'outside.xml'
    target.write_text('untouched')
    (tmp_path / 'raw.xml').symlink_to(target)
    result, data = execute(tmp_path, 'def test_good(): pass')
    assert result.returncode != 0 and data is None
    assert target.read_text() == 'untouched'


@pytest.mark.parametrize('kind', ['missing', 'empty', 'symlink', 'directory', 'size', 'receipt-size', 'late-existing'])
def test_finish_rejects_invalid_raw_or_receipt_without_success(tmp_path, monkeypatch, kind):
    obj = observer(tmp_path, monkeypatch)
    raw = tmp_path / 'raw.xml'
    if kind != 'missing': raw.write_text('<testsuites/>')
    if kind == 'empty': raw.write_bytes(b'')
    elif kind == 'symlink':
        raw.unlink()
        raw.symlink_to(tmp_path / 'missing-target')
    elif kind == 'directory':
        raw.unlink()
        raw.mkdir()
    elif kind == 'size': monkeypatch.setattr(plugin, 'MAX_XML_BYTES', 1)
    elif kind == 'receipt-size': monkeypatch.setattr(plugin, 'MAX_BYTES', 1)
    elif kind == 'late-existing': obj.output.write_text('untouched')
    session = SimpleNamespace(exitstatus=0)
    finish = obj.pytest_sessionfinish(session, 0)
    next(finish)
    with pytest.raises((ValueError, OSError)):
        finish.send(SimpleNamespace(excinfo=None))
    assert session.exitstatus == pytest.ExitCode.INTERNAL_ERROR
    if kind == 'late-existing': assert obj.output.read_text() == 'untouched'
    else: assert not obj.output.exists()


def test_subprocess_event_overflow_cannot_report_pass(tmp_path):
    (tmp_path / 'conftest.py').write_text('import tools.qa.native_regression.pytest_accounting as p\np.MAX_EVENTS = 1\n')
    result, data = execute(tmp_path, 'def test_good(): pass')
    assert result.returncode != 0 and data is None


@pytest.mark.parametrize('attribute', ['wasxfail', 'worker_id', 'item_index'])
def test_custom_report_attributes_fail_closed(tmp_path, monkeypatch, attribute):
    obj = observer(tmp_path, monkeypatch)
    value = report()
    setattr(value, attribute, 'unexpected')
    with pytest.raises(ValueError, match='Unsupported'):
        obj.pytest_runtest_logreport(value)
    assert obj.failed and not obj.output.exists()


def test_actual_xfail_cannot_produce_accepted_accounting(tmp_path):
    result, data = execute(tmp_path, 'import pytest\n@pytest.mark.xfail(reason="outside native accounting domain")\ndef test_xfail(): assert False\n')
    assert result.returncode != 0 and data is None
