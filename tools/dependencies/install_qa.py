#!/usr/bin/env python3
"""Prepare isolated, verified QA tooling outside the checkout; emit CI activation and receipts."""
from __future__ import annotations
import argparse,base64,hashlib,http.client,json,os,platform,re,shlex,shutil,ssl,subprocess,sys,tarfile,tempfile,urllib.error,urllib.request
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

class BlenderRedirects(urllib.request.HTTPRedirectHandler):
 """Permit only the two reviewed HTTPS URLs for this exact Blender file."""
 def __init__(self,urls):self.urls=urls
 def redirect_request(self,request,fp,code,message,headers,newurl):
  if newurl not in self.urls:raise ValueError('Unapproved Blender redirect: '+newurl)
  return super().redirect_request(request,fp,code,message,headers,newurl)

def blender_transport_failure(error):
 if isinstance(error,urllib.error.HTTPError):
  return error.code in (403,404,408,429,500,502,503,504)
 if isinstance(error,urllib.error.URLError):
  return not isinstance(error.reason,ssl.SSLError)
 return isinstance(error,(TimeoutError,ConnectionError,http.client.IncompleteRead))

class BlenderTransportFailure(Exception):
 def __init__(self,cause):
  super().__init__(str(cause));self.cause=cause

def fetch_blender_file(urls,path,expected,maximum_bytes=None):
 """Fetch a pinned Blender file from the primary or reviewed Dotsrc mirror.

 Only transport failures permit the alternate. Content/identity failures and
 TLS verification errors remain fatal. A verified cache has no inferred origin.
 """
 filename=urls[0].rsplit('/',1)[-1]
 approved=('https://download.blender.org/release/Blender4.3/'+filename,
           'https://mirror.dotsrc.org/blender/release/Blender4.3/'+filename)
 if tuple(urls)!=approved or filename not in ('blender-4.3.2.sha256','blender-4.3.2-linux-x64.tar.xz'):
  raise ValueError('Unapproved Blender download URLs')
 if not isinstance(expected,str) or not re.fullmatch('[0-9a-f]{64}',expected):
  raise ValueError('Invalid pinned Blender SHA256')
 record=dict(fileName=path.name,sha256=expected,cached=False,requestedUrl=None,
             finalUrl=None,transportFailures=[])
 if path.exists() or path.is_symlink():
  if path.is_symlink() or not path.is_file():raise ValueError('Invalid Blender cache entry: '+path.name)
  verify(path,expected)
  if maximum_bytes is not None and path.stat().st_size>maximum_bytes:raise ValueError('Oversized Blender checksum file')
  return path,dict(record,cached=True,byteCount=path.stat().st_size)
 opener=urllib.request.build_opener(BlenderRedirects(approved))
 for url in approved:
  partial=None
  try:
   # The standard opener retains HTTPS certificate verification. Restrict each
   # redirect before following it, and verify the final URL again for evidence.
   try:response=opener.open(url,timeout=120)
   except Exception as error:
    if blender_transport_failure(error):raise BlenderTransportFailure(error) from error
    raise
   with response:
    final=response.geturl()
    if final not in approved:raise ValueError('Unapproved Blender response origin: '+final)
    with tempfile.NamedTemporaryFile(mode='wb',dir=path.parent,prefix='.'+path.name+'.',suffix='.partial',delete=False) as output:
     partial=Path(output.name);total=0
     while True:
      try:chunk=response.read(1024*1024)
      except Exception as error:
       if blender_transport_failure(error):raise BlenderTransportFailure(error) from error
       raise
      if not chunk:break
      total+=len(chunk)
      if maximum_bytes is not None and total>maximum_bytes:raise ValueError('Oversized Blender checksum file')
      output.write(chunk)
   verify(partial,expected);partial.replace(path)
   return path,dict(record,requestedUrl=url,finalUrl=final,byteCount=path.stat().st_size)
  except BlenderTransportFailure as failure:
   error=failure.cause
   record['transportFailures'].append(dict(requestedUrl=url,errorType=type(error).__name__,
       httpStatus=error.code if isinstance(error,urllib.error.HTTPError) else None))
   last=error
   if isinstance(error,urllib.error.HTTPError):error.close()
  finally:
   if partial is not None:partial.unlink(missing_ok=True)
 raise OSError('Approved Blender sources unavailable: '+json.dumps(record['transportFailures'])) from last

def blender_archive(blender,downloads):
 publisher,publisher_origin=fetch_blender_file(
  (blender['checksumSource'],blender['checksumFallbackSource']),
  downloads/'blender-publisher.sha256',blender['checksumSha256'],maximum_bytes=65536)
 checksum=publisher_checksum(publisher.read_text(encoding='utf-8'),blender['archive'])
 if checksum!=blender['sha256']:raise ValueError('Publisher entry differs from pinned Blender archive SHA256')
 artifact,archive_origin=fetch_blender_file((blender['url'],blender['fallbackUrl']),
  downloads/blender['archive'],blender['sha256'])
 return artifact,dict(publisherChecksum=publisher_origin,archive=archive_origin)

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
 blender=manifest['blender'];existing=shutil.which('blender');mode='existing-system-package';blender_downloads=None
 if not a.require_portable_blender and existing and run([existing,'--version']).splitlines()[0]=='Blender '+blender['version']:
  blender_bin=Path(existing).resolve()
 else:
  mode='official-portable-publisher-checksum'
  artifact,blender_downloads=blender_archive(blender,downloads);blender_root=root/'blender'
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
  blender={'version':blender['version'],'mode':mode,'binarySha256':digest(blender_bin),'downloads':blender_downloads},
  lockSha256={name:digest(HERE/name) for name in ['qa-requirements.lock','security-requirements.lock','qa-tools.json']},activation=str(root/'activate.sh'))
 (root/'installation.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt,indent=2))
if __name__=='__main__':main()
