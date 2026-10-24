"""Real Gradle lifecycle regression; synthetic extension is not Android qualification."""
import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PROBE = ROOT / 'tools/eight_view_host/probe.init.gradle'
DEFER = ''' // Android and host test tasks are registered by later afterEvaluate callbacks.
 // Keep source-set setup above in project evaluation, but resolve tasks only once
 // every project has finished evaluation. Missing tasks still fail explicitly.
 // Keep providers unrealized here: projectsEvaluated has no project state lock.
 // Host task creation traverses configuration-backed ordinary dependencies.
 gradle.projectsEvaluated {
'''

BUILD = '''
tasks.withType(Test).configureEach { testClassesDirs = files(); classpath = files() }
class SyntheticSourceDirectory {
    List<File> dirs = []
    void srcDir(File dir) { dirs.add(dir) }
}
def syntheticJava = new SyntheticSourceDirectory()
ext.android = [sourceSets: [test: [java: syntheticJava]],
               compileSdk: 35, buildToolsVersion: '35.0.0', ndkVersion: 'synthetic-not-installed']
configurations { syntheticRuntime; debugRuntimeClasspath }
repositories { maven { url = uri(rootProject.file('synthetic-repository')) } }
dependencies { debugRuntimeClasspath 'org.opencv:opencv:4.12.0@aar' }
afterEvaluate {
    tasks.register('syntheticCompile') { doLast { throw new GradleException('compile must not execute') } }
    if (!project.hasProperty('omitOrdinary')) {
        tasks.register('testDebugUnitTest', Test) {
            dependsOn tasks.named('syntheticCompile')
            // Match AGP's configuration-backed implicit Test classpath dependencies.
            def providerCase = project.findProperty('providerCase') ?: 'single'
            def providers = [rootProject.file('synthetic-classes.jar')]
            if (providerCase == 'zero') providers = [rootProject.file('synthetic-empty.jar')]
            if (providerCase == 'duplicate') providers += rootProject.file('synthetic-copy.jar')
            if (providerCase == 'directory') providers = [rootProject.file('synthetic-directory')]
            if (providerCase == 'mismatch') providers = [rootProject.file('synthetic-mismatch.jar')]
            if (providerCase == 'truncated') providers = (0..<270).collect { rootProject.file('empty-' + it) }
            if (providerCase == 'late') providers = (0..<270).collect { rootProject.file('empty-' + it) } +
                [rootProject.file('synthetic-classes.jar'), rootProject.file('synthetic-copy.jar'), rootProject.file('synthetic-directory')]
            if (providerCase == 'bytecap') providers = rootProject.file('long-directories').readLines().collect { file(it) } +
                [rootProject.file('synthetic-classes.jar'), rootProject.file('synthetic-copy.jar'), rootProject.file('synthetic-directory')]
            classpath = files(providers, configurations.syntheticRuntime)
        }
    }
    tasks.register('syntheticOracle') { doLast { throw new GradleException('oracle must not execute') } }
    tasks.register('hostIntegrationTest', Test) {
        def ordinary = tasks.named('testDebugUnitTest').get()
        testClassesDirs = ordinary.testClassesDirs
        classpath = ordinary.classpath
        dependsOn(ordinary.taskDependencies.getDependencies(ordinary))
        dependsOn tasks.named('syntheticOracle')
    }
    tasks.register('verifyHostIntegrationSdk') { doLast { throw new GradleException('SDK check must not execute') } }
}
tasks.register('inspectLifecycle') {
    doLast {
        assert tasks.names.containsAll(['eightPrepare', 'eightProbe'])
        def probe = tasks.getByName('eightProbe')
        assert probe.dependsOn*.name.toSet() == ['eightPrepare'].toSet()
        assert probe.testClassesDirs == tasks.getByName('hostIntegrationTest').testClassesDirs
        assert probe.excludes.isEmpty()
        assert probe.includes == ['**/EightViewSparsePrefixHostTest.class'].toSet()
        assert syntheticJava.dirs.size() == 1
        def prepare = tasks.getByName('eightPrepare')
        assert prepare.taskDependencies.getDependencies(prepare)*.name.toSet() ==
            ['syntheticCompile', 'verifyHostIntegrationSdk'].toSet()
        assert tasks.getByName('testDebugUnitTest').taskDependencies
            .getDependencies(tasks.getByName('testDebugUnitTest'))*.name == ['syntheticCompile']
        assert tasks.getByName('hostIntegrationTest').taskDependencies
            .getDependencies(tasks.getByName('hostIntegrationTest'))*.name.toSet() ==
            ['syntheticCompile', 'syntheticOracle'].toSet()
        println 'SYNTHETIC_LIFECYCLE_PASS'
    }
}
'''


class EightViewGradleLifecycleTests(unittest.TestCase):
    def test_deferred_lookup_keeps_source_set_setup_early(self):
        source = PROBE.read_text()
        self.assertEqual(source.count(DEFER), 1)
        self.assertLess(source.index('android.sourceSets.test.java.srcDir(sourceDir)'), source.index(DEFER))
        self.assertLess(source.index(DEFER), source.index("tasks.named('testDebugUnitTest')"))
        self.assertTrue(source.endswith('} } } }\n'))

    def test_real_gradle_lifecycle_and_missing_task(self):
        home = os.environ.get('RF_GRADLE_HOME')
        if not home:
            if os.environ.get('RF_GRADLE_REQUIRE_RUNTIME') == '1':
                self.fail('RF_GRADLE_HOME is required for runtime qualification')
            self.skipTest('Gradle 8.13 not supplied; NOT runtime qualification')
        gradle = Path(home) / 'bin/gradle'
        self.assertTrue(gradle.is_file())
        source = PROBE.read_text()
        # Reconstruct only the pre-fix lifecycle placement, not a substitute probe.
        old = source.replace(DEFER, '', 1)
        self.assertTrue(old.endswith('} } } }\n'))
        old = old[:-8] + '} } }\n'
        with tempfile.TemporaryDirectory(prefix='eight-gradle-lifecycle-') as tmp:
            root = Path(tmp)
            version = subprocess.run([str(gradle), '-g', str(root / 'gradle-home'), '--version'],
                                     text=True, capture_output=True, timeout=30)
            self.assertEqual(version.returncode, 0, version.stderr)
            self.assertIn('Gradle 8.13\n', version.stdout)
            # Inert bytes in a LOCAL synthetic artifact, never an OpenCV runtime.
            # This allows the unchanged init script's provider to resolve during
            # dry-run graph construction, without replacing its classpath code.
            classes = io.BytesIO()
            with zipfile.ZipFile(classes, 'w') as jar:
                jar.writestr('org/opencv/core/Core.class', b'synthetic-not-bytecode')
            (root / 'synthetic-classes.jar').write_bytes(classes.getvalue())
            (root / 'synthetic-copy.jar').write_bytes(classes.getvalue())
            with zipfile.ZipFile(root / 'synthetic-empty.jar', 'w') as jar:
                jar.writestr('unrelated.txt', b'not-a-provider')
            with zipfile.ZipFile(root / 'synthetic-mismatch.jar', 'w') as jar:
                jar.writestr('org/opencv/core/Core.class', b'different-synthetic-bytes')
            core = root / 'synthetic-directory/org/opencv/core/Core.class'
            core.parent.mkdir(parents=True)
            core.write_bytes(b'synthetic-not-bytecode')
            for index in range(270):
                (root / ('empty-' + str(index))).mkdir()
            long_parent = root
            for _ in range(5):
                long_parent /= 'long-' + 'a' * 175
            long_parent.mkdir(parents=True)
            long_directories = [long_parent / str(index) for index in range(240)]
            for directory in long_directories:
                directory.mkdir()
            (root / 'long-directories').write_text('\n'.join(map(str, long_directories)))
            artifact = root / 'synthetic-repository/org/opencv/opencv/4.12.0'
            artifact.mkdir(parents=True)
            with zipfile.ZipFile(artifact / 'opencv-4.12.0.aar', 'w') as aar:
                aar.writestr('classes.jar', classes.getvalue())
            (artifact / 'opencv-4.12.0.pom').write_text(
                '<project><modelVersion>4.0.0</modelVersion><groupId>org.opencv</groupId>'
                '<artifactId>opencv</artifactId><version>4.12.0</version><packaging>aar</packaging></project>')
            (root / 'app').mkdir()
            (root / 'sources').mkdir()
            (root / 'settings.gradle').write_text("rootProject.name='synthetic-lifecycle'; include ':app'\n")
            (root / 'app/build.gradle').write_text(BUILD)
            config = root / 'config.json'
            config.write_text(json.dumps({'sources': str(root / 'sources'), 'work': str(root / 'output'),
                                         'phase': 'prepare', 'sourceSha': 'synthetic-not-a-source-receipt',
                                         'selector': 'synthetic.NoExecution', 'observations': str(root / 'sources')}))
            # The predecessor realizes the host task in projectsEvaluated. Its
            # constructor traverses ordinary Test's configuration-backed inputs.
            eager = source.replace(
                "def ordinaryProvider=tasks.named('testDebugUnitTest')",
                "def ordinaryProvider=tasks.named('testDebugUnitTest'); ordinaryProvider.get()",
            ).replace(
                "def hostProvider=tasks.named('hostIntegrationTest')",
                "def hostProvider=tasks.named('hostIntegrationTest'); hostProvider.get()",
            )
            for name, script, missing, expected in [
                ('before', old, False, False), ('eager', eager, False, False),
                ('after', source, False, True),
                ('missing', source, True, False),
            ]:
                init = root / (name + '.init.gradle')
                init.write_text(script)
                args = [str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                        '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                        '-PeightConfig=' + str(config), ':app:inspectLifecycle']
                if missing:
                    args.append('-PomitOrdinary=true')
                result = subprocess.run(args, text=True, capture_output=True, timeout=120)
                log = result.stdout + result.stderr
                self.assertEqual(result.returncode == 0, expected, name + '\n' + log)
                if expected:
                    self.assertIn('SYNTHETIC_LIFECYCLE_PASS', log)
                elif name == 'eager':
                    self.assertIn("Current thread does not hold the state lock for project ':app'", log)
                else:
                    self.assertIn("Task with name 'testDebugUnitTest' not found", log)
                self.assertNotIn('compile must not execute', log)
                self.assertNotIn('SDK check must not execute', log)
                self.assertNotIn('oracle must not execute', log)
            # Normal graph construction, not just task realization in an action.
            init = root / 'after.init.gradle'
            for selected in ['eightPrepare', 'eightProbe']:
                result = subprocess.run([
                    str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                    '-PeightConfig=' + str(config), ':app:' + selected, '--dry-run',
                ], text=True, capture_output=True, timeout=120)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
                graph = json.loads((root / 'output/task-graph.json').read_text())
                expected = {':app:syntheticCompile', ':app:verifyHostIntegrationSdk', ':app:eightPrepare'}
                if selected == 'eightProbe':
                    expected.add(':app:eightProbe')
                self.assertEqual(set(graph['tasks']), expected)
                self.assertEqual(graph['externalNativeTasks'], [])
                self.assertNotIn('EIGHT_VIEW_PROVIDER_DIAGNOSTIC ', result.stdout)
            for case, count in [('zero', 0), ('duplicate', 2), ('directory', 0), ('truncated', 0), ('mismatch', None), ('late', 2), ('bytecap', 2)]:
                result = subprocess.run([
                    str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                    '-PeightConfig=' + str(config), '-PproviderCase=' + case,
                    ':app:eightProbe', '--dry-run',
                ], text=True, capture_output=True, timeout=120)
                log = result.stdout + result.stderr
                self.assertNotEqual(result.returncode, 0, log)
                marker = 'EIGHT_VIEW_PROVIDER_DIAGNOSTIC '
                if case == 'mismatch':
                    self.assertIn('OpenCV transformed provider differs', log)
                    self.assertNotIn(marker, log)
                    continue
                self.assertIn('exactly one existing OpenCV runtime provider expected', log)
                records = [line.removeprefix(marker) for line in log.splitlines() if line.startswith(marker)]
                self.assertEqual(len(records), 1, log)
                self.assertLess(len(records[0].encode('utf-8')), 256 * 1024)
                record = json.loads(records[0])
                self.assertEqual(record['matchingJarCount'], count)
                self.assertEqual(record['reason'], 'existing-opencv-provider-count')
                self.assertEqual(record['classpathEntryCount'], len(record['entries']) + record['entriesOmitted'])
                self.assertEqual(record['truncated'], case in ('truncated', 'late', 'bytecap'))
                self.assertRegex(record['expectedAar']['sha256'], r'^[0-9a-f]{64}$')
                self.assertRegex(record['expectedClasses']['sha256'], r'^[0-9a-f]{64}$')
                if case == 'duplicate':
                    self.assertEqual(len(record['entries']), 2)
                    for entry in record['entries']:
                        self.assertTrue(entry['matchesExpectedClassMap'])
                        self.assertEqual(entry['classMapSha256'], record['expectedClasses']['classMapSha256'])
                        self.assertEqual(entry['coreSha256'], record['expectedClasses']['coreSha256'])
                if case == 'directory':
                    entry = record['entries'][0]
                    self.assertEqual(entry['type'], 'directory')
                    self.assertTrue(entry['corePresent'])
                    self.assertFalse(entry['coreHashOmitted'])
                    self.assertEqual(entry['coreSha256'], record['expectedClasses']['coreSha256'])
                if case == 'truncated':
                    self.assertEqual(record['entriesOmitted'], 14)
                if case in ('late', 'bytecap'):
                    first = 270 if case == 'late' else 240
                    self.assertEqual([entry['index'] for entry in record['entries'][:3]], [first, first + 1, first + 2])
                    self.assertTrue(all(entry['matchesExpectedClassMap'] for entry in record['entries'][:2]))
                    self.assertEqual(record['entries'][2]['type'], 'directory')
                    self.assertTrue(record['entries'][2]['corePresent'])
                    self.assertEqual(record['matchingJarRowsOmitted'], 0)
                    self.assertEqual(record['directoryCoreRowsOmitted'], 0)
                    self.assertEqual(record['directoryCoreObservedCount'], 1)
                    self.assertGreater(record['entriesOmitted'], 0)
                    if case == 'bytecap':
                        # Fewer than256 original entries: truncation is byte-bound,
                        # and low-priority generic entries, not providers, were cut.
                        self.assertEqual(record['classpathEntryCount'], 243)
                        self.assertLess(len(record['entries']), 243)


if __name__ == '__main__':
    unittest.main()
