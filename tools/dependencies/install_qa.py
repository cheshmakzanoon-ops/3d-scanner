#!/usr/bin/env python3
"""Prepare isolated, verified QA tooling outside the checkout; emit CI activation and receipts."""
from __future__ import annotations
import argparse,base64,hashlib,json,os,platform,re,shlex,shutil,subprocess,sys,tarfile,urllib.request
from pathlib import Path
HERE=Path(__file__).resolve().parent

def digest(path,algorithm='sha256'):
 with open(path,'rb') as f:return hashlib.file_digest(f,algorithm).hexdigest()
def verify(path,expected,algorithm='sha256'):
 if digest(path,algorithm)!=expected:raise ValueError('Checksum mismatch: '+path.name)
 return path

def fetch(url,path,expected,algorithm='sha256'):
 if not path.exists():
  partial=path.with_suffix(path.suffix+'.partial')
  try:
   with urllib.request.urlopen(url,timeout=120) as response,partial.open('wb') as output:shutil.copyfileobj(response,output)
   verify(partial,expected,algorithm);partial.replace(path)
  finally:partial.unlink(missing_ok=True)
 return verify(path,expected,algorithm)

def publisher_checksum(text,filename):
 matches=[]
 for line in text.splitlines():
  match=re.fullmatch(r'([0-9a-fA-F]{64})\s+\*?(.+)',line.strip())
  if match and match[2]==filename:matches.append(match[1].lower())
 if len(matches)!=1:raise ValueError('Missing/duplicate exact publisher checksum entry: '+filename)
 return matches[0]

def extract(archive,destination):
 destination.mkdir(parents=True,exist_ok=True)
 with tarfile.open(archive) as tar:tar.extractall(destination,filter='data')

def run(args,log=None,env=None):
 if log:
  with log.open('w') as output:subprocess.run(list(map(str,args)),check=True,stdout=output,stderr=subprocess.STDOUT,env=env)
  return ''
 return subprocess.check_output(list(map(str,args)),text=True,stderr=subprocess.PIPE,env=env).strip()

def venv(root,name,lock,wheels=None):
 target=root/name;python=target/'bin/python'
 if not python.is_file():run([sys.executable,'-m','venv',target])
 args=[python,'-m','pip','--cache-dir',root/'pip-cache','install','--require-hashes','-r',lock]
 if wheels:args+=['--no-index','--find-links',wheels]
 run(args,root/(name+'-install.log'));run([python,'-m','pip','check'],root/(name+'-check.log'))
 return python

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,required=True)
 p.add_argument('--github-env',type=Path);p.add_argument('--github-path',type=Path)
 p.add_argument('--qa-wheelhouse',type=Path);p.add_argument('--security-wheelhouse',type=Path)
 p.add_argument('--require-portable-blender',action='store_true');a=p.parse_args()
 if sys.version_info[:2]!=(3,12) or platform.system()!='Linux' or platform.machine() not in ('x86_64','AMD64'):raise ValueError('QA locks require CPython3.12 Linuxx86_64')
 root=a.root.resolve();checkout=HERE.parents[1]
 if root==checkout or root.is_relative_to(checkout):raise ValueError('QA installation must stay outside source checkout')
 root.mkdir(parents=True,exist_ok=True);downloads=root/'downloads';downloads.mkdir(exist_ok=True)
 manifest=json.loads((HERE/'qa-tools.json').read_text())
 qa=venv(root,'qa-python',HERE/'qa-requirements.lock',a.qa_wheelhouse)
 security=venv(root,'security-python',HERE/'security-requirements.lock',a.security_wheelhouse)
 node=manifest['node'];archive=fetch(node['url'],downloads/Path(node['url']).name,node['sha256']);node_root=root/'node'
 binary=node_root/('node-'+node['version']+'-linux-x64')/'bin/node'
 if not binary.exists():extract(archive,node_root)
 if run([binary,'--version'])!=node['version']:raise ValueError('Unexpected Node runtime')
 validator=manifest['gltfValidator'];expected=base64.b64decode(validator['integrity'].split('-',1)[1]).hex()
 tgz=fetch(validator['url'],downloads/'gltf-validator.tgz',expected,'sha512');gltf_root=root/'gltf-validator'
 if not (gltf_root/'package/package.json').exists():extract(tgz,gltf_root)
 package=gltf_root/'package';metadata=json.loads((package/'package.json').read_text())
 if metadata['version']!=validator['version']:raise ValueError('Unexpected glTF validator')
 env=dict(os.environ,PATH=str(binary.parent)+os.pathsep+os.environ.get('PATH',''))
 check=run([binary,'-e',"const v=require(process.argv[1]); if(v.version()!==process.argv[2])process.exit(1); console.log(v.version());",package,validator['version']],env=env)
 blender=manifest['blender'];existing=shutil.which('blender');mode='existing-system-package'
 if not a.require_portable_blender and existing and run([existing,'--version']).splitlines()[0]=='Blender '+blender['version']:
  blender_bin=Path(existing).resolve()
 else:
  mode='official-portable-publisher-checksum'
  with urllib.request.urlopen(blender['checksumSource'],timeout=60) as response:text=response.read().decode('utf-8')
  checksum=publisher_checksum(text,blender['archive']);(downloads/'blender-publisher.sha256').write_text(text)
  artifact=fetch(blender['url'],downloads/blender['archive'],checksum);blender_root=root/'blender'
  blender_bin=blender_root/('blender-'+blender['version']+'-linux-x64')/'blender'
  if not blender_bin.exists():extract(artifact,blender_root)
  if run([blender_bin,'--version']).splitlines()[0]!='Blender '+blender['version']:raise ValueError('Unexpected Blender executable')
 # Validate actual tools and scientific imports, not just successful installation processes.
 versions=json.loads(run([qa,'-c',"import json,importlib.metadata as m; print(json.dumps({n:m.version(n) for n in ['numpy','opencv-python-headless','scikit-image','pytest','trimesh','scipy','Pillow']}))"]))
 if versions['trimesh']!='4.5.3':raise ValueError('Unexpected Trimesh')
 scanner_versions=json.loads(run([security,'-c',"import json,importlib.metadata as m; print(json.dumps({n:m.version(n) for n in ['bandit','detect-secrets','pip-audit']}))"]))
 if scanner_versions!={'bandit':'1.8.6','detect-secrets':'1.5.0','pip-audit':'2.9.0'}:raise ValueError('Unexpected scanner versions')
 paths=[str(qa.parent),str(binary.parent),str(blender_bin.parent)]
 variables={'RF_GLTF_VALIDATOR':str(package),'RF_SECURITY_PYTHON':str(security),'SCANNER_PYTHON':str(security)}
 if a.github_env:
  with a.github_env.open('a') as output:
   for name,value in variables.items():output.write(name+'='+value+'\n')
 if a.github_path:
  with a.github_path.open('a') as output:output.write('\n'.join(paths)+'\n')
 activation='\n'.join('export '+name+'='+shlex.quote(value) for name,value in variables.items())+'\nexport PATH='+shlex.quote(os.pathsep.join(paths))+':"$PATH"\n'
 (root/'activate.sh').write_text(activation)
 receipt=dict(schemaVersion=1,status='PASS',scope='external-host-QA-only-not-Android-runtime',python=versions,scanners=scanner_versions,
  node={'version':node['version'],'archiveSha256':digest(archive),'binarySha256':digest(binary)},gltfValidator={'version':check,'archiveSha512':digest(tgz,'sha512')},
  blender={'version':blender['version'],'mode':mode,'binarySha256':digest(blender_bin)},
  lockSha256={name:digest(HERE/name) for name in ['qa-requirements.lock','security-requirements.lock','qa-tools.json']},activation=str(root/'activate.sh'))
 (root/'installation.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
