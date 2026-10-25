#!/usr/bin/env python3
"""One authorized disposable-runner KVM lease; not a standing CI permission grant."""
from __future__ import annotations
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess

REPOSITORY = 'cheshmakzanoon-ops/3d-scanner'
BASE = '5beb2d301b98713ca106247f7230655d099612f0'
MESSAGE = 'test: run authorized installed synthetic scanner journey'
ALLOWED = frozenset({'.github/workflows/installed-journey-once.yml',
    'tools/device/installed_journey/kvm_lease.py', 'tools/device/installed_journey/run.py',
    'tests/test_installed_journey_kvm_lease.py', 'SOURCE_MANIFEST.sha256'})
ROOT = Path(__file__).resolve().parents[3]
DEVICE = Path('/dev/kvm')


def command(*args):
    return subprocess.check_output(args, text=True, timeout=30)


def admit(env, event, head, parent, message, changed):
    if (env.get('GITHUB_EVENT_NAME') != 'push' or env.get('GITHUB_REPOSITORY') != REPOSITORY
            or env.get('GITHUB_REF') != 'refs/heads/main' or env.get('GITHUB_RUN_ATTEMPT') != '1'
            or env.get('RUNNER_ENVIRONMENT') != 'github-hosted' or env.get('RUNNER_OS') != 'Linux'
            or event.get('repository', {}).get('full_name') != REPOSITORY
            or event.get('repository', {}).get('private') is not False
            or event.get('before') != BASE or event.get('after') != head
            or event.get('forced') is not False or event.get('deleted') is not False
            or env.get('GITHUB_SHA') != head or parent != BASE or message != MESSAGE
            or event.get('head_commit', {}).get('id') != head
            or event.get('head_commit', {}).get('message') != MESSAGE
            or set(changed) != ALLOWED):
        raise ValueError('Not the single authorized source-bound installed journey')


def admission():
    event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
    def git(*args):
        return command('git', '-C', str(ROOT), *args).strip()
    admit(os.environ, event, git('rev-parse', 'HEAD'), git('rev-parse', 'HEAD^'),
          git('log', '-1', '--format=%B'), git('diff', '--name-only', 'HEAD^', 'HEAD').splitlines())
    if git('status', '--porcelain', '--untracked-files=no'):
        raise ValueError('Tracked checkout changed before KVM lease')


def runner_acl(original: str, uid: int) -> str:
    """Add rw for this UID without broadening any other entry's effective access."""
    if uid <= 0:
        raise ValueError('Lease must target the non-root runner')
    entries = {}
    for line in original.splitlines():
        line = line.split('#', 1)[0].strip()
        if not line:
            continue
        parts = line.split(':')
        if len(parts) != 3 or parts[0] not in ('user', 'group', 'mask', 'other'):
            raise ValueError('Unsupported KVM ACL')
        kind, qualifier, perms = parts
        if (len(perms) != 3 or any(p not in ('-', c) for p, c in zip(perms, 'rwx'))
                or (qualifier and (kind not in ('user', 'group') or not qualifier.isdecimal()))
                or (kind, qualifier) in entries):
            raise ValueError('Malformed or duplicate KVM ACL')
        entries[kind, qualifier] = set(perms) - {'-'}
    if not {('user', ''), ('group', ''), ('other', '')} <= entries.keys():
        raise ValueError('Incomplete KVM ACL')
    mask = entries.get(('mask', ''), set('rwx'))
    for (kind, qualifier), perms in entries.items():
        if kind == 'group' or (kind == 'user' and qualifier):
            perms.intersection_update(mask)
    entries.setdefault(('user', str(uid)), set()).update('rw')
    entries['mask', ''] = set().union(*(perms for (kind, qualifier), perms in entries.items()
                                       if kind == 'group' or (kind == 'user' and qualifier)))
    return '\n'.join(f'{kind}:{qualifier}:' + ''.join(c if c in perms else '-' for c in 'rwx')
                     for (kind, qualifier), perms in sorted(entries.items())) + '\n'


def device_identity():
    info = DEVICE.lstat()
    if not stat.S_ISCHR(info.st_mode) or (os.major(info.st_rdev), os.minor(info.st_rdev)) != (10, 232):
        raise ValueError('Expected the real KVM character device, not a symlink or replacement')
    return {'inode': info.st_ino, 'device': info.st_dev, 'rdev': info.st_rdev,
            'uid': info.st_uid, 'gid': info.st_gid}


def acquire(directory: Path):
    admission()
    directory.mkdir(mode=0o700, parents=False, exist_ok=False)
    identity = device_identity()
    uid = os.getuid()
    original = command('getfacl', '--absolute-names', '--numeric', str(DEVICE))
    leased = runner_acl(original, uid)
    (directory / 'original.acl').write_text(original)
    (directory / 'leased.acl').write_text(leased)
    (directory / 'identity.json').write_text(json.dumps(identity))
    # Persist restoration state before the only permission mutation.
    command('sudo', '-n', 'setfacl', '--no-mask', '--set-file=' + str(directory / 'leased.acl'), str(DEVICE))
    if device_identity() != identity or not os.access(DEVICE, os.R_OK | os.W_OK):
        raise ValueError('Runner-only lease did not establish KVM access')
    import fcntl
    fd = os.open(DEVICE, os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW)
    try:
        if fcntl.ioctl(fd, 0xAE00, 0) != 12:
            raise ValueError('Unexpected KVM API')
    finally:
        os.close(fd)
    print('Temporary runner-only KVM access established; restoration required.')


def release(directory: Path):
    if not directory.exists():
        return  # Setup failed before any permission change.
    original = directory / 'original.acl'
    identity = directory / 'identity.json'
    if not original.exists() or not identity.exists():
        return  # Mutation is strictly after both snapshot files are written.
    if device_identity() != json.loads(identity.read_text()):
        raise ValueError('KVM device identity changed; refusing restoration onto replacement')
    command('sudo', '-n', 'setfacl', '--restore=' + str(original))
    if command('getfacl', '--absolute-names', '--numeric', str(DEVICE)) != original.read_text():
        raise ValueError('Original KVM ACL restoration did not verify')
    print('Original KVM ACL restored and verified.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=('admit', 'acquire', 'release'))
    args = parser.parse_args()
    if args.operation == 'admit':
        admission()
    else:
        directory = Path(os.environ['RUNNER_TEMP']) / 'rf-installed-kvm-lease'
        {'acquire': acquire, 'release': release}[args.operation](directory)
