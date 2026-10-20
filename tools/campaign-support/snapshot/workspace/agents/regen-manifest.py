import hashlib,subprocess
from pathlib import Path
rows=[]
for record in subprocess.check_output(['git','ls-files','--stage','-z']).split(b'\0'):
 if not record:continue
 meta,name=record.split(b'\t',1);mode,oid,stage=meta.split();name=name.decode()
 if stage!=b'0':raise SystemExit('Unmerged index')
 if name=='SOURCE_MANIFEST.sha256':continue
 if mode not in (b'100644',b'100755') or '\n' in name or '\r' in name:raise SystemExit('Unsupported path')
 rows.append((name,hashlib.sha256(subprocess.check_output(['git','cat-file','blob',oid.decode()])).hexdigest()))
Path('SOURCE_MANIFEST.sha256').write_text(''.join(f'{h}  {p}\n' for p,h in sorted(rows)))
