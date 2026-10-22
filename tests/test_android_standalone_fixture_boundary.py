"""Source boundary guard only; real provider-process behavior still needs device tests."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = '{http://schemas.android.com/apk/res/android}'


class StandaloneFixtureBoundaryTest(unittest.TestCase):
    def test_manifest_components_are_platform_only_java(self):
        manifest = ET.parse(ROOT / 'app/src/androidTest/AndroidManifest.xml').getroot()
        sources = ROOT / 'app/src/androidTest/java'
        components = [node for tag in ('provider', 'receiver') for node in manifest.findall(f'application/{tag}')]
        self.assertGreater(len(components), 0)
        for node in components:
            owner = node.attrib[ANDROID + 'name'].split('$', 1)[0]
            with self.subTest(component=node.attrib[ANDROID + 'name']):
                base = sources / owner.replace('.', '/')
                java = base.with_suffix('.java')
                self.assertTrue(java.is_file(), 'Standalone manifest components need platform-only Java, not target-APK Kotlin dependencies')
                self.assertFalse(base.with_suffix('.kt').exists(), 'Do not keep a second Kotlin definition')
                source = java.read_text()
                imports = re.findall(r'^import\s+(?:static\s+)?([^;]+);', source, re.MULTILINE)
                self.assertTrue(all(item.startswith(('android.', 'java.', 'javax.')) or item in ('org.json.JSONArray', 'org.json.JSONObject') for item in imports), imports)
                code = re.sub(r'/\*.*?\*/|//[^\n]*', '', source, flags=re.DOTALL)
                self.assertNotRegex(code, r'\b(?:kotlin|androidx)\s*\.|\bInstrumentationRegistry\b|\bSharedFaultDocumentFixtures\b')

    def test_shared_control_permissions_remain_narrow(self):
        manifest = ET.parse(ROOT / 'app/src/androidTest/AndroidManifest.xml').getroot()
        names = {node.attrib[ANDROID + 'name']: node for node in manifest.findall('application/*') if ANDROID + 'name' in node.attrib}
        provider = names['com.realityforge.scanner.qa.SharedFaultDocumentProvider']
        receiver = names['com.realityforge.scanner.qa.SharedFaultDocumentProvider$ControlReceiver']
        self.assertEqual(provider.attrib[ANDROID + 'permission'], 'android.permission.MANAGE_DOCUMENTS')
        self.assertEqual(provider.attrib[ANDROID + 'grantUriPermissions'], 'true')
        self.assertEqual(receiver.attrib[ANDROID + 'permission'], 'android.permission.DUMP')

    def test_instrumentation_client_is_not_a_manifest_component(self):
        manifest = (ROOT / 'app/src/androidTest/AndroidManifest.xml').read_text()
        self.assertNotIn('SharedFaultDocumentFixtures', manifest)
        helper = (ROOT / 'app/src/androidTest/java/com/realityforge/scanner/qa/SharedFaultDocumentFixtures.kt').read_text()
        self.assertIn('InstrumentationRegistry.getInstrumentation()', helper)
        self.assertIn('finally { instrumentation.uiAutomation.dropShellPermissionIdentity() }', helper)


if __name__ == '__main__':
    unittest.main()
