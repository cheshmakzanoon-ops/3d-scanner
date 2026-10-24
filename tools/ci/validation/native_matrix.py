#!/usr/bin/env python3
"""Producer-sealed native shards; transport never manufactures execution freshness."""
from __future__ import annotations
import argparse
import json
import hashlib
import os
import re
import stat
from pathlib import Path, PurePosixPath
import sys
import time

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT))
from tools.ci.validation import evidence as ci

SCHEMA = 'realityforge.native-shard.v1'
MATRIX = 'realityforge.native-matrix.v1'
PROFILES = ('gcc-release', 'clang-release', 'gcc-sanitize', 'clang-sanitize')
PROJECT = 'cheshmakzanoon-ops/3d-scanner'
# Bounded above expected sealed logs/binaries, not unbounded raw build trees.
MAX_FILES = 4096
MAX_ENTRIES = 8192
MAX_BYTES = 8 * 1024**3
MAX_FILE_BYTES = 1024**3
MAX_DEPTH = 16
INVENTORY_SECONDS = 30


def repository_name(value=None):
    value = value if value is not None else os.environ.get('GITHUB_REPOSITORY', PROJECT)
    if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', value):
        raise ci.EvidenceError('Repository identity required')
    return value


def stable_snapshot(path, *, allow_empty=False):
    path = Path(path)
    if path.is_symlink() or any(parent.is_symlink() for parent in path.parents):
        raise ci.EvidenceError('Symlink producer artifact')
    def fingerprint(value):
        return (value.st_dev, value.st_ino, value.st_mode, value.st_size,
                value.st_mtime_ns, value.st_ctime_ns)
    with os.fdopen(os.open(path, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or not (0 if allow_empty else 1) <= before.st_size <= MAX_FILE_BYTES:
            raise ci.EvidenceError('Invalid/oversized producer artifact')
        hasher = hashlib.sha256()
        remaining = before.st_size
        while remaining:
            block = stream.read(min(remaining, 1024 * 1024))
            if not block:
                raise ci.EvidenceError('Producer artifact truncated while hashing')
            hasher.update(block)
            remaining -= len(block)
        if stream.read(1):
            raise ci.EvidenceError('Producer artifact grew while hashing')
        digest = hasher.hexdigest()
        if fingerprint(before) != fingerprint(os.fstat(stream.fileno())):
            raise ci.EvidenceError('Producer artifact changed while hashing')
    if fingerprint(before) != fingerprint(path.lstat()):
        raise ci.EvidenceError('Producer artifact replaced while hashing')
    return fingerprint(before), digest



def copy_snapshot(source, target, snapshot):
    """Never copy more than the previously measured size, even if source grows."""
    expected, expected_hash = snapshot
    def fingerprint(value):
        return (value.st_dev, value.st_ino, value.st_mode, value.st_size,
                value.st_mtime_ns, value.st_ctime_ns)
    with os.fdopen(os.open(source, os.O_RDONLY | os.O_NOFOLLOW), 'rb') as stream:
        if fingerprint(os.fstat(stream.fileno())) != expected:
            raise ci.EvidenceError('Producer artifact changed before copying')
        digest = hashlib.sha256()
        remaining = expected[3]
        with target.open('xb') as output:
            while remaining:
                block = stream.read(min(remaining, 1024 * 1024))
                if not block:
                    raise ci.EvidenceError('Producer artifact truncated during copying')
                output.write(block)
                digest.update(block)
                remaining -= len(block)
            if stream.read(1):
                raise ci.EvidenceError('Producer artifact grew during copying')
        if fingerprint(os.fstat(stream.fileno())) != expected or digest.hexdigest() != expected_hash:
            raise ci.EvidenceError('Producer artifact changed during copying')


def dump(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + '\n')


def counts(xml):
    result = dict(tests=0, passed=0, skipped=0, failed=0)
    for path in xml:
        for key, value in ci.junit(path).items():
            result[key] += value
    return result


def inventory(directory):
    """Bound fd-anchored enumeration; never traverse a queued path/symlink."""
    directory = Path(directory)
    if directory.is_symlink() or not directory.is_dir() or any(p.is_symlink() for p in directory.parents):
        raise ci.EvidenceError('Missing/symlink bundle directory')
    result = set()
    entries, total = 0, 0
    deadline = time.monotonic() + INVENTORY_SECONDS
    def walk(fd, prefix, depth):
        nonlocal entries, total
        if depth > MAX_DEPTH:
            raise ci.EvidenceError('Native bundle depth limit')
        with os.scandir(fd) as children:
            for child in children:
                entries += 1
                if entries > MAX_ENTRIES or time.monotonic() > deadline:
                    raise ci.EvidenceError('Native bundle enumeration limit')
                info = child.stat(follow_symlinks=False)
                relative = prefix + child.name
                if stat.S_ISLNK(info.st_mode):
                    raise ci.EvidenceError('Symlink in native bundle')
                if stat.S_ISDIR(info.st_mode):
                    child_fd = os.open(child.name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
                    try:
                        opened = os.fstat(child_fd)
                        if (opened.st_dev, opened.st_ino) != (info.st_dev, info.st_ino):
                            raise ci.EvidenceError('Native directory replaced during inventory')
                        walk(child_fd, relative + '/', depth + 1)
                    finally:
                        os.close(child_fd)
                elif stat.S_ISREG(info.st_mode):
                    total += info.st_size
                    result.add(relative)
                    if len(result) > MAX_FILES or info.st_size > MAX_FILE_BYTES or total > MAX_BYTES:
                        raise ci.EvidenceError('Native bundle file/byte limit')
                else:
                    raise ci.EvidenceError('Special file in native bundle')
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        walk(fd, '', 0)
    finally:
        os.close(fd)
    return result


def safe_file(directory, relative):
    if not isinstance(relative, str) or not relative or '\\' in relative or any(ord(char) < 32 or ord(char) == 127 for char in relative):
        raise ci.EvidenceError('Unsafe native bundle path')
    parts = PurePosixPath(relative).parts
    if PurePosixPath(relative).is_absolute() or '..' in parts or str(PurePosixPath(relative)) != relative:
        raise ci.EvidenceError('Unsafe native bundle path')
    path = directory
    for part in parts:
        path = path / part
        if path.is_symlink():
            raise ci.EvidenceError('Symlink native bundle path')
    if not path.is_file():
        raise ci.EvidenceError('Missing native bundle file')
    return path


def identity(path, relative, original, role):
    return dict(path=relative, original_path=original, role=role,
                sha256=ci.sha(path), size_bytes=path.stat().st_size)


def begin(root, expected, run_id, profile, output, repository=None):
    if profile not in PROFILES:
        raise ci.EvidenceError('Unknown native profile')
    data = ci.begin(root, 'native', expected, run_id, output)
    data.update(schema=SCHEMA, profile=profile, repository=repository_name(repository))
    dump(output, data)
    return data


def check_identity(data, expected, run_id, profile=None, repository=None):
    if (data.get('schema') != SCHEMA or data.get('repository') != repository_name(repository)
            or data.get('gate') != 'native' or data.get('kind') != ci.GATES['native']
            or data.get('source_sha') != expected
            or data.get('run_id') != run_id or data.get('profile') not in PROFILES
            or (profile is not None and data['profile'] != profile)):
        raise ci.EvidenceError('Foreign native shard identity')


def validate_qa(paths):
    names = [path.name for path in paths]
    if (len(names) != len(set(names)) or 'installation.json' not in names
            or 'blender-publisher.sha256' not in names
            or not any(name.endswith('-install.log') for name in names)
            or not any(name.endswith('-check.log') for name in names)
            or any(name not in ('installation.json', 'blender-publisher.sha256')
                   and not name.endswith(('-install.log', '-check.log')) for name in names)):
        raise ci.EvidenceError('Incomplete/foreign QA installation evidence')


def seal(start_path, native_path, output, artifact_patterns=(), repository=None):
    start = ci.load(start_path)
    check_identity(start, start.get('source_sha'), start.get('run_id'), repository=repository)
    ci.source(Path(start['root']), start['source_sha'])
    if output.exists():
        raise ci.EvidenceError('Native output must be new')
    record = ci.load(native_path)
    configs = record.get('configurations', [])
    if len(configs) != 1:
        raise ci.EvidenceError('Exactly one producer profile required')
    config = configs[0]
    qa = ci.expanded(artifact_patterns)
    validate_qa(qa)
    referenced = [native_path, *qa]
    command_logs = {}
    for command in config.get('commands', []):
        log = Path(command['log'])
        if (command.get('result') != 'PASS' or command.get('exit_code') != 0
                or command.get('reason') is not None or log in command_logs):
            raise ci.EvidenceError('Failed/duplicate producer command')
        command_logs[log] = command
        referenced.append(log)
        if log.stem == 'pytest':
            referenced.append(log.with_name('pytest.xml'))
    referenced += [Path(row['path']) for row in config.get('binaries', []) + config.get('artifacts', [])]
    referenced = list(dict.fromkeys(referenced))
    if len(referenced) > MAX_FILES:
        raise ci.EvidenceError('Producer file count limit')
    snapshots = {}
    total_bytes = 0
    for path in referenced:
        if path in command_logs:
            if path.is_symlink() or not path.is_file() or path.stat().st_mtime_ns < start['started_ns']:
                raise ci.EvidenceError('Missing/stale/symlink command log')
        else:
            ci.fresh(path, start['started_ns'])
        snapshots[path] = stable_snapshot(path, allow_empty=path in command_logs)
        if path in command_logs and snapshots[path][1] != command_logs[path].get('log_sha256'):
            raise ci.EvidenceError('Command log differs from successful command receipt')
        total_bytes += snapshots[path][0][3]
        if total_bytes > MAX_BYTES:
            raise ci.EvidenceError('Producer byte limit')
    files, xml = ci.native_record(native_path, start['source_sha'], profile=start['profile'])
    files = list(dict.fromkeys([*files, *xml, *qa]))
    if set(files) != set(referenced):
        raise ci.EvidenceError('Producer references changed during validation')
    total = counts(xml)
    output.mkdir(parents=True)
    (output / 'files').mkdir()
    artifacts = []
    for index, path in enumerate(files):
        before, digest = snapshots[path]
        relative = f'files/{index}-{digest}-{path.name}'
        copy_snapshot(path, output / relative, snapshots[path])
        if stable_snapshot(path, allow_empty=path in command_logs) != snapshots[path] or ci.sha(output / relative) != digest:
            raise ci.EvidenceError('Producer artifact changed during sealing')
        role = 'native-record' if path == native_path else 'command-log' if path in command_logs else 'junit' if path in xml else 'qa' if path in qa else 'artifact'
        artifacts.append(dict(identity(output / relative, relative, str(path), role),
                              produced_mtime_ns=before[4]))
    data = dict(start, status='PASS', completed_ns=time.time_ns(), tests=total, artifacts=artifacts)
    dump(output / 'shard.json', data)
    verify_shard(output, start['source_sha'], start['run_id'], start['profile'], repository)
    return data


def verify_shard(directory, expected, run_id, profile=None, repository=None):
    actual_files = inventory(directory)
    data = ci.load(safe_file(directory, 'shard.json'))
    check_identity(data, expected, run_id, profile, repository)
    if (data.get('status') != 'PASS' or type(data.get('started_ns')) is not int
            or type(data.get('completed_ns')) is not int
            or data['started_ns'] <= 0 or data['completed_ns'] < data['started_ns']):
        raise ci.EvidenceError('Failed/incomplete native shard')
    mapped, declared, roles = {}, {'shard.json'}, {}
    for artifact in data.get('artifacts', []):
        relative, original = artifact['path'], artifact['original_path']
        if (not isinstance(original, str) or not PurePosixPath(original).is_absolute()
                or str(PurePosixPath(original)) != original or '..' in PurePosixPath(original).parts
                or original.startswith('//') or '\\' in original
                or any(ord(char) < 32 or ord(char) == 127 for char in original)):
            raise ci.EvidenceError('Unsafe original native artifact path')
        if not relative.startswith('files/') or relative in declared or original in mapped:
            raise ci.EvidenceError('Duplicate/unsafe native artifact identity')
        produced = artifact.get('produced_mtime_ns')
        if type(produced) is not int or not data['started_ns'] <= produced <= data['completed_ns']:
            raise ci.EvidenceError('Native artifact predates producer or exceeds completion')
        path = safe_file(directory, relative)
        if (type(artifact.get('size_bytes')) is not int
                or artifact['size_bytes'] < (0 if artifact['role'] == 'command-log' else 1)
                or path.stat().st_size != artifact['size_bytes'] or ci.sha(path) != artifact['sha256']):
            raise ci.EvidenceError('Native artifact transport integrity failure')
        declared.add(relative)
        mapped[original] = path
        roles.setdefault(artifact['role'], []).append(path)
    if actual_files != declared or len(roles.get('native-record', [])) != 1:
        raise ci.EvidenceError('Missing/foreign native bundle files')
    record_path = roles['native-record'][0]
    files, xml = ci.native_record(record_path, expected, mapped, profile=data['profile'])
    if len(files + xml) != len(set(files + xml)):
        raise ci.EvidenceError('Duplicate native record file reference')
    if set(xml) != set(roles.get('junit', [])) or counts(xml) != data.get('tests'):
        raise ci.EvidenceError('Native shard JUnit count/role mismatch')
    command_paths = {mapped[row['log']] for row in ci.load(record_path)['configurations'][0]['commands']}
    if not set(roles.get('command-log', [])) <= command_paths:
        raise ci.EvidenceError('Command-log role is not bound to a successful command')
    validate_qa([Path(row['original_path']) for row in data['artifacts'] if row['role'] == 'qa'])
    allowed = set(files) | set(xml) | set(roles.get('qa', []))
    if allowed != set(mapped.values()) or set(roles) - {'native-record', 'command-log', 'junit', 'qa', 'artifact'}:
        raise ci.EvidenceError('Foreign native artifact role')
    record = ci.load(record_path)
    numeric_rows = [row for row in record['configurations'][0]['artifacts'] if Path(row['path']).name == 'numerical.json']
    return dict(envelope=data, record=record, mapped=mapped, counts=counts(xml),
                numerical=ci.load(mapped[numeric_rows[0]['path']]))


def parity(shards):
    sys.path.insert(0, str(ROOT / 'tools/qa/native_regression'))
    from numerical import compare
    result = []
    for profile in PROFILES[1:]:
        compare(shards[PROFILES[0]]['numerical'], shards[profile]['numerical'])
        result.append(dict(config=profile, reference=PROFILES[0], result='PASS'))
    return result


def aggregate(directory, expected, run_id, output, root=None, repository=None):
    ci.source(ROOT if root is None else root, expected)
    if output.exists():
        raise ci.EvidenceError('Native aggregate output must be new')
    started = time.time_ns()
    inventory(directory)
    entries = list(directory.iterdir())
    if len(entries) != 4 or any(not entry.is_dir() for entry in entries):
        raise ci.EvidenceError('Exactly four shard directories required')
    shards, origins = {}, {}
    for entry in entries:
        shard = verify_shard(entry, expected, run_id, repository=repository)
        profile = shard['envelope']['profile']
        if profile in shards:
            raise ci.EvidenceError('Duplicate native profile')
        shards[profile], origins[profile] = shard, entry
    if set(shards) != set(PROFILES):
        raise ci.EvidenceError('Incomplete native profile matrix')
    comparisons = parity(shards)
    output.mkdir(parents=True)
    (output / 'files').mkdir()
    manifest = dict(schema=MATRIX, source_sha=expected, run_id=run_id, repository=repository_name(repository),
                    comparisons=comparisons, shards=[])
    total = dict(tests=0, passed=0, skipped=0, failed=0)
    for profile in PROFILES:
        destination = output / 'files' / profile
        command_logs = {row['path'] for row in shards[profile]['envelope']['artifacts'] if row['role'] == 'command-log'}
        for name in sorted(inventory(origins[profile])):
            source_file = safe_file(origins[profile], name)
            target_file = destination / name
            target_file.parent.mkdir(parents=True, exist_ok=True)
            snapshot = stable_snapshot(source_file, allow_empty=name in command_logs)
            copy_snapshot(source_file, target_file, snapshot)
            if stable_snapshot(source_file, allow_empty=name in command_logs) != snapshot:
                raise ci.EvidenceError('Imported shard changed during aggregation')
        relative = f'files/{profile}/shard.json'
        manifest['shards'].append(dict(profile=profile, path=relative, sha256=ci.sha(output / relative)))
        for key, value in shards[profile]['counts'].items():
            total[key] += value
    dump(output / 'matrix.json', manifest)
    data = dict(schema=ci.SCHEMA, gate='native', kind=ci.GATES['native'], native_format='matrix-v1',
                source_sha=expected, run_id=run_id, repository=repository_name(repository), status='PASS', started_ns=started,
                completed_ns=time.time_ns(), tests=total,
                artifacts=[identity(output / 'matrix.json', 'matrix.json', 'matrix.json', 'native-matrix')])
    dump(output / 'evidence.json', data)
    verify_matrix(output, expected, run_id, repository)
    return data


def verify_matrix(directory, expected, run_id, repository=None):
    actual = inventory(directory)
    data = ci.load(safe_file(directory, 'evidence.json'))
    if (data.get('schema') != ci.SCHEMA or data.get('gate') != 'native'
            or data.get('kind') != ci.GATES['native'] or data.get('native_format') != 'matrix-v1'
            or data.get('source_sha') != expected or data.get('run_id') != run_id
            or data.get('repository') != repository_name(repository)
            or data.get('status') != 'PASS' or type(data.get('started_ns')) is not int
            or type(data.get('completed_ns')) is not int or data.get('started_ns', 0) <= 0
            or data.get('completed_ns', 0) < data['started_ns']):
        raise ci.EvidenceError('Invalid native matrix envelope')
    if data.get('artifacts') != [identity(safe_file(directory, 'matrix.json'), 'matrix.json', 'matrix.json', 'native-matrix')]:
        raise ci.EvidenceError('Native matrix manifest integrity failure')
    manifest = ci.load(directory / 'matrix.json')
    if manifest.get('schema') != MATRIX or manifest.get('source_sha') != expected or manifest.get('run_id') != run_id or manifest.get('repository') != repository_name(repository):
        raise ci.EvidenceError('Foreign native matrix manifest')
    rows = manifest.get('shards', [])
    if len(rows) != 4 or [row.get('profile') for row in rows] != list(PROFILES):
        raise ci.EvidenceError('Incomplete/duplicate/unordered native matrix')
    shards, declared = {}, {'evidence.json', 'matrix.json'}
    total = dict(tests=0, passed=0, skipped=0, failed=0)
    for row in rows:
        profile = row['profile']
        relative = f'files/{profile}/shard.json'
        if row.get('path') != relative or ci.sha(safe_file(directory, relative)) != row.get('sha256'):
            raise ci.EvidenceError('Native shard manifest integrity failure')
        folder = directory / 'files' / profile
        shards[profile] = verify_shard(folder, expected, run_id, profile, repository)
        declared.update(f'files/{profile}/{name}' for name in inventory(folder))
        for key, value in shards[profile]['counts'].items():
            total[key] += value
    if actual != declared or data.get('tests') != total or total['passed'] < 1 or total['failed']:
        raise ci.EvidenceError('Foreign/incomplete native matrix evidence')
    if manifest.get('comparisons') != parity(shards):
        raise ci.EvidenceError('Native parity evidence mismatch')
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    start = commands.add_parser('begin')
    start.add_argument('--root', type=Path, default=Path.cwd())
    start.add_argument('--repository', required=True)
    start.add_argument('--profile', choices=PROFILES, required=True)
    for sub in (start,):
        sub.add_argument('--source-sha', required=True)
        sub.add_argument('--run-id', required=True)
        sub.add_argument('--output', type=Path, required=True)
    seal_parser = commands.add_parser('seal')
    seal_parser.add_argument('--start', type=Path, required=True)
    seal_parser.add_argument('--native-record', type=Path, required=True)
    seal_parser.add_argument('--artifact', action='append', default=[])
    seal_parser.add_argument('--output', type=Path, required=True)
    agg = commands.add_parser('aggregate')
    agg.add_argument('--repository', required=True)
    agg.add_argument('--directory', type=Path, required=True)
    agg.add_argument('--source-sha', required=True)
    agg.add_argument('--run-id', required=True)
    agg.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.command == 'begin':
        begin(args.root, args.source_sha, args.run_id, args.profile, args.output, args.repository)
    elif args.command == 'seal':
        seal(args.start, args.native_record, args.output, args.artifact)
    else:
        aggregate(args.directory, args.source_sha, args.run_id, args.output, repository=args.repository)


if __name__ == '__main__':
    main()
