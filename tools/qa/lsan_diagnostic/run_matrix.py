"""Bounded diagnostic only; owns and drains every process group it launches."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import resource
import subprocess
import time

HERE = Path(__file__).resolve().parent
LOG_LIMIT = 4 * 1024 * 1024


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def group_exists(pgid):
    try:
        os.killpg(pgid, 0)
        return True
    except ProcessLookupError:
        return False


def drain(process):
    """Always terminate descendants, including after the group leader exits."""
    survived = group_exists(process.pid)
    for sig, grace in ((signal.SIGTERM, 1.0), (signal.SIGKILL, 2.0)):
        try:
            os.killpg(process.pid, sig)
        except ProcessLookupError:
            pass
        until = time.monotonic() + grace
        while time.monotonic() < until:
            process.poll()
            if not group_exists(process.pid):
                break
            time.sleep(.05)
    process.wait(timeout=1)
    return survived, not group_exists(process.pid)


def child_limits():
    # This diagnostic runner is single-threaded. Limits affect only each child
    # and its descendants, not production processes or the orchestrator.
    resource.setrlimit(resource.RLIMIT_FSIZE, (LOG_LIMIT, LOG_LIMIT))
    resource.setrlimit(resource.RLIMIT_CORE, (0, 0))


def run(argv, env, folder, name, timeout=30):
    log = folder / name
    process = None
    reason = None
    code = 127
    drained = True
    with log.open('xb') as stream:
        try:
            process = subprocess.Popen(list(map(str, argv)), env=env, stdout=stream,
                                       stderr=subprocess.STDOUT, start_new_session=True,
                                       preexec_fn=child_limits)
            started = time.monotonic()
            while process.poll() is None:
                if time.monotonic() - started > timeout:
                    reason = 'TIMEOUT'
                    break
                if log.stat().st_size >= LOG_LIMIT:
                    reason = 'LOG_LIMIT'
                    break
                time.sleep(.05)
        except BaseException as error:
            reason = 'INTERRUPTED' if isinstance(error, (KeyboardInterrupt, InterruptedError)) else 'EXEC_FAILURE'
            marker = (type(error).__name__ + '\n').encode()
            stream.write(marker[:max(0, LOG_LIMIT-log.stat().st_size)])
        finally:
            # Block repeated termination while the reserved cleanup window runs.
            previous = {s: signal.signal(s, signal.SIG_IGN) for s in (signal.SIGTERM, signal.SIGINT)}
            try:
                if process:
                    already_exited = process.poll() is not None
                    survived, drained = drain(process)
                    code = process.returncode
                    if not drained:
                        reason = 'GROUP_DRAIN_FAILED'
                    elif already_exited and survived and reason is None:
                        reason = 'ORPHAN_DESCENDANTS'
            finally:
                for sig, handler in previous.items():
                    signal.signal(sig, handler)
    if log.stat().st_size >= LOG_LIMIT and reason is None:
        reason = 'LOG_LIMIT'
    return dict(exit_code=code, reason=reason, log=name, log_bytes=log.stat().st_size,
                sha256=sha(log), group_drained=drained)


def terminate(signum, frame):
    raise InterruptedError('termination requested')


def save(folder, receipt):
    temporary = folder/'receipt.tmp'
    temporary.write_text(json.dumps(receipt, indent=2)+'\n')
    temporary.replace(folder/'receipt.json')


def main():
    p = argparse.ArgumentParser()
    for name in ('compiler', 'runtime', 'cxx-runtime', 'python'):
        p.add_argument('--'+name, required=True)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--lock', type=Path, required=True)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    receipt = dict(schema=2, status='RUNNING', records=[], pins_before={}, pins_after={})
    signal.signal(signal.SIGTERM, terminate)
    signal.signal(signal.SIGINT, terminate)
    try:
        for key in ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS'):
            if os.environ.get(key):
                raise RuntimeError('nonclean_orchestrator_'+key)
        files = {p.name:p for p in HERE.glob('*.py')}
        files.update({'faults.c':HERE/'faults.c', 'requirements.lock':args.lock,
                      'compiler':Path(args.compiler), 'asan_runtime':Path(args.runtime),
                      'cxx_runtime':Path(args.cxx_runtime), 'python':Path(args.python)})
        receipt['pins_before'] = {k:sha(v) for k,v in files.items()}
        save(args.output, receipt)
        env = os.environ.copy()
        helper = args.output/'faults.so'
        row = run([args.compiler, '-shared', '-fPIC', '-g', '-O0', '-fno-omit-frame-pointer',
                   '-fsanitize=address,undefined', '-pthread', HERE/'faults.c', '-o', helper],
                  env, args.output, 'compile.log', 60)
        row['kind'] = 'compile'
        receipt['records'].append(row)
        save(args.output, receipt)
        if row['exit_code'] or row['reason']:
            raise RuntimeError('compile_failed')
        receipt['helper_sha256'] = sha(helper)
        child = env | dict(LD_PRELOAD=args.runtime+':'+args.cxx_runtime,
                           ASAN_OPTIONS='halt_on_error=1:detect_leaks=1:fast_unwind_on_malloc=0',
                           UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
                           PYTHONMALLOC='malloc', OPENBLAS_NUM_THREADS='1', OMP_NUM_THREADS='1')
        for kind in ('empty', 'numpy', 'leak', 'uaf', 'ub'):
            for check in ('exit', 'observe', 'boundary'):
                row = run([args.python, HERE/'probe.py', '--kind', kind, '--check', check,
                           '--helper', helper], child, args.output, kind+'-'+check+'.log')
                row.update(kind=kind, check=check)
                receipt['records'].append(row)
                save(args.output, receipt)
                if row['reason'] in ('INTERRUPTED', 'GROUP_DRAIN_FAILED'):
                    raise RuntimeError('execution_interrupted_or_group_undrained')
        receipt['pins_after'] = {k:sha(v) for k,v in files.items()}
        if receipt['pins_before'] != receipt['pins_after'] or receipt['helper_sha256'] != sha(helper):
            raise RuntimeError('input_changed_during_proof')
        receipt['status'] = 'COMPLETE'
    except BaseException as error:
        receipt.update(status='FAILED', error=type(error).__name__+':'+str(error))
    finally:
        save(args.output, receipt)
    print(args.output/'receipt.json')
    return 0 if receipt['status'] == 'COMPLETE' else 1

if __name__ == '__main__':
    raise SystemExit(main())
