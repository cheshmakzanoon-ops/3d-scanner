import hashlib,subprocess
from pathlib import Path
rows=[]
for record in subprocess.check_output(['git','ls-files','--stage','-z']).split(b'\0'):
 if not record:continue
 meta,path=record.split(b'\t',1);mode,oid,stage=meta.split();name=path.decode()
 if stage!=b'0':raise SystemExit('Resolve all conflicts')
 if name=='SOURCE_MANIFEST.sha256':continue
 if mode not in (b'100644',b'100755') or '\n' in name or '\r' in name:raise SystemExit(name)
 blob=subprocess.check_output(['git','cat-file','blob',oid.decode()])
 rows.append((name,hashlib.sha256(blob).hexdigest()))
Path('SOURCE_MANIFEST.sha256').write_text(''.join(f'{digest}  {path}\n' for path,digest in sorted(rows)))
