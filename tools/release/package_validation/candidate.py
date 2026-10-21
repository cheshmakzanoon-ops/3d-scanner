#!/usr/bin/env python3
"""Candidate package entry point; signing inputs stay external and no store API is invoked."""
import argparse,json,os,subprocess,tempfile
from pathlib import Path
from apk import validate,sha256

def main():
 p=argparse.ArgumentParser();p.add_argument('--mode',choices=['test','production'],default='test');p.add_argument('--bundletool',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 certificate=os.environ.get('RF_RELEASE_CERTIFICATE_SHA256')
 if a.mode=='production' and not certificate:raise ValueError('BLOCKED_EXTERNAL: production certificate identity missing')
 apk=Path('app/build/outputs/apk/release/app-release.apk');aab=Path('app/build/outputs/bundle/release/app-release.aab')
 result=validate(apk,os.environ['ANDROID_HOME'],release=True,mode=a.mode,certificate=certificate,required_notices=True)
 a.output.mkdir(parents=True,exist_ok=True);(a.output/'apk-validation.json').write_text(json.dumps(result,indent=2)+'\n')
 if a.mode=='test':
  keystore=Path(os.environ.get('ANDROID_USER_HOME',str(Path.home()/'.android')))/'debug.keystore';alias='androiddebugkey';store=key='android'
 else:
  keystore=Path(os.environ['RF_RELEASE_STORE_FILE']);alias=os.environ['RF_RELEASE_KEY_ALIAS'];store=os.environ['RF_RELEASE_STORE_PASSWORD'];key=os.environ['RF_RELEASE_KEY_PASSWORD']
 with tempfile.TemporaryDirectory() as directory:
  files=[]
  for name,value in [('store',store),('key',key)]:
   file=Path(directory)/name;file.write_text(value);file.chmod(0o600);files.append(file)
  cmd=['python3',str(Path(__file__).with_name('bundle.py')),str(aab),'--bundletool',str(a.bundletool),'--output',str(a.output/'splits'),
       '--keystore',str(keystore),'--key-alias',alias,'--store-pass-file',str(files[0]),'--key-pass-file',str(files[1]),'--signing-mode',a.mode,'--require-notices']
  if certificate:cmd+=['--certificate-sha256',certificate]
  subprocess.run(cmd,check=True)
 paths=[apk,aab,*Path('app/build/outputs/mapping/release').glob('*'),*Path('app/build/outputs/native-debug-symbols/release').glob('*')]
 if not any(p.name=='mapping.txt' for p in paths):raise ValueError('Minification mapping artifact absent')
 if not any(p.suffix=='.zip' for p in paths):raise ValueError('Native debug symbol archive absent')
 (a.output/'artifact-hashes.json').write_text(json.dumps({str(p):sha256(p) for p in paths if p.is_file()},indent=2)+'\n')
if __name__=='__main__':main()
