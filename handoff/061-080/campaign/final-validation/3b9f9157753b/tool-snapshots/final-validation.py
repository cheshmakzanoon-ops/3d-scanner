#!/usr/bin/env python3
"""Validate an explicitly frozen combined commit in a fresh detached worktree.

No arguments print the recipe. Execution requires the parent-confirmed exact
combined commit and exact Agent080 input. Never edits or pushes role branches.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile

BASE = Path('/workspace/campaign-061-080')
COMBINED = BASE / 'combined'
INPUTS = BASE / 'combined-final-inputs.json'
TOOLCHAINS = Path('/workspace/toolchains')
SDK = TOOLCHAINS / 'android-sdk'
PYTHON = Path('/workspace/.venvs/3d-scanner/bin/python')
GRADLE_RUNNER = TOOLCHAINS / 'gradle-cloud.py'
MANIFEST_TOOL = BASE / 'dependencies/094/source_manifest.py'
NEW_NATIVE = ('asset_model.cpp', 'glb_export.cpp', 'obj_export.cpp',
              'stl_export.cpp', 'ply_export.cpp', 'mesh_lod.cpp', 'asset_edit.cpp')
EXPECTED_SYMBOLS = ('rf::asset::validate(', 'rf::asset::writeGlb(',
                    'rf::asset::writeObj(', 'rf::asset::writeStl(',
                    'rf::asset::writePly(', 'rf::asset::simplifySurface(',
                    'rf::asset::applyEdits(')
TASKS = (':app:assembleDebug', ':app:assembleDebugAndroidTest',
         ':app:testDebugUnitTest', ':app:lintDebug')
STALE_LITERAL_TESTS = {
    'tests/test_project_contracts.py::test_sparse_output_disclosed_in_ui':
        'ScannerApp moved disclosure to localized resources and route composition.',
    'tests/test_project_contracts.py::test_capture_package_requires_explicit_destination_and_preserves_hashes':
        'ScannerApp now delegates document destination and transport to coordinator.',
    'tests/test_project_contracts.py::test_m09_camera_projected_uvs_are_embedded_and_exportable':
        'ScannerApp moved individual export handlers behind project-scoped export route.',
    'tests/test_project_contracts.py::test_photographic_viewer_uses_bounded_local_assets_and_on_demand_gl':
        'Photographic viewer lifecycle disposal implementation changed spelling/ownership.',
    'tests/test_project_contracts.py::test_m19_persisted_scan_evidence_and_local_export':
        'ScannerApp now delegates diagnostic export behind coordinator route.',
    'tests/test_surface.py::test_android_mesh_is_shown_and_exportable_without_network_calls':
        'ScannerApp now delegates mesh display to studio route.',
}


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')


def git(root, *args, check=True):
    return subprocess.run(['git', '-C', str(root), *args], capture_output=True,
                          check=check, env={**os.environ, 'GIT_OPTIONAL_LOCKS': '0'}, timeout=60)


def git_text(root, *args):
    return git(root, *args).stdout.decode('utf-8').strip()


def assert_no_operation(root):
    for name in ('MERGE_HEAD', 'CHERRY_PICK_HEAD', 'REBASE_HEAD', 'rebase-merge', 'rebase-apply'):
        path = Path(git_text(root, 'rev-parse', '--git-path', name))
        if not path.is_absolute():
            path = root / path
        if path.exists():
            raise RuntimeError(f'Git operation is still in progress: {name}')
    if git(root, 'ls-files', '-u').stdout:
        raise RuntimeError('Unmerged index entries remain')


def assert_clean(root):
    assert_no_operation(root)
    dirty = git(root, 'status', '--porcelain=v1', '-z', '--untracked-files=all').stdout
    if dirty:
        raise RuntimeError('Checkout is not clean: ' + repr(dirty.decode('utf-8')))


def source_snapshot(root, commit):
    if git_text(root, 'rev-parse', 'HEAD^{commit}') != commit:
        raise RuntimeError('Source HEAD changed from the pinned immutable commit')
    if git(root, 'diff', '--name-only', commit).stdout or git(root, 'diff', '--cached', '--name-only', commit).stdout:
        raise RuntimeError('Tracked source/index differs from immutable commit')
    files = {}
    records = git(root, 'ls-tree', '-r', '-z', commit).stdout.split(b'\0')
    for record in records:
        if not record:
            continue
        metadata, raw_path = record.split(b'\t', 1)
        mode, kind, oid = metadata.decode().split()
        path = raw_path.decode('utf-8')
        if kind != 'blob' or mode not in ('100644', '100755'):
            raise RuntimeError(f'Unsupported source entry: {path}')
        actual = root / path
        if actual.is_symlink() or not actual.is_file():
            raise RuntimeError(f'Nonregular tracked source: {path}')
        data = actual.read_bytes()
        expected = git(root, 'cat-file', 'blob', oid).stdout
        if data != expected:
            raise RuntimeError(f'Worktree bytes differ from immutable blob: {path}')
        files[path] = {'mode': mode, 'blob_oid': oid, 'sha256': sha(data), 'bytes': len(data)}
    encoded = json.dumps(files, sort_keys=True, separators=(',', ':')).encode()
    return {'commit': commit, 'tree_oid': git_text(root, 'rev-parse', f'{commit}^{{tree}}'),
            'files': files, 'tracked_source_digest_sha256': sha(encoded)}


def manifest_check(root, commit):
    spec = importlib.util.spec_from_file_location('final_manifest_verifier', MANIFEST_TOOL)
    verifier = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(verifier)
    return verifier.verify(root, commit)


def build_environment():
    environment = dict(os.environ)
    environment['JAVA_HOME'] = str(TOOLCHAINS / 'jdk-17.0.16+8')
    environment['ANDROID_HOME'] = str(SDK)
    environment['ANDROID_USER_HOME'] = str(TOOLCHAINS / 'android-user')
    environment['GRADLE_USER_HOME'] = str(TOOLCHAINS / 'gradle-user')
    environment['PATH'] = ':'.join((environment['JAVA_HOME'] + '/bin',
                                    str(PYTHON.parent), str(TOOLCHAINS / 'gradle-8.13/bin'),
                                    str(SDK / 'platform-tools'), environment['PATH']))
    return environment


def run(command, root, log, environment, timeout=2400):
    print(json.dumps({'event': 'command_started', 'command': command, 'log': str(log)}), flush=True)
    start = time.monotonic()
    with log.open('w') as output:
        process = subprocess.Popen(command, cwd=root, stdout=output, stderr=subprocess.STDOUT, env=environment)
        last_notice = start
        while process.poll() is None:
            now = time.monotonic()
            if now - start > timeout:
                process.terminate()
                try:
                    process.wait(timeout=20)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
                raise RuntimeError(f'Command exceeded {timeout}s: {log}')
            if now - last_notice >= 30:
                print(json.dumps({'event': 'command_running', 'seconds': round(now-start), 'log': str(log)}), flush=True)
                last_notice = now
            time.sleep(2)
    record = {'command': command, 'log': str(log), 'exit_code': process.returncode,
              'duration_seconds': round(time.monotonic() - start, 3),
              'log_sha256': sha(log.read_bytes())}
    print(json.dumps({'event': 'command_finished', **record}), flush=True)
    return record


def write_adapters(directory, source, owner004_kotlin_dsl_fix=False):
    cmake = directory / 'cmake'
    cmake.mkdir()
    cmake_file = cmake / 'CMakeLists.txt'
    cmake_file.write_text('''cmake_minimum_required(VERSION 3.22)
project(RealityForgeFinalVerification LANGUAGES CXX)
set(CMAKE_EXPORT_COMPILE_COMMANDS ON)
add_subdirectory("'''+str(source)+'''" baseline)
if(NOT TARGET realityforge)
  message(FATAL_ERROR "Owner004 realityforge target is missing")
endif()
get_target_property(_rf_sources realityforge SOURCES)
get_target_property(_rf_source_dir realityforge SOURCE_DIR)
set(_rf_absolute_sources)
foreach(_source IN LISTS _rf_sources)
  get_filename_component(_absolute "${_source}" ABSOLUTE BASE_DIR "${_rf_source_dir}")
  list(APPEND _rf_absolute_sources "${_absolute}")
endforeach()
'''+''.join('set(_new "'+str(source/'reconstruction/src'/name)+'''")
if(NOT _new IN_LIST _rf_absolute_sources)
  target_sources(realityforge PRIVATE "${_new}")
endif()
''' for name in NEW_NATIVE)+'''
target_compile_options(realityforge PRIVATE -Werror)
if(ANDROID)
  target_link_options(realityforge PRIVATE -Wl,--no-undefined)
endif()
''')
    build_script_adapter = ''
    result = {'cmake': cmake_file}
    if owner004_kotlin_dsl_fix:
        original = (source/'app/build.gradle.kts').read_text()
        if original.count('java.net.URI(') != 1 or original.count('java.security.MessageDigest.getInstance(') != 1:
            raise RuntimeError('Owner004 Kotlin DSL workaround does not match the known two package-shadow references')
        generated = directory/'app-build.gradle.kts'
        generated.write_text('import java.net.URI\nimport java.security.MessageDigest\n\n' +
                             original.replace('java.net.URI(', 'URI(').replace(
                                 'java.security.MessageDigest.getInstance(', 'MessageDigest.getInstance('))
        build_script_adapter = '''gradle.settingsEvaluated { s ->
  s.project(':app').buildFileName = ''' + json.dumps(os.path.relpath(generated, source/'app')) + '''
}
'''
        result['owner004_app_build_script_import_fix'] = generated
    gradle = directory / 'final.init.gradle'
    gradle.write_text(build_script_adapter + '''gradle.beforeProject { p ->
  if (p.path == ':app') {
    p.plugins.withId('com.android.application') {
      p.dependencies.add('implementation', 'com.google.android.filament:filament-android:1.56.0')
      p.dependencies.add('implementation', 'com.google.android.filament:gltfio-android:1.56.0')
      p.extensions.getByName('androidComponents').finalizeDsl { dsl ->
        dsl.externalNativeBuild.cmake.path = new File('''+
                      json.dumps(str(cmake_file))+''')
      }
    }
  }
}
''')
    result['gradle'] = gradle
    return result


def junit_summary(source):
    reports = sorted((source/'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
    if not reports:
        raise RuntimeError('Gradle produced no JVM test result XML')
    result = {'reports': [], 'tests': 0, 'failures': 0, 'errors': 0, 'skipped': 0}
    for path in reports:
        node = ET.parse(path).getroot()
        row = {'path': str(path), 'sha256': sha(path.read_bytes()), 'suite': node.attrib.get('name')}
        for name in ('tests', 'failures', 'errors', 'skipped'):
            row[name] = int(node.attrib.get(name, 0))
            result[name] += row[name]
        result['reports'].append(row)
    if result['tests'] == 0 or result['failures'] or result['errors']:
        raise RuntimeError('JVM results have no tests or contain failures/errors')
    return result


def lint_summary(source):
    path = source/'app/build/reports/lint-results-debug.xml'
    root = ET.parse(path).getroot()
    counts = {}
    for issue in root.findall('issue'):
        severity = issue.attrib.get('severity', 'Unknown')
        counts[severity] = counts.get(severity, 0) + 1
    if counts.get('Error', 0) or counts.get('Fatal', 0):
        raise RuntimeError('Lint XML contains Error/Fatal issues')
    return {'path': str(path), 'sha256': sha(path.read_bytes()), 'severity_counts': counts}


def packaged_native(source, apk, directory, environment):
    nm = SDK/'ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm'
    result = {'libraries': [], 'compile_commands': []}
    with zipfile.ZipFile(apk) as archive:
        for abi in ('arm64-v8a', 'x86_64'):
            name = f'lib/{abi}/librealityforge.so'
            data = archive.read(name)
            local = directory/f'librealityforge-{abi}.so'
            local.write_bytes(data)
            output = subprocess.check_output([str(nm), '-D', '--demangle', str(local)],
                                             text=True, env=environment)
            symbols = [symbol for symbol in EXPECTED_SYMBOLS if symbol in output]
            if len(symbols) != len(EXPECTED_SYMBOLS):
                raise RuntimeError(f'Packaged {abi} library lacks new native symbols: {set(EXPECTED_SYMBOLS)-set(symbols)}')
            result['libraries'].append({'apk_entry': name, 'sha256': sha(data),
                                        'bytes': len(data), 'verified_exported_symbols': symbols})
            candidates = [p for p in (source/'app/.cxx').rglob('compile_commands.json')
                          if abi in p.parts]
            if not candidates:
                raise RuntimeError(f'No native compile_commands.json for {abi}')
            covered = set()
            for commands in candidates:
                entries = json.loads(commands.read_text())
                for entry in entries:
                    path = Path(entry['file']).resolve()
                    if path.parent == source/'reconstruction/src' and path.name in NEW_NATIVE:
                        covered.add(path.name)
                result['compile_commands'].append({'path': str(commands), 'abi': abi,
                                                    'sha256': sha(commands.read_bytes())})
            if covered != set(NEW_NATIVE):
                raise RuntimeError(f'New native compile coverage incomplete for {abi}: {covered}')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--execute', action='store_true')
    parser.add_argument('--commit', help='Parent-confirmed exact immutable combined commit, full SHA')
    parser.add_argument('--expected-080', help='Exact parent-confirmed Agent080 commit in final-inputs')
    parser.add_argument('--output-dir', type=Path)
    parser.add_argument('--owner004-kotlin-dsl-fix', action='store_true',
                        help='Use an external app build script adding URI/MessageDigest imports for known owner004 java accessor shadowing')
    args = parser.parse_args()
    if not args.execute:
        print('After final refresh and Agent080 merge are confirmed, run:')
        print('python3 /workspace/campaign-061-080/final-validation.py --execute --commit <full combined SHA> --expected-080 <full 080 SHA>')
        print('This creates a fresh detached source worktree, proves planned-input ancestry and exact tracked bytes, then acquires android.lock for assembleDebug/assembleDebugAndroidTest/testDebugUnitTest/lintDebug and APK checks.')
        print('External CMake source registration and two pinned Filament dependencies are verification adapters; owner004/JNI/route integration and physical/runtime gates remain explicit.')
        return 0
    if not args.commit or not re.fullmatch('[0-9a-f]{40}', args.commit):
        parser.error('--execute requires a full --commit SHA confirmed by the parent')
    if not args.expected_080 or not re.fullmatch('[0-9a-f]{40}', args.expected_080):
        parser.error('--execute requires a full --expected-080 SHA confirmed by the parent')
    if git_text(COMBINED, 'rev-parse', 'HEAD^{commit}') != args.commit:
        raise RuntimeError('Combined checkout HEAD is not the parent-confirmed frozen commit')
    assert_clean(COMBINED)
    inputs_bytes = INPUTS.read_bytes()
    inputs = json.loads(inputs_bytes)
    by_agent = {row['agent']: row['source_sha'] for row in inputs}
    required_agents = {f'{value:03d}' for value in range(61, 81)} | {'009', '088'}
    if len(by_agent) != len(inputs) or not required_agents.issubset(by_agent):
        raise RuntimeError('Final planned inputs omit required roles or contain duplicate roles')
    if by_agent['080'] != args.expected_080:
        raise RuntimeError('Final planned Agent080 input differs from confirmed source freeze')
    for row in inputs:
        if not re.fullmatch('[0-9a-f]{40}', row['source_sha']):
            raise RuntimeError('Input source must be a full immutable SHA')
        if git(COMBINED, 'merge-base', '--is-ancestor', row['source_sha'], args.commit, check=False).returncode:
            raise RuntimeError('Planned source is not merged into frozen candidate: ' + str(row))
    manifest_result = manifest_check(COMBINED, args.commit)
    directory = args.output_dir or BASE/'final-validation'/args.commit[:12]
    directory = directory.resolve()
    if not directory.is_relative_to(BASE/'final-validation'):
        raise RuntimeError('Output must stay inside campaign/final-validation')
    directory.mkdir(parents=True, exist_ok=False)
    source = directory/'source'
    record = {'schema': 1, 'started_at': utc(), 'state': 'WORKING',
              'source_commit': args.commit, 'source_directory': str(source),
              'planned_inputs': inputs, 'planned_inputs_sha256': sha(inputs_bytes),
              'manifest_commit': manifest_result,
              'commands': [], 'limitations': [
                  'External verification CMake adapter registers seven native sources on owner004 realityforge target.',
                  'External verification Gradle adapter adds exact Filament/gltfio1.56.0 dependencies requested by owner069.',
                  'No source-set replacement, handwritten stubs or foreign worktree source injection is used.',
                  'Compilation and packaged symbol inclusion do not prove JNI/application routing or runtime invocation.',
                  'No instrumentation/device execution, physical accuracy, frame budget, accessibility or production readiness is claimed.',
              ], 'known_prior_literal_test_failures': STALE_LITERAL_TESTS}
    result_path = directory/'result.json'
    try:
        git(COMBINED, 'worktree', 'add', '--detach', str(source), args.commit)
        assert_clean(source)
        snapshot = source_snapshot(source, args.commit)
        write_json(directory/'tracked-source.json', snapshot)
        record['tracked_source_digest_sha256'] = snapshot['tracked_source_digest_sha256']
        record['tracked_files'] = len(snapshot['files'])
        record['source_tree_oid'] = snapshot['tree_oid']
        adapters = write_adapters(directory, source, args.owner004_kotlin_dsl_fix)
        if args.owner004_kotlin_dsl_fix:
            record['limitations'].append('Original owner004 app/build.gradle.kts fails compilation because java DSL accessor shadows package qualifiers; external build script adds URI and MessageDigest imports and replaces exactly those two package references. Original immutable attempt remains FAIL in its separate result directory.')
        record['verification_adapters'] = {name: {'path': str(path), 'sha256': sha(path.read_bytes())}
                                           for name, path in adapters.items()}
        record['tools'] = {str(path): sha(path.read_bytes())
                           for path in (Path(__file__), MANIFEST_TOOL, GRADLE_RUNNER)}
        environment = build_environment()
        write_json(result_path, record)
        with (BASE/'android.lock').open('a') as lock:
            wait_started = time.monotonic()
            next_notice = wait_started
            while True:
                try:
                    fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
                    break
                except BlockingIOError:
                    now = time.monotonic()
                    if now >= next_notice:
                        print(json.dumps({'event': 'waiting_for_android_lock', 'seconds': round(now-wait_started)}), flush=True)
                        next_notice = now + 30
                    time.sleep(2)
            print(json.dumps({'event': 'android_lock_acquired', 'source_commit': args.commit}), flush=True)
            if source_snapshot(source, args.commit) != snapshot:
                raise RuntimeError('Tracked source changed while waiting for Android lock')
            command = [str(PYTHON), str(GRADLE_RUNNER), '--init-script', str(adapters['gradle']), *TASKS]
            result = run(command, source, directory/'android.log', environment)
            record['commands'].append(result)
            write_json(result_path, record)
            if result['exit_code']:
                raise RuntimeError(f'Android Gradle validation failed: {directory/"android.log"}')
            record['jvm_tests'] = junit_summary(source)
            record['lint'] = lint_summary(source)
            record['artifacts'] = {}
            for relative in ('app/build/outputs/apk/debug/app-debug.apk',
                             'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'):
                artifact = source/relative
                record['artifacts'][relative] = {'path': str(artifact), 'sha256': sha(artifact.read_bytes()),
                                                 'bytes': artifact.stat().st_size}
            apk = source/'app/build/outputs/apk/debug/app-debug.apk'
            apk_report = directory/'apk-check.json'
            check = run([str(PYTHON), str(source/'tools/check_apk.py'), str(apk), '--sdk', str(SDK),
                         '--report', str(apk_report)], source, directory/'apk-check.log', environment)
            record['commands'].append(check)
            if check['exit_code']:
                raise RuntimeError('Built APK permission/alignment verification failed')
            record['apk_checks'] = json.loads(apk_report.read_text())
            record['new_native_packaging'] = packaged_native(source, apk, directory, environment)
            if source_snapshot(source, args.commit) != snapshot:
                raise RuntimeError('Tracked source changed during validation')
            record['tracked_source_unchanged_after_validation'] = True
            record['postbuild_untracked_status'] = git(source, 'status', '--porcelain=v1', '-z', '--untracked-files=all').stdout.decode('utf-8').split('\0')
            record['state'] = 'PASS'
    except Exception as exc:
        record['state'] = 'FAIL'
        record['error'] = str(exc)
    record['finished_at'] = utc()
    write_json(result_path, record)
    print(json.dumps({'result': str(result_path), 'state': record['state'],
                      'source_commit': args.commit, 'error': record.get('error')}, indent=2), flush=True)
    return 0 if record['state'] == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
