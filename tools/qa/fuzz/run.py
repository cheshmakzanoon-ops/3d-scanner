#!/usr/bin/env python3
"""Bounded compiler-sanitized regression runner for the app's native inputs."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import time
import zlib

ROOT = Path(__file__).resolve().parents[3]
KINDS = ('numeric', 'checkpoint', 'exports')
STAMP_NAME = '.rf-fuzz-buildstamp.json'


def fresh_output(directory: Path) -> Path:
    """Never mix a new qualification run with any prior files or symlink targets."""
    directory = directory.absolute()
    if any(p.is_symlink() for p in (directory, *directory.parents)):
        raise ValueError('Evidence directory and its parents must not be symlinks')
    directory.parent.mkdir(parents=True, exist_ok=True)
    try:
        directory.mkdir()  # Also rejects an existing empty/reused directory.
    except FileExistsError as error:
        raise ValueError('Evidence directory must be new and nonexistent') from error
    return directory.resolve()


def source_snapshot() -> dict:
    paths = [ROOT / 'CMakeLists.txt', ROOT / 'tools/qa/fuzz/run.py']
    paths += [p for p in (ROOT / 'reconstruction').rglob('*') if p.is_file() or p.is_symlink()]
    records = []
    for path in sorted(set(paths)):
        if path.is_symlink() or not path.is_file():
            raise ValueError('Native source must be a regular file: ' + str(path))
        records.append(dict(path=path.relative_to(ROOT).as_posix(), bytes=path.stat().st_size, sha256=sha(path)))
    encoded = json.dumps(records, sort_keys=True, separators=(',', ':')).encode()
    return dict(source_sha=subprocess.check_output(['git', 'rev-parse', '--verify', 'HEAD^{commit}'], cwd=ROOT, text=True).strip(),
                source_dirty=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True)),
                source_fingerprint=hashlib.sha256(encoded).hexdigest(), source_files=records)


def compiler_record(compiler: str) -> dict:
    # Preserve clang++'s invocation name: resolving its symlink to clang would
    # change the driver's default C++ link behavior.
    path = Path(compiler).absolute()
    return dict(path=str(path), resolved_path=str(path.resolve()), sha256=sha(path),
                version=subprocess.check_output([str(path), '--version'], text=True, timeout=10).strip())


def build_record(build: Path, compiler: dict, source: dict) -> dict:
    def output(path):
        if path.is_symlink() or not path.is_file():
            raise ValueError('Missing regular build output: ' + str(path))
        return dict(path=str(path.resolve()), sha256=sha(path), bytes=path.stat().st_size)
    return dict(schema=1, build_source_sha=source['source_sha'], build_source_dirty=source['source_dirty'],
                source_fingerprint=source['source_fingerprint'], source_files=source['source_files'],
                compiler=compiler, configuration='RelWithDebInfo;address,undefined;libFuzzer;parallel=1',
                binaries={kind: output(build / ('rf_fuzz_' + kind)) for kind in KINDS},
                library=output(build / 'native/librealityforge.so'))


def verify_build(build: Path, expected: dict) -> dict:
    path = build / STAMP_NAME
    if path.is_symlink() or not path.is_file():
        raise ValueError('No regular buildstamp; run a normal build before --skip-build')
    saved = json.loads(path.read_text())
    if saved != expected:
        raise ValueError('Buildstamp mismatch: source revision/fingerprint, compiler, executable or native library changed; rebuild required')
    return saved


def completion(log: str, runs: int, seconds: int, elapsed: float) -> dict:
    matches = re.findall(r'^Done (\d+) runs in (\d+) second\(s\)\s*$', log, re.MULTILINE)
    counts = [int(n) for n, _ in matches]
    reported = int(matches[0][1]) if len(matches) == 1 else None
    positive = len(counts) == 1 and counts[0] > 0
    budget = None
    if positive:
        if counts[0] >= runs:
            budget = 'runs'
        elif reported >= seconds and elapsed >= seconds:
            budget = 'time'
    return dict(completed_runs=counts, reported_seconds=reported,
                requested_runs=runs, time_budget_seconds=seconds, budget_completion=budget)


def sha(path: Path) -> str:
    with path.open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def corpus(directory: Path) -> dict:
    """Small first-party generated fixtures, never real photographs or private files."""
    cases = {'numeric': {'empty': b'', 'zeros': bytes(1024),
                         'boundary': bytes(range(256)) * 4,
                         'nonfinite': b'\x00\x10\x00' + struct.pack('<d', float('nan')) * 112},
             'checkpoint': {'truncated': b'RFDPT018', 'bad_magic': bytes(96)},
             'exports': {'empty': b'', 'point': b'\x00\x01' + struct.pack('<ddd', 0, 0, 3) + bytes([128, 64, 32]),
                         'triangle': b'\x01' + struct.pack('<ddd', 0, 0, 3) + bytes([0, 1, 2]),
                         'textured_boundary': b'\x02' + bytes(range(96))}}
    payload = struct.pack('<ff', 4.0, .75) * 1024
    header = b'RFDPT018' + struct.pack('<IIIII', 1, 32, 32, 0, len(payload)) + b'a' * 64
    valid = header + struct.pack('<I', zlib.crc32(payload)) + payload
    cases['checkpoint']['valid32'] = valid
    cases['checkpoint']['bad_crc'] = valid[:-1] + bytes([valid[-1] ^ 1])
    records = {}
    for kind, seeds in cases.items():
        folder = directory / kind
        folder.mkdir(parents=True, exist_ok=True)
        records[kind] = []
        for name, data in seeds.items():
            path = folder / name
            path.write_bytes(data)
            records[kind].append({'name': name, 'bytes': len(data), 'sha256': sha(path)})
    return {'schema': 1, 'synthetic': True, 'license': 'CC0-1.0 generated test bytes', 'targets': records}


def invoke(command: list[str], log: Path, timeout: int, environment: dict | None = None) -> dict:
    start = time.monotonic()
    try:
        with log.open('w') as stream:
            process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT,
                                     timeout=timeout, env=environment)
        code = process.returncode
        state = 'PASS' if code == 0 else 'FAIL'
    except subprocess.TimeoutExpired:
        code, state = None, 'FAIL'
        with log.open('a') as stream:
            stream.write('\nHarness wall-clock timeout; incomplete run is FAIL.\n')
    return {'command': command, 'exit_code': code, 'status': state,
            'seconds': time.monotonic() - start, 'log': str(log), 'log_sha256': sha(log)}


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--build', type=Path, default=ROOT / 'build/fuzz')
    p.add_argument('--compiler', default=shutil.which('clang++') or '')
    p.add_argument('--runs', type=int, default=1000)
    p.add_argument('--seconds', type=int, default=30)
    p.add_argument('--seed', type=int, default=82019)
    p.add_argument('--skip-build', action='store_true', help='Require an exact source/compiler/binary/library buildstamp match')
    args = p.parse_args(argv)
    if not 1 <= args.runs <= 1_000_000 or not 1 <= args.seconds <= 3600 or args.seed < 0:
        p.error('Use bounded positive runs/time and nonnegative seed')
    try:
        args.output = fresh_output(args.output)
    except ValueError as error:
        p.error(str(error))
    args.build = args.build.resolve()
    if args.build == ROOT or ROOT / 'reconstruction' in (args.build, *args.build.parents):
        p.error('Build directory must remain outside native source directories')
    source = source_snapshot()
    evidence = {'schema': 1, 'agent': '082', **source,
                'synthetic': True, 'seed': args.seed, 'status': 'NOT_RUN',
                'android_jni_instrumentation': 'NOT_RUN', 'steps': []}
    def finish():
        (args.output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps({'status': evidence['status'], 'evidence': str(args.output / 'evidence.json')}))
        return 0 if evidence['status'] == 'PASS' else 1
    args.compiler = shutil.which(args.compiler) or args.compiler
    if not args.compiler or not Path(args.compiler).is_file():
        evidence['blocker'] = 'Clang and compiler-rt/libFuzzer required'
        return finish()
    args.compiler = str(Path(args.compiler).absolute())
    compiler = compiler_record(args.compiler)
    evidence['compiler'] = compiler['version'].splitlines()[0]
    evidence['compiler_identity'] = compiler
    seeds = corpus(args.output / 'corpus')
    (args.output / 'corpus.json').write_text(json.dumps(seeds, indent=2) + '\n')
    if not args.skip_build:
        steps = [('configure', ['cmake', '-S', str(ROOT / 'reconstruction/fuzz'), '-B', str(args.build),
                                '-G', 'Ninja', '-DCMAKE_BUILD_TYPE=RelWithDebInfo',
                                '-DCMAKE_CXX_COMPILER=' + args.compiler]),
                 ('build', ['cmake', '--build', str(args.build), '--parallel', '1', '--target',
                            *['rf_fuzz_' + k for k in KINDS]])]
        for name, command in steps:
            step = invoke(command, args.output / (name + '.log'), 1200)
            evidence['steps'].append(step)
            if step['status'] != 'PASS':
                evidence['status'] = 'FAIL'
                return finish()
    try:
        if source_snapshot() != source or compiler_record(args.compiler) != compiler:
            raise ValueError('Source/compiler changed while building')
        built = build_record(args.build, compiler, source)
        stamp = args.build / STAMP_NAME
        if args.skip_build:
            verify_build(args.build, built)
        else:
            if stamp.is_symlink():
                raise ValueError('Buildstamp must not be a symlink')
            pending = stamp.with_suffix('.tmp')
            if pending.exists() or pending.is_symlink():
                raise ValueError('Refuse an existing temporary buildstamp')
            pending.write_text(json.dumps(built, indent=2) + '\n')
            pending.replace(stamp)
        retained_stamp = args.output / 'buildstamp.json'
        shutil.copyfile(stamp, retained_stamp)
        evidence.update(buildstamp=str(retained_stamp), build_source_sha=built['build_source_sha'], build_source_dirty=built['build_source_dirty'],
                        build_source_fingerprint=built['source_fingerprint'], library=built['library'],
                        buildstamp_sha256=sha(retained_stamp))
    except (ValueError, OSError, json.JSONDecodeError) as error:
        evidence['status'], evidence['blocker'] = 'FAIL', str(error)
        return finish()
    # Coverage/sanitizer runtime limits stay enabled. No leak/error suppressions.
    environment = os.environ.copy()
    environment['ASAN_OPTIONS'] = 'detect_leaks=1:abort_on_error=1'
    environment['UBSAN_OPTIONS'] = 'halt_on_error=1:print_stacktrace=1'
    inherited_library_path = environment.get('LD_LIBRARY_PATH', '')
    environment['LD_LIBRARY_PATH'] = str(Path(built['library']['path']).parent) + (os.pathsep + inherited_library_path if inherited_library_path else '')
    for kind in KINDS:
        binary = args.build / ('rf_fuzz_' + kind)
        if not binary.is_file():
            evidence['status'] = 'FAIL'; evidence['blocker'] = 'Missing built target: ' + kind
            return finish()
        crash = args.output / 'artifacts' / kind
        crash.mkdir(parents=True, exist_ok=True)
        command = [str(binary), str(args.output / 'corpus' / kind), f'-runs={args.runs}',
                   f'-max_total_time={args.seconds}', '-timeout=3', '-rss_limit_mb=1024',
                   '-malloc_limit_mb=256', '-max_len=16384', f'-seed={args.seed}',
                   '-error_exitcode=77', '-artifact_prefix=' + str(crash) + '/']
        step = invoke(command, args.output / (kind + '.log'), args.seconds + 60, environment)
        step.update(target=kind, binary_sha256=sha(binary), library_sha256=built['library']['sha256'],
                    build_source_sha=built['build_source_sha'], source_fingerprint=built['source_fingerprint'],
                    artifacts=[{'path': str(f), 'sha256': sha(f)} for f in sorted(crash.iterdir()) if f.is_file()])
        text = (args.output / (kind + '.log')).read_text()
        step.update(completion(text, args.runs, args.seconds, step['seconds']))
        if step['budget_completion'] is None or step['artifacts']:
            step['status'] = 'FAIL'
        evidence['steps'].append(step)
        try:
            if source_snapshot() != source or compiler_record(args.compiler) != compiler:
                raise ValueError('Source/compiler changed during execution')
            verify_build(args.build, build_record(args.build, compiler, source))
        except (ValueError, OSError, json.JSONDecodeError) as error:
            step['status'], step['blocker'] = 'FAIL', str(error)
            break
    evidence['status'] = 'PASS' if all(s['status'] == 'PASS' for s in evidence['steps']) else 'FAIL'
    return finish()


if __name__ == '__main__':
    raise SystemExit(main())
