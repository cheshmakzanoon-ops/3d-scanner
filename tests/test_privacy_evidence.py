import importlib.util
from pathlib import Path
import xml.etree.ElementTree as E

import pytest

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('privacy', ROOT / 'tools/device/offline_privacy/audit.py')
privacy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(privacy)


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


def test_runtime_evidence_rejects_disabled_partial_and_skipped_runs():
    path = ROOT / 'tools/device/offline_privacy/journey.py'
    spec = importlib.util.spec_from_file_location('journey', path)
    journey = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(journey)
    assert journey.network_disabled({'airplane_mode_on': '1', 'wifi_on': '0', 'mobile_data': '0'})
    assert not journey.network_disabled({'airplane_mode_on': '1', 'wifi_on': '1', 'mobile_data': '0'})
    assert not journey.network_disabled({})
    assert journey.completed('OK (3 tests)')
    for output in ['OK (0 tests)', 'OK (2 tests)', 'FAILURES!!!\nOK (3 tests)',
                   'INSTRUMENTATION_FAILED\nOK (3 tests)', 'INSTRUMENTATION_STATUS_CODE: -3\nOK (3 tests)']:
        assert not journey.completed(output)
