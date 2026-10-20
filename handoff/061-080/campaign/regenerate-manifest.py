import hashlib
import subprocess
from pathlib import Path
records = subprocess.check_output(['git', 'ls-files', '--stage', '-z']).split(b'\0')
rows = []
for record in records:
    if not record:
        continue
    metadata, raw_path = record.split(b'\t', 1)
    mode, oid, stage = metadata.split()
    path = raw_path.decode('utf-8')
    if stage != b'0':
        raise SystemExit('Resolve all semantic merge conflicts before regenerating the manifest.')
    if path == 'SOURCE_MANIFEST.sha256':
        continue
    if mode not in (b'100644', b'100755') or '\n' in path or '\r' in path:
        raise SystemExit('Unsupported tracked entry; coordinate with 094: ' + path)
    content = subprocess.check_output(['git', 'cat-file', 'blob', oid.decode('ascii')])
    rows.append((path, hashlib.sha256(content).hexdigest()))
Path('SOURCE_MANIFEST.sha256').write_text(
    ''.join(f'{digest}  {path}\n' for path, digest in sorted(rows)), encoding='utf-8')
