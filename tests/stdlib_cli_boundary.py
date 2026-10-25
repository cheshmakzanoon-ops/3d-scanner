"""TEST ONLY: reviewed stdlib/Git CLI closures, never an engine runner.

Hash pins describe reviewed source identities, not a generic Python exception. Unknown
code/argv is refused before launch. -I -S preserves the real script entrypoint
while excluding cwd, PYTHONPATH and site initialization. Archive alone adds its
own tools directory; check that directory cannot shadow a stdlib import.

This is a functional-test boundary, not a security sandbox: the selected Python,
its stdlib and /usr/bin/git are trusted host tools, and fixture/source files must
not be concurrently replaced. No native runtime/control invocation uses this.
"""
import hashlib
import configparser
import json
import marshal
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PINS = {
    'manifest': '41c38c8320e27ce8e1c4720fa8abde872a4e62b6a8fda11b17a988a7680b1ad8',
    'archive': '17e76332693dddf44f94e5ea6bb9eb49f83a11a2d0221902b3248abf5863965c',
    'receipt': 'e9fd81aef899a0310f6688e1d5505548bb444cdf55b04b9a074e059b84db5e0d',
    # SDK main rejects this one argument before install()/any subprocess call.
    'sdk-rejected-argument': '295e5f72bb56c13cc9e5c96b887ebf5fc1ba37cc4cdcdffaf70f9934d0e4fcb9',
    'release-missing-records': '179d90b7df10ff807b220d690777ee1526340f814e1def108450400062c272b4',
    'release-policy': '7c7d1ebe5a41d5db90b65d2ab78f76b5f3fc2c1d5355f43322e90f36afe861d4',
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


def sdk_rejected_argument_cli(argv, parent=None):
    """Only the fixed --licenses rejection; never the installer or run_attempt.

    The reviewed entrypoint imports stdlib only and returns 2 before install().
    No valid install invocation, alternate script, help or arbitrary flag is
    admitted. Parent/native environment and sanitizer options remain unchanged.
    """
    args = list(map(str, argv))
    script = ROOT / 'tools/ci/install_android_sdk.py'
    if len(args) != 3 or args[1:] != [str(script), '--licenses']:
        raise ValueError('Unreviewed SDK rejection path or argv')
    return _launch(_source(script, 'sdk-rejected-argument'), ['--licenses'], parent, args[0])


def missing_release_records_cli(root, argv, parent=None):
    """Only the owned empty-records synthetic fixture, never real qualification.

    Admission binds source/policy, all data paths/content, a one-file Git source,
    and exact argv before returning the existing stdlib/Git environment. No
    record producer, native library, real APK or device invocation is admitted.
    As with other boundaries, concurrent same-user replacement is out of scope.
    """
    root = Path(os.path.abspath(root))
    if (root == ROOT or ROOT in root.parents or not root.is_dir()
            or any(p.is_symlink() for p in (root, *root.parents))):
        raise ValueError('Release fixture must be an external nonsymlink directory')
    paths = {'candidate': root/'candidate.json', 'records': root/'records',
             'evidence-root': root, 'candidate-apk': root/'unit-fixture.apk',
             'source-root': root/'source', 'output': root/'report.json'}
    args = list(map(str, argv))
    tail = [part for key, value in paths.items() for part in ('--'+key, str(value))]
    if len(args) != 15 or args[1:] != ['-m', 'tools.release.qualification.aggregate', *tail]:
        raise ValueError('Unreviewed missing-records argv or paths')
    if {p.name for p in root.iterdir()} != {'candidate.json','records','unit-fixture.apk','source'}:
        raise ValueError('Unexpected release fixture content or existing output')
    # Bound the whole generated tree, including Git metadata, before invoking Git.
    count = size = 0
    for directory, dirs, files in os.walk(root, followlinks=False):
        for name in [*dirs, *files]:
            path = Path(directory)/name
            count += 1
            if count > 128 or path.is_symlink():
                raise ValueError('Release fixture traversal or symlink refused')
            info = path.stat()
            if info.st_uid != os.getuid() or info.st_mode & 0o022:
                raise ValueError('Release fixture ownership/mode refused')
            if not path.is_dir():
                if not path.is_file():
                    raise ValueError('Release fixture must contain regular files')
                size += info.st_size
                if size > 1024*1024:
                    raise ValueError('Release fixture size exceeded')
    if root.stat().st_uid != os.getuid() or root.stat().st_mode & 0o022:
        raise ValueError('Release fixture ownership/mode refused')
    if not paths['records'].is_dir() or list(paths['records'].iterdir()):
        raise ValueError('Only empty release records are admitted')
    if paths['candidate-apk'].read_bytes() != b'SYNTHETIC UNIT ARTIFACT':
        raise ValueError('Only synthetic APK bytes are admitted')
    source = paths['source-root']
    if ({p.name for p in source.iterdir()} != {'.git','source.txt'}
            or (source/'source.txt').read_bytes() != b'SYNTHETIC UNIT SOURCE\n'
            or not (source/'.git').is_dir()):
        raise ValueError('Only the one-file synthetic source is admitted')
    # BEFORE ANY Git process: reject linked-worktree/common-dir, object-store,
    # attribute/config and other metadata indirections. A local config pin alone
    # does not bind Git's effective config when .git/commondir is present.
    metadata = source/'.git'
    allowed_dirs = {'branches','hooks','info','objects','objects/info','objects/pack',
                    'refs','refs/heads','refs/tags','logs','logs/refs','logs/refs/heads'}
    allowed_files = {'HEAD','config','description','index','COMMIT_EDITMSG','info/exclude',
                     'refs/heads/fixture-source','logs/HEAD','logs/refs/heads/fixture-source'}
    for path in metadata.rglob('*'):
        name = path.relative_to(metadata).as_posix()
        if path.is_dir():
            admitted = name in allowed_dirs or re.fullmatch(r'objects/[0-9a-f]{2}', name)
        else:
            admitted = (name in allowed_files or re.fullmatch(r'hooks/[a-zA-Z0-9-]+\.sample', name)
                        or re.fullmatch(r'objects/[0-9a-f]{2}/[0-9a-f]{38}', name))
        if not admitted:
            raise ValueError('Unreviewed synthetic Git metadata: '+name)
    if (metadata/'HEAD').read_bytes() != b'ref: refs/heads/fixture-source\n':
        raise ValueError('Synthetic Git branch differs')
    config = configparser.ConfigParser(interpolation=None)
    config.read_string((source/'.git/config').read_text())
    if (config.defaults() or config.sections() != ['core'] or dict(config['core']) != {
            'repositoryformatversion':'0','filemode':'true','bare':'false','logallrefupdates':'true'}):
        raise ValueError('Synthetic Git configuration differs')
    script = _source(ROOT/'tools/release/qualification/aggregate.py', 'release-missing-records')
    _source(ROOT/'tools/release/qualification/policy-v1.json', 'release-policy')
    command, env = _launch(script, tail, parent, args[0])
    # Redundant explicit binding after closed-world metadata admission. Inherited
    # GIT_* values were removed by _launch; none can select an external common dir,
    # object store, worktree or system attributes for either admission or CLI.
    env.update(GIT_DIR=str(metadata), GIT_COMMON_DIR=str(metadata),
               GIT_WORK_TREE=str(source), GIT_OBJECT_DIRECTORY=str(metadata/'objects'),
               GIT_ALTERNATE_OBJECT_DIRECTORIES='', GIT_ATTR_NOSYSTEM='1')
    def git(*arguments):
        return subprocess.check_output(['/usr/bin/git','-C',str(source),*arguments],
                                       env=env, timeout=5)
    revision = git('rev-parse','HEAD').decode().strip()
    if (git('ls-tree','-r','--name-only','HEAD') != b'source.txt\n'
            or git('show','HEAD:source.txt') != b'SYNTHETIC UNIT SOURCE\n'
            or git('status','--porcelain=v1','--untracked-files=all')):
        raise ValueError('Synthetic Git source identity differs')
    expected = {'schema':1, 'source_sha':revision,
                'artifact_sha256':hashlib.sha256(b'SYNTHETIC UNIT ARTIFACT').hexdigest(),
                'run_id':'SYNTHETIC-UNIT-RECEIPTS','created_at':'2026-01-01T00:00:00Z',
                'policy_sha256':PINS['release-policy'],
                'protocols':{key:'6'*64 for key in ('geometry','texture','resources')},
                'scenes':[], 'devices':[]}
    # Byte binding rejects duplicate fields, hidden payloads and alternative schemas.
    if paths['candidate'].read_bytes() != json.dumps(expected).encode():
        raise ValueError('Only the exact missing-records candidate is admitted')
    return command, env


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
