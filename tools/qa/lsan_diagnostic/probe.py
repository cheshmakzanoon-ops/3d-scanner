"""Diagnostic only: no sanitizer options are changed in this process."""
import argparse
import ctypes
import gc
import json
import os
import sys
import threading


def workload(kind, helper):
    if kind == 'numpy':
        import numpy
        # Exercise import only; no first-party NativeEngine is loaded.
        print(json.dumps({'numpy': numpy.__version__}), flush=True)
    elif kind in ('leak', 'uaf', 'ub'):
        lib = ctypes.CDLL(helper)
        call = getattr(lib, 'rf_proof_' + kind)
        call.argtypes = []
        call.restype = None
        call()
    elif kind != 'empty':
        raise ValueError(kind)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--kind', choices=['empty', 'numpy', 'leak', 'uaf', 'ub'], required=True)
    p.add_argument('--check', choices=['exit', 'observe', 'boundary'], required=True)
    p.add_argument('--helper', required=True)
    args = p.parse_args()
    print(json.dumps({'python': sys.version, 'executable': sys.executable,
                      'kind': args.kind, 'check': args.check,
                      'asan': os.environ.get('ASAN_OPTIONS'),
                      'ubsan': os.environ.get('UBSAN_OPTIONS'), 'lsan': os.environ.get('LSAN_OPTIONS')}), flush=True)
    # Function return destroys the workload's local namespace. Shared module
    # caches remain legitimate interpreter roots until interpreter finalization.
    workload(args.kind, args.helper)
    if any(t is not threading.current_thread() and t.is_alive()
           for t in threading.enumerate()):
        raise RuntimeError('Workload left live Python workers')
    # Native helper explicitly joins its own worker; Python enumeration alone
    # does not establish native thread cleanup for arbitrary future workloads.
    print(json.dumps({'gc_collected': gc.collect()}), flush=True)
    if args.check != 'exit':
        runtime = ctypes.CDLL(None)
        if args.check == 'observe':
            check = runtime.__lsan_do_recoverable_leak_check
            check.argtypes = []
            check.restype = ctypes.c_int
            found = check()
            print(json.dumps({'prefinalization_leaks': found}), flush=True)
            # Ordinary finalization check remains active in diagnostic mode.
            if found:
                return 73
        else:
            check = runtime.__lsan_do_leak_check
            check.argtypes = []
            check.restype = None
            print('boundary_check_begin', flush=True)
            check()
            print('boundary_check_clean', flush=True)
    return 0

if __name__ == '__main__':
    raise SystemExit(main())
