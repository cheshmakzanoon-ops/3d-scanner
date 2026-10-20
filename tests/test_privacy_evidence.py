import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as E
import zipfile

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('privacy', ROOT / 'tools/device/offline_privacy/audit.py')
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)
journey_spec = importlib.util.spec_from_file_location('journey', ROOT / 'tools/device/offline_privacy/journey.py')
journey = importlib.util.module_from_spec(journey_spec)
journey_spec.loader.exec_module(journey)


def manifest():
    return E.fromstring('''<manifest package="com.realityforge.scanner">
    <uses-permission name="android.permission.CAMERA"/>
    <uses-feature name="android.hardware.camera.any" required="false"/>
    <application allowBackup="false" fullBackupContent="false" usesCleartextTraffic="false" dataExtractionRules="@0x7f00"/>
    </manifest>''')


def test_packaged_policy_is_scoped_and_rejects_network_permissions():
    root = manifest()
    assert privacy.check_manifest(root) == []
    E.SubElement(root, 'uses-permission-sdk-23', name='android.permission.INTERNET')
    assert privacy.check_manifest(root)


@pytest.mark.parametrize('flag', ['allowBackup', 'fullBackupContent', 'usesCleartextTraffic'])
@pytest.mark.parametrize('value', ['true', None])
def test_missing_or_unsafe_flags_never_inherit_safe_defaults(flag, value):
    root = manifest()
    if value is None:
        del root.find('application').attrib[flag]
    else:
        root.find('application').set(flag, value)
    assert privacy.check_manifest(root)


def test_camera_required_and_wrong_package_fail():
    root = manifest()
    root.find('uses-feature').set('required', 'true')
    root.set('package', 'some.other.app')
    assert len(privacy.check_manifest(root)) == 2


def test_all_credential_and_device_protected_domains_excluded():
    root = E.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
    assert privacy.check_rules(root) == []
    root.find('device-transfer').remove(root.find('device-transfer')[0])
    assert privacy.check_rules(root)


def test_include_cannot_counterfeit_global_exclusion():
    root = E.Element('data-extraction-rules')
    for name in ['cloud-backup', 'device-transfer']:
        s = E.SubElement(root, name)
        for domain in privacy.DOMAINS:
            E.SubElement(s, 'exclude', domain=domain, path='.')
    E.SubElement(root.find('cloud-backup'), 'include', domain='file', path='originals')
    assert privacy.check_rules(root)


def test_aapt_tree_retains_parent_attribute_scope():
    root = privacy.aapt_tree('''N: android=http://schemas.android.com/apk/res/android (line=2)
  E: manifest (line=2)
    A: package="com.realityforge.scanner" (Raw: "com.realityforge.scanner")
    E: application (line=3)
      A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false
      E: provider (line=4)
        A: http://schemas.android.com/apk/res/android:name(0x01010003)="fixture.Provider" (Raw: "fixture.Provider")
    E: uses-permission (line=5)
      A: http://schemas.android.com/apk/res/android:name(0x01010003)="android.permission.INTERNET" (Raw: "android.permission.INTERNET")''')
    assert root.find('application').get('allowBackup') == 'false'
    assert root.find('uses-permission').get('name') == 'android.permission.INTERNET'
    assert root.find('application/provider').get('name') == 'fixture.Provider'


@pytest.mark.parametrize('text', ['', 'A: name=true', 'E: manifest (line=1)\nE: manifest (line=2)',
                                  'E: manifest (line=1)\n A: name=false\n A: name=true'])
def test_bad_dump_cannot_pass(text):
    with pytest.raises(ValueError):
        privacy.aapt_tree(text)


def transcript(methods=None, fields_order=None):
    """Labelled synthetic transcript matching AndroidJUnitRunner's -w -r protocol."""
    lines = []
    for current, method in enumerate(methods or sorted(journey.TEST_METHODS), 1):
        for code in (1, 0):
            fields = {'class': journey.TEST_CLASS, 'current': str(current),
                      'id': 'AndroidJUnitRunner', 'numtests': '3',
                      'stream': '\n' + journey.TEST_CLASS + ':' if code == 1 else '.',
                      'test': method}
            for key in fields_order or fields:
                lines.append(f'INSTRUMENTATION_STATUS: {key}={fields[key]}')
            lines.append(f'INSTRUMENTATION_STATUS_CODE: {code}')
    return '\n'.join(lines) + '\nINSTRUMENTATION_RESULT: stream=\n\nTime: 12.345\n\nOK (3 tests)\n\nINSTRUMENTATION_CODE: -1\n'


def test_runtime_evidence_accepts_complete_identity_bound_transcripts_and_disabled_radios():
    assert journey.network_disabled({'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'})
    assert not journey.network_disabled({'airplane_mode_on': '1', 'wifi_on': '1', 'mobile_data': '0'})
    assert not journey.network_disabled({})
    assert journey.completed(transcript())
    assert journey.completed(transcript(list(reversed(sorted(journey.TEST_METHODS))),
                                        ['stream', 'test', 'numtests', 'current', 'class', 'id']))


@pytest.mark.parametrize('field', ['class', 'current', 'id', 'numtests', 'stream', 'test'])
@pytest.mark.parametrize('operation', ['missing', 'duplicate'])
def test_runtime_requires_unique_complete_start_and_end_fields(field, operation):
    text = transcript()
    line = next(line for line in text.splitlines() if line.startswith(f'INSTRUMENTATION_STATUS: {field}='))
    replacement = '' if operation == 'missing' else line + '\n' + line
    assert not journey.completed(text.replace(line, replacement, 1))


@pytest.mark.parametrize('old,new', [
    ('INSTRUMENTATION_STATUS_CODE: 1', 'INSTRUMENTATION_STATUS_CODE: 0'),
    ('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: 1'),
    ('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -1'),
    ('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -2'),
    ('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -3'),
    ('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS_CODE: -4'),
    ('INSTRUMENTATION_STATUS: current=1', 'INSTRUMENTATION_STATUS: current=2'),
    ('INSTRUMENTATION_STATUS: numtests=3', 'INSTRUMENTATION_STATUS: numtests=4'),
    ('INSTRUMENTATION_STATUS: id=AndroidJUnitRunner', 'INSTRUMENTATION_STATUS: id=UnknownRunner'),
    (journey.TEST_CLASS, 'com.other.SmokeTest'),
    ('Time: 12.345', ''),
    ('OK (3 tests)', 'OK (2 tests)'),
    ('OK (3 tests)', 'OK (3 tests)\nOK (3 tests)'),
    ('INSTRUMENTATION_RESULT: stream=', 'INSTRUMENTATION_RESULT: unknown='),
    ('INSTRUMENTATION_CODE: -1', ''),
    ('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: 0'),
    ('INSTRUMENTATION_CODE: -1', 'INSTRUMENTATION_CODE: -1\nINSTRUMENTATION_CODE: -1'),
])
def test_runtime_rejects_corrupt_identity_counters_results_or_final_code(old, new):
    assert not journey.completed(transcript().replace(old, new, 1))


def test_runtime_rejects_fabricated_partial_unknown_repeated_and_failed_runs():
    good = transcript()
    methods = sorted(journey.TEST_METHODS)
    for bad in [
            'INSTRUMENTATION_STATUS_CODE: 0\n' * 3 + 'OK (3 tests)',
            'OK (3 tests)', transcript(methods[:2]), transcript([methods[0]] * 3),
            transcript([methods[0], methods[1], 'unknownMethod']),
            good.replace('INSTRUMENTATION_STATUS_CODE: 1\n', '', 1),
            good.replace('INSTRUMENTATION_STATUS_CODE: 0\n', '', 1),
            good.replace('INSTRUMENTATION_STATUS: current=2', 'INSTRUMENTATION_STATUS: current=1'),
            good.replace('INSTRUMENTATION_STATUS: test=' + methods[0],
                         'INSTRUMENTATION_STATUS: test=' + methods[1], 1),
            good.replace('INSTRUMENTATION_STATUS_CODE: 0', 'INSTRUMENTATION_STATUS: unknown=x\nINSTRUMENTATION_STATUS_CODE: 0', 1),
            good + 'INSTRUMENTATION_STATUS_CODE: 0\n',
            good.replace('INSTRUMENTATION_RESULT: stream=', 'OK (3 tests)\nINSTRUMENTATION_RESULT: stream='),
            *[marker + '\n' + good for marker in ['FAILURES!!!', 'INSTRUMENTATION_FAILED',
                'AssumptionFailure', 'AssumptionViolatedException', 'Process crashed', 'shortMsg=crash']],
    ]:
        assert not journey.completed(bad), bad


def resource_manifest(reference='@0x7f0e0000'):
    root = manifest()
    root.find('application').set('dataExtractionRules', reference)
    return root


def resource_table(value='() (file) res/4j.xml type=XML'):
    return 'Package name=com.realityforge.scanner id=7f\n  type xml id=0e entryCount=1\n    resource 0x7f0e0000 xml/data_extraction_rules\n      ' + value + '\n'


def test_resource_resolution_uses_manifest_identity_and_actual_renamed_entry():
    assert privacy.extraction_resource(resource_manifest(), resource_table()) == (
        '0x7f0e0000', 'data_extraction_rules', 'res/4j.xml')


@pytest.mark.parametrize('reference', ['', '@xml/data_extraction_rules', '@0x7f0e0001', '@0x7f00', '@../unsafe'])
def test_unknown_or_non_numeric_resource_reference_fails(reference):
    with pytest.raises(ValueError):
        privacy.extraction_resource(resource_manifest(reference), resource_table())


@pytest.mark.parametrize('table', [
    resource_table() + resource_table(),
    resource_table() + '    resource 0x7f0e0001 xml/data_extraction_rules\n      () (file) res/other.xml type=XML\n',
    resource_table().replace('xml/data_extraction_rules', 'raw/data_extraction_rules'),
    resource_table().replace('xml/data_extraction_rules', 'xml/../unsafe'),
    resource_table() + '      (v31) (file) res/unsafe.xml type=XML\n',
    resource_table('(v31) (file) res/4j.xml type=XML'),
    resource_table('() @0x7f0e0001'),
    resource_table().replace('xml/data_extraction_rules', 'xml/data_extraction_rules unexpected'),
])
def test_multiple_alias_qualified_or_unknown_resource_values_fail(table):
    with pytest.raises(ValueError):
        privacy.extraction_resource(resource_manifest(), table)


@pytest.mark.parametrize('path', ['../outside.xml', '/res/a.xml', 'res/../a.xml', 'res/./a.xml',
                                  'res//a.xml', 'res/a.xml\nres/b.xml', 'res/a.xml --unsafe',
                                  'res/a\\b.xml', 'res/a.json'])
def test_unsafe_compiled_xml_paths_fail(path):
    with pytest.raises(ValueError):
        privacy.compiled_xml_path(path)


def aapt_dump(root, depth=0):
    lines = [' ' * depth + f'E: {root.tag} (line=1)']
    lines += [' ' * (depth + 2) + f'A: {key}={json.dumps(value)}' for key, value in root.attrib.items()]
    for child in root:
        lines.append(aapt_dump(child, depth + 2))
    return '\n'.join(lines)


@pytest.mark.parametrize('unsafe_referenced_policy', [False, True])
@pytest.mark.parametrize('package_fault', [None, 'missing', 'duplicate', 'disagreement', 'multiple_paths'])
def test_package_gate_checks_referenced_policy_even_with_safe_stale_original(tmp_path, monkeypatch, unsafe_referenced_policy, package_fault):
    apk = tmp_path / 'candidate.apk'
    with zipfile.ZipFile(apk, 'w') as archive:
        if package_fault != 'missing':
            archive.writestr('res/4j.xml', b'actual referenced policy bytes')
        if package_fault == 'duplicate':
            with pytest.warns(UserWarning, match='Duplicate name'):
                archive.writestr('res/4j.xml', b'conflicting policy bytes')
        archive.writestr('res/xml/data_extraction_rules.xml', b'unreferenced safe original')
    rules = E.parse(ROOT / 'app/src/main/res/xml/data_extraction_rules.xml').getroot()
    if unsafe_referenced_policy:
        E.SubElement(rules.find('device-transfer'), 'include', domain='file', path='originals')
    called = []
    def output(args, **kwargs):
        called.append(args)
        if args[1:3] == ['dump', 'resources']:
            return resource_table()
        if args[1:3] == ['resources', 'value']:
            if package_fault == 'disagreement':
                return 'res/xml/data_extraction_rules.xml'
            if package_fault == 'multiple_paths':
                return 'res/4j.xml\nres/xml/data_extraction_rules.xml\n'
            return 'res/4j.xml\n'
        if args[-1] == 'AndroidManifest.xml':
            return aapt_dump(resource_manifest())
        assert args[-1] == 'res/4j.xml'
        return aapt_dump(rules)
    monkeypatch.setattr(privacy.subprocess, 'check_output', output)
    if package_fault:
        with pytest.raises(ValueError):
            privacy.package_report(apk, tmp_path / 'sdk', 'a' * 40)
        return
    report = privacy.package_report(apk, tmp_path / 'sdk', 'a' * 40)
    assert report['status'] == ('FAIL' if unsafe_referenced_policy else 'PASS')
    assert report['extraction_policy']['apk_entry'] == 'res/4j.xml'
    assert not any(args[-1] == 'res/xml/data_extraction_rules.xml' for args in called)


def test_installed_identity_rejects_wrong_package_or_mixed_splits():
    spec = importlib.util.spec_from_file_location('journey', ROOT / 'tools/device/offline_privacy/journey.py')
    journey = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(journey)
    def invoke(*args):
        if args[1:3] == ('pm', 'path'):
            return 'package:/data/app/~~safe/package/base.apk'
        return 'a' * 64 + '  /data/app/~~safe/package/base.apk'
    assert journey.installed_hash(invoke, 'com.realityforge.scanner') == 'a' * 64
    for response in ['', 'package:/data/app/a/base.apk\npackage:/data/app/a/split.apk',
                     'package:/data/app/a/$(unsafe).apk']:
        with pytest.raises(ValueError):
            journey.installed_hash(lambda *args: response, 'com.realityforge.scanner')
