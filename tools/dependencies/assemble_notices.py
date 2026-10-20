#!/usr/bin/env python3
"""Recreate complete notices from approved origins and exact official OpenCV SDK."""
import argparse,hashlib,json,urllib.request,zipfile
from pathlib import Path

def sha(data):return hashlib.sha256(data).hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--opencv-sdk',type=Path,required=True);p.add_argument('--ndk',type=Path,required=True);p.add_argument('--output',type=Path,default=Path('app/src/main/assets/third-party-notices'));a=p.parse_args()
 evidence=Path(__file__).with_name('evidence');origins=json.loads((evidence/'notice-origins.json').read_text());opencv=json.loads((evidence/'opencv-sdk-provenance.json').read_text())
 if sha(a.opencv_sdk.read_bytes())!=opencv['archiveSha256']:raise ValueError('Unapproved OpenCV SDK archive')
 a.output.mkdir(parents=True,exist_ok=True)
 for record in origins:
  if record.get('url'):data=urllib.request.urlopen(record['url'],timeout=60).read()
  else:data=(a.ndk/record['file'].removeprefix('Android-NDK-28.2-').removesuffix('.txt')).read_bytes()
  if sha(data)!=record['sha256']:raise ValueError('Changed license origin requires review: '+record['file'])
  (a.output/record['file']).write_bytes(data)
 with zipfile.ZipFile(a.opencv_sdk) as z:
  for record in opencv['records']:
   if 'archivePath' not in record:continue
   data=z.read(record['archivePath'])
   if sha(data)!=record['sha256']:raise ValueError('OpenCV notice integrity mismatch')
   (a.output/record['path']).write_bytes(data)
 inventory=json.loads((evidence/'inventory.json').read_text());runtime=[m for m in inventory['modules'] if 'android-runtime' in m['scopes']]
 lines=['Runtime dependency attribution, resolved release graph. Metadata-only platforms are labelled.',
        'First-party source distribution license requires an explicit owner decision.',
        'Complete upstream license texts and preserved notices accompany this inventory.','']
 for m in runtime:
  lines.append(m['coordinate']+(' [metadata only]' if m['metadataOnly'] else ''))
  lines.extend('  '+l['name']+' '+(l['url'] or '') for l in m['licenses'])
 (a.output/'DEPENDENCIES.txt').write_text('\n'.join(lines)+'\n')
 files={p.name:sha(p.read_bytes()) for p in sorted(a.output.iterdir()) if p.is_file() and p.name!='NOTICE_INDEX.json'}
 index=dict(schemaVersion=1,runtimeCoordinates=[m['coordinate'] for m in runtime],files=files,unresolved=[],distributionAuthorization='BLOCKED_EXTERNAL: first-party owner license decision; no permission inferred')
 (a.output/'NOTICE_INDEX.json').write_text(json.dumps(index,indent=2)+'\n')
if __name__=='__main__':main()
