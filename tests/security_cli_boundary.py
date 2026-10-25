"""TEST ONLY: exact external security-tool integration, never scanner engine code.

The pinned stdlib driver invokes pinned Bandit/detect-secrets/pip-audit in the
installer-owned venv. This is an external-tool functional boundary, not a
sanitizer pass, leak suppression, or sandbox against a malicious same-uid host.
Native/JNI tests, production scan() and sanitizer controls are unchanged.
"""
import hashlib
import importlib.util
import json
import marshal
import os
from pathlib import Path
import re
import signal
import subprocess
import sys
import tempfile
import time
import types

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'tools/security/scan.py'
SCRIPT_SHA = '58da3dd83a7b1c782226033ae1c5da8f03fd00acdd3eaa2c69fb0346ca8e8d87'
LOCK_SHA = 'a4a0176a006636f295065f8420aca5ea2b418379b3a21fc096801e533d6c1295'
PINS = {'bandit': '1.8.6', 'detect-secrets': '1.5.0', 'pip-audit': '2.9.0'}
MAX_REPORT = 4 * 1024 * 1024
MAX_STDIO = 64 * 1024
TIMEOUT = 900  # Existing per-tool 180-second deadlines remain inside scan.py.


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def regular(path, directory=False):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('security boundary refuses symlink input')
    if not (path.is_dir() if directory else path.is_file()):
        raise ValueError('security boundary input unavailable')
    return path


def reviewed_source():
    source = regular(SCRIPT)
    if digest(source) != SCRIPT_SHA:
        raise ValueError('security driver differs from reviewed source')
    return source


def scanner_identity(scanner):
    """Trust only the dedicated hashed-lock installer venv, never arbitrary -c."""
    scanner = Path(scanner).absolute()
    selected = os.environ.get('RF_SECURITY_PYTHON')
    if selected is None or scanner != Path(selected).absolute():
        raise ValueError('security interpreter differs from selected fixture')
    venv = regular(scanner.parent.parent, directory=True)
    if scanner != venv / 'bin/python' or venv.name != 'security-python':
        raise ValueError('security interpreter is not installer-owned')
    regular(scanner.parent, directory=True)
    # Normal venv interpreter symlinks are permitted only to this exact host Python.
    host = Path(sys.executable).resolve(strict=True)
    if scanner.resolve(strict=True) != host:
        raise ValueError('security interpreter differs from test host Python')
    cfg = regular(venv / 'pyvenv.cfg').read_text()
    if not re.search(r'^include-system-site-packages\s*=\s*false\s*$', cfg, re.M):
        raise ValueError('security venv exposes system site packages')
    lock = regular(ROOT / 'tools/dependencies/security-requirements.lock')
    if digest(lock) != LOCK_SHA:
        raise ValueError('security lock differs from reviewed identity')
    receipt = venv.parent / 'installation.json'
    if receipt.exists() or receipt.is_symlink():
        regular(receipt)
        metadata = json.loads(receipt.read_text())
        if (metadata.get('status') != 'PASS' or metadata.get('scanners') != PINS or
                metadata.get('lockSha256', {}).get(lock.name) != LOCK_SHA):
            raise ValueError('security installer receipt or lock differs')
    sites = list((venv / 'lib').glob('python*/site-packages'))
    if len(sites) != 1:
        raise ValueError('security site-packages identity ambiguous')
    site = regular(sites[0], directory=True)
    rows = [('host-python', digest(host)), ('config', digest(venv/'pyvenv.cfg')),
            ('installer-receipt', digest(receipt) if receipt.exists() else None), ('lock', digest(lock))]
    for path in sorted(site.rglob('*')):
        regular(path, directory=path.is_dir())
        if path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o022:
            raise ValueError('security package ownership is untrusted')
        if path.name.split('.')[0] in ('sitecustomize', 'usercustomize'):
            raise ValueError('security startup hook is not admitted')
        if not path.is_file():
            continue
        if path.suffix == '.pth' and any(line.strip() and not line.lstrip().startswith('#')
                                        for line in path.read_text().splitlines()):
            raise ValueError('security pth startup behavior is not admitted')
        if path.suffix == '.pyc':
            try:
                source = regular(importlib.util.source_from_cache(str(path)))
                match = re.search(r'\.opt-([12])\.pyc$', path.name)
                code = compile(source.read_bytes(), str(source), 'exec', dont_inherit=True,
                               optimize=int(match[1]) if match else 0)
                with path.open('rb') as stream:
                    payload = stream.read(16 * 1024 * 1024 + 1)
                if len(payload) > 16 * 1024 * 1024 or payload[:4] != importlib.util.MAGIC_NUMBER:
                    raise ValueError('security bytecode header or size is invalid')
                stored_code = marshal.loads(payload[16:])
                def filenames(value):
                    return (value.co_filename, tuple(filenames(item) for item in value.co_consts
                            if isinstance(item, types.CodeType)))
                # Code equality ignores marshal interning/reference flags; also
                # bind filenames recursively, which code equality alone omits.
                if stored_code != code or filenames(stored_code) != filenames(code):
                    raise ValueError('security bytecode differs from source')
            except (ValueError, OSError, EOFError, TypeError) as error:
                raise ValueError('security bytecode is unreviewed') from error
        rows.append((path.relative_to(venv).as_posix(), digest(path)))
    # -I -S driver does not run site hooks. Scanner children need site-packages,
    # but must not find startup hooks in the trusted host stdlib either.
    import sysconfig
    stdlib = Path(sysconfig.get_path('stdlib'))
    if any((stdlib/name).exists() for name in ('sitecustomize.py', 'usercustomize.py',
                                              'sitecustomize', 'usercustomize')):
        raise ValueError('host startup hook is not admitted')
    return rows


def _security_environment():
    # Private to this exact external-tool closure; never used for engine tests.
    env = dict(os.environ)
    for key in tuple(env):
        if key in ('LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES',
                   'DYLD_LIBRARY_PATH', 'DYLD_FRAMEWORK_PATH', 'DYLD_FALLBACK_LIBRARY_PATH',
                   'DYLD_FALLBACK_FRAMEWORK_PATH') or key.startswith(
                       ('PYTHON', 'PYTEST', 'RF_NATIVE_', 'RF_CABI_', 'GIT_')):
            env.pop(key)
    # Retain sanitizer option variables, including detect_leaks, unchanged.
    if 'PYTHONMALLOC' in os.environ:
        env['PYTHONMALLOC'] = os.environ['PYTHONMALLOC']
    env.update(PATH='/usr/bin:/bin', PYTHONNOUSERSITE='1', PYTHONSAFEPATH='1',
               PYTHONDONTWRITEBYTECODE='1', GIT_CONFIG_NOSYSTEM='1',
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
               GIT_NO_LAZY_FETCH='1', GIT_NO_REPLACE_OBJECTS='1',
               GIT_CONFIG_COUNT='2', GIT_CONFIG_KEY_0='core.fsmonitor', GIT_CONFIG_VALUE_0='false',
               GIT_CONFIG_KEY_1='core.hooksPath', GIT_CONFIG_VALUE_1=os.devnull)
    return env


def _execute(argv, env, folder, report):
    """Bound output/deadline; defer handled interruption until owned cleanup."""
    cancelled = [None]
    previous = {}
    process = None

    def record(signum, frame):
        # Never raise asynchronously inside Popen, waitid or wait. Repeated
        # signals cannot interrupt termination/reaping of this owned session.
        if cancelled[0] is None:
            cancelled[0] = signum

    try:
        for signum in (signal.SIGINT, signal.SIGTERM):
            previous[signum] = signal.getsignal(signum)
            if previous[signum] != signal.SIG_IGN:
                signal.signal(signum, record)
        with (folder/'stdout').open('wb') as out, (folder/'stderr').open('wb') as err:
            try:
                if cancelled[0] is None:
                    process = subprocess.Popen(argv, cwd=folder, env=env, stdin=subprocess.DEVNULL,
                                               stdout=out, stderr=err, start_new_session=True)
                    deadline = time.monotonic() + TIMEOUT
                    while cancelled[0] is None:
                        if out.tell() + err.tell() > MAX_STDIO or (report.exists() and report.stat().st_size > MAX_REPORT):
                            raise ValueError('security CLI output budget exceeded')
                        if time.monotonic() >= deadline:
                            raise ValueError('security CLI timeout')
                        if os.waitid(os.P_PID, process.pid, os.WEXITED | os.WNOHANG | os.WNOWAIT):
                            break
                        time.sleep(.02)
            finally:
                if process is not None:
                    # WNOWAIT reserves the leader PID until its group is killed.
                    # Signal handlers stay flag-only through the final reap.
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait()
    finally:
        # Restore the pair atomically with respect to these signals: a new
        # interruption must not leave the other handler installed in the parent.
        old_mask = signal.pthread_sigmask(signal.SIG_BLOCK, {signal.SIGINT, signal.SIGTERM})
        try:
            for signum, handler in previous.items():
                signal.signal(signum, handler)
        finally:
            signal.pthread_sigmask(signal.SIG_SETMASK, old_mask)
        if cancelled[0] is not None:
            # Deliver the original parent's SIGINT/SIGTERM semantics only after
            # owned children are terminated/reaped and all handlers restored.
            signal.raise_signal(cancelled[0])
    if (folder/'stdout').stat().st_size + (folder/'stderr').stat().st_size > MAX_STDIO:
        raise ValueError('security CLI output budget exceeded')
    # A prior custom handler may return: interruption still cannot become PASS,
    # even when the child had already exited successfully.
    return -int(cancelled[0]) if cancelled[0] is not None else process.returncode


def scan_external(root, scanner, requirements=None, suppressions=None):
    source = reviewed_source()
    root = regular(root, directory=True)
    scanner = Path(scanner).absolute()
    before = scanner_identity(scanner)
    if sys.platform != 'linux' or not Path('/usr/bin/git').is_file():
        raise ValueError('security boundary requires Linux system Git')
    options = []
    for flag, value in (('--requirements', requirements), ('--suppressions', suppressions)):
        if value is not None:
            options += [flag, str(regular(value))]
    with tempfile.TemporaryDirectory(prefix='rf-security-cli-') as temporary:
        folder = regular(temporary, directory=True)
        if folder.is_relative_to(root):
            raise ValueError('security output must be outside scanned source')
        report_path = folder/'report.json'
        argv = [sys.executable, '-I', '-S', str(source), str(root), '--scanner-python',
                str(scanner), *options, '--output', str(report_path)]
        code = _execute(argv, _security_environment(), folder, report_path)
        reviewed_source()
        if scanner_identity(scanner) != before:
            raise ValueError('security interpreter or packages changed during execution')
        regular(report_path)
        if report_path.stat().st_size > MAX_REPORT:
            raise ValueError('security report budget exceeded')
        if report_path.stat().st_nlink != 1:
            raise ValueError('security report has unexpected hard links')
        report = json.loads(report_path.read_text())
        if (type(report) is not dict or report.get('schema') != 1 or
                report.get('status') not in ('PASS', 'FAIL', 'NOT_RUN') or
                code != {'PASS': 0, 'FAIL': 1, 'NOT_RUN': 2}[report['status']] or
                report.get('scanner_source_sha256') != SCRIPT_SHA or report.get('tools') != PINS or
                report.get('scan_root') != str(root) or not isinstance(report.get('checks'), dict) or
                not isinstance(report.get('findings'), list)):
            raise ValueError('security CLI exit/report identity mismatch')
        return report
