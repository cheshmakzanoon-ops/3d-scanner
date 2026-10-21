import importlib.util,struct,sys,zipfile
from pathlib import Path
import pytest
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from release.package_validation.apk import manifest_policy,archive_policy,elf_alignment,signature_policy,backup_policy,backup_resource_name,LIBRARIES
from release.package_validation.render_convention import render

def elf(align=16384,machine=183):
 d=bytearray(120);d[:6]=b'\x7fELF\x02\x01';struct.pack_into('<H',d,18,machine);struct.pack_into('<Q',d,32,64);struct.pack_into('<HH',d,54,56,1)
 struct.pack_into('<I',d,64,1);struct.pack_into('<Q',d,112,align);return d

def manifest(extra='',permissions=''):
 return f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.realityforge.scanner" android:versionCode="19" android:versionName="test">{permissions}<application android:allowBackup="false" android:fullBackupContent="false" android:usesCleartextTraffic="false" android:dataExtractionRules="@xml/rules" {extra}/></manifest>'

def package(path,abis=('arm64-v8a','x86_64'),bad=None):
 with zipfile.ZipFile(path,'w') as z:
  for abi in abis:
   for lib in LIBRARIES:z.writestr(f'lib/{abi}/{lib}',elf(4096 if bad==lib else 16384,183 if abi=='arm64-v8a' else 62))
 return path

def test_manifest_identity():assert manifest_policy(manifest(),True)['versionCode']=='19'
@pytest.mark.parametrize('extra',['android:debuggable="true"','android:testOnly="true"'])
def test_debug_release_rejected(extra):
 with pytest.raises(ValueError):manifest_policy(manifest(extra),True)
@pytest.mark.parametrize('permission',['INTERNET','ACCESS_NETWORK_STATE'])
def test_network_rejected(permission):
 with pytest.raises(ValueError):manifest_policy(manifest(permissions=f'<uses-permission android:name="android.permission.{permission}"/>'),True)
@pytest.mark.parametrize('field',['allowBackup','fullBackupContent','usesCleartextTraffic'])
def test_backup_and_cleartext_fail_closed(field):
 with pytest.raises(ValueError):manifest_policy(manifest().replace(f'{field}="false"',f'{field}="true"'))
def test_real_library_inventory_and_alignment(tmp_path):
 assert len(archive_policy(package(tmp_path/'valid.apk')))==16
 with pytest.raises(ValueError):archive_policy(package(tmp_path/'bad.apk',bad='libopencv_java4.so'))
 with pytest.raises(ValueError):archive_policy(package(tmp_path/'abi.apk',abis=('x86_64',)))
def test_missing_native_or_notices_rejected(tmp_path):
 p=package(tmp_path/'no-notices.apk')
 with pytest.raises(ValueError):archive_policy(p,required_notices=True)
 with zipfile.ZipFile(p,'a') as z:z.writestr('assets/third-party-notices/NOTICE.txt','notice')
 archive_policy(p,required_notices=True)
def test_fixture_leak_rejected(tmp_path):
 p=package(tmp_path/'fixture.apk')
 with zipfile.ZipFile(p,'a') as z:z.writestr('assets/fixtures/view1.png','synthetic')
 with pytest.raises(ValueError):archive_policy(p,release=True)
@pytest.mark.parametrize('data',[b'',b'not elf',b'\x7fELF',elf(4096),elf(8192)])
def test_bad_elf(data):
 with pytest.raises(ValueError):elf_alignment(data)
def test_signer_identity_and_mode():
 digest='a'*64;output='Signer #1 certificate DN: CN=Android Debug, O=Android, C=US\nVerified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: '+digest
 assert signature_policy(output,'test')==[digest]
 with pytest.raises(ValueError):signature_policy(output,'production')
 with pytest.raises(ValueError):signature_policy(output,'production','b'*64)
 with pytest.raises(ValueError):signature_policy(output.replace('true','false'),'test')
 assert signature_policy(output.replace('CN=Android Debug','CN=Approved Release'),'production',digest)==[digest]

def test_baseline_relief_fixture_leak_rejected(tmp_path):
 p=package(tmp_path/'leak.apk')
 with zipfile.ZipFile(p,'a') as z:z.writestr('assets/relief/view-03.jpg','synthetic')
 with pytest.raises(ValueError):archive_policy(p,release=True)

def test_wrong_elf_machine_rejected(tmp_path):
 p=package(tmp_path/'machine.apk')
 q=tmp_path/'bad-machine.apk'
 with zipfile.ZipFile(p) as src,zipfile.ZipFile(q,'w') as z:
  for n in src.namelist():z.writestr(n,elf(machine=62) if n=='lib/arm64-v8a/librealityforge.so' else src.read(n))
 with pytest.raises(ValueError,match='machine'):archive_policy(q)
def test_backup_rules_cover_all_domains():
 xml=(Path(__file__).resolve().parents[1]/'app/src/main/res/xml/data_extraction_rules.xml').read_text()
 assert backup_policy(xml)
 with pytest.raises(ValueError):backup_policy(xml.replace('<exclude domain="database" path="." />',''))
 with pytest.raises(ValueError):backup_policy(xml.replace('</cloud-backup>','<include domain="file" path="." /></cloud-backup>'))
def test_production_refuses_debug_identity():
 output='Signer #1 certificate DN: CN=Android Debug\nVerified using v2 scheme: true\nSigner #1 certificate SHA-256 digest: '+'a'*64
 with pytest.raises(ValueError):signature_policy(output,'production','a'*64)
 with pytest.raises(ValueError):signature_policy(output.replace('CN=Android Debug','CN=Unknown'),'test')

def test_shrunk_backup_resource_is_resolved_from_manifest_identity():
 xml=manifest().replace('@xml/rules','@ref/0x7f0e0000')
 resources='resource 0x7f0e0000 xml/data_extraction_rules\n () (file) res/4j.xml type=XML'
 assert backup_resource_name(xml,resources)=='data_extraction_rules'
 with pytest.raises(ValueError):backup_resource_name(xml,resources.replace('0x7f0e0000','0x7f0e0001'))
 assert backup_resource_name(manifest().replace('@xml/rules','@xml/data_extraction_rules'),resources)=='data_extraction_rules'

def test_owner_inline_is_exact_idempotent_and_rejects_stale_conventions():
 root=Path(__file__).resolve().parents[1]
 fragment=(root/'gradle/release-validation.gradle.kts').read_text()
 label='gradle/release-validation.gradle.kts'
 base='plugins { id("com.android.application") }\napply(from = "../'+label+'")\n'
 integrated=render(base,fragment,label)
 assert 'apply(from =' not in integrated
 assert render(integrated,fragment,label)==integrated
 with pytest.raises(ValueError,match='differs'):
  render(integrated,fragment+'\n// unreviewed source difference\n',label)


def test_integrated_filament_libraries_remain_subject_to_alignment_gate(tmp_path):
    assert {'libfilament-jni.so', 'libgltfio-jni.so'} <= LIBRARIES
    for library in ('libfilament-jni.so', 'libgltfio-jni.so'):
        with pytest.raises(ValueError, match='16 KB'):
            archive_policy(package(tmp_path / (library + '.apk'), bad=library))
