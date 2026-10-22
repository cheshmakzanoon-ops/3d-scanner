#!/usr/bin/env python3
"""Diagnostic experiment only. No scanner imports, suppressions or early leak checks."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import selectors
import shutil
import signal
import subprocess
import sys
import time

SOURCE_URL = 'https://www.python.org/ftp/python/3.12.15/Python-3.12.15.tar.xz'
SOURCE_HASH = 'c2c4321961fab0fb999d66e0cecf521c2ab3994c7992873ea99e306c1094fd5a'
WHEEL_HASH = 'fd83c01228a688733f1ded5201c678f0c53ecc1006ffbc404db9f7a899ac6249'
MAX_LOG = 4 * 1024 * 1024
HERE = Path(__file__).resolve().parent


def digest(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def clean_env(home, inherited):
    forbidden = ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS')
    if any(k in inherited for k in forbidden):
        raise RuntimeError('Reject inherited sanitizer/loader override: ' + ', '.join(k for k in forbidden if k in inherited))
    # Deliberate allowlist: no inherited Python, compiler, loader, pip, locale or site settings.
    return dict(PATH='/usr/bin:/bin', HOME=str(home), LANG='C.UTF-8', LC_ALL='C.UTF-8',
                PYTHONNOUSERSITE='1', PYTHONSAFEPATH='1', PYTHONDONTWRITEBYTECODE='1',
                OPENBLAS_NUM_THREADS='1', OMP_NUM_THREADS='1', MKL_NUM_THREADS='1')


def kill_group(process):
    try:
        os.killpg(process.pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


class Cancellation:
    def __init__(self):
        self.signum = 0

    def record(self, signum, frame):
        # Never raise in Popen's spawn-to-ownership-assignment window.
        self.signum = signum


def run(command, cwd, env, evidence, name, seconds=30, max_log=MAX_LOG, cancel=None):
    """Reserve the leader PID until all owned-group signaling is finished."""
    cancel = cancel or Cancellation()
    started = time.monotonic()
    result = dict(command=[str(x) for x in command], cwd=str(cwd), timeout=False,
                  truncated=False, exit_code=None, seconds_limit=seconds,
                  cancelled_signal=0, cleanup_errors=[])
    process = None
    try:
        with open(evidence / (name + '.log'), 'wb') as log, selectors.DefaultSelector() as sel:
            if not cancel.signum:
                process = subprocess.Popen(result['command'], cwd=cwd, env=env,
                                           stdin=subprocess.DEVNULL,
                                           stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                           start_new_session=True)
                # Signal handlers only record cancellation, including before this assignment.
                result['pid'] = process.pid
                sel.register(process.stdout, selectors.EVENT_READ)
                total = 0
                while True:
                    if cancel.signum:
                        break
                    remaining = seconds - (time.monotonic() - started)
                    if remaining <= 0:
                        result['timeout'] = True
                        break
                    events = sel.select(min(0.1, remaining)) if sel.get_map() else []
                    for key, _ in events:
                        data = os.read(key.fileobj.fileno(), 65536)
                        if not data:
                            sel.unregister(key.fileobj)
                            continue
                        log.write(data[:max(0, max_log - total)])
                        total += len(data)
                        if total > max_log:
                            result['truncated'] = True
                            break
                    if result['truncated']:
                        break
                    # Never poll()/wait() here: reaping before killpg could permit PID reuse.
                    exited = os.waitid(os.P_PID, process.pid,
                                       os.WEXITED | os.WNOHANG | os.WNOWAIT)
                    if exited is not None and not sel.get_map():
                        break
                    if not sel.get_map():
                        time.sleep(min(0.1, remaining))
    except OSError as exc:
        result['launch_error'] = str(exc)
    finally:
        try:
            if process is not None:
                # Capture cleanup failures independently; none may skip the JSON evidence.
                try:
                    kill_group(process)
                except Exception as exc:
                    result['cleanup_errors'].append('kill_group: ' + repr(exc))
                try:
                    # First and only reap; every group signal precedes this point.
                    result['exit_code'] = process.wait(timeout=5)
                except Exception as exc:
                    result['cleanup_errors'].append('wait: ' + repr(exc))
                try:
                    process.stdout.close()
                except Exception as exc:
                    result['cleanup_errors'].append('close: ' + repr(exc))
        finally:
            result['cancelled_signal'] = cancel.signum
            result['elapsed_seconds'] = round(time.monotonic() - started, 3)
            (evidence / (name + '.json')).write_text(json.dumps(result, indent=2) + '\n')
    return result


def classify(result, log):
    fatal = bool(re.search(r'LeakSanitizer has encountered a fatal error|does not work under ptrace|AddressSanitizer:DEADLYSIGNAL|ERROR: AddressSanitizer|runtime error:|Traceback \(most recent call last\)', log))
    sums = re.findall(r'SUMMARY: AddressSanitizer: (\d+) byte\(s\) leaked in (\d+) allocation\(s\)', log)
    blocks = re.split(r'(?=(?:Direct|Indirect) leak of )', log)
    planted = any(re.match(r'Direct leak of 1237 byte\(s\) in 1 object\(s\)', b) and re.search(r'\bplanted_leak\b', b.split('SUMMARY:')[0]) for b in blocks)
    valid = not (result.get('timeout') or result.get('truncated') or result.get('launch_error') or result.get('cancelled_signal') or result.get('cleanup_errors') or fatal)
    code = result['exit_code']
    clean = valid and code == 0 and not sums and 'LeakSanitizer' not in log
    leak = valid and code == 1 and len(sums) == 1 and 'ERROR: LeakSanitizer: detected memory leaks' in log
    return dict(state='clean' if clean else 'leaks' if leak else 'invalid', fatal=fatal,
                bytes=int(sums[0][0]) if len(sums) == 1 else None,
                allocations=int(sums[0][1]) if len(sums) == 1 else None,
                planted_detected=bool(leak and planted))


class Experiment:
    def __init__(self, output, cancel=None):
        self.cancel = cancel or Cancellation()
        self.root = output.resolve()
        self.root.mkdir(exist_ok=False, parents=True)
        self.evidence = self.root / 'evidence'
        self.evidence.mkdir()
        self.rows = {}
        self.env = {}
        self.deadline = time.monotonic() + 1320  # Leave artifact-upload time in the 30-minute job.

    def command(self, name, args, seconds=30, cwd=None, env=None, require=True):
        if self.cancel.signum:
            raise RuntimeError('Cancelled by signal ' + str(self.cancel.signum))
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError('Overall 22-minute experiment budget exhausted')
        result = run(args, cwd or self.root, env or self.env, self.evidence, name, min(seconds, remaining), cancel=self.cancel)
        if result.get('cancelled_signal') or result.get('cleanup_errors'):
            raise RuntimeError('Cancelled or cleanup failed: ' + name)
        if require and (result['exit_code'] != 0 or result['timeout'] or result['truncated'] or result.get('launch_error')):
            raise RuntimeError('Failed bounded command: ' + name)
        return result

    def cell(self, name, args, env):
        result = self.command(name, args, env=env, require=False)
        result.update(classify(result, (self.evidence / (name + '.log')).read_text(errors='replace')))
        self.rows[name] = result
        self.save()
        return result

    def save(self):
        (self.evidence / 'cells.json').write_text(json.dumps(self.rows, indent=2) + '\n')
        lines = ['# Interpreter-only diagnostic — NOT scanner/release qualification', '',
                 '| Cell | Exit | State | Bytes | Allocations | Planted | Timeout | Truncated |',
                 '|---|---:|---|---:|---:|---|---|---|']
        for name, row in self.rows.items():
            lines.append('| ' + ' | '.join(str(x) for x in (name, row['exit_code'], row['state'], row['bytes'], row['allocations'], row['planted_detected'], row['timeout'], row['truncated'])) + ' |')
        expected = ['native-empty', 'native-planted'] + [arm + '-' + cell for arm in ('release', 'debug') for cell in ('empty', 'numpy', 'planted', 'metadata')]
        for name in expected:
            if name not in self.rows:
                lines.append('| ' + name + ' | — | NOT_RUN | — | — | — | — | — |')
        (self.evidence / 'SUMMARY.md').write_text('\n'.join(lines) + '\n')

    def execute(self):
        home = self.root / 'home'
        home.mkdir()
        self.env = clean_env(home, os.environ)
        (self.evidence / 'environment.json').write_text(json.dumps(dict(environment=self.env, runner_image=os.environ.get('ImageVersion'), runner_os=os.environ.get('ImageOS'), source_sha=os.environ.get('DIAGNOSTIC_SOURCE_SHA')), indent=2))
        shutil.copy2(HERE / 'control.c', self.evidence)
        shutil.copy2(HERE / 'run.py', self.evidence)
        for name, command in [('controller-python', [sys.executable, '--version']), ('gcc', ['gcc-13', '--version']), ('gxx', ['g++-13', '--version']), ('kernel', ['uname', '-a']), ('packages', ['dpkg-query', '-W']), ('os', ['cat', '/etc/os-release'])]:
            self.command(name, command)
        for name, command in [('asan-path', ['gcc-13', '-print-file-name=libasan.so']), ('cxx-path', ['g++-13', '-print-file-name=libstdc++.so']), ('gcc-major', ['gcc-13', '-dumpversion'])]:
            self.command(name, command)
        if (self.evidence / 'gcc-major.log').read_text().strip() != '13':
            raise RuntimeError('Requires GCC13')
        runtimes = [Path((self.evidence / (n + '.log')).read_text().strip()).resolve(strict=True) for n in ('asan-path', 'cxx-path')]
        hashes = {str(p): digest(p) for p in runtimes + [Path('/usr/bin/gcc-13').resolve(), Path('/usr/bin/g++-13').resolve()]}
        (self.evidence / 'toolchain-sha256.json').write_text(json.dumps(hashes, indent=2))
        probe_env = dict(self.env, LD_PRELOAD=':'.join(str(p) for p in runtimes), ASAN_OPTIONS='halt_on_error=1:detect_leaks=1', UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1', PYTHONMALLOC='malloc')
        native = self.root / 'control'
        shared = self.root / 'control.so'
        for name, extra, target in [('control', [], native), ('shared', ['-DSHARED_CONTROL', '-fPIC', '-shared'], shared)]:
            self.command('compile-' + name, ['gcc-13', '-g', '-O0', '-fno-omit-frame-pointer', '-fsanitize=address', *extra, HERE / 'control.c', '-o', target], seconds=60)
        (self.evidence / 'controls-sha256.json').write_text(json.dumps({p.name: digest(p) for p in (native, shared)}, indent=2))
        empty = self.cell('native-empty', [native], probe_env)
        planted = self.cell('native-planted', [native, 'leak'], probe_env)
        if empty['state'] != 'clean' or not planted['planted_detected']:
            raise RuntimeError('Native control prerequisite invalid; no Python inference')
        archive = self.root / 'Python-3.12.15.tar.xz'
        self.command('download-source', ['curl', '--fail', '--location', '--proto', '=https', '--max-time', '120', SOURCE_URL, '-o', archive], seconds=130)
        if digest(archive) != SOURCE_HASH:
            raise RuntimeError('Source hash mismatch')
        (self.evidence / 'source-sha256.txt').write_text(SOURCE_HASH + '\n' + SOURCE_URL + '\n')
        # Extraction is bounded as a subprocess too (host Python supports data filter).
        self.command('extract-source', [sys.executable, '-I', '-c', 'import tarfile,sys; tarfile.open(sys.argv[1]).extractall(sys.argv[2], filter="data")', archive, self.root], seconds=60)
        source = self.root / 'Python-3.12.15'
        self.command('readonly-source', ['chmod', '-R', 'a-w', source], seconds=30)
        metadata = self.root / 'pypi.json'
        self.command('download-wheel-metadata', ['curl', '--fail', '--proto', '=https', '--max-time', '60', 'https://pypi.org/pypi/numpy/2.2.6/json', '-o', metadata], seconds=70)
        matches = [x for x in json.loads(metadata.read_text())['urls'] if x['digests']['sha256'] == WHEEL_HASH]
        if len(matches) != 1:
            raise RuntimeError('Official PyPI wheel pin unavailable')
        wheel_info = matches[0]
        if not wheel_info['url'].startswith('https://files.pythonhosted.org/') or not re.fullmatch(r'numpy-2\.2\.6-cp312-cp312-manylinux[^/]*x86_64\.whl', wheel_info['filename']):
            raise RuntimeError('Unexpected wheel identity/origin')
        wheel = self.root / wheel_info['filename']
        (self.evidence / 'wheel-origin.json').write_text(json.dumps(wheel_info, indent=2))
        self.command('download-wheel', ['curl', '--fail', '--proto', '=https', '--max-time', '120', wheel_info['url'], '-o', wheel], seconds=130)
        if digest(wheel) != WHEEL_HASH:
            raise RuntimeError('Wheel hash mismatch')
        deps = self.root / 'numpy-wheel'
        deps.mkdir()
        self.command('extract-wheel', [sys.executable, '-I', '-c', 'import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])', wheel, deps], seconds=30)
        probe_env['PYTHONPATH'] = str(deps)
        (self.evidence / 'probe-environment.json').write_text(json.dumps(probe_env, indent=2))
        control_failures = []
        for arm in ('release', 'debug'):
            build = self.root / arm
            build.mkdir()
            started = time.monotonic()
            try:
                self.command(arm + '-configure', [source / 'configure', '--with-ensurepip=no', *(['--with-pydebug'] if arm == 'debug' else [])], cwd=build, env=dict(self.env, CC='gcc-13', CXX='g++-13'), seconds=600)
                self.command(arm + '-make', ['make', '-j2'], cwd=build, seconds=max(0.001, 600 - (time.monotonic() - started)))
            finally:
                # Includes failed configure/builds; never delete the evidence.
                for filename in ('config.log', 'config.status', 'pyconfig.h', 'Makefile'):
                    if (build / filename).is_file():
                        original = build / filename
                        with original.open('rb') as stream:
                            data = stream.read(MAX_LOG + 1)
                        (self.evidence / (arm + '-' + filename)).write_bytes(data[:MAX_LOG])
                        if len(data) > MAX_LOG:
                            (self.evidence / (arm + '-' + filename + '.TRUNCATED')).write_text('INVALID: configuration evidence exceeded 4 MiB cap\n')
                            raise RuntimeError('Truncated build evidence: ' + arm + '/' + filename)
            python = build / 'python'
            (self.evidence / (arm + '-python-sha256.txt')).write_text(digest(python) + '\n')
            self.cell(arm + '-empty', [python, '-s', '-P', '-c', 'pass'], probe_env)
            self.cell(arm + '-numpy', [python, '-s', '-P', '-c', 'import numpy'], probe_env)
            control = self.cell(arm + '-planted', [python, '-s', '-P', '-c', 'import ctypes,sys; helper=ctypes.CDLL(sys.argv[1]); helper.planted_leak.argtypes=[]; helper.planted_leak.restype=None; helper.planted_leak()', shared], probe_env)
            code = ('import sys,sysconfig,json,numpy; print("METADATA="+json.dumps(dict(version=sys.version,version_info=list(sys.version_info),executable=sys.executable,config_args=sysconfig.get_config_var("CONFIG_ARGS"),py_debug=sysconfig.get_config_var("Py_DEBUG"),numpy_version=numpy.__version__,numpy_file=numpy.__file__,path=sys.path))); print("MAPS_BEGIN"); print(open("/proc/self/maps").read())')
            self.cell(arm + '-metadata', [python, '-s', '-P', '-c', code], probe_env)
            log = (self.evidence / (arm + '-metadata.log')).read_text(errors='replace')
            found = re.findall(r'^METADATA=(.*)$', log, re.MULTILINE)
            if len(found) != 1:
                raise RuntimeError('Missing metadata for ' + arm)
            meta = json.loads(found[0])
            (self.evidence / (arm + '-metadata-parsed.json')).write_text(json.dumps(meta, indent=2))
            if (meta['version_info'][:3] != [3, 12, 15] or meta['py_debug'] != int(arm == 'debug') or meta['numpy_version'] != '2.2.6' or not Path(meta['numpy_file']).is_relative_to(deps)):
                raise RuntimeError('Interpreter/wheel metadata mismatch: ' + arm)
            if not control['planted_detected']:
                control_failures.append(arm)
        if control_failures:
            raise RuntimeError('Python planted controls invalid: ' + ', '.join(control_failures))
        if any(row['state'] == 'invalid' for row in self.rows.values()):
            raise RuntimeError('Invalid cell; see preserved evidence')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    cancel = Cancellation()
    experiment = Experiment(args.output, cancel)
    previous = {sig: signal.getsignal(sig) for sig in (signal.SIGTERM, signal.SIGINT)}
    for sig in previous:
        signal.signal(sig, cancel.record)
    try:
        experiment.execute()
        if cancel.signum:
            raise RuntimeError('Cancelled by signal ' + str(cancel.signum))
    except Exception as exc:
        (experiment.evidence / 'FAILURE.txt').write_text(type(exc).__name__ + ': ' + str(exc) + '\n')
        print(str(exc), file=sys.stderr)
        return 1
    finally:
        try:
            experiment.save()
        finally:
            for sig, handler in previous.items():
                signal.signal(sig, handler)
    if cancel.signum:
        (experiment.evidence / 'FAILURE.txt').write_text('Cancelled by signal ' + str(cancel.signum) + '\n')
        return 1
    (experiment.evidence / 'COMPLETE.txt').write_text('Interpretable interpreter-only experiment; NOT scanner/release qualification.\n')
    return 0


if __name__ == '__main__':
    sys.exit(main())
