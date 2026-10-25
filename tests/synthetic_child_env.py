"""Test-only launch boundary for individually audited, generated Python payloads.

Never use for real CLI tools, native/CABI/JNI executions, or runtime controls.
Callsites must also use -I -S and review their complete payload/import closure.
This is not a sanitizer-result filter: parent instrumentation and sanitizer
options stay intact, and all child exit/output assertions remain authoritative.
"""
import os

_LOADER_KEYS = frozenset({
    'LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH',
    'DYLD_INSERT_LIBRARIES', 'DYLD_LIBRARY_PATH', 'DYLD_FRAMEWORK_PATH',
    'DYLD_FALLBACK_LIBRARY_PATH', 'DYLD_FALLBACK_FRAMEWORK_PATH',
})


def synthetic_child_env(parent=None):
    result = dict(os.environ if parent is None else parent)
    for key in tuple(result):
        if (key in _LOADER_KEYS or key.startswith(('PYTHON', 'PYTEST', 'RF_NATIVE_', 'RF_CABI_'))):
            result.pop(key)
    return result


def child_evidence(code, output):
    """Bounded metadata only: never echo subprocess streams, secrets or paths."""
    raw = output.encode('utf-8', 'replace') if isinstance(output, str) else output
    return {'exit': code, 'bytes': len(raw), 'markers': {
        name: raw.count(name.encode()) for name in
        ('AddressSanitizer', 'LeakSanitizer', 'UndefinedBehaviorSanitizer', 'SUMMARY:')}}
