"""Real Gradle lifecycle regression; synthetic extension is not Android qualification."""
import hashlib
import io
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import types
import sys
from unittest.mock import patch
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

KSP_MODEL = '''
package com.google.devtools.ksp.gradle
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.*
import org.gradle.api.provider.Property
class KspAATask extends DefaultTask {
    private final SyntheticKspConfig config = project.objects.newInstance(SyntheticKspConfig)
    @Nested SyntheticKspConfig getKspConfig() { config }
    @TaskAction void generateNothing() {
        File output = kspConfig.resourceOutputDir.get()
        if (output.isDirectory() && output.list().length == 0) assert output.delete()
    }
}
abstract class SyntheticKspConfig {
    @OutputDirectory abstract Property<File> getResourceOutputDir()
}
'''

# Explicit synthetic task API model only; actual AGP serialization/Fs lives in separate fixture.
AGP_MODELS = {
'GenerateTestConfig.groovy': '''package com.android.build.gradle.tasks
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
abstract class GenerateTestConfig extends DefaultTask {
 @OutputDirectory abstract DirectoryProperty getOutputDirectory()
 @Internal abstract RegularFileProperty getProjectDir()
 @Internal Map testConfigInputs
 @TaskAction void generate() {
  def values=testConfigInputs.computeProperties(project.projectDir)
  def f=new File(outputDirectory.get().asFile,'com/android/tools/test_config.properties');f.parentFile.mkdirs()
  f.text='android_resource_apk='+values.resourceApkFile+'\\nandroid_merged_assets='+values.mergedAssetsDir+'\\nandroid_merged_manifest='+values.mergedManifestDir+'\\nandroid_custom_package='+values.customPackage+'\\n'
 }
}
''',
'PackageForHostTest.groovy': '''package com.android.build.gradle.internal.tasks
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
abstract class PackageForHostTest extends DefaultTask {
 @OutputFile abstract RegularFileProperty getApkForHostTest()
 @TaskAction void generate(){def f=apkForHostTest.get().asFile;f.parentFile.mkdirs();f.text='synthetic apk, not Android qualification'}
}
''',
'MergeSourceSetFolders.groovy': '''package com.android.build.gradle.tasks
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
abstract class MergeSourceSetFolders extends DefaultTask {
 @OutputDirectory abstract DirectoryProperty getOutputDir()
 @TaskAction void generate(){outputDir.get().asFile.mkdirs()}
}
''',
'ProcessPackagedManifestTask.groovy': '''package com.android.build.gradle.tasks
import org.gradle.api.DefaultTask
import org.gradle.api.file.*
import org.gradle.api.tasks.*
abstract class ProcessPackagedManifestTask extends DefaultTask {
 @OutputDirectory abstract DirectoryProperty getPackageManifests()
 @TaskAction void generate(){def f=new File(packageManifests.get().asFile,'AndroidManifest.xml');f.parentFile.mkdirs();f.text='<manifest package="synthetic.fixture"/>'}
}
'''}

BUILD = '''
buildscript { dependencies { classpath files(rootProject.file('synthetic-ksp-task.jar')) } }
import com.google.devtools.ksp.gradle.KspAATask
tasks.withType(Test).configureEach { testClassesDirs = files(); classpath = files() }
class SyntheticSourceDirectory {
    List<File> dirs = []
    void srcDir(File dir) { dirs.add(dir) }
}
def syntheticJava = new SyntheticSourceDirectory()
ext.android = [sourceSets: [test: [java: syntheticJava]],
               compileSdk: 35, buildToolsVersion: '35.0.0', ndkVersion: 'synthetic-not-installed']
configurations { syntheticRuntime; debugRuntimeClasspath }
repositories { maven { url = uri(rootProject.file(project.findProperty('providerRepository') ?: 'synthetic-repository')) } }
dependencies { debugRuntimeClasspath 'org.opencv:opencv:4.12.0@aar' }
afterEvaluate {
    def apkTask=tasks.register('packageDebugUnitTestForUnitTest',com.android.build.gradle.internal.tasks.PackageForHostTest) { apkForHostTest.set(layout.buildDirectory.file('synthetic-resources/resources.ap_')) }
    def assetsTask=tasks.register('mergeDebugAssets',com.android.build.gradle.tasks.MergeSourceSetFolders) { outputDir.set(layout.buildDirectory.dir('synthetic-resources/assets')) }
    def manifestTask=tasks.register('processDebugManifestForPackage',com.android.build.gradle.tasks.ProcessPackagedManifestTask) { packageManifests.set(layout.buildDirectory.dir('synthetic-resources/manifest')) }
    tasks.register('generateDebugUnitTestConfig',com.android.build.gradle.tasks.GenerateTestConfig) {
        dependsOn apkTask,assetsTask,manifestTask
        if (project.hasProperty('disableAgpConfig')) enabled=false
        if (project.hasProperty('skipAgpConfig')) onlyIf {false}
        outputDirectory.set(layout.buildDirectory.dir('intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out'));getProjectDir().set(project.projectDir)
        testConfigInputs=[resourceApk:apkTask.flatMap {it.apkForHostTest},mergedAssets:assetsTask.flatMap {it.outputDir},mergedManifest:manifestTask.flatMap {it.packageManifests},
          computeProperties:{File ignored -> [resourceApkFile:'build/synthetic-resources/resources.ap_',mergedAssetsDir:'build/synthetic-resources/assets',mergedManifestDir:'build/synthetic-resources/manifest/AndroidManifest.xml',customPackage:'synthetic.fixture']}]
    }
    tasks.register('kspDebugUnitTestKotlin', project.hasProperty('wrongKspType') ? DefaultTask : KspAATask) {
        if (project.hasProperty('wrongKspType')) {
            def target = layout.buildDirectory.dir('generated/ksp/debugUnitTest/resources').get().asFile
            outputs.dir(target)
            doLast { if (target.isDirectory() && target.list().length == 0) assert target.delete() }
        } else kspConfig.resourceOutputDir.set(layout.buildDirectory.dir(project.hasProperty('wrongKspOutput') ? 'wrong-ksp-output' : 'generated/ksp/debugUnitTest/resources').get().asFile)
        if (project.hasProperty('skipKsp')) onlyIf { false }
        if (project.hasProperty('disableKsp')) enabled = false
    }
    tasks.register('compileDebugUnitTestJavaWithJavac', JavaCompile) {
        source = files()
        classpath = files()
        destinationDirectory = layout.buildDirectory.dir(project.hasProperty('wrongJavacOutput') ? 'wrong-javac-output' : 'intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes')
        if (project.hasProperty('skipJavac')) onlyIf { false }
    }
    tasks.register('syntheticCompile') { doLast { if (!project.hasProperty('allowSyntheticPreparation')) throw new GradleException('compile must not execute') } }
    if (!project.hasProperty('omitOrdinary')) {
        tasks.register('testDebugUnitTest', Test) {
            dependsOn tasks.named('syntheticCompile'), tasks.named('generateDebugUnitTestConfig')
            // Match AGP's configuration-backed implicit Test classpath dependencies.
            def providerCase = project.findProperty('providerCase') ?: 'single'
            def providers = [rootProject.file('synthetic-classes.jar')]
            if (providerCase == 'single-runtime') providers = [rootProject.file('synthetic-runtime.jar')]
            if (providerCase == 'zero') providers = [rootProject.file('synthetic-empty.jar')]
            if (providerCase == 'duplicate') providers += rootProject.file('synthetic-copy.jar')
            if (providerCase == 'directory') providers = [rootProject.file('synthetic-directory')]
            if (providerCase == 'mismatch') providers = [rootProject.file('synthetic-mismatch.jar')]
            if (providerCase == 'truncated') providers = (0..<270).collect { rootProject.file('empty-' + it) }
            if (providerCase == 'late') providers = (0..<270).collect { rootProject.file('empty-' + it) } +
                [rootProject.file('synthetic-classes.jar'), rootProject.file('synthetic-copy.jar'), rootProject.file('synthetic-directory')]
            if (providerCase == 'bytecap') providers = rootProject.file('long-directories').readLines().collect { file(it) } +
                [rootProject.file('synthetic-classes.jar'), rootProject.file('synthetic-copy.jar'), rootProject.file('synthetic-directory')]
            if (providerCase in ['agp', 'third', 'runtime-resource', 'signed', 'unsafe', 'symlink-entry', 'mr', 'loader-manifest', 'reverse', 'changed-resources', 'partial', 'partial-directory', 'duplicate-entry', 'oversized']) {
                def runtimeName = providerCase in ['runtime-resource', 'signed', 'unsafe', 'symlink-entry', 'mr', 'loader-manifest', 'duplicate-entry', 'oversized'] ? providerCase : 'runtime'
                providers = [rootProject.file('synthetic-' + runtimeName + '.jar'), rootProject.file('synthetic-middle.jar'), rootProject.file(providerCase == 'changed-resources' ? 'synthetic-changed-resources.jar' : 'synthetic-classes.jar')]
                if (providerCase == 'partial') providers += rootProject.file('synthetic-partial.jar')
                if (providerCase == 'partial-directory') providers += rootProject.file('synthetic-partial-directory')
                if (providerCase == 'third') providers += rootProject.file('synthetic-copy.jar')
                if (providerCase == 'reverse') providers = providers.reverse()
            }
            if (providerCase in ['agp-empty', 'agp-directories']) providers = [rootProject.file('synthetic-runtime.jar'),
                rootProject.file('synthetic-middle.jar'), rootProject.file(providerCase + '-source.jar')]
            if (project.hasProperty('includeAbsence')) {
                if (!project.hasProperty('omitAbsentDependencies')) dependsOn tasks.named('kspDebugUnitTestKotlin'), tasks.named('compileDebugUnitTestJavaWithJavac')
                providers.add(1, layout.buildDirectory.file('generated/ksp/debugUnitTest/resources').get().asFile)
                providers.add(3, layout.buildDirectory.file('intermediates/javac/debugUnitTest/compileDebugUnitTestJavaWithJavac/classes').get().asFile)
            }
            if (project.hasProperty('unclassifiedMissing')) providers += layout.buildDirectory.file('not-an-admitted-output').get().asFile
            classpath = files(providers, configurations.syntheticRuntime,layout.buildDirectory.dir('intermediates/unit_test_config_directory/debugUnitTest/generateDebugUnitTestConfig/out'))
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
    tasks.register('verifyHostIntegrationSdk') { doLast { if (!project.hasProperty('allowSyntheticPreparation')) throw new GradleException('SDK check must not execute') } }
}
gradle.taskGraph.whenReady {
    if (project.hasProperty('appearanceMode')) tasks.getByName('eightPrepare').doFirst {
        def target = layout.buildDirectory.file('generated/ksp/debugUnitTest/resources').get().asFile.toPath()
        def mode = project.property('appearanceMode')
        if (mode == 'directory') java.nio.file.Files.createDirectory(target)
        if (mode == 'file') java.nio.file.Files.write(target, 'appeared'.bytes)
        if (mode == 'symlink') {
            def safe = rootProject.file('empty-symlink-target');safe.mkdirs()
            java.nio.file.Files.createSymbolicLink(target, safe.toPath())
        }
    }
}
tasks.register('inspectNormalization') {
    dependsOn 'generateDebugUnitTestConfig'
    doLast {
        def original = tasks.getByName('hostIntegrationTest').classpath.files*.canonicalPath
        def effective = tasks.getByName('eightProbe').classpath.files*.canonicalPath
        rootProject.file('classpath-observation.json').text = groovy.json.JsonOutput.toJson([original:original,effective:effective])
    }
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
            ['syntheticCompile', 'verifyHostIntegrationSdk', 'generateDebugUnitTestConfig'].toSet()
        assert tasks.getByName('testDebugUnitTest').taskDependencies
            .getDependencies(tasks.getByName('testDebugUnitTest'))*.name .toSet() == ['syntheticCompile', 'generateDebugUnitTestConfig'].toSet()
        assert tasks.getByName('hostIntegrationTest').taskDependencies
            .getDependencies(tasks.getByName('hostIntegrationTest'))*.name.toSet() ==
            ['syntheticCompile', 'syntheticOracle', 'generateDebugUnitTestConfig'].toSet()
        println 'SYNTHETIC_LIFECYCLE_PASS'
    }
}
'''


HARNESS = r"""import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
public class Harness {
 static URL[] urls(String text) throws Exception {
  var values=new ArrayList<URL>();for(String p:text.split(java.io.File.pathSeparator)) values.add(new java.io.File(p).toURI().toURL());return values.toArray(URL[]::new);
 }
 static List<String> resources(ClassLoader loader,String name) throws Exception {
  var values=new ArrayList<String>();var found=loader.getResources(name);
  while(found.hasMoreElements())try(var input=found.nextElement().openStream()){values.add(Base64.getEncoder().encodeToString(input.readAllBytes()));}
  return values;
 }
 public static void main(String[] args) throws Exception {
  try(var before=new URLClassLoader(urls(args[0]),null);var after=new URLClassLoader(urls(args[1]),null)) {
   for(String name:List.of("shared.txt","META-INF/services/example.Service","META-INF/MANIFEST.MF","assets/","assets/only.txt"))
    if(!resources(before,name).equals(resources(after,name)))throw new AssertionError("Resource contents/order/multiplicity changed: "+name);
   var shared=resources(after,"shared.txt");
   if(!shared.equals(List.of(Base64.getEncoder().encodeToString("middle".getBytes(StandardCharsets.UTF_8)),Base64.getEncoder().encodeToString("opencv".getBytes(StandardCharsets.UTF_8)))))throw new AssertionError("Interleaved resource precedence changed");
   var old=Class.forName("org.opencv.core.Core",true,before);var now=Class.forName("org.opencv.core.Core",true,after);
   if(!old.getMethod("identity").invoke(null).equals(now.getMethod("identity").invoke(null)))throw new AssertionError("Class identity changed");
   if(Collections.list(before.getResources("org/opencv/core/Core.class")).size()!=2 || Collections.list(after.getResources("org/opencv/core/Core.class")).size()!=1)throw new AssertionError("Class-resource normalization failed");
   System.out.println("SYNTHETIC_JAVA_CLASS_AND_RESOURCE_PASS");
  }
 }
}
"""


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
            # Compile a minimal synthetic Java class, never a real OpenCV runtime.
            java = shutil.which('java')
            self.assertIsNotNone(java)
            java_source = root / 'java-src/org/opencv/core/Core.java'
            java_source.parent.mkdir(parents=True)
            java_source.write_text('package org.opencv.core; public class Core { public static String identity() { return "synthetic-core"; } }')
            java_sources = [java_source]
            for package, name in [('org.opencv.android', 'OpenCVLoader'), ('org.opencv.android', 'StaticHelper'),
                                  ('org.opencv.core', 'Mat'), ('com.realityforge.scanner.engine', 'NativeCore'), ('com.realityforge.scanner.export', 'NativeAssetExports')]:
                source_file = root / 'java-src' / package.replace('.', '/') / (name + '.java')
                source_file.parent.mkdir(parents=True, exist_ok=True)
                source_file.write_text('package ' + package + '; public class ' + name + ' {}')
                java_sources.append(source_file)
            compiled = root / 'compiled'
            compiled.mkdir()
            compile_result = subprocess.run([java, '-m', 'jdk.compiler/com.sun.tools.javac.Main',
                                             '-d', str(compiled), *map(str, java_sources)], capture_output=True, text=True, timeout=30)
            self.assertEqual(compile_result.returncode, 0, compile_result.stdout + compile_result.stderr)
            core_bytes = (compiled / 'org/opencv/core/Core.class').read_bytes()
            cv_classes = {path.relative_to(compiled).as_posix(): path.read_bytes() for path in sorted((compiled / 'org/opencv').rglob('*.class'))}
            def write_cv(jar):
                for name, value in cv_classes.items():
                    jar.writestr(name, value)
            classes = io.BytesIO()
            resource_entries = [('META-INF/', b''), ('META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\r\nCreated-By: synthetic\r\n\r\n'),
                                ('shared.txt', b'opencv'), ('META-INF/services/example.Service', b'example.Synthetic\n'),
                                ('assets/', b''), ('assets/only.txt', b'preserve')]
            with zipfile.ZipFile(classes, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
                write_cv(jar)
                for name, value in resource_entries:
                    jar.writestr(name, value)
            (root / 'synthetic-classes.jar').write_bytes(classes.getvalue())
            (root / 'synthetic-copy.jar').write_bytes(classes.getvalue())
            with zipfile.ZipFile(root / 'synthetic-runtime.jar', 'w') as jar:
                write_cv(jar)
            with zipfile.ZipFile(root / 'synthetic-middle.jar', 'w') as jar:
                jar.writestr('shared.txt', b'middle')
                jar.writestr('com/realityforge/scanner/engine/NativeCore.class', (compiled / 'com/realityforge/scanner/engine/NativeCore.class').read_bytes())
                jar.writestr('com/realityforge/scanner/export/NativeAssetExports.class', (compiled / 'com/realityforge/scanner/export/NativeAssetExports.class').read_bytes())
            with zipfile.ZipFile(root / 'synthetic-empty.jar', 'w') as jar:
                jar.writestr('unrelated.txt', b'not-a-provider')
            with zipfile.ZipFile(root / 'synthetic-mismatch.jar', 'w') as jar:
                jar.writestr('org/opencv/core/Core.class', b'different-synthetic-bytes')
            with zipfile.ZipFile(root / 'synthetic-changed-resources.jar', 'w') as jar:
                write_cv(jar)
                for name, value in resource_entries:
                    jar.writestr(name, b'changed' if name == 'shared.txt' else value)
            for case, name, value in [('runtime-resource', 'unexpected.txt', b'no'),
                                      ('signed', 'META-INF/TEST.SF', b'no'), ('unsafe', '../outside', b'no'),
                                      ('mr', 'META-INF/versions/9/extra.txt', b'no'),
                                      ('loader-manifest', 'META-INF/MANIFEST.MF', b'Manifest-Version: 1.0\r\nClass-Path: other.jar\r\n\r\n')]:
                with zipfile.ZipFile(root / ('synthetic-' + case + '.jar'), 'w') as jar:
                    write_cv(jar)
                    jar.writestr(name, value)
            with zipfile.ZipFile(root / 'synthetic-symlink-entry.jar', 'w') as jar:
                write_cv(jar)
                link = zipfile.ZipInfo('link');link.create_system = 3;link.external_attr = 0o120777 << 16
                jar.writestr(link, b'target')
            with zipfile.ZipFile(root / 'synthetic-partial.jar', 'w') as jar:
                jar.writestr('org/opencv/core/Mat.class', cv_classes['org/opencv/core/Mat.class'])
            with zipfile.ZipFile(root / 'synthetic-duplicate-entry.jar', 'w') as jar:
                write_cv(jar)
                jar.writestr('org/opencv/core/Core.class', core_bytes)
            with zipfile.ZipFile(root / 'synthetic-oversized.jar', 'w', compression=zipfile.ZIP_DEFLATED) as jar:
                write_cv(jar)
                jar.writestr('oversized.txt', b'x' * (16 * 1024 * 1024 + 1))
            partial = root / 'synthetic-partial-directory/org/opencv/core/Mat.class'
            partial.parent.mkdir(parents=True)
            partial.write_bytes(cv_classes['org/opencv/core/Mat.class'])
            core = root / 'synthetic-directory/org/opencv/core/Core.class'
            core.parent.mkdir(parents=True)
            core.write_bytes(core_bytes)
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
            policy = root / 'tools/eight_view_host/build-policy.json'
            policy.parent.mkdir(parents=True)
            policy.write_text(json.dumps({'opencvAarSha256': hashlib.sha256((artifact / 'opencv-4.12.0.aar').read_bytes()).hexdigest()}))
            # Compile a synthetic model against bundled Gradle APIs and load via
            # Gradle's tracked buildscript classpath, never an unknown loader.
            model_source = root / 'SyntheticKsp.groovy'
            model_source.write_text(KSP_MODEL)
            model_sources=[model_source]
            for name,contents in AGP_MODELS.items():
                path=root/name;path.write_text(contents);model_sources.append(path)
            model_classes = root / 'model-classes'
            model_classes.mkdir()
            model_compile = subprocess.run([java, '-cp', str(Path(home) / 'lib/*'),
                'org.codehaus.groovy.tools.FileSystemCompiler', '-d', str(model_classes), *map(str,model_sources)],
                text=True, capture_output=True, timeout=30)
            self.assertEqual(model_compile.returncode, 0, model_compile.stdout + model_compile.stderr)
            with zipfile.ZipFile(root / 'synthetic-ksp-task.jar', 'w') as model_jar:
                for model_class in sorted(model_classes.rglob('*.class')):
                    model_jar.write(model_class, model_class.relative_to(model_classes).as_posix())
            (root / 'app/build').mkdir(parents=True)
            (root / 'sources').mkdir()
            (root / 'settings.gradle').write_text("rootProject.name='synthetic-lifecycle'; include ':app'\n")
            (root / 'app/build.gradle').write_text(BUILD)
            config = root / 'config.json'
            config.write_text(json.dumps({'sources': str(root / 'sources'), 'work': str(root / 'output'),
                                         'phase': 'prepare', 'sourceSha': 'a' * 40,
                                         'experiment':'eight-cuboid', 'selector': 'com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure', 'observations': str(root / 'sources')}))
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
                expected = {':app:syntheticCompile', ':app:verifyHostIntegrationSdk', ':app:eightPrepare', ':app:generateDebugUnitTestConfig', ':app:packageDebugUnitTestForUnitTest', ':app:mergeDebugAssets', ':app:processDebugManifestForPackage'}
                if selected == 'eightProbe':
                    expected.add(':app:eightProbe')
                self.assertEqual(set(graph['tasks']), expected)
                self.assertEqual(graph['externalNativeTasks'], [])
                self.assertFalse((root/'output/agp-test-config').exists())
                self.assertFalse((root/'output/agp-original-test-config.properties').exists())
                self.assertNotIn(':app:testDebugUnitTest',graph['tasks'])
                self.assertNotIn(':app:hostIntegrationTest',graph['tasks'])
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
                self.assertIn('Ambiguous source-AAR resource role' if count == 2 else 'One equivalent provider or exact AGP two-role pair required', log)
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
                    self.assertEqual(len(record['entries']), 3)
                    self.assertTrue(record['entries'][2]['path'].endswith('generateDebugUnitTestConfig/out'))
                    for entry in record['entries'][:2]:
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
                    self.assertEqual(record['entriesOmitted'], 15)
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
                        self.assertEqual(record['classpathEntryCount'], 244)
                        self.assertLess(len(record['entries']), 243)

            # Execute the unchanged producer under actual Gradle, then load actual
            # Java bytecode/resources using two isolated URLClassLoaders.
            def normalization_run(case):
                return subprocess.run([
                    str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                    '-PeightConfig=' + str(config), '-PproviderCase=' + case, ':app:inspectNormalization',
                ], text=True, capture_output=True, timeout=120)
            single_runtime = normalization_run('single-runtime')
            self.assertEqual(single_runtime.returncode, 0, single_runtime.stdout + single_runtime.stderr)
            single_paths = json.loads((root / 'classpath-observation.json').read_text())
            self.assertEqual(single_paths['original'][:-1], single_paths['effective'][:-1])
            self.assertEqual(Path(single_paths['effective'][-1]).name,'agp-test-config')
            self.assertNotIn('EIGHT_VIEW_PROVIDER_DIAGNOSTIC ', single_runtime.stdout)
            valid = normalization_run('agp')
            self.assertEqual(valid.returncode, 0, valid.stdout + valid.stderr)
            paths = json.loads((root / 'classpath-observation.json').read_text())
            self.assertEqual(paths['effective'][:2], paths['original'][:2])
            self.assertEqual(len(paths['effective']), len(paths['original']))
            projection = Path(paths['effective'][2])
            self.assertEqual(projection.name, 'opencv-resources-only.jar')
            with zipfile.ZipFile(projection) as jar:
                self.assertEqual([(entry.filename, jar.read(entry)) for entry in jar.infolist()], resource_entries)
                self.assertFalse(any(name.endswith('.class') for name in jar.namelist()))
            harness = root / 'Harness.java'
            harness.write_text(HARNESS)
            loaded = subprocess.run([java, str(harness), os.pathsep.join(paths['original']), os.pathsep.join(paths['effective'])],
                                    text=True, capture_output=True, timeout=30)
            self.assertEqual(loaded.returncode, 0, loaded.stdout + loaded.stderr)
            self.assertIn('SYNTHETIC_JAVA_CLASS_AND_RESOURCE_PASS', loaded.stdout)
            prepared_run = subprocess.run([
                str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                '-PeightConfig=' + str(config), '-PproviderCase=agp', '-PallowSyntheticPreparation=true', ':app:eightPrepare',
            ], text=True, capture_output=True, timeout=120)
            self.assertEqual(prepared_run.returncode, 0, prepared_run.stdout + prepared_run.stderr)
            prepared_record = json.loads((root / 'output/prepared.json').read_text())
            self.assertEqual(prepared_record['schema'], 4)
            self.assertEqual(prepared_record['opencvNormalization']['mode'], 'agp-two-role-resource-projection')
            self.assertEqual(set(prepared_record['owners']), {'org.opencv.android.OpenCVLoader', 'org.opencv.android.StaticHelper',
                                                              'org.opencv.core.Core', 'org.opencv.core.Mat', 'com.realityforge.scanner.engine.NativeCore', 'com.realityforge.scanner.export.NativeAssetExports'})
            sys.path.insert(0, str(ROOT))
            from tools.eight_view_host import controller as ctl
            with patch.object(ctl.capability, 'POLICY', policy):
                ctl.normalization_check(prepared_record,types.SimpleNamespace(root=root,work=root/'output',remaining=lambda maximum,**kwargs: maximum))
                ctl.classpath_check(prepared_record,types.SimpleNamespace(root=root,work=root/'output',remaining=lambda maximum,**kwargs: maximum))
            evidence = os.environ.get('RF_GRADLE_EVIDENCE_DIR')
            if evidence:
                destination = Path(evidence)
                destination.mkdir(parents=True, exist_ok=True)
                (destination / 'synthetic-prepared.json').write_text(json.dumps(prepared_record, indent=2))
            projection.write_bytes(b'tampered')
            tampered = normalization_run('agp')
            self.assertNotEqual(tampered.returncode, 0)
            self.assertIn('Derived archive changed', tampered.stdout + tampered.stderr)
            projection.unlink()
            negative = {'third': 'One equivalent provider or exact AGP two-role pair required',
                        'runtime-resource': 'Runtime class role has non-class payload',
                        'signed': 'Unsupported signed/multi-release archive',
                        'unsafe': 'Unsafe ZIP path', 'symlink-entry': 'ZIP symlink/special entry',
                        'mr': 'Unsupported signed/multi-release archive',
                        'loader-manifest': 'Unsupported loader-affecting manifest',
                        'reverse': 'Unsupported reversed AGP roles',
                        'changed-resources': 'Ambiguous source-AAR resource role',
                        'partial': 'OpenCV transformed provider differs',
                        'partial-directory': 'Unclassified canonical class directory provider',
                        'duplicate-entry': 'Duplicate ZIP entry', 'oversized': 'ZIP entry byte limit'}
            for case, error in negative.items():
                failed = normalization_run(case)
                self.assertNotEqual(failed.returncode, 0, failed.stdout + failed.stderr)
                self.assertIn(error, failed.stdout + failed.stderr)
            # Re-enter actual preparation in run phase against the immutable
            # producer receipt, then prove original-provider byte drift refuses.
            cfg = json.loads(config.read_text())
            cfg.update(phase='run', preparedSha256=hashlib.sha256((root / 'output/prepared.json').read_bytes()).hexdigest())
            config.write_text(json.dumps(cfg))
            runtime_args = [str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                            '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                            '-PeightConfig=' + str(config), '-PproviderCase=agp', '-PallowSyntheticPreparation=true', ':app:eightPrepare']
            stable = subprocess.run(runtime_args, text=True, capture_output=True, timeout=120)
            self.assertEqual(stable.returncode, 0, stable.stdout + stable.stderr)
            self.assertEqual(json.loads((root / 'output/runtime-prepare.json').read_text()), prepared_record)
            with zipfile.ZipFile(root / 'synthetic-runtime.jar', 'a') as jar:
                jar.comment = b'original-provider-byte-drift'
            drift = subprocess.run(runtime_args, text=True, capture_output=True, timeout=120)
            self.assertNotEqual(drift.returncode, 0)
            self.assertIn('actual ordered runtime classpath/owners differ from baseline', drift.stdout + drift.stderr)
            # The observed real AAR's resource inventory was not captured. Empty
            # and directory-only projections must therefore be tested explicitly.
            for case, markers in [('agp-empty', []), ('agp-directories', ['META-INF/', 'assets/'])]:
                source_jar = root / (case + '-source.jar')
                with zipfile.ZipFile(source_jar, 'w', compression=zipfile.ZIP_DEFLATED) as jar:
                    write_cv(jar)
                    for marker in markers:
                        jar.writestr(marker, b'')
                repository = root / (case + '-repository')
                variant_artifact = repository / 'org/opencv/opencv/4.12.0'
                variant_artifact.mkdir(parents=True)
                variant_aar = variant_artifact / 'opencv-4.12.0.aar'
                with zipfile.ZipFile(variant_aar, 'w') as aar:
                    aar.writestr('classes.jar', source_jar.read_bytes())
                shutil.copyfile(artifact / 'opencv-4.12.0.pom', variant_artifact / 'opencv-4.12.0.pom')
                policy.write_text(json.dumps({'opencvAarSha256': hashlib.sha256(variant_aar.read_bytes()).hexdigest()}))
                variant_output = root / (case + '-output')
                variant_output.mkdir()
                variant_config = root / (case + '-config.json')
                variant_config.write_text(json.dumps({'schema': 1, 'phase': 'prepare', 'sourceSha': 'a' * 40,
                    'sources': str(root / 'sources'), 'work': str(variant_output),
                    'experiment':'eight-cuboid', 'selector': 'com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure', 'observations': str(root / 'sources')}))
                variant_run = subprocess.run([
                    str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                    '-PeightConfig=' + str(variant_config), '-PproviderCase=' + case,
                    '-PproviderRepository=' + repository.name, '-PallowSyntheticPreparation=true', ':app:eightPrepare',
                ], text=True, capture_output=True, timeout=120)
                self.assertEqual(variant_run.returncode, 0, variant_run.stdout + variant_run.stderr)
                variant_record = json.loads((variant_output / 'prepared.json').read_text())
                projected = variant_record['opencvNormalization']['projection']
                self.assertEqual([entry['name'] for entry in projected['entries']], markers)
                self.assertTrue(all(entry['directory'] and entry['bytes'] == 0 for entry in projected['entries']))
                if not markers:
                    self.assertEqual(projected['bytes'], 22)  # Valid empty ZIP, no fabricated resource.
                with patch.object(ctl.capability, 'POLICY', policy):
                    ctl.normalization_check(variant_record,types.SimpleNamespace(root=root,work=variant_output,remaining=lambda maximum,**kwargs: maximum))
                    ctl.classpath_check(variant_record,types.SimpleNamespace(root=root,work=variant_output,remaining=lambda maximum,**kwargs: maximum))



            # Optional outputs remain in exactly their original classpath slots.
            # KSP is a declared-output synthetic model; JavaCompile is Gradle's
            # real built-in task and must report actual NO-SOURCE.
            policy.write_text(json.dumps({'opencvAarSha256': hashlib.sha256((artifact / 'opencv-4.12.0.aar').read_bytes()).hexdigest()}))
            authority = types.SimpleNamespace(root=root, work=root/'bound-absence', remaining=lambda maximum, **kwargs: maximum)
            def absent_run(name, extra=(), phase='prepare', prepared_sha=None):
                work = root / name
                work.mkdir(exist_ok=True)
                cfg = {'schema': 1, 'phase': phase, 'sourceSha': 'a' * 40,
                       'sources': str(root / 'sources'), 'work': str(work),
                       'experiment':'eight-cuboid', 'selector': 'com.realityforge.scanner.hostintegration.EightViewSparsePrefixHostTest.fixedEightPhotosPersistSparseOrRecordUnmodifiedProductionFailure', 'observations': str(root / 'sources')}
                if prepared_sha:
                    cfg['preparedSha256'] = prepared_sha
                config_path = root / (name + '.json')
                config_path.write_text(json.dumps(cfg))
                result = subprocess.run([
                    str(gradle), '--offline', '--no-daemon', '--console=plain', '--max-workers=1',
                    '-g', str(root / 'gradle-home'), '-p', str(root), '-I', str(init),
                    '-PeightConfig=' + str(config_path), '-PproviderCase=agp', '-PincludeAbsence=true',
                    '-PallowSyntheticPreparation=true', *extra, ':app:eightPrepare',
                ], text=True, capture_output=True, timeout=120)
                return result, work
            absent_result, absent_work = absent_run('bound-absence')
            self.assertEqual(absent_result.returncode, 0, absent_result.stdout + absent_result.stderr)
            self.assertIn(':app:compileDebugUnitTestJavaWithJavac NO-SOURCE', absent_result.stdout)
            bound = json.loads((absent_work / 'prepared.json').read_text())
            self.assertEqual(bound['schema'], 4)
            self.assertEqual([entry['kind'] for entry in bound['classpath']],
                             ['jar', 'absent-declared-directory', 'jar', 'absent-declared-directory', 'jar', 'directory'])
            self.assertEqual(bound['classpathString'], os.pathsep.join(entry['path'] for entry in bound['classpath']))
            absent_entries = [bound['classpath'][1], bound['classpath'][3]]
            for entry in absent_entries:
                self.assertFalse(Path(entry['path']).exists())
                self.assertEqual(entry['absence']['ancestors'][0]['path'], '/')
                self.assertEqual(entry['absence']['root'], str(root / 'app/build'))
                self.assertTrue(entry['absence']['firstMissing'].startswith(str(root / 'app/build') + '/'))
            self.assertEqual(absent_entries[1]['producer']['admission'], 'java-no-source-output')
            with patch.object(ctl.capability, 'POLICY', policy):
                ctl.classpath_check(bound, authority)
                ctl.normalization_check(bound, authority)
            loaded_absent = subprocess.run([java, str(harness), os.pathsep.join(paths['original']), bound['classpathString']],
                                           text=True, capture_output=True, timeout=30)
            self.assertEqual(loaded_absent.returncode, 0, loaded_absent.stdout + loaded_absent.stderr)
            self.assertIn('SYNTHETIC_JAVA_CLASS_AND_RESOURCE_PASS', loaded_absent.stdout)
            javap = subprocess.run([java, '-m', 'jdk.jdeps/com.sun.tools.javap.Main', '-classpath', bound['classpathString'],
                                    'org.opencv.core.Core'], text=True, capture_output=True, timeout=30)
            self.assertEqual(javap.returncode, 0, javap.stdout + javap.stderr)
            self.assertIn('identity()', javap.stdout)
            prepared_sha = hashlib.sha256((absent_work / 'prepared.json').read_bytes()).hexdigest()
            absent_stable, _ = absent_run('bound-absence', phase='run', prepared_sha=prepared_sha)
            self.assertEqual(absent_stable.returncode, 0, absent_stable.stdout + absent_stable.stderr)
            self.assertEqual(json.loads((absent_work / 'runtime-prepare.json').read_text()), bound)
            if evidence:
                (Path(evidence) / 'synthetic-bound-absence.json').write_text(json.dumps(bound, indent=2))
            for mode in ('file', 'directory', 'symlink'):
                appeared, _ = absent_run('bound-absence', ['-PappearanceMode=' + mode], phase='run', prepared_sha=prepared_sha)
                self.assertNotEqual(appeared.returncode, 0, appeared.stdout + appeared.stderr)
                target = Path(absent_entries[0]['path'])
                with patch.object(ctl.capability, 'POLICY', policy), self.assertRaises(ctl.Failure):
                    ctl.classpath_check(bound, authority)
                if target.is_symlink() or target.is_file():
                    target.unlink()
                else:
                    target.rmdir()
            with patch.object(ctl.capability, 'POLICY', policy):
                ctl.classpath_check(bound, authority)

            # Producer-configuration refusal cases run after the baseline window;
            # changing declared outputs is not expected to preserve old ancestors.
            for flag, error in [('disableAgpConfig','AGP resource producer did not complete'),
                                ('skipAgpConfig','AGP resource producer arbitrary skip'),
                                ('skipJavac', 'requires actual NO-SOURCE'),
                                ('skipKsp', 'arbitrary skipped task'), ('disableKsp', 'did not complete successfully'),
                                ('omitAbsentDependencies', 'did not complete successfully'),
                                ('wrongKspOutput', 'declared property/path differs'),
                                ('wrongJavacOutput', 'declared property/path differs'),
                                ('wrongKspType', 'com.google.devtools.ksp.gradle.KspAATask'),
                                ('unclassifiedMissing', 'Undeclared absent classpath directory')]:
                rejected, _ = absent_run('refuse-' + flag, ['-P' + flag + '=true'])
                self.assertNotEqual(rejected.returncode, 0, rejected.stdout + rejected.stderr)
                self.assertIn(error, rejected.stdout + rejected.stderr)


if __name__ == '__main__':
    unittest.main()
