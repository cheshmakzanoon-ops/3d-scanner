import copy,hashlib,importlib.util,json,struct,zipfile
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
def test_parent_pom_license_inheritance(tmp_path):
 for name,text in [('child','<parent><groupId>x</groupId><artifactId>parent</artifactId><version>1</version></parent>'),('parent','<licenses><license><name>Apache-2.0</name><url>https://apache.org/license</url></license></licenses>')]:
  path=tmp_path/'x'/name/'1'/'hash';path.mkdir(parents=True);(path/(name+'.pom')).write_text('<project xmlns="http://maven.apache.org/POM/4.0.0">'+text+'</project>')
 licenses,evidence=inv.pom_evidence('x:child:1',tmp_path);assert licenses[0]['name']=='Apache-2.0';assert len(evidence)==2

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
def test_committed_runtime_graph_and_complete_notice_texts():
 inventory=json.loads((ROOT/'tools/dependencies/evidence/inventory.json').read_text());assets=ROOT/'app/src/main/assets/third-party-notices';index=json.loads((assets/'NOTICE_INDEX.json').read_text())
 runtime={m['coordinate'] for m in inventory['modules'] if 'android-runtime' in m['scopes']};assert runtime==set(index['runtimeCoordinates']);assert len(runtime)>100
 for name,sha in index['files'].items():assert inv.digest((assets/name).read_bytes())==sha
 assert 'END OF TERMS AND CONDITIONS' in (assets/'Apache-2.0.txt').read_text()
 assert all(x['identical'] for x in json.loads((ROOT/'tools/dependencies/evidence/opencv-sdk-provenance.json').read_text())['records'] if 'identical' in x)
