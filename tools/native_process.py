"""Environment boundary for tools which build, but do not execute, native code."""
import os


def native_compile_env(environment=None) -> dict[str, str]:
    """Keep runtime injection out of compiler/readelf processes, not test binaries.

    LD_PRELOAD and LD_AUDIT execute libraries inside the host tool itself. They
    neither instrument its output nor belong to the candidate under test. In
    particular, preloading ASan into GCC makes cc1plus shutdown allocations fail
    the compilation before any candidate binary runs. Preserve sanitizer options
    and all other build inputs; native executions must use their original env.
    """
    result = dict(os.environ if environment is None else environment)
    for name in ('LD_PRELOAD', 'LD_AUDIT'):
        result.pop(name, None)
    return result
