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
    p.add_argument('--skip-build', action='store_true', help='Use already-built, hash-recorded executables')
    args = p.parse_args(argv)
    if not 1 <= args.runs <= 1_000_000 or not 1 <= args.seconds <= 3600 or args.seed < 0:
        p.error('Use bounded positive runs/time and nonnegative seed')
    args.output.mkdir(parents=True, exist_ok=True)
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    dirty = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True))
    evidence = {'schema': 1, 'agent': '082', 'source_sha': source, 'source_dirty': dirty,
                'synthetic': True, 'seed': args.seed, 'status': 'NOT_RUN',
                'android_jni_instrumentation': 'NOT_RUN', 'steps': []}
    def finish():
        (args.output / 'evidence.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps({'status': evidence['status'], 'evidence': str(args.output / 'evidence.json')}))
        return 0 if evidence['status'] == 'PASS' else 1
    if not args.compiler or not Path(args.compiler).is_file():
        evidence['blocker'] = 'Clang and compiler-rt/libFuzzer required'
        return finish()
    evidence['compiler'] = subprocess.check_output([args.compiler, '--version'], text=True).splitlines()[0]
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
    # Coverage/sanitizer runtime limits stay enabled. No leak/error suppressions.
    environment = os.environ.copy()
    environment['ASAN_OPTIONS'] = 'detect_leaks=1:abort_on_error=1'
    environment['UBSAN_OPTIONS'] = 'halt_on_error=1:print_stacktrace=1'
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
        step.update(target=kind, binary_sha256=sha(binary),
                    artifacts=[{'path': str(f), 'sha256': sha(f)} for f in sorted(crash.iterdir()) if f.is_file()])
        text = (args.output / (kind + '.log')).read_text()
        step['completed_runs'] = [int(v) for v in re.findall(r'Done (\d+) runs', text)]
        if not step['completed_runs']:
            step['status'] = 'FAIL'
        evidence['steps'].append(step)
    evidence['status'] = 'PASS' if all(s['status'] == 'PASS' for s in evidence['steps']) else 'FAIL'
    return finish()


if __name__ == '__main__':
    raise SystemExit(main())
