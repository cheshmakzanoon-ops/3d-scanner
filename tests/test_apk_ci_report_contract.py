"""Producer/collector contract; synthetic files are not APK or device qualification.

Fixture is the actual check_apk JSON from CI run 39696643409/job 119555520470,
2026-10-22 00:08:01 UTC. It exposed the missing internetPermission field.
"""
import importlib.util
import json
from pathlib import Path
from xml.sax.saxutils import quoteattr
import pytest

ROOT = Path(__file__).resolve().parents[1]
def module(name, path):
    spec = importlib.util.spec_from_file_location(name, ROOT / path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result
apk = module('apk_contract_producer', 'tools/release/package_validation/apk.py')
ci = module('apk_contract_collector', 'tools/ci/validation/evidence.py')

def actual_report():
    return json.loads((ROOT / 'tests/fixtures/apk-validation-fdded23-ci.json').read_text())

def manifest(permissions):
    return ('<manifest xmlns:android="http://schemas.android.com/apk/res/android" '
            'package="com.realityforge.scanner">' + permissions +
            '<application android:allowBackup="false" android:fullBackupContent="false" '
            'android:usesCleartextTraffic="false" android:dataExtractionRules="@xml/rules"/>'
            '</manifest>')

def producer_report():
    report = actual_report()
    xml = manifest(''.join('<uses-permission android:name=' + quoteattr(p) + '/>' for p in report['permissions']))
    report.update(apk.manifest_policy(xml))
    return report

def collect_report(tmp_path, monkeypatch, report):
    monkeypatch.setattr(ci, 'source', lambda *args: None)
    start = tmp_path / 'start.json'
    start.write_text(json.dumps(dict(schema=ci.SCHEMA, gate='android', root=str(tmp_path), source_sha='a'*40, started_ns=0)))
    paths = [tmp_path / 'app.apk', tmp_path / 'test.apk']
    for path in paths: path.write_text('synthetic file, not a real APK')
    junit = tmp_path / 'tests.xml'
    junit.write_text('<testsuite tests="1"><testcase name="synthetic"/></testsuite>')
    path = tmp_path / 'apk-report.json'
    path.write_text(json.dumps(report))
    return ci.collect(start, tmp_path / 'sealed', xml_patterns=[str(junit)], artifact_patterns=[str(p) for p in paths], apk_report=path)

def test_real_producer_report_collects(tmp_path, monkeypatch):
    assert collect_report(tmp_path, monkeypatch, producer_report())['status'] == 'PASS'

@pytest.mark.parametrize('permissions', [[], ['com.realityforge.scanner.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION']])
def test_explicit_no_network_permissions_collect(tmp_path, monkeypatch, permissions):
    report = actual_report()
    report.update(apk.manifest_policy(manifest(''.join('<uses-permission android:name=' + quoteattr(p) + '/>' for p in permissions))))
    assert report['internetPermission'] is False
    assert collect_report(tmp_path, monkeypatch, report)['status'] == 'PASS'

def test_original_ci_report_missing_boolean_rejected(tmp_path, monkeypatch):
    with pytest.raises(ci.EvidenceError): collect_report(tmp_path, monkeypatch, actual_report())

@pytest.mark.parametrize('permissions', [None, {}, '', 'android.permission.CAMERA', [None], [1], [''], [' bad '], ['android.permission.INTERNET'], ['android.permission.ACCESS_NETWORK_STATE']])
def test_unknown_malformed_or_conflicting_permissions_rejected(tmp_path, monkeypatch, permissions):
    report = producer_report()
    report.update(internetPermission=False, permissions=permissions)
    with pytest.raises(ci.EvidenceError): collect_report(tmp_path, monkeypatch, report)

def test_absent_permissions_rejected(tmp_path, monkeypatch):
    report = producer_report(); report['internetPermission'] = False; report.pop('permissions')
    with pytest.raises(ci.EvidenceError): collect_report(tmp_path, monkeypatch, report)

@pytest.mark.parametrize('flag', [None, True, 0, '', 'false'])
def test_non_false_boolean_rejected(tmp_path, monkeypatch, flag):
    report = producer_report(); report['internetPermission'] = flag
    with pytest.raises(ci.EvidenceError): collect_report(tmp_path, monkeypatch, report)

@pytest.mark.parametrize('field,value', [('zipAlignment16k', False), ('zipAlignment16k', 1), ('elfLibrariesChecked', [])])
def test_alignment_and_elf_gate_preserved(tmp_path, monkeypatch, field, value):
    report = producer_report(); report[field] = value
    with pytest.raises(ci.EvidenceError): collect_report(tmp_path, monkeypatch, report)

@pytest.mark.parametrize('declaration', ['<uses-permission/>', '<uses-permission android:name=""/>', '<uses-permission android:name=" bad "/>'])
def test_malformed_manifest_permission_rejected(declaration):
    with pytest.raises(ValueError): apk.manifest_policy(manifest(declaration))

@pytest.mark.parametrize('tag', ['uses-permission', 'uses-permission-sdk-23'])
@pytest.mark.parametrize('name', ['INTERNET', 'ACCESS_NETWORK_STATE'])
def test_network_manifest_permission_rejected(tag, name):
    with pytest.raises(ValueError): apk.manifest_policy(manifest(f'<{tag} android:name="android.permission.{name}"/>'))
