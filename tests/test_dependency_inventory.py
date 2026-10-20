import copy,hashlib,importlib.util,json,struct,sys,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];spec=importlib.util.spec_from_file_location('inventory',ROOT/'tools/dependencies/inventory.py');inv=importlib.util.module_from_spec(spec);spec.loader.exec_module(inv)
def elf(identity=b'identity'):
 note=struct.pack('<III',4,len(identity),3)+b'GNU\0'+identity+b'\0'*((-len(identity))%4)
 d=bytearray(120);d[:6]=b'\x7fELF\x02\x01';struct.pack_into('<Q',d,32,64);struct.pack_into('<HH',d,54,56,1)
 struct.pack_into('<I',d,64,4);struct.pack_into('<Q',d,72,120);struct.pack_into('<Q',d,96,len(note));return bytes(d)+note

def resolution(tmp_path):
 p=tmp_path/'lib.jar';p.write_bytes(b'actual dependency bytes');h=hashlib.sha256(p.read_bytes()).hexdigest()
 row=dict(coordinate='org.example:lib:1',name='lib.jar',sha256=h,path=str(p))
 r=dict(configurations=[dict(configuration='releaseRuntimeClasspath',modules=['org.example:lib:1'],artifacts=[row])]);a=dict(modules=[dict(coordinate=row['coordinate'],artifacts=[{k:v for k,v in row.items() if k!='path'}])]);return r,a,p

def test_resolved_bytes_and_graph_are_enforced(tmp_path):
 r,a,p=resolution(tmp_path);assert inv.verify_resolution(r,a)
 p.write_bytes(b'altered artifact')
 with pytest.raises(ValueError):inv.verify_resolution(r,a)
def test_unapproved_module_rejected(tmp_path):
 r,a,_=resolution(tmp_path);r['configurations'][0]['modules']+=['evil:unknown:1']
 with pytest.raises(ValueError):inv.verify_resolution(r,a)
def test_changed_claimed_hash_rejected(tmp_path):
 r,a,_=resolution(tmp_path);r['configurations'][0]['artifacts'][0]['sha256']='0'*64
 with pytest.raises(ValueError):inv.verify_resolution(r,a)
def test_native_identity_survives_nonidentity_changes():
 data=elf();assert inv.build_id(data)==b'identity'.hex();assert inv.build_id(data+b'debug symbols')==inv.build_id(data)
 assert inv.build_id(elf(b'tampered'))!=inv.build_id(data)
 with pytest.raises(ValueError):inv.build_id(b'not an ELF')

def test_debug_inventory_keeps_native_identity_and_full_notice_bytes(tmp_path):
 p=tmp_path/'qa.aar';native=elf()
 with zipfile.ZipFile(p,'w') as archive:
  archive.writestr('jni/arm64-v8a/libfixture.so',native)
  archive.writestr('META-INF/LICENSE.txt','Full upstream synthetic license fixture\n')
 row={'coordinate':'x:qa:1','path':str(p),'name':p.name,'sha256':inv.digest(p.read_bytes())}
 r={'configurations':[{'configuration':'debugRuntimeClasspath','modules':['x:qa:1'],'artifacts':[row]}]}
 record=inv.native_inventory(r)[0]
 assert record['sha256']==inv.digest(native) and record['gnuBuildId']==inv.build_id(native)
 notices=tmp_path/'notices';notices.mkdir()
 extracted=inv.extract_notices(r,notices)
 assert (notices/extracted[0]['path']).read_text()=='Full upstream synthetic license fixture\n'
def test_parent_pom_license_inheritance(tmp_path):
 for name,text in [('child','<parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>'),('parent','<licenses><license><name>Apache-2.0</name><url>https://apache.org/license</url></license></licenses>')]:
  path=tmp_path/'x'/name/'1'/'hash';path.mkdir(parents=True);(path/(name+'.pom')).write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'+text+'</project>')
 licenses,evidence=inv.pom_evidence('x:child:1',tmp_path);assert licenses[0]['name']=='Apache-2.0';assert len(evidence)==2

def test_debug_resolution_retains_actual_variant_runtime_and_hash_evidence(tmp_path):
 r,_,_=resolution(tmp_path)
 r['configurations'][0]['configuration']='debugRuntimeClasspath'
 pom=tmp_path/'cache/org.example/lib/1/hash/lib.pom';pom.parent.mkdir(parents=True)
 pom.write_text('<project xmlns="http://maven.apache.org/POM/4.0.0"><licenses><license><name>MIT</name><url>https://example.org/MIT</url></license></licenses></project>')
 modules=inv.artifacts_export(r,tmp_path/'cache')
 assert modules[0]['scopes']==['android-runtime']
 assert inv.verify_resolution(r,{'modules':modules})
 assert inv.sbom({'modules':modules})['components'][0]['scope']=='required'
 assert inv.resolution_variant(r)=='debug'
 r['configurations'].append({'configuration':'releaseRuntimeClasspath','modules':[],'artifacts':[]})
 with pytest.raises(ValueError,match='One APK variant'):
  inv.resolution_variant(r)
 r['configurations'].pop()
 r['configurations'][0]['configuration']='unreviewedRuntimeClasspath'
 with pytest.raises(ValueError,match='Unsupported resolved'):
  inv.artifacts_export(r,tmp_path/'cache')

def package(tmp_path):
 assets=tmp_path/'assets';assets.mkdir();(assets/'NOTICE.txt').write_text('Actual full notice fixture; synthetic test only')
 index=dict(files={'NOTICE.txt':inv.digest((assets/'NOTICE.txt').read_bytes())},runtimeCoordinates=['x:runtime:1'],unresolved=[])
 (assets/'NOTICE_INDEX.json').write_text(json.dumps(index));p=tmp_path/'candidate.apk'
 libs=['librealityforge.so','libopencv_java4.so','libc++_shared.so','libandroidx.graphics.path.so','libimage_processing_util_jni.so','libsurface_util_jni.so']
 native=[dict(path='lib/arm64-v8a/'+name,gnuBuildId=inv.build_id(elf())) for name in libs if name!='librealityforge.so']
 inventory=dict(modules=[dict(coordinate='x:runtime:1',scopes=['android-runtime'])],nativeRedistributables=native)
 with zipfile.ZipFile(p,'w') as z:
  for q in assets.iterdir():z.writestr('assets/third-party-notices/'+q.name,q.read_bytes())
  for name in libs:z.writestr('lib/arm64-v8a/'+name,elf())
 return p,assets,inventory

def test_complete_attribution_native_reconciles(tmp_path):
 p,a,i=package(tmp_path);assert inv.package_audit(p,a,i)['status']=='PASS'
def test_missing_or_altered_notice_fails(tmp_path):
 p,a,i=package(tmp_path);(a/'NOTICE.txt').write_text('tamper')
 with pytest.raises(ValueError):inv.package_audit(p,a,i)
def test_unapproved_native_fails(tmp_path):
 p,a,i=package(tmp_path);i['nativeRedistributables'][0]['gnuBuildId']='wrong'
 with pytest.raises(ValueError):inv.package_audit(p,a,i)
def test_blocked_terms_fail_closed(tmp_path):
 p,a,i=package(tmp_path);index=json.loads((a/'NOTICE_INDEX.json').read_text());index['unresolved']=['unknown redistribution terms'];(a/'NOTICE_INDEX.json').write_text(json.dumps(index))
 q=tmp_path/'blocked.apk'
 with zipfile.ZipFile(p) as src,zipfile.ZipFile(q,'w') as dest:
  for n in src.namelist():dest.writestr(n,(a/'NOTICE_INDEX.json').read_bytes() if n.endswith('NOTICE_INDEX.json') else src.read(n))
 with pytest.raises(ValueError,match='BLOCKED_EXTERNAL'):inv.package_audit(q,a,i)

def test_debug_attribution_rejects_release_inventory_and_missing_runtime(tmp_path):
 p,a,i=package(tmp_path);i['variant']='debug'
 with pytest.raises(ValueError,match='variant'):inv.package_audit(p,a,i)
 index=json.loads((a/'NOTICE_INDEX.json').read_text());index['variant']='debug';index['runtimeCoordinates'].append('x:extra-debug:1')
 (a/'NOTICE_INDEX.json').write_text(json.dumps(index))
 q=tmp_path/'debug.apk'
 with zipfile.ZipFile(p) as source,zipfile.ZipFile(q,'w') as out:
  for name in source.namelist():out.writestr(name,(a/'NOTICE_INDEX.json').read_bytes() if name.endswith('NOTICE_INDEX.json') else source.read(name))
 with pytest.raises(ValueError,match='graph'):inv.package_audit(q,a,i)

def test_debug_notice_generation_preserves_bytes_and_requires_reviewed_licenses(tmp_path):
 sys.path.insert(0,str(ROOT/'tools/dependencies'))
 import variant_notices
 r,_,_=resolution(tmp_path);r['configurations'][0]['configuration']='debugRuntimeClasspath'
 row=r['configurations'][0]['artifacts'][0]
 module={'coordinate':row['coordinate'],'scopes':['android-runtime'],'artifacts':[row],'metadataOnly':False,
         'licenses':[{'name':'Apache License, Version 2.0','url':'https://www.apache.org/licenses/LICENSE-2.0'}],'licenseEvidence':[]}
 inventory={'variant':'debug','modules':[module]};base=tmp_path/'base';base.mkdir()
 text=b'Complete synthetic Apache license fixture; unit test only\n';(base/'Apache-2.0.txt').write_bytes(text)
 (base/'NOTICE_INDEX.json').write_text(json.dumps({'files':{'Apache-2.0.txt':inv.digest(text)},'runtimeCoordinates':[],'unresolved':[]}))
 output=tmp_path/'debug'
 result=variant_notices.generate(r,inventory,{'modules':[]},base,output)
 assert result['runtimeModules']==1 and (output/'Apache-2.0.txt').read_bytes()==text
 index=json.loads((output/'NOTICE_INDEX.json').read_text());assert index['variant']=='debug' and index['runtimeCoordinates']==[row['coordinate']]
 module['licenses'][0]['name']='Unreviewed license'
 with pytest.raises(ValueError,match='explicit full-text review'):
  variant_notices.generate(r,inventory,{'modules':[]},base,tmp_path/'blocked')
 (base/'Apache-2.0.txt').write_bytes(b'tamper')
 with pytest.raises(ValueError,match='integrity'):
  variant_notices.generate(r,inventory,{'modules':[]},base,tmp_path/'tampered')
def test_committed_runtime_graph_and_complete_notice_texts():
 inventory=json.loads((ROOT/'tools/dependencies/evidence/inventory.json').read_text());assets=ROOT/'app/src/main/assets/third-party-notices';index=json.loads((assets/'NOTICE_INDEX.json').read_text())
 runtime={m['coordinate'] for m in inventory['modules'] if 'android-runtime' in m['scopes']};assert runtime==set(index['runtimeCoordinates']);assert len(runtime)>100
 for name,sha in index['files'].items():assert inv.digest((assets/name).read_bytes())==sha
 assert 'END OF TERMS AND CONDITIONS' in (assets/'Apache-2.0.txt').read_text()
 assert all(x['identical'] for x in json.loads((ROOT/'tools/dependencies/evidence/opencv-sdk-provenance.json').read_text())['records'] if 'identical' in x)

installer_spec=importlib.util.spec_from_file_location('install_qa',ROOT/'tools/dependencies/install_qa.py');installer=importlib.util.module_from_spec(installer_spec);installer_spec.loader.exec_module(installer)
def test_installer_rejects_corrupted_cached_artifact(tmp_path):
 p=tmp_path/'artifact';p.write_bytes(b'altered bytes')
 with pytest.raises(ValueError,match='Checksum mismatch'):installer.verify(p,'0'*64)
def test_publisher_checksum_binds_exact_portable_artifact():
 name='blender-4.3.2-linux-x64.tar.xz';sha='a'*64
 assert installer.publisher_checksum(sha+'  '+name+'\n',name)==sha
 with pytest.raises(ValueError):installer.publisher_checksum(sha+'  malicious-other.tar.xz',name)
 with pytest.raises(ValueError):installer.publisher_checksum((sha+'  '+name+'\n')*2,name)
def test_installer_rejects_archive_path_escape(tmp_path):
 import tarfile,io
 p=tmp_path/'escape.tar';payload=b'synthetic hostile archive fixture'
 with tarfile.open(p,'w') as tar:
  entry=tarfile.TarInfo('../outside');entry.size=len(payload);tar.addfile(entry,io.BytesIO(payload))
 with pytest.raises(tarfile.FilterError):installer.extract(p,tmp_path/'safe')
 assert not (tmp_path/'outside').exists()
