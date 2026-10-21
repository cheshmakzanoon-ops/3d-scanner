#!/usr/bin/env python3
"""Opt-in plain-JVM wrapper validation. Never downloads or builds native artifacts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import uuid

SOURCES = ['NativeCore.kt', 'IncrementalRegistration.kt', 'registration/RegistrationFrontier.kt',
           'NativeFrontierGeometry.kt', 'FinalSparseExecution.kt', 'FinalSparsePublication.kt',
           'BoundedSparseCoordinator.kt', 'TypedBundleAdapter.kt', 'LensGeometry.kt',
           'MultiViewTracks.kt', 'tracks/TrackSnapshot.kt', 'nativebridge/NativeResultJson.kt',
           'pipeline/NativeStageProgress.kt']
JARS = [('org.jetbrains.kotlin', 'kotlin-stdlib', '2.1.21'),
        ('org.jetbrains.kotlinx', 'kotlinx-coroutines-core-jvm', '1.10.2'),
        ('org.json', 'json', '20250517'), ('junit', 'junit', '4.13.2'),
        ('org.hamcrest', 'hamcrest-core', '1.3'), ('org.jetbrains', 'annotations', '13.0')]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def tree_hashes(root):
    """Bind every consumed tree file, including module metadata, not only engine classes."""
    root = Path(root)
    if not root.is_dir():
        raise ValueError('Missing tree: ' + str(root))
    result = {}
    for p in sorted(root.rglob('*')):
        if p.is_symlink():
            raise ValueError('Symlink in input/output tree: ' + str(p))
        if p.is_file():
            result[p.relative_to(root).as_posix()] = sha(p)
    if not result:
        raise ValueError('Empty tree: ' + str(root))
    return result


def native_sources(repo):
    paths = [repo / 'CMakeLists.txt']
    for directory in ['reconstruction/src', 'reconstruction/include']:
        paths.extend(p for p in (repo / directory).rglob('*') if p.is_file())
    return {p.relative_to(repo).as_posix(): sha(p) for p in sorted(paths)}


def observed_manifest(repo, engine, shim):
    return {'schema': 1, 'artifacts': {'engine': sha(engine), 'shim': sha(shim)},
            'observedNativeSources': native_sources(repo),
            'provenance': {'kind': 'observed-prebuilt-inputs',
                           'binarySourceRelationship': 'Hashes pin supplied artifacts and current source observations; '
                           'they do NOT prove these binaries were built from these sources. Supply external build '
                           'receipts or matching historical provenance separately.'}}


def check_manifest(manifest, repo, engine, shim):
    if manifest.get('schema') != 1:
        raise ValueError('Unsupported artifact manifest schema')
    provenance = manifest.get('provenance', {})
    if not provenance.get('kind') or not provenance.get('binarySourceRelationship'):
        raise ValueError('Manifest must describe prebuilt provenance limitations')
    if manifest.get('artifacts') != {'engine': sha(engine), 'shim': sha(shim)}:
        raise ValueError('Native artifact hash mismatch')
    if manifest.get('observedNativeSources') != native_sources(repo):
        raise ValueError('Observed native source closure changed')


def parser():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode', choices=['observe', 'evidence', 'compile', 'run'])
    for name in ['repo', 'work', 'java', 'cache', 'compiler-classpath', 'native-dir', 'shim', 'artifact-manifest']:
        p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('--app-classes', type=Path, help='Default: REPO/app/build/tmp/kotlin-classes/debug')
    p.add_argument('--junit-jar', type=Path, help='Optional explicit cached JUnit 4.13.2 JAR')
    return p


def atomic_json(path, value):
    temp = path.with_name(path.name + '.' + uuid.uuid4().hex + '.partial')
    temp.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    temp.replace(path)


def invalidate_receipt(path):
    if path.exists():
        path.rename(path.with_name("superseded-" + uuid.uuid4().hex + "-" + path.name))


def main(argv=None):
    args = parser().parse_args(argv)
    repo = args.repo.resolve(strict=True)
    work = args.work.resolve()
    if work == repo or repo in work.parents:
        raise ValueError('Work directory must be outside the source repository')
    work.mkdir(parents=True, exist_ok=True)
    if args.mode in ["compile", "run"]:
        invalidate_receipt(work / "run-receipt.json")
    java = args.java.resolve(strict=True)
    native = args.native_dir.resolve(strict=True)
    shim = args.shim.resolve(strict=True)
    engine = native / 'librealityforge.so'
    manifest_path = args.artifact_manifest.resolve()
    if args.mode == 'observe':
        if manifest_path.exists():
            raise ValueError('Refusing to overwrite existing provenance manifest')
        atomic_json(manifest_path, observed_manifest(repo, engine, shim))
        return
    manifest = json.loads(manifest_path.read_text())
    check_manifest(manifest, repo, engine, shim)
    app = (args.app_classes or repo / 'app/build/tmp/kotlin-classes/debug').resolve(strict=True)
    home = Path(__file__).resolve().parent
    compiler = [str(Path(p).resolve(strict=True)) for p in args.compiler_classpath.read_text().strip().split(os.pathsep)]
    if not compiler or any(not Path(p).is_file() for p in compiler):
        raise ValueError('Compiler classpath must contain existing JAR files')
    cp = [str(app)]
    for group, name, version in JARS:
        if name == 'junit' and args.junit_jar:
            found = [args.junit_jar.resolve(strict=True)]
        else:
            found = list((args.cache / group / name / version).glob('*/*.jar'))
        if len(found) != 1:
            raise ValueError('Expected exactly one cached runtime JAR: ' + name)
        cp.append(str(found[0].resolve(strict=True)))
    sources = [repo / 'app/src/main/java/com/realityforge/scanner/engine' / n for n in SOURCES]
    sources += [repo / 'app/src/androidTest/java/com/realityforge/scanner/engine/FinalSparseExecutionDeviceTest.kt',
                home / 'ProductionWrapperHostTest.kt', home / 'HostRunner.kt',
                repo / 'app/src/main/java/com/realityforge/scanner/work/execution/ReconstructionAttemptCleanup.kt',
                home / 'ReconstructionAttemptCleanupHostTest.kt']
    def inputs():
        check_manifest(manifest, repo, engine, shim)
        return {'artifactManifest': sha(manifest_path), 'nativeSources': native_sources(repo),
                'javaExecutable': sha(java), 'javaRelease': sha(java.parent.parent / 'release'),
                'compilerClasspathFile': sha(args.compiler_classpath),
                'compilerJars': {p: sha(p) for p in compiler},
                'sources': {str(p): sha(p) for p in sources},
                'runtimeJars': {p: sha(p) for p in cp[1:]}, 'appClasses': tree_hashes(app),
                'helper': sha(Path(__file__)), 'nativeArtifacts': {'engine': sha(engine), 'shim': sha(shim)}}
    before = inputs()
    if args.mode == 'evidence':
        atomic_json(work / 'dependencies.json', before)
        return
    env = dict(os.environ)
    for key in ['JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH', 'LD_PRELOAD']:
        env.pop(key, None)
    # Explicit native-dir controls resolution of shim's librealityforge dependency.
    env['LD_LIBRARY_PATH'] = str(native)
    receipt_path = work / 'compiled-inputs.json'
    if args.mode == 'compile':
        invalidate_receipt(receipt_path)
        output = work / ('classes-' + uuid.uuid4().hex)
        subprocess.run([str(java), '-XX:ActiveProcessorCount=2', '-Xmx384m', '-cp', os.pathsep.join(compiler),
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-jvm-target', '17', '-Xfriend-paths=' + str(app), '-cp', os.pathsep.join(cp),
                        '-d', str(output), *map(str, sources)], check=True, timeout=120, env=env)
        if inputs() != before:
            raise ValueError('Inputs changed during compilation')
        atomic_json(receipt_path, {'inputs': before, 'output': str(output), 'classes': tree_hashes(output)})
    else:
        receipt = json.loads(receipt_path.read_text())
        output = Path(receipt['output']).resolve(strict=True)
        if output.parent != work or not output.name.startswith('classes-'):
            raise ValueError('Receipt output outside work directory')
        if receipt['inputs'] != before or tree_hashes(output) != receipt['classes']:
            raise ValueError('Compiled inputs/output changed')
        subprocess.run([str(java), '-XX:ActiveProcessorCount=2', '-Xmx256m',
                        '-Djava.library.path=' + str(native), '-cp', os.pathsep.join([str(output), *cp]),
                        'HostRunnerKt', str(shim), str(engine)], check=True, timeout=90, env=env)
        if inputs() != before or tree_hashes(output) != receipt['classes']:
            raise ValueError('Inputs/output changed during execution')
        atomic_json(work / 'run-receipt.json', {'status': 'passed', 'newTests': 13,
                    'compiledReceiptSha256': sha(receipt_path), 'inputs': before,
                    'scope': 'host wrappers with synthetic observations; no in-flight interruption or Android claim'})


if __name__ == '__main__':
    main()
