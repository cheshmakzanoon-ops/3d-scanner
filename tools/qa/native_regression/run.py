#!/usr/bin/env python3
"""Bounded exact-source GCC/Clang Release + ASan/UBSan numerical gate."""
import argparse
import hashlib
import json
import os
import platform
import shutil
import signal
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from numerical import compare

ROOT = Path(__file__).resolve().parents[3]
CONFIGS = ('gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def command(argv, cwd, folder, label, env=None, timeout=600):
    folder.mkdir(parents=True, exist_ok=True)
    log = folder / (label + '.log')
    started = time.monotonic()
    reason = None
    with log.open('wb') as stream:
        try:
            process = subprocess.Popen(list(map(str, argv)), cwd=cwd, env=env,
                                       stdout=stream, stderr=subprocess.STDOUT, start_new_session=True)
            while process.poll() is None:
                if time.monotonic() - started > timeout or log.stat().st_size > 32 * 1024**2:
                    reason = 'TIMEOUT' if time.monotonic() - started > timeout else 'LOG_LIMIT'
                    os.killpg(process.pid, signal.SIGKILL)
                    break
                time.sleep(.1)
            code = process.wait()
        except OSError as error:
            stream.write(str(error).encode())
            code, reason = 127, 'EXEC_FAILURE'
    return dict(command=list(map(str, argv)), cwd=str(cwd), exit_code=code, reason=reason,
                seconds=round(time.monotonic() - started, 3), log=str(log), log_sha256=sha(log),
                result='PASS' if code == 0 and reason is None else 'FAIL')


def write(path, evidence):
    path.write_text(json.dumps(evidence, indent=2, allow_nan=False) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--configs', nargs='+', choices=CONFIGS, default=list(CONFIGS))
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--gcc', default='g++')
    parser.add_argument('--clang', default='clang++')
    parser.add_argument('--timeout', type=int, default=600)
    args = parser.parse_args()
    if not 1 <= args.timeout <= 3600:
        parser.error('timeout must be 1..3600 seconds per command')
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    evidence = dict(schema='realityforge.native-regression.v1', started_utc=datetime.now(timezone.utc).isoformat(),
                    source_sha=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                    dirty_tree=bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT)),
                    platform=platform.platform(), python=sys.version, configurations=[], comparisons=[],
                    tolerance=dict(rtol=1e-5, atol=1e-6, rationale='Existing exact geometry 1e-5 and float32 export 1e-6 contracts; same scene/order/IDs required'))
    reference = None
    failed = False
    for name in args.configs:
        compiler = shutil.which(args.gcc if name.startswith('gcc') else args.clang)
        record = dict(name=name, compiler=compiler, commands=[], binaries=[])
        evidence['configurations'].append(record)
        if not compiler:
            record.update(result='NOT_RUN', reason='Compiler not found; install supported GCC/Clang, do not substitute one compiler for both')
            failed = True
            write(output / 'evidence.json', evidence)
            continue
        record['compiler_version'] = subprocess.check_output([compiler, '--version'], text=True)
        record['compiler_sha256'] = sha(Path(compiler).resolve())
        folder = output / name
        build = folder / 'build'
        env = os.environ.copy()
        env.update(OMP_NUM_THREADS='1', OPENBLAS_NUM_THREADS='1', MKL_NUM_THREADS='1',
                   ASAN_OPTIONS='halt_on_error=1:detect_leaks=1', UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
                   PYTHONMALLOC='malloc')
        sanitize = name.endswith('sanitize')
        steps = [('configure', ['cmake', '-S', ROOT, '-B', build, '-G', 'Ninja',
                  '-DCMAKE_BUILD_TYPE=' + ('Debug' if sanitize else 'Release'),
                  '-DCMAKE_CXX_COMPILER=' + compiler, '-DCMAKE_CXX_FLAGS=-Werror',
                  '-DRF_SANITIZE=' + ('ON' if sanitize else 'OFF')]),
                 ('build', ['cmake', '--build', build, '--parallel', '1']),
                 ('ctest', ['ctest', '--test-dir', build, '--output-on-failure', '--timeout', str(args.timeout), '--no-tests=error'])]
        for label, argv in steps:
            receipt = command(argv, ROOT, folder, label, env, args.timeout)
            record['commands'].append(receipt)
            if receipt['result'] != 'PASS':
                break
        else:
            lib = build / 'librealityforge.so'
            record['binaries'] = [dict(path=str(p), sha256=sha(p)) for p in sorted(build.glob('rf_*tests'))] + [dict(path=str(lib), sha256=sha(lib))]
            env['RF_NATIVE_LIBRARY'] = str(lib)
            if sanitize:
                runtime_name = 'libasan.so' if name.startswith('gcc') else 'libclang_rt.asan-x86_64.so'
                runtime = subprocess.check_output([compiler, '-print-file-name=' + runtime_name], text=True).strip()
                if not Path(runtime).is_file():
                    record['commands'].append(dict(result='NOT_RUN', reason='Host ASan runtime missing: ' + runtime))
                else:
                    env['LD_PRELOAD'] = runtime
            python_steps = [('numerical', [sys.executable, ROOT / 'tools/qa/native_regression/numerical.py', '--output', folder / 'numerical.json']),
                            ('pytest', [sys.executable, '-m', 'pytest', 'tests', '-q', '--junitxml=' + str(folder / 'pytest.xml')]),
                            ('bundle-three-view', [sys.executable, 'tools/bundle_three_view.py'])]
            python_steps += [(tool, [sys.executable, 'tools/' + tool + '.py', '--gate', '--output', folder / (tool + '.json')])
                             for tool in ('benchmark_multiview_coverage', 'benchmark_middlebury', 'benchmark_dense_middlebury', 'benchmark_surface_middlebury', 'benchmark_texture_middlebury')]
            for label, argv in python_steps:
                receipt = command(argv, ROOT, folder, label, env, args.timeout)
                record['commands'].append(receipt)
            if (folder / 'numerical.json').exists():
                numeric = json.loads((folder / 'numerical.json').read_text())
                record['numeric_metrics'] = [{k: v for k, v in row.items() if k not in ('points', 'rotation', 'translation')} for row in numeric['scenes']]
                if reference is None:
                    reference = numeric
                else:
                    try:
                        compare(reference, numeric)
                        evidence['comparisons'].append(dict(config=name, result='PASS'))
                    except (ValueError, TypeError) as error:
                        evidence['comparisons'].append(dict(config=name, result='FAIL', reason=str(error)))
                        failed = True
            # Benchmark JSON remains complete, per-scene and independently hashed.
            record['artifacts'] = [dict(path=str(p), sha256=sha(p)) for p in sorted(folder.glob('*.json'))]
        record['result'] = 'PASS' if record['commands'] and all(x['result'] == 'PASS' for x in record['commands']) else 'FAIL'
        failed |= record['result'] != 'PASS'
        write(output / 'evidence.json', evidence)
        print(name + ': ' + record['result'], flush=True)
    evidence['result'] = 'FAIL' if failed else 'PASS'
    write(output / 'evidence.json', evidence)
    return int(failed)


if __name__ == '__main__':
    raise SystemExit(main())
