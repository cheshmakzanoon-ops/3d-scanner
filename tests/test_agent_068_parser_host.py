"""Optional host execution of real Android parser, using the pinned Kotlin compiler.

ANDROID/DEVICE remains a separate required gate. Set RF_ANDROID_JSON_JAR to the
Maven android-json 0.0.20131108.vaadin1 artifact (host test only, never app shipped).
The standalone driver runs without mocked Android JSON or bitmap decoders.
"""
import os
from pathlib import Path
import subprocess
import pytest

ROOT = Path(__file__).resolve().parents[1]


def test_real_parser_corpus_on_host(tmp_path):
    cache = Path(os.environ.get('GRADLE_USER_HOME', '/workspace/toolchains/gradle-user'))/'caches/modules-2/files-2.1'
    json_jar = Path(os.environ.get('RF_ANDROID_JSON_JAR', '/workspace/campaign-061-080/android-json.jar'))
    if not cache.is_dir() or not json_jar.is_file():
        pytest.skip('Requires Android/Kotlin setup and real android-json host artifact; run Android corpus otherwise.')
    def jar(group, name, version):
        matches = list((cache/group/name/version).glob('*/*.jar'))
        if len(matches) != 1:
            pytest.skip(f'Requires pinned {name} {version} in Gradle cache.')
        return str(matches[0])
    compiler = [jar('org.jetbrains.kotlin','kotlin-compiler-embeddable','2.1.21'),
                jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'),
                jar('org.jetbrains.kotlin','kotlin-script-runtime','2.1.21'),
                jar('org.jetbrains.kotlin','kotlin-reflect','1.6.10'),
                jar('org.jetbrains.kotlin','kotlin-daemon-embeddable','2.1.21'),
                jar('org.jetbrains.intellij.deps','trove4j','1.0.20200330'),
                jar('org.jetbrains','annotations','13.0'),
                jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.8.0')]
    runtime = [str(json_jar), jar('org.jetbrains.kotlin','kotlin-stdlib','2.1.21'),
               jar('org.jetbrains.kotlinx','kotlinx-coroutines-core-jvm','1.10.2'),
               jar('org.jetbrains','annotations','13.0')]
    classes = tmp_path/'classes'; classes.mkdir()
    viewer = ROOT/'app/src/main/java/com/realityforge/scanner/viewer'
    files = [viewer/'PhotographicGlb.kt',viewer/'loading/GlbStructure.kt',viewer/'loading/PhotographicModelLoader.kt']
    subprocess.run(['java','-cp',os.pathsep.join(compiler),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                    '-no-stdlib','-no-reflect','-classpath',os.pathsep.join(runtime),'-jvm-target','17','-d',str(classes),*map(str,files)],check=True)
    runtime.append(str(classes))
    source = ROOT/'app/src/androidTest/java/com/realityforge/scanner/agents/a068/GlbParserHostHarness.java'
    subprocess.run(['javac','-classpath',os.pathsep.join(runtime),'-d',str(classes),str(source)],check=True)
    args = []
    if os.environ.get('RF_AGENT061_GLB_FIXTURES'):
        args.append(os.environ['RF_AGENT061_GLB_FIXTURES'])
    subprocess.run(['java','-Xmx256m','-cp',os.pathsep.join(runtime),'com.realityforge.scanner.agents.a068.GlbParserHostHarness',*args],check=True)
