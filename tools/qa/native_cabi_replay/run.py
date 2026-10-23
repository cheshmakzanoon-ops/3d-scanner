"""Supplementary native-only replay. Never replaces Python sanitizer qualification."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import signal
import struct
import subprocess
import sys
import time

from .prepare import digest, require
from .verify import verify

ROOT = Path(__file__).resolve().parents[3]
MAX_LOG = 32 * 1024 * 1024


def planted_detected(text, returncode):
    """Accept only the complete, single-allocation positive-control report.

    GCC/Clang ASan+LSan ordinarily label the summary AddressSanitizer;
    standalone LSan may label it LeakSanitizer. PID prefixes and whitespace
    differ, but extra findings, missing totals and unsymbolized frames fail.
    """
    if returncode != 23:
        return False
    header = re.compile(r'^[ \t]*(?:==[0-9]+==)?ERROR: LeakSanitizer: detected memory leaks[ \t]*$', re.MULTILINE)
    allocation = re.compile(r'^[ \t]*Direct leak of 1237 byte\(s\) in 1 object\(s\) allocated from:[ \t]*$', re.MULTILINE)
    summary = re.compile(r'^[ \t]*SUMMARY: (?:AddressSanitizer|LeakSanitizer): 1237 byte\(s\) leaked in 1 allocation\(s\)\.[ \t]*$', re.MULTILINE)
    headers, allocations, summaries = [list(pattern.finditer(text)) for pattern in (header, allocation, summary)]
    if any(len(matches) != 1 for matches in (headers, allocations, summaries)):
        return False
    if len(re.findall(r'\b(?:Direct|Indirect) leak of\b', text)) != 1 or len(re.findall(r'\bSUMMARY:', text)) != 1:
        return False
    if len(re.findall(r'\b(?:ERROR|WARNING|FATAL)\s*:', text, re.IGNORECASE)) != 1:
        return False
    if re.search(r'DEADLYSIGNAL|runtime error:|CHECK failed|fatal error|ABORTING', text, re.IGNORECASE):
        return False
    # No separate sanitizer report may hide alongside the expected leak report.
    for line in text.splitlines():
        if re.search(r'(?:Address|Leak|UndefinedBehavior|Memory|Thread|HWAddress)Sanitizer', line):
            if not header.fullmatch(line) and not summary.fullmatch(line):
                return False
    first, block, last = headers[0], allocations[0], summaries[0]
    if not first.end() < block.start() < block.end() < last.start():
        return False
    stack = text[block.end():last.start()]
    return bool(re.search(r'^[ \t]*#[0-9]+[ \t]+[^\n]*\bin[ \t]+planted_leak(?:\(\))?(?=[ \t]|$)', stack, re.MULTILINE))


def native_environment(config):
    # The controller is deliberately uninstrumented; injected/suppressed environments
    # are rejected, not silently cleaned or reused for qualification.
    require(not any(os.environ.get(key) for key in ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS')),
            'Use a clean controller environment without sanitizer/runtime injection')
    env = dict(os.environ)
    env['UBSAN_OPTIONS'] = 'halt_on_error=1:print_stacktrace=1'
    if config == 'sanitize':
        env['ASAN_OPTIONS'] = 'detect_leaks=1:halt_on_error=1:abort_on_error=0:symbolize=1'
        env['LSAN_OPTIONS'] = 'exitcode=23'
    return env


class Runner:
    def __init__(self, output, timeout):
        output.mkdir(parents=True, exist_ok=False)
        self.output, self.timeout = output, timeout
        self.receipt = {'schema': 1, 'classification': 'SUPPLEMENTARY_DIAGNOSTIC_NOT_RELEASE_QUALIFICATION',
                        'status': 'RUNNING', 'commands': [], 'started_utc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}
        self.save()

    def save(self):
        target = self.output / 'receipt.json'
        temporary = self.output / 'receipt.tmp'
        temporary.write_text(json.dumps(self.receipt, indent=2) + '\n')
        temporary.replace(target)

    def command(self, name, argv, env=None, expected=0):
        log = self.output / (name + '.log')
        entry = {'name': name, 'argv': list(map(str, argv)), 'status': 'RUNNING'}
        self.receipt['commands'].append(entry)
        self.save()
        started = time.monotonic()
        try:
            with log.open('xb') as stream:
                with subprocess.Popen(entry['argv'], stdout=stream, stderr=subprocess.STDOUT,
                                      env=env, start_new_session=True) as process:
                    while process.poll() is None:
                        if time.monotonic() - started > self.timeout or log.stat().st_size > MAX_LOG:
                            entry['status'] = 'TIMEOUT' if time.monotonic() - started > self.timeout else 'LOG_LIMIT'
                            os.killpg(process.pid, signal.SIGKILL)
                            process.wait()
                            break
                        time.sleep(.1)
                    entry['returncode'] = process.returncode
            if entry['status'] == 'RUNNING':
                entry['status'] = 'COMPLETED' if entry['returncode'] == expected else 'FAILED'
            if log.stat().st_size > MAX_LOG:
                entry['status'] = 'LOG_LIMIT'
            require(log.stat().st_size <= MAX_LOG, 'Diagnostic log limit exceeded')
            require(entry['status'] == 'COMPLETED', f'{name}: {entry["status"]}; see {log}')
            return log.read_text(errors='replace')
        except Exception:
            if entry['status'] == 'RUNNING':
                entry['status'] = 'FAILED'
            raise
        finally:
            entry['seconds'] = time.monotonic() - started
            if log.exists():
                entry['log_sha256'] = digest(log)
            self.save()


def snapshot(runner):
    source = runner.output / 'source'
    source.mkdir()
    for name in ('reconstruction', 'tools', 'tests'):
        shutil.copytree(ROOT / name, source / name, ignore=shutil.ignore_patterns('__pycache__', '*.pyc'))
    shutil.copy2(ROOT / 'CMakeLists.txt', source / 'CMakeLists.txt')
    runner.receipt['source_sha'] = runner.command('git-head', ['git', '-C', ROOT, 'rev-parse', 'HEAD']).strip()
    runner.receipt['source_dirty'] = bool(runner.command('git-status', ['git', '-C', ROOT, 'status', '--porcelain']).strip())
    runner.receipt['source_hashes'] = {str(path.relative_to(source)): digest(path) for path in sorted(source.rglob('*')) if path.is_file()}
    runner.save()
    return source


def require_native_log(text, expected=''):
    # This driver and empty control intentionally print nothing on success.
    # Exact rejection messages prevent sanitizer/fatal output masquerading as
    # the expected exit1 malformed-input rejection (or an unexpected exit0).
    require(text.strip() == expected, 'Unexpected native output; not a clean result')


def malformed(runner, executable, env):
    valid = (runner.output / 'inputs/sparse.bin').read_bytes()
    cases = {'truncated': valid[:-1], 'trailing': valid + b'x', 'version': b'BAD!' + valid[4:],
             'shape': valid[:8] + struct.pack('<I', 0xffffffff) + valid[12:]}
    reasons = {'truncated': 'truncated input', 'trailing': 'trailing input',
               'version': 'format/mode', 'shape': 'sparse shape'}
    for name, data in cases.items():
        path = runner.output / ('malformed-' + name + '.bin')
        path.write_bytes(data)
        log = runner.command('reject-' + name, [executable, path, runner.output / 'must-not-exist', 2], env, expected=1)
        require_native_log(log, reasons[name])
        require(not (runner.output / 'must-not-exist').exists(), 'Malformed input reached output stage')


def ctypes_parity(source, library, inputs, results):
    # This function is called ONLY for the normal configuration. Never load a
    # sanitizer-instrumented candidate into the Python preparation/controller.
    import numpy as np
    sys.path.insert(0, str(source / 'tools'))
    from native_api import NativeEngine
    engine = NativeEngine(library)
    for case in results:
        data = (inputs / (case + '.bin')).read_bytes()
        _, mode, n, w, h, pw, ph = struct.unpack_from('<7I', data)
        offset = 28
        if mode == 0:
            actual = engine.reconstruct(np.frombuffer(data, dtype='<f8', offset=offset).reshape(n, 7))
        else:
            cameras = np.frombuffer(data, dtype='<f8', count=n * 16, offset=offset).reshape(n, 16)
            offset += n * 16 * 8
            depth = np.frombuffer(data, dtype='u1', count=n * h * w * 4, offset=offset).reshape(n, h, w, 4)
            offset += n * h * w * 4
            photos = np.frombuffer(data, dtype='u1', offset=offset).reshape(n, ph, pw, 4)
            actual = engine.surface(depth, cameras, 6, 80, 64, 3) if mode == 1 else engine.textured_surface(depth, photos, cameras, 6, 80, 64, 3)
        require(actual == results[case], f'Ordinary ctypes parity differs: {case}')


def ordinary_parity_command(source, library, inputs, expected):
    return [sys.executable, '-c',
            'import json,sys; from pathlib import Path; '
            'source=Path(sys.argv[1]).resolve(); sys.path.insert(0,str(source)); '
            'from tools.qa.native_cabi_replay import run as snapshot_controller; '
            'snapshot_controller.require(Path(snapshot_controller.__file__).resolve().is_relative_to(source), '
            '"Parity controller escaped source snapshot"); '
            'snapshot_controller.ctypes_parity(source,Path(sys.argv[2]),Path(sys.argv[3]),json.loads(Path(sys.argv[4]).read_text()))',
            source, library, inputs, expected]


def execute(runner, config, compiler):
    env = native_environment(config)
    runner.receipt.update(config=config, sanitizer_environment={key: env[key] for key in ('ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS') if key in env},
                          lsan='ENABLED_NATIVE_ORDINARY_EXIT' if config == 'sanitize' else 'NOT_RUN',
                          ctypes_parity='NOT_RUN' if config != 'normal' else 'PENDING')
    compiler = Path(shutil.which(compiler) or compiler).resolve(strict=True)
    runner.receipt['compiler_sha256'] = digest(compiler)
    runner.receipt['compiler_version'] = runner.command('compiler-version', [compiler, '--version'])
    source = snapshot(runner)
    inputs = runner.output / 'inputs'
    runner.command('prepare-inputs', [sys.executable, source / 'tools/qa/native_cabi_replay/prepare.py', '--source', source, '--output', inputs])
    manifest = json.loads((inputs / 'manifest.json').read_text())
    runner.receipt['inputs'] = manifest
    runner.save()
    flags = [] if config == 'normal' else ['-fsanitize=' + ('address,' if config == 'sanitize' else '') + 'undefined,float-cast-overflow', '-fno-sanitize-recover=all', '-fno-omit-frame-pointer']
    build = runner.output / 'build'
    runner.command('configure', ['cmake', '-S', source, '-B', build, '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=RelWithDebInfo', '-DRF_REQUIRE_CAMPAIGN_031_060=ON',
                                '-DCMAKE_CXX_COMPILER=' + str(compiler), '-DCMAKE_CXX_FLAGS=' + ' '.join(flags),
                                '-DCMAKE_SHARED_LINKER_FLAGS=' + ' '.join(flags)])
    runner.command('build', ['cmake', '--build', build, '--target', 'realityforge', '-j2'])
    library = build / 'librealityforge.so'
    executable = runner.output / 'native-cabi-replay'
    runner.command('compile-driver', [compiler, '-std=c++20', '-O1', '-g', '-Wall', '-Wextra', '-Werror', *flags,
                                      '-I' + str(source / 'reconstruction/include'), source / 'tools/qa/native_cabi_replay/driver.cpp',
                                      library, '-Xlinker', '-rpath', '-Xlinker', build, '-o', executable])
    env['LD_LIBRARY_PATH'] = str(build) + (os.pathsep + env['LD_LIBRARY_PATH'] if env.get('LD_LIBRARY_PATH') else '')
    runner.receipt['library_sha256'], runner.receipt['driver_sha256'] = digest(library), digest(executable)
    linkage = runner.command('driver-linkage', ['ldd', executable], env)
    require(str(library) in linkage, 'Loader selected a different native library')
    runner.receipt['linked_library_hashes'] = {match: digest(Path(match)) for match in re.findall(r'=> (/[^\s]+)', linkage) if Path(match).is_file()}
    runner.save()
    if config != 'normal':
        for name, binary in [('library', library), ('driver', executable)]:
            symbols = runner.command(name + '-symbols', ['nm', '-D', binary])
            require('__ubsan_handle' in symbols, 'UBSan instrumentation missing: ' + name)
            if config == 'sanitize':
                require('__asan_' in symbols, 'ASan instrumentation missing: ' + name)
    if config == 'sanitize':
        control = runner.output / 'native-control'
        runner.command('compile-controls', [compiler, '-x', 'c', '-g', '-O0', *flags,
                                           source / 'tools/qa/python_lsan_comparison/control.c', '-o', control])
        require_native_log(runner.command('native-empty-control', [control], env))
        planted = runner.command('native-planted-control', [control, 'plant'], env, expected=23)
        require(planted_detected(planted, 23), 'Exact planted1237-byte native leak not detected')
    results = {}
    for case, metadata in manifest['cases'].items():
        path = inputs / (case + '.bin')
        require(digest(path) == metadata['sha256'], 'Input identity changed')
        output = runner.output / 'results' / case
        require_native_log(runner.command('replay-' + case, [executable, path, output, 2], env))
        results[case] = verify(case, output)
    malformed(runner, executable, env)
    if config == 'normal':
        expected = runner.output / 'normal-expected.json'
        expected.write_text(json.dumps(results))
        runner.command('ordinary-ctypes-parity', ordinary_parity_command(source, library, inputs, expected))
        runner.receipt['ctypes_parity'] = 'PASS_ORDINARY_UNINSTRUMENTED_ONLY'
    runner.receipt['status'] = 'PASS_SUPPLEMENTARY_DIAGNOSTIC_ONLY'


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--config', choices=('normal', 'ubsan', 'sanitize'), required=True)
    parser.add_argument('--compiler', default='g++')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args(argv)
    require(1 <= args.timeout <= 1800, 'Timeout outside1..1800 seconds')
    output = args.output.resolve()
    require(not output.is_relative_to(ROOT), 'Evidence directory must be outside source checkout')
    runner = Runner(output, args.timeout)
    try:
        execute(runner, args.config, args.compiler)
        return 0
    except Exception as error:
        runner.receipt.update(status='FAIL', failure=type(error).__name__ + ': ' + str(error))
        print(runner.receipt['failure'], file=sys.stderr)
        return 1
    finally:
        runner.receipt['artifact_hashes'] = {str(path.relative_to(output)): digest(path) for directory in ('inputs', 'results')
                                           for path in sorted((output / directory).rglob('*')) if path.is_file()}
        runner.save()


if __name__ == '__main__':
    raise SystemExit(main())
