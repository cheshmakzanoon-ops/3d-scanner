"""Source-bound supplementary matcher JNI test; never a real-photo result or capability producer."""
from pathlib import Path
import hashlib
import json
import os
import xml.etree.ElementTree as ET

from . import capability, policy
from .runtime import Failure, atomic_json, inventory, sha

SOURCES = ('NativeCore.kt', 'LensGeometry.kt', 'features/FeatureFrame.kt',
           'matching/FeatureMatcher.kt', 'matching/PairCandidate.kt',
           'matching/VerifiedMatches.kt', 'matching/PairWorkTiming.kt')
RUNTIME_JARS = ('kotlin-stdlib-2.1.21.jar', 'kotlinx-coroutines-core-jvm-1.10.2.jar',
                'json-20250517.jar', 'junit-4.13.2.jar', 'hamcrest-core-1.3.jar', 'annotations-23.0.0.jar')


def compiler_inputs(run, source):
    from .controller import bounded_bytes, bounded_hash
    path = run.work / 'matcher-compiler.json'
    record = json.loads(bounded_bytes(path, 64 * 1024, run, cleanup=False))
    if (set(record) != {'schema', 'sourceSha', 'configuration', 'entries'} or record['schema'] != 1
            or type(record['schema']) is not int or record['sourceSha'] != source or record['configuration'] != 'kotlinCompilerClasspath'):
        raise Failure('Foreign supplementary compiler ledger')
    entries = record['entries']
    if not isinstance(entries, list) or not 1 <= len(entries) <= 32:
        raise Failure('Supplementary compiler count')
    metadata = ET.fromstring(bounded_bytes(run.root / 'gradle/verification-metadata.xml', 4*1024**2, run, cleanup=False))
    pins = {}
    for artifact in metadata.findall('.//{*}artifact'):
        pins.setdefault(artifact.get('name'), set()).update(x.get('value') for x in artifact.findall('{*}sha256'))
    paths = []
    total = 0
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {'path', 'sha256'}:
            raise Failure('Supplementary compiler entry schema')
        file = Path(entry['path'])
        if (not file.is_absolute() or file.resolve() != file or not file.is_relative_to(run.work / 'gradle')
                or file.suffix != '.jar' or entry['sha256'] not in pins.get(file.name, set())):
            raise Failure('Unpinned supplementary compiler input')
        total += file.stat().st_size
        if total > 256*1024**2: raise Failure('Supplementary compiler total byte bound')
        if bounded_hash(file, 128*1024**2, run, cleanup=False) != entry['sha256']:
            raise Failure('Supplementary compiler input changed')
        paths.append(file)
    if len(set(paths)) != len(paths) or len({p.name for p in paths}) != len(paths) or sum(p.name == 'kotlin-compiler-embeddable-2.1.21.jar' for p in paths) != 1:
        raise Failure('Ambiguous supplementary Kotlin compiler')
    return paths, record


def runtime_inputs(prepared):
    result = []
    for name in RUNTIME_JARS:
        entries = [x for x in prepared['classpath'] if x['kind'] == 'jar' and Path(x['path']).name == name]
        if len(entries) != 1:
            raise Failure('Missing/ambiguous supplementary runtime JAR: ' + name)
        result.append(entries[0])
    return result


def execute(run, source, config, prepared, cap):
    from .controller import bounded_bytes, bounded_hash, classpath_check, gradle_args, prepared_record_check, phase_budget
    prepared_hash = sha(run.work / 'prepared.json')
    verified = policy.verify_capability(cap, source, capability.POLICY, run, prepared)
    classpath_check(prepared, run)
    prepared_record_check(run, prepared, prepared_hash)
    # --offline and strict dependency verification are inherited; no new native build/download.
    run.command('matcher-compiler-capture', [*gradle_args(run, config, offline=True), ':app:eightMatcherCompiler'], seconds=90)
    graph = json.loads(bounded_bytes(run.work / 'matcher-task-graph.json', 64*1024, run, cleanup=False))
    if graph.get('tasks') != [':app:eightMatcherCompiler'] or graph.get('externalNativeTasks'):
        raise Failure('Unexpected supplementary Gradle task graph')
    compiler, ledger = compiler_inputs(run, source)
    runtime = runtime_inputs(prepared)
    root = run.work / 'matcher-supplement'
    root.mkdir()  # Fresh only; no old classes or result can be reused.
    output = root / 'classes'
    temp = root / 'tmp'; temp.mkdir()
    sources = [run.root / 'app/src/main/java/com/realityforge/scanner/engine' / name for name in SOURCES]
    sources += [run.root / 'tools/feature_matcher_validation' / name for name in ('ProductionMatcherHostTest.kt', 'HostRunner.kt')]
    inputs = {str(p): hashlib.sha256(bounded_bytes(p, 2*1024**2, run, cleanup=False)).hexdigest() for p in sources}
    inputs.update({entry['path']: entry['sha256'] for entry in [*runtime, *ledger['entries']]})
    inputs[str(Path(__file__).resolve())] = sha(__file__)
    inputs[str(run.root / 'gradle/verification-metadata.xml')] = sha(run.root / 'gradle/verification-metadata.xml')
    inputs[str(run.work / 'matcher-compiler.json')] = sha(run.work / 'matcher-compiler.json')
    inputs[str(cap)] = sha(cap)
    aar_classes = Path(prepared['opencvClasses'])
    if verified['inputs']['classes']['path'] != str(aar_classes):
        raise Failure('Supplementary AAR class binding differs')
    inputs[str(aar_classes)] = verified['inputs']['classes']['sha256']
    java = Path(verified['inputs']['java']['path'])
    inputs[str(java)] = verified['inputs']['java']['sha256']
    native = verified['native']['opencv']
    inputs[native['path']] = native['sha256']
    cp = os.pathsep.join([str(aar_classes), *[x['path'] for x in runtime]])
    env = dict(run.env)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'LD_PRELOAD'):
        if env.get(key): raise Failure('Injected supplementary JVM environment')
    env['OPENCV_FOR_THREADS_NUM'] = '2'
    limits = ['-XX:ActiveProcessorCount=2', '-Djava.io.tmpdir=' + str(temp)]
    result = {'schema': 1, 'classification': 'SUPPLEMENTARY_SYNTHETIC_FIXED_FEATURE_MATCHER_JNI_NOT_REAL_PHOTO_RESULT',
              'sourceSha': source, 'status': 'RUNNING', 'expectedTests': 9,
              'preparedSha256': prepared_hash, 'capabilitySha256': sha(cap),
              'compiler': ledger, 'runtime': runtime, 'inputs': inputs, 'native': native,
              'limits': {'compilerSeconds': 120, 'testSeconds': 90, 'compilerHeap': '384m', 'testHeap': '256m',
                         'processors': 2, 'opencvThreads': 2, 'overallDeadlineExtended': False}}
    def check_inputs():
        prepared_record_check(run, prepared, prepared_hash)
        for path, digest in inputs.items():
            if bounded_hash(path, 128*1024**2, run, cleanup=False) != digest:
                raise Failure('Supplementary input changed')
    check_inputs()
    _, code = run.command('matcher-supplement-compile', [java, '-Xmx384m', *limits,
        '-cp', os.pathsep.join(map(str, compiler)), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
        '-no-stdlib', '-no-reflect', '-jvm-target', '17', '-cp', cp, '-d', output, *sources],
        seconds=120, env=env, check=False)
    if code < 0: raise Failure('Supplementary compiler terminated by signal')
    if code not in (0, 1): raise Failure('Supplementary compiler internal/launch failure')
    check_inputs()
    result['compileExitCode'] = code
    classes = {}
    if code == 0:
        _, files = inventory(output, max_bytes=16*1024**2, max_files=2000, seconds=phase_budget(run, cleanup=False))
        classes = {name: sha(output / name) for name in files if (output / name).is_file()}
        required = {'HostRunnerKt.class', 'com/realityforge/scanner/engine/matching/FeatureMatcher.class',
                    'com/realityforge/scanner/engine/matching/TentativeMatches.class',
                    'com/realityforge/scanner/engine/matching/ProductionMatcherHostTest.class'}
        if not required <= set(classes): raise Failure('Missing fresh supplementary classes')
        _, code = run.command('matcher-supplement-test', [java, '-Xmx256m', *limits, '-cp', str(output)+os.pathsep+cp,
            'HostRunnerKt', native['path']], seconds=90, env=env, check=False)
        if code < 0: raise Failure('Supplementary matcher terminated by signal')
        if code not in (0, 10): raise Failure('Supplementary matcher runner integrity/launch failure')
        result['testExitCode'] = code
    # Any integrity/resource/cancellation exception propagates: only ordinary compiler/test
    # nonzero exits become supplementary FAIL and permit the unchanged strict photographic run.
    policy.verify_capability(cap, source, capability.POLICY, run, prepared)
    classpath_check(prepared, run)
    prepared_record_check(run, prepared, prepared_hash)
    if compiler_inputs(run, source)[1] != ledger: raise Failure('Supplementary compiler ledger changed')
    check_inputs()
    if classes:
        _, files = inventory(output, max_bytes=16*1024**2, max_files=2000, seconds=phase_budget(run, cleanup=False))
        if {name: sha(output/name) for name in files if (output/name).is_file()} != classes:
            raise Failure('Supplementary classes changed')
    result['classes'] = classes
    result['status'] = 'PASS' if code == 0 else 'FAIL'
    atomic_json(run.work / 'matcher-supplement.json', result)
    return result
