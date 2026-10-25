"""TEST ONLY: three reviewed stdlib/Git CLI closures, never an engine runner.

Hash pins describe reviewed 0055 source, not a generic Python exception. Unknown
code/argv is refused before launch. -I -S preserves the real script entrypoint
while excluding cwd, PYTHONPATH and site initialization. Archive alone adds its
own tools directory; check that directory cannot shadow a stdlib import.

This is a functional-test boundary, not a security sandbox: the selected Python,
its stdlib and /usr/bin/git are trusted host tools, and fixture/source files must
not be concurrently replaced. No native runtime/control invocation uses this.
"""
import hashlib
import marshal
import os
from pathlib import Path
import shutil
import sys

ROOT = Path(__file__).resolve().parents[1]
PINS = {
    'manifest': '41c38c8320e27ce8e1c4720fa8abde872a4e62b6a8fda11b17a988a7680b1ad8',
    'archive': '17e76332693dddf44f94e5ea6bb9eb49f83a11a2d0221902b3248abf5863965c',
    'receipt': 'e9fd81aef899a0310f6688e1d5505548bb444cdf55b04b9a074e059b84db5e0d',
}
_LOADER_KEYS = frozenset({
    'LD_PRELOAD', 'LD_AUDIT', 'LD_LIBRARY_PATH',
    'DYLD_INSERT_LIBRARIES', 'DYLD_LIBRARY_PATH', 'DYLD_FRAMEWORK_PATH',
    'DYLD_FALLBACK_LIBRARY_PATH', 'DYLD_FALLBACK_FRAMEWORK_PATH',
})


def _source(path, kind):
    path = Path(path).absolute()
    if any(p.is_symlink() for p in (path, *path.parents)):
        raise ValueError('CLI source symlink is not admitted')
    if not path.is_file() or hashlib.sha256(path.read_bytes()).hexdigest() != PINS[kind]:
        raise ValueError('CLI source differs from reviewed identity')
    return path


def _launch(script, tail, parent=None, executable=None):
    original = dict(os.environ if parent is None else parent)
    selected = str(executable or sys.executable)
    python = shutil.which(selected, path=original.get('PATH', os.defpath))
    if python is None or Path(python).resolve() != Path(sys.executable).resolve():
        raise ValueError('CLI interpreter differs from test interpreter')
    # These CLIs launch only Git. Do not admit PATH shims or propagate Git config
    # injection, lazy fetch, fsmonitor or hooks into the isolated child closure.
    git = shutil.which('git', path=original.get('PATH', os.defpath))
    if sys.platform != 'linux' or git is None or Path(git).resolve() != Path('/usr/bin/git').resolve():
        raise ValueError('CLI requires the reviewed system Git location')
    env = dict(original)
    for key in tuple(env):
        if key in _LOADER_KEYS or key.startswith(('PYTHON', 'PYTEST', 'RF_NATIVE_', 'RF_CABI_', 'GIT_')):
            env.pop(key)
    env.update(PATH='/usr/bin:/bin', GIT_CONFIG_NOSYSTEM='1',
               GIT_CONFIG_GLOBAL=os.devnull, GIT_CONFIG_SYSTEM=os.devnull,
               GIT_NO_LAZY_FETCH='1', GIT_NO_REPLACE_OBJECTS='1',
               GIT_CONFIG_COUNT='2', GIT_CONFIG_KEY_0='core.fsmonitor',
               GIT_CONFIG_VALUE_0='false', GIT_CONFIG_KEY_1='core.hooksPath',
               GIT_CONFIG_VALUE_1=os.devnull)
    return [str(Path(python).absolute()), '-I', '-S', str(script), *tail], env


def manifest_cli(repo, argv, parent=None):
    """Only copied manifest generate/index and source-guard verify/HEAD calls."""
    repo = Path(repo).absolute()
    args = list(map(str, argv))
    if len(args) < 3:
        raise ValueError('Unreviewed manifest argv')
    script = Path(args[1])
    script = script if script.is_absolute() else repo / script
    if script.absolute() != repo / 'tools/source_manifest.py':
        raise ValueError('Unreviewed manifest path')
    if tuple(args[2:]) not in {('generate',), ('generate', '--source', 'index'),
                               ('verify', '--source', 'HEAD')}:
        raise ValueError('Unreviewed manifest argv')
    return _launch(_source(script, 'manifest'), args[2:], parent, args[0])


def archive_cli(repo, command, args, parent=None):
    """Exact create/verify shapes used by test_source_manifest_tool.run_archive."""
    tail = list(map(str, args))
    option = {'create': '--output', 'verify': '--archive'}.get(command)
    if option is None or len(tail) != 4 or tail[0] != option or tail[2] != '--record':
        raise ValueError('Unreviewed archive argv')
    script = _source(ROOT / 'tools/ci/source_snapshot/archive.py', 'archive')
    manifest = _source(ROOT / 'tools/source_manifest.py', 'manifest')
    # The reviewed archive inserts this directory before importing source_manifest.
    # Refuse Python/extension/package shadows, including a poisoned native loader.
    for child in (ROOT / 'tools').iterdir():
        if child.name.split('.', 1)[0] in sys.stdlib_module_names or (
                child.name.split('.', 1)[0] == 'source_manifest' and child.name != 'source_manifest.py'):
            raise ValueError('Archive tools directory shadows a stdlib module')
    # A valid .py hash alone does not exclude a forged timestamp-valid .pyc.
    cache_dir = ROOT / 'tools/__pycache__'
    if cache_dir.is_symlink():
        raise ValueError('Archive import cache symlink is not admitted')
    cache = cache_dir / ('source_manifest.' + sys.implementation.cache_tag + '.pyc')
    if cache.exists() or cache.is_symlink():
        code = compile(manifest.read_bytes(), str(manifest), 'exec', dont_inherit=True, optimize=0)
        expected = marshal.dumps(code)
        if cache.is_symlink() or not cache.is_file() or cache.read_bytes()[16:] != expected:
            raise ValueError('Archive import bytecode differs from reviewed source')
    return _launch(script, [command, '--repo', str(repo), '--revision', 'HEAD', *tail], parent)


def receipt_cli(reports, repo, strict=False, extra=(), parent=None):
    """Only fixture receipt verification, not optional artifact or unknown flags."""
    if type(strict) is not bool or extra:
        raise ValueError('Unreviewed receipt argv')
    script = _source(Path(reports) / 'published-validation/verify.py', 'receipt')
    tail = ['--repo', str(repo)] + (['--require-retained-source-objects'] if strict else [])
    return _launch(script, tail, parent)
