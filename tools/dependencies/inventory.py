#!/usr/bin/env python3
"""Resolved artifact inventory, CycloneDX SBOM, verified license evidence and package notice gate."""
from __future__ import annotations
import argparse,hashlib,io,json,zipfile,struct
from pathlib import Path
from xml.etree import ElementTree as ET
NS={'m':'http://maven.apache.org/POM/4.0.0'}
def digest(data):return hashlib.sha256(data).hexdigest()
def notice_path(root,name):
 path=Path(name)
 if not name or path.is_absolute() or path.as_posix()!=name or any(part in ('.','..') or part.startswith('.') for part in path.parts) or '\\' in name:
  raise ValueError('Unsafe notice path: '+name)
 target=root/path
 if not target.resolve().is_relative_to(root.resolve()):raise ValueError('Notice path escapes root: '+name)
 return target

def notice_files(root):
 return {p.relative_to(root).as_posix():digest(p.read_bytes()) for p in sorted(root.rglob('*'))
         if p.is_file() and not any(part.startswith('.') for part in p.relative_to(root).parts)
         and p.relative_to(root).as_posix()!='NOTICE_INDEX.json'
         and notice_path(root,p.relative_to(root).as_posix())}

def pom_evidence(coordinate,cache,seen=None):
 seen=set() if seen is None else seen
 if coordinate in seen:raise ValueError('POM parent cycle')
 seen.add(coordinate);g,a,v=coordinate.split(':');paths=sorted((cache/g/a/v).glob('*/*.pom'))
 if not paths:raise ValueError('Missing resolved POM '+coordinate)
 data=paths[0].read_bytes();root=ET.fromstring(data)
 licenses=[{'name':x.findtext('m:name',namespaces=NS),'url':x.findtext('m:url',namespaces=NS)} for x in root.findall('m:licenses/m:license',NS)]
 evidence=[dict(coordinate=coordinate,pomSha256=digest(data))]
 if not licenses:
  p=root.find('m:parent',NS)
  if p is not None:
   parent=':'.join(p.findtext('m:'+key,namespaces=NS) for key in ('groupId','artifactId','version'))
   licenses,parents=pom_evidence(parent,cache,seen);evidence+=parents
 if not licenses:raise ValueError('Unresolved license declaration '+coordinate)
 return licenses,evidence

def build_id(data):
 if len(data)<64 or data[:6]!=b'\x7fELF\x02\x01':raise ValueError('Expected ELF64 native redistributable')
 offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54)
 for n in range(count):
  at=offset+n*size
  if at+56>len(data):raise ValueError('Invalid ELF headers')
  if struct.unpack_from('<I',data,at)[0]!=4:continue
  pos=struct.unpack_from('<Q',data,at+8)[0];end=pos+struct.unpack_from('<Q',data,at+32)[0]
  while pos+12<=end:
   namesz,descsz,typ=struct.unpack_from('<III',data,pos);pos+=12
   name=data[pos:pos+namesz];pos+=(namesz+3)//4*4
   value=data[pos:pos+descsz];pos+=(descsz+3)//4*4
   if name==b'GNU\0' and typ==3:return value.hex()
 raise ValueError('Missing ELF GNU build identity')

def native_inventory(resolution):
 records={}
 for conf in resolution['configurations']:
  if conf['configuration'] not in ('releaseRuntimeClasspath','debugRuntimeClasspath','benchmarkRuntimeClasspath','releaseAndroidTestRuntimeClasspath','debugAndroidTestRuntimeClasspath'):continue
  for artifact in conf['artifacts']:
   path=Path(artifact['path'])
   if not zipfile.is_zipfile(path):continue
   with zipfile.ZipFile(path) as z:
    for name in z.namelist():
     parts=name.split('/')
     if len(parts)==3 and parts[0]=='jni' and parts[1] in ('arm64-v8a','x86_64') and name.endswith('.so'):
      data=z.read(name);key='lib/'+parts[1]+'/'+parts[2]
      records[key]=dict(path=key,coordinate=artifact['coordinate'],sourceEntry=name,sha256=digest(data),gnuBuildId=build_id(data))
 return [records[k] for k in sorted(records)]

def configuration_scope(name):
 if name=='releaseRuntimeClasspath':return 'android-runtime'
 if name=='releaseCompileClasspath':return 'android-compile'
 if name=='debugRuntimeClasspath':return 'android-runtime'
 if name=='debugCompileClasspath':return 'android-compile'
 if name.startswith('benchmark') and name.endswith(('RuntimeClasspath','CompileClasspath')):return 'qa-benchmark'
 if name.startswith(('releaseAndroidTest','debugAndroidTest')) and name.endswith(('RuntimeClasspath','CompileClasspath')):return 'qa-instrumentation'
 if name.startswith(('releaseUnitTest','debugUnitTest')) and name.endswith(('RuntimeClasspath','CompileClasspath')):return 'qa-jvm'
 if name in ('kspReleaseKotlinProcessorClasspath','kspDebugKotlinProcessorClasspath','kspBenchmarkKotlinProcessorClasspath'):return 'build-processor'
 raise ValueError('Unsupported resolved dependency configuration: '+name)

def resolution_variant(resolution):
 names={c['configuration'] for c in resolution['configurations']}
 if {'debugRuntimeClasspath','releaseRuntimeClasspath'}<=names:raise ValueError('One APK variant required per runtime inventory')
 return 'debug' if 'debugRuntimeClasspath' in names else 'release' if 'releaseRuntimeClasspath' in names else 'qa'

def artifacts_export(resolution,cache):
 modules={};artifacts={}
 for configuration in resolution['configurations']:
  scope=configuration_scope(configuration['configuration'])
  for c in configuration['modules']:
   entry=modules.setdefault(c,{'coordinate':c,'scopes':[]})
   if scope not in entry['scopes']:entry['scopes'].append(scope)
  for a in configuration['artifacts']:
   raw=Path(a['path']).read_bytes()
   if digest(raw)!=a['sha256']:raise ValueError('Resolved artifact hash mismatch: '+a['coordinate'])
   key=(a['coordinate'],a['name']);artifacts[key]=dict(coordinate=a['coordinate'],name=a['name'],sha256=a['sha256'],bytes=len(raw))
 for c,entry in modules.items():
  entry['licenses'],entry['licenseEvidence']=pom_evidence(c,cache)
  g,a,v=c.split(':');entry['purl']=f'pkg:maven/{g}/{a}@{v}';entry['artifacts']=[a for k,a in sorted(artifacts.items()) if k[0]==c]
  entry['metadataOnly']=not entry['artifacts']
 return sorted(modules.values(),key=lambda x:x['coordinate'])

def verify_resolution(resolution,approved):
 expected={(a['coordinate'],a['name']):a['sha256'] for m in approved['modules'] for a in m['artifacts']}
 actual={(a['coordinate'],a['name']):a['sha256'] for c in resolution['configurations'] for a in c['artifacts']}
 if expected!=actual:raise ValueError('Unapproved, altered or missing resolved artifact')
 if {c for conf in resolution['configurations'] for c in conf['modules']}!={m['coordinate'] for m in approved['modules']}:raise ValueError('Resolved module graph differs')
 for conf in resolution['configurations']:
  for a in conf['artifacts']:
   if digest(Path(a['path']).read_bytes())!=a['sha256']:raise ValueError('Artifact bytes altered')
 return True

def sbom(inventory):
 components=[]
 for m in inventory['modules']:
  g,a,v=m['coordinate'].split(':')
  components.append(dict(type='library',group=g,name=a,version=v,purl=m['purl'],
   scope='required' if 'android-runtime' in m['scopes'] else 'excluded',
   licenses=[{'license':{'name':l['name'],'url':l['url']}} for l in m['licenses']],
   hashes=[{'alg':'SHA-256','content':artifact['sha256']} for artifact in m['artifacts']],
   properties=[{'name':'realityforge:scope','value':','.join(m['scopes'])}]))
 return dict(bomFormat='CycloneDX',specVersion='1.5',version=1,components=components)

def extract_notices(resolution,output):
 # Preserve all upstream license/notice resources even when packaging strips META-INF.
 records=[]
 for conf in resolution['configurations']:
  if conf['configuration'] not in ('releaseRuntimeClasspath','debugRuntimeClasspath','benchmarkRuntimeClasspath','releaseAndroidTestRuntimeClasspath','debugAndroidTestRuntimeClasspath','releaseUnitTestRuntimeClasspath','debugUnitTestRuntimeClasspath'):continue
  for a in conf['artifacts']:
   path=Path(a['path'])
   if not zipfile.is_zipfile(path):continue
   def extract(z,prefix=''):
    for name in z.namelist():
     lower=name.lower()
     if ('license' in lower or 'notice' in lower or lower.endswith('copying')) and not name.endswith('/'):
      data=z.read(name)
      if len(data)>2_000_000:raise ValueError('Unbounded notice resource')
      if b'\0' in data:continue
      dest=a['coordinate'].replace(':','_')+'__'+(prefix+name).replace('/','_')
      (output/dest).write_bytes(data);records.append(dict(coordinate=a['coordinate'],source=name,path=dest,sha256=digest(data)))
   with zipfile.ZipFile(path) as z:
    extract(z)
    if 'classes.jar' in z.namelist():
     with zipfile.ZipFile(io.BytesIO(z.read('classes.jar'))) as nested:extract(nested,'classes.jar_')
 return sorted({(r['coordinate'],r['path']):r for r in records}.values(),key=lambda r:(r['coordinate'],r['path']))

def package_audit(package,assets,inventory):
 manifest=json.loads((assets/'NOTICE_INDEX.json').read_text())
 if manifest.get('variant','release')!=inventory.get('variant','release'):raise ValueError('Attribution variant mismatch')
 for path,expected in manifest['files'].items():
  if digest(notice_path(assets,path).read_bytes())!=expected:raise ValueError('Notice source integrity mismatch: '+path)
 prefix='base/assets/third-party-notices/' if package.suffix=='.aab' else 'assets/third-party-notices/'
 with zipfile.ZipFile(package) as z:
  for name in ['NOTICE_INDEX.json',*manifest['files']]:
   if prefix+name not in z.namelist() or z.read(prefix+name)!=(assets/name).read_bytes():raise ValueError('Missing/altered packaged attribution: '+name)
  runtime={m['coordinate'] for m in inventory['modules'] if 'android-runtime' in m['scopes']}
  if runtime!=set(manifest['runtimeCoordinates']):raise ValueError('Notice graph mismatch')
  native=sorted(n for n in z.namelist() if n.endswith('.so') and ('lib/' in n))
  required={'librealityforge.so','libopencv_java4.so','libc++_shared.so','libandroidx.graphics.path.so','libimage_processing_util_jni.so','libsurface_util_jni.so'}
  filament={'com.google.android.filament:filament-android:1.56.0':'libfilament-jni.so','com.google.android.filament:gltfio-android:1.56.0':'libgltfio-jni.so'}
  required.update(name for coordinate,name in filament.items() if coordinate in runtime)
  if {Path(n).name for n in native}!=required:raise ValueError('Unapproved native redistributable')
  approved={x['path']:x for x in inventory.get('nativeRedistributables',[])}
  for name in native:
   canonical=name.removeprefix('base/')
   if Path(name).name=='librealityforge.so':continue # First-party source provenance is Agent094's manifest.
   if canonical not in approved or build_id(z.read(name))!=approved[canonical]['gnuBuildId']:
    raise ValueError('Native binary identity differs from resolved dependency: '+name)
 if manifest.get('unresolved'):raise ValueError('BLOCKED_EXTERNAL: unresolved redistribution evidence: '+', '.join(manifest['unresolved']))
 return dict(schemaVersion=1,variant=inventory.get('variant','release'),artifact=package.name,artifactSha256=digest(package.read_bytes()),runtimeModules=len(runtime),nativeLibraries=native,noticeFiles=len(manifest['files']),status='PASS')

def main():
 p=argparse.ArgumentParser();sub=p.add_subparsers(dest='command',required=True)
 create=sub.add_parser('generate');create.add_argument('resolution',type=Path);create.add_argument('--cache',type=Path,required=True);create.add_argument('--output',type=Path,required=True);create.add_argument('--notices',type=Path,required=True)
 verify=sub.add_parser('verify');verify.add_argument('resolution',type=Path);verify.add_argument('--inventory',type=Path,required=True)
 audit=sub.add_parser('audit');audit.add_argument('package',type=Path);audit.add_argument('--inventory',type=Path,required=True);audit.add_argument('--notices',type=Path,required=True);audit.add_argument('--report',type=Path)
 a=p.parse_args()
 if a.command=='generate':
  resolution=json.loads(a.resolution.read_text());modules=artifacts_export(resolution,a.cache);a.output.mkdir(parents=True,exist_ok=True);a.notices.mkdir(parents=True,exist_ok=True)
  inventory=dict(schemaVersion=1,variant=resolution_variant(resolution),nativeRedistributables=native_inventory(resolution),source='Gradle resolved configurations: '+', '.join(c['configuration'] for c in resolution['configurations']),modules=modules)
  (a.output/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n');(a.output/'sbom.cdx.json').write_text(json.dumps(sbom(inventory),indent=2)+'\n')
  extracted=extract_notices(resolution,a.notices);(a.output/'extracted-notices.json').write_text(json.dumps(extracted,indent=2)+'\n')
 elif a.command=='verify':verify_resolution(json.loads(a.resolution.read_text()),json.loads(a.inventory.read_text()));print('Approved resolved graph and artifact bytes verified')
 else:
  result=package_audit(a.package,a.notices,json.loads(a.inventory.read_text()));text=json.dumps(result,indent=2)+'\n'
  if a.report:a.report.write_text(text)
  print(text,end='')
if __name__=='__main__':main()
