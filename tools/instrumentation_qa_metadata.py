#!/usr/bin/env python3
"""Exact two-class QA-only javac EnclosingMethod correction; never a shipping waiver."""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import struct
import tempfile
import zipfile

TARGETS = {
 'androidx/test/internal/platform/util/TestOutputEmitter$1.class': (
  'cadaf2750969b978d060109bc20932b545a91dd54d2a3f7c533ea23d71a0ce45',
  'androidx/test/internal/platform/util/TestOutputEmitter',
  '()Landroidx/test/internal/platform/util/TestOutputHandler;'),
 'androidx/test/internal/util/Checks$ThreadCheckerSingleton$1.class': (
  '4730062f582788f36b20ace3cfdf5e9bdaefe2ff4fb0ad0d6d200bb526d96f09',
  'androidx/test/internal/util/Checks$ThreadCheckerSingleton',
  '()Landroidx/test/internal/platform/ThreadChecker;'),
}

def digest(data):
    return hashlib.sha256(data).hexdigest()


def plain(path):
    original = Path(path)
    if '..' in original.parts:
        raise ValueError('Parent path aliases forbidden')
    p = Path(os.path.abspath(original))
    if p != p.resolve() or p.is_symlink():
        raise ValueError('Symlink/alias forbidden: ' + str(p))
    return p


class Reader:
    def __init__(self, data):
        self.data, self.pos = data, 0
    def take(self, count):
        if count < 0 or self.pos + count > len(self.data):
            raise ValueError('Truncated classfile')
        out = self.data[self.pos:self.pos + count]
        self.pos += count
        return out
    def u1(self): return int.from_bytes(self.take(1), 'big')
    def u2(self): return int.from_bytes(self.take(2), 'big')
    def u4(self): return int.from_bytes(self.take(4), 'big')


def enclosing(data, name):
    """Parse bounded class structure and return exact method_index offset and value."""
    r = Reader(data)
    if r.take(4) != b'\xca\xfe\xba\xbe': raise ValueError('Not a classfile')
    r.take(4)
    count = r.u2()
    if count < 2: raise ValueError('Invalid constant pool')
    cp = [None] * count
    i = 1
    while i < count:
        tag = r.u1()
        if tag == 1: value = r.take(r.u2())
        elif tag in (3, 4): value = r.take(4)
        elif tag in (5, 6):
            value = r.take(8)
            if i + 1 >= count: raise ValueError('Invalid wide constant')
        elif tag in (7, 8, 16, 19, 20): value = r.u2()
        elif tag in (9, 10, 11, 12, 17, 18): value = (r.u2(), r.u2())
        elif tag == 15: value = (r.u1(), r.u2())
        else: raise ValueError('Unknown constant pool tag')
        cp[i] = (tag, value)
        i += 2 if tag in (5, 6) else 1
    def get(index, tag):
        if not 0 < index < count or cp[index] is None or cp[index][0] != tag:
            raise ValueError('Invalid constant pool reference')
        return cp[index][1]
    def utf(index):
        # Only structural attribute/owner/name/descriptor strings need decoding;
        # all other modified UTF-8 constants remain opaque and unchanged.
        return get(index, 1).decode('ascii')
    r.u2()
    if utf(get(r.u2(), 7)) + '.class' != name: raise ValueError('Wrong class identity')
    r.u2()
    r.take(2 * r.u2())
    def attributes(collect=False):
        result = []
        for _ in range(r.u2()):
            attr = utf(r.u2()); length = r.u4(); offset = r.pos
            payload = r.take(length)
            if collect: result.append((attr, offset, payload))
        return result
    for _kind in range(2):
        for _ in range(r.u2()):
            r.take(6); attributes()
    attrs = attributes(True)
    if r.pos != len(data): raise ValueError('Trailing classfile bytes')
    em = [(off, payload) for attr, off, payload in attrs if attr == 'EnclosingMethod']
    if len(em) != 1 or len(em[0][1]) != 4: raise ValueError('Expected one EnclosingMethod')
    if sum(attr == 'InnerClasses' for attr, _, _ in attrs) != 1: raise ValueError('Expected InnerClasses')
    off, payload = em[0]
    owner_idx, method_idx = struct.unpack('>HH', payload)
    owner = utf(get(owner_idx, 7))
    if method_idx == 0: raise ValueError('Already normalized')
    method_name, descriptor = get(method_idx, 12)
    return off + 2, method_idx, owner, utf(method_name), utf(descriptor)


def patch(name, data):
    if name not in TARGETS: raise ValueError('Not an approved class')
    expected_hash, owner, descriptor = TARGETS[name]
    if digest(data) != expected_hash: raise ValueError('Pinned original class hash mismatch: ' + name)
    off, index, actual_owner, method, actual_descriptor = enclosing(data, name)
    if (actual_owner, method, actual_descriptor) != (owner, 'lambda$static$0', descriptor):
        raise ValueError('Unexpected enclosing owner/name/descriptor')
    after = data[:off] + b'\0\0' + data[off + 2:]
    return after, {'class': name, 'before_sha256': digest(data), 'after_sha256': digest(after),
                   'offset': off, 'before_hex': data[off:off+2].hex(), 'after_hex': '0000',
                   'owner': owner, 'method': method, 'descriptor': descriptor}


def entry_name(name):
    p = PurePosixPath(name)
    if not name or '\\' in name or p.is_absolute() or any(x in ('', '.', '..') for x in name.split('/')) or str(p) != name:
        raise ValueError('Unsafe archive entry: ' + name)
    return name


def read_input(path, excluded=None):
    p = plain(path)
    entries = {}
    if p.is_dir():
        rows = []
        for f in sorted(p.rglob('*')):
            plain(f)
            if f.is_dir(): continue
            if not f.is_file(): raise ValueError('Nonregular input')
            name = entry_name(f.relative_to(p).as_posix()); data = f.read_bytes()
            rows.append((name, digest(data)))
            if name.endswith('.class'): entries[name] = data
        fingerprint = digest(json.dumps(rows, separators=(',', ':')).encode())
    elif p.is_file():
        raw = p.read_bytes(); fingerprint = digest(raw)
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            seen = set()
            for info in z.infolist():
                name = info.filename
                entry_name(name[:-1] if info.is_dir() else name)
                if name in seen: raise ValueError('Duplicate ZIP entry: ' + name)
                seen.add(name)
                if (info.external_attr >> 16) & 0o170000 == 0o120000: raise ValueError('ZIP symlink')
                if not info.is_dir() and name.endswith('.class'): entries[name] = z.read(info)
    else: raise ValueError('Missing input: ' + str(p))
    for name in list(entries):
        if name == 'META-INF/versions/9/module-info.class':
            if excluded is not None:
                excluded.append({'input': str(p), 'entry': name, 'sha256': digest(entries[name]),
                                 'reason': 'Java 9 JPMS descriptor, not Android program bytecode'})
            del entries[name]
        elif name.startswith('META-INF/') or name == 'module-info.class':
            raise ValueError('Unapproved module/metadata class input: ' + name)
    return fingerprint, entries


def merge(inputs):
    result, hashes, excluded = {}, {}, []
    paths = [plain(p) for p in inputs]
    if len(set(paths)) != len(paths): raise ValueError('Duplicate input path')
    for p in sorted(paths):
        h, entries = read_input(p, excluded); hashes[str(p)] = h
        for name, data in entries.items():
            if name in result and result[name] != data: raise ValueError('Conflicting class: ' + name)
            result[name] = data
    changes = []
    for name in sorted(TARGETS):
        if name not in result: raise ValueError('Missing required class: ' + name)
        result[name], change = patch(name, result[name]); changes.append(change)
    return result, hashes, changes, excluded


def jar_bytes(entries):
    stream = io.BytesIO()
    with zipfile.ZipFile(stream, 'w', compression=zipfile.ZIP_STORED) as z:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0)); info.external_attr = 0o100644 << 16
            info.create_system = 3; z.writestr(info, data)
    return stream.getvalue()


def helpers(root):
    return {str(p): digest(p.read_bytes()) for p in
            (plain(root / 'tools/instrumentation_qa_metadata.py'), plain(root / 'tools/instrumentation_abi.py'))}


def locations(root, output, receipt):
    root, output, receipt = plain(root), plain(output), plain(receipt)
    if receipt != root / 'app/build/generated/instrumentation-qa/metadata.json': raise ValueError('Wrong receipt location')
    base = root / 'app/build/intermediates'
    if not output.is_relative_to(base) or 'releaseAndroidTest' not in output.parts or 'normalizeQaMonitorMetadata' not in output.parts or output.suffix != '.jar':
        raise ValueError('Output must be scoped QA intermediates jar')
    return root, output, receipt


def atomic(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    plain(path)
    with tempfile.NamedTemporaryFile(dir=path.parent, delete=False) as f:
        tmp = Path(f.name)
        try: f.write(data); f.flush(); os.fsync(f.fileno())
        except BaseException: tmp.unlink(missing_ok=True); raise
    try: tmp.replace(path)
    finally: tmp.unlink(missing_ok=True)


def normalize(inputs, output, receipt, root):
    root, output, receipt = locations(root, output, receipt)
    receipt.unlink(missing_ok=True)
    helper_snapshot = helpers(root)
    for p in inputs:
        p = plain(p)
        if p in (output, receipt) or (p.is_dir() and (output.is_relative_to(p) or receipt.is_relative_to(p))):
            raise ValueError('Output/receipt overlaps input')
    entries, hashes, changes, excluded = merge(inputs); raw = jar_bytes(entries)
    record = {'schema': 1, 'purpose': 'qa-only-exact-javac-initializer-enclosing-method-repair',
              'inputs': hashes, 'output': str(output), 'output_sha256': digest(raw),
              'class_changes': changes, 'helper_inputs': helper_snapshot, 'excluded_entries': excluded}
    if helpers(root) != helper_snapshot: raise ValueError('Helper changed during normalization')
    try:
        atomic(output, raw)
        atomic(receipt, (json.dumps(record, indent=2, sort_keys=True) + '\n').encode())
        return verify(receipt, root)
    except BaseException:
        receipt.unlink(missing_ok=True)
        raise


def verify(receipt, root):
    record = json.loads(plain(receipt).read_text())
    if set(record) != {'schema','purpose','inputs','output','output_sha256','class_changes','helper_inputs','excluded_entries'} or record['schema'] != 1 or record['purpose'] != 'qa-only-exact-javac-initializer-enclosing-method-repair':
        raise ValueError('Unknown receipt schema')
    root, output, receipt = locations(root, record['output'], receipt)
    if record['helper_inputs'] != helpers(root): raise ValueError('Normalizer/helper provenance changed')
    for p in record['inputs']:
        p = plain(p)
        if p in (output, receipt) or (p.is_dir() and (output.is_relative_to(p) or receipt.is_relative_to(p))): raise ValueError('Overlapping receipt inputs')
    entries, hashes, changes, excluded = merge(record['inputs'])
    if hashes != record['inputs'] or changes != record['class_changes'] or excluded != record['excluded_entries']: raise ValueError('Raw inputs or exact delta changed')
    expected = jar_bytes(entries); actual = output.read_bytes()
    if actual != expected or digest(actual) != record['output_sha256']: raise ValueError('Normalized output differs from exact reconstruction')
    return record


def main():
    parser = argparse.ArgumentParser(); sub = parser.add_subparsers(dest='command', required=True)
    p = sub.add_parser('normalize'); p.add_argument('--input', action='append', required=True); p.add_argument('--output', required=True); p.add_argument('--receipt', required=True); p.add_argument('--root', required=True)
    p = sub.add_parser('verify'); p.add_argument('--receipt', required=True); p.add_argument('--root', required=True)
    a = parser.parse_args()
    if a.command == 'normalize': normalize(a.input, a.output, a.receipt, a.root)
    else: verify(a.receipt, a.root)

if __name__ == '__main__': main()
