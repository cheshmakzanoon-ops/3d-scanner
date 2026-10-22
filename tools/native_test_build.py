"""Bind Linux release probes to the selected library under ordinary ELF lookup.

Arbitrary LD_PRELOAD/LD_AUDIT symbol interposition is outside this guarantee.
Native execution preserves loader injection, including legitimate ASan preload;
its sanitizer lifecycle/process policy is owned by the native CI runner.
"""
import os
import re
import subprocess
from pathlib import Path
from tools.native_api import library_path
from tools.native_process import native_compile_env


def _selected_library() -> Path:
    # A missing explicit override must never fall back to an older host build.
    library = library_path().resolve(strict=True)
    if not library.is_file():
        raise FileNotFoundError(f"Native library is not a file: {library}")
    # ELF expands '$' loader tokens, while LD_LIBRARY_PATH treats ':' and ';'
    # as separators. These paths cannot be represented by this literal binding.
    if any(character in str(library) for character in ('$', ':', ';')):
        raise ValueError(f"Unsupported loader token or separator in native library path: {library}")
    # Absolute linking still records an ELF SONAME when one is present. Refuse
    # aliases that would load a different file from this candidate's directory.
    # readelf does not execute the native library: isolate this tool invocation
    # from injected runtimes, without changing the native process environment.
    tool_environment = native_compile_env()
    tool_environment['LC_ALL'] = 'C'
    dynamic = subprocess.check_output(
        ['readelf', '--dynamic', str(library)], text=True, env=tool_environment)
    soname_lines = [line for line in dynamic.splitlines() if '(SONAME)' in line]
    if soname_lines:
        match = re.search(r'\[([^\]\n]*)\]', soname_lines[0])
        if len(soname_lines) != 1 or not match:
            raise ValueError(f"Cannot validate native library SONAME: {library}")
        soname = match.group(1)
        if not soname or any(character in soname for character in ('/', '\\', '$', ':', ';')) or soname in ('.', '..'):
            raise ValueError(f"Unsafe native library SONAME: {soname!r}")
        alias = library.parent / soname
        if not alias.is_file() or alias.resolve(strict=True) != library:
            raise ValueError(f"Native library SONAME {soname!r} does not resolve to selected file: {library}")
    return library


def native_compile_prefix() -> list[str]:
    """Compile fixtures with the matrix's compiler and sanitizer runtime ABI.

    A runtime preload instruments the compiler process, not its generated code.
    These explicit driver flags instrument and link the fixture itself instead.
    Clang uses its shared ASan runtime to match the Python runner's preload;
    linking the default static runtime would introduce a second ASan instance.
    """
    config = os.environ.get('RF_NATIVE_CONFIG')
    compiler = os.environ.get('RF_NATIVE_CXX')
    if config is None and compiler is None:
        return ['c++']
    if config not in ('gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize'):
        raise RuntimeError('Missing or unsupported RF_NATIVE_CONFIG for native fixture')
    if not compiler:
        raise RuntimeError('RF_NATIVE_CXX is required for a matrix native fixture')
    prefix = [compiler]
    if config.endswith('-sanitize'):
        prefix += ['-fsanitize=address,undefined,float-cast-overflow', '-fno-omit-frame-pointer']
        if config.startswith('clang-'):
            prefix.append('-shared-libasan')
    return prefix


def native_link_args() -> list[str]:
    library = _selected_library()
    # Separate linker arguments preserve spaces and commas in build paths.
    return [str(library), '-Xlinker', '-rpath', '-Xlinker', str(library.parent)]


def native_run_env() -> dict[str, str]:
    # ELF SONAME lookup can otherwise prefer an inherited, stale host build over
    # RUNPATH. Keep dependency search paths, but give this candidate precedence.
    parent = str(_selected_library().parent)
    environment = dict(os.environ)
    previous = environment.get('LD_LIBRARY_PATH')
    environment['LD_LIBRARY_PATH'] = parent + (os.pathsep + previous if previous else '')
    return environment
