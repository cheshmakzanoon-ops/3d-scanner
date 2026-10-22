#!/usr/bin/env python3
"""Host-only ordinary-exit control: no scanner import or sanitizer API calls."""
import hashlib
import importlib
import json
import os
from pathlib import Path
import sys
import sysconfig


ENVIRONMENT_KEYS = ('LD_PRELOAD', 'LD_AUDIT', 'ASAN_OPTIONS', 'LSAN_OPTIONS', 'UBSAN_OPTIONS',
                    'PYTHONMALLOC', 'OMP_NUM_THREADS', 'OPENBLAS_NUM_THREADS', 'MKL_NUM_THREADS')


def identity(path):
    resolved = Path(path).resolve(strict=True)
    digest = hashlib.sha256()
    with resolved.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return dict(path=str(path), resolved_path=str(resolved), sha256=digest.hexdigest())


def scanner_mappings():
    # Reading the Linux map does not load the selected candidate. Fail closed if
    # loaded-library evidence cannot be read, rather than inventing independence.
    candidate = os.path.realpath(os.environ.get('RF_NATIVE_LIBRARY', ''))
    matches = []
    for line in Path('/proc/self/maps').read_text().splitlines():
        fields = line.split(maxsplit=5)
        if len(fields) == 6:
            path = fields[5].removesuffix(' (deleted)')
            if 'librealityforge' in path or path == candidate:
                matches.append(path)
    return sorted(set(matches))


def emit(record):
    print(json.dumps(record, sort_keys=True, allow_nan=False), flush=True)


def main():
    if len(sys.argv) != 2 or sys.argv[1] not in ('empty', 'numpy'):
        raise ValueError('Expected control kind: empty or numpy')
    kind = sys.argv[1]
    emit(dict(event='start', kind=kind, python=sys.version,
              implementation=sys.implementation.name, executable=identity(sys.executable),
              prefix=sys.prefix, base_prefix=sys.base_prefix, probe=identity(__file__),
              build={key: sysconfig.get_config_var(key)
                     for key in ('Py_DEBUG', 'Py_TRACE_REFS', 'SOABI', 'CONFIG_ARGS')},
              environment={key: os.environ.get(key) for key in ENVIRONMENT_KEYS}))
    if kind == 'numpy':
        numpy = importlib.import_module('numpy')
        extensions = [identity(module.__file__) for name, module in sorted(sys.modules.items())
                      if name.startswith('numpy.') and
                      str(getattr(module, '__file__', '')).endswith('.so')]
        emit(dict(event='numpy_import', version=numpy.__version__,
                  module=identity(numpy.__file__), native_extensions=extensions))
    loaded = scanner_mappings()
    emit(dict(event='complete', kind=kind, scanner_mappings=loaded,
              finalization='ordinary interpreter shutdown; final leak check unchanged'))
    if loaded:
        raise RuntimeError('Host control unexpectedly loaded the scanner candidate')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
