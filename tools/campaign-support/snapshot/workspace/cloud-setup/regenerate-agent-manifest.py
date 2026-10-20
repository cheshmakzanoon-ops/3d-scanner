import hashlib,subprocess
from pathlib import Path
rows=[]
for record in subprocess.check_output(['git','ls-files','--stage','-z']).split(b'\0'):
 if not record:continue
 metadata,raw_path=record.split(b'\t',1);mode,oid,stage=metadata.split();path=raw_path.decode()
 if stage!=b'0':raise SystemExit('Unresolved merge')
 if path=='SOURCE_MANIFEST.sha256':continue
 if mode not in (b'100644',b'100755') or '\n' in path or '\r' in path:raise SystemExit('Unsupported tracked path')
 content=subprocess.check_output(['git','cat-file','blob',oid.decode()]);rows.append((path,hashlib.sha256(content).hexdigest()))
Path('SOURCE_MANIFEST.sha256').write_text(''.join(f'{h}  {p}\n' for p,h in sorted(rows)))
