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

@pytest.mark.parametrize('name',['../outside','/absolute','a/../../outside','a\\outside','.hidden/license','a//license'])
def test_nested_notice_paths_reject_escape_and_hidden_entries(tmp_path,name):
 with pytest.raises(ValueError,match='notice path'):
  inv.notice_path(tmp_path,name)

def test_recursive_notice_index_includes_filament_and_rejects_symlink_escape(tmp_path):
 folder=tmp_path/'filament-1.56.0/upstream';folder.mkdir(parents=True)
 (folder/'LICENSE').write_text('synthetic nested notice')
 (tmp_path/'NOTICE_INDEX.json').write_text('{}');(tmp_path/'.gitattributes').write_text('* -whitespace')
 assert inv.notice_files(tmp_path)=={'filament-1.56.0/upstream/LICENSE':inv.digest(b'synthetic nested notice')}
 outside=tmp_path.parent/(tmp_path.name+'-outside');outside.write_text('outside')
 (folder/'escape').symlink_to(outside)
 with pytest.raises(ValueError,match='escapes root'):inv.notice_files(tmp_path)

def test_filament_natives_require_exact_runtime_coordinates_and_build_ids(tmp_path):
 p,a,i=package(tmp_path)
 names={'filament-android':'libfilament-jni.so','gltfio-android':'libgltfio-jni.so'}
 for artifact,name in names.items():
  i['modules'].append({'coordinate':'com.google.android.filament:'+artifact+':1.56.0','scopes':['android-runtime']})
  i['nativeRedistributables'].append({'path':'lib/arm64-v8a/'+name,'gnuBuildId':inv.build_id(elf())})
 index=json.loads((a/'NOTICE_INDEX.json').read_text());index['runtimeCoordinates']=[m['coordinate'] for m in i['modules']]
 (a/'NOTICE_INDEX.json').write_text(json.dumps(index));q=tmp_path/'filament.apk'
 with zipfile.ZipFile(p) as source,zipfile.ZipFile(q,'w') as target:
  for name in source.namelist():target.writestr(name,(a/'NOTICE_INDEX.json').read_bytes() if name.endswith('NOTICE_INDEX.json') else source.read(name))
  for name in names.values():target.writestr('lib/arm64-v8a/'+name,elf())
 assert inv.package_audit(q,a,i)['status']=='PASS'
 i['nativeRedistributables'][-1]['gnuBuildId']='wrong'
 with pytest.raises(ValueError,match='identity'):inv.package_audit(q,a,i)


def test_variant_copies_nested_notices_and_hash_binds_them(tmp_path):
 sys.path.insert(0,str(ROOT/'tools/dependencies'));import variant_notices
 r={'configurations':[{'configuration':'debugRuntimeClasspath','modules':[],'artifacts':[]}]}
 base=tmp_path/'base';nested=base/'filament-1.56.0/upstream/LICENSE';nested.parent.mkdir(parents=True)
 nested.write_bytes(b'exact synthetic upstream text\r\n');(base/'Apache-2.0.txt').write_text('synthetic Apache fixture')
 (base/'NOTICE_INDEX.json').write_text(json.dumps({'files':inv.notice_files(base),'runtimeCoordinates':[],'unresolved':[]}))
 out=tmp_path/'debug';variant_notices.generate(r,{'variant':'debug','modules':[]},{'modules':[]},base,out)
 assert (out/nested.relative_to(base)).read_bytes()==nested.read_bytes()
 assert nested.relative_to(base).as_posix() in json.loads((out/'NOTICE_INDEX.json').read_text())['files']


@pytest.fixture
def blender_download_fixture():
    manifest=json.loads((ROOT/'tools/dependencies/qa-tools.json').read_text())['blender']
    archive=b'Synthetic reviewed archive fixture; never extracted as Blender.\n'
    manifest['sha256']=hashlib.sha256(archive).hexdigest()
    publisher=(manifest['sha256']+'  '+manifest['archive']+'\n').encode()
    manifest['checksumSha256']=hashlib.sha256(publisher).hexdigest()
    return manifest,publisher,archive


def blender_responses(monkeypatch,replies):
    import io
    calls=[]
    class Response(io.BytesIO):
        def __init__(self,data,url):super().__init__(data);self.url=url
        def geturl(self):return self.url
    class Opener:
        def open(self,url,timeout):
            calls.append(url)
            reply=replies[url]
            if isinstance(reply,BaseException):raise reply
            if hasattr(reply,'geturl'):return reply
            data,final=reply if isinstance(reply,tuple) else (reply,url)
            return Response(data,final)
    monkeypatch.setattr(installer.urllib.request,'build_opener',lambda handler:Opener())
    return calls


def test_blender_manifest_pins_independently_reviewed_publisher_and_archive():
    manifest=json.loads((ROOT/'tools/dependencies/qa-tools.json').read_text())['blender']
    assert manifest['version']=='4.3.2'
    assert manifest['sha256']=='4da1c956673c0485e63054e563ee69198cc8f80d8157dd7592dffc8a6a5592e6'
    assert manifest['checksumSha256']=='eee745a32c28b3e6ae90d246474dacfde6d2e05291c118b153d8ef72cff45c7f'
    assert manifest['mirrorAuthority']=='https://projects.blender.org/infrastructure/mirror/wiki'


def test_blender_primary_403_uses_reviewed_fallback_and_records_actual_sources(tmp_path,monkeypatch,blender_download_fixture):
    from urllib.error import HTTPError
    manifest,publisher,archive=blender_download_fixture
    replies={manifest['checksumSource']:HTTPError(manifest['checksumSource'],403,'Forbidden',{},None),
             manifest['checksumFallbackSource']:publisher,
             manifest['url']:HTTPError(manifest['url'],403,'Forbidden',{},None),manifest['fallbackUrl']:archive}
    calls=blender_responses(monkeypatch,replies)
    path,origins=installer.blender_archive(manifest,tmp_path)
    assert path.read_bytes()==archive
    assert (tmp_path/'blender-publisher.sha256').read_bytes()==publisher
    assert calls==[manifest['checksumSource'],manifest['checksumFallbackSource'],manifest['url'],manifest['fallbackUrl']]
    for key,primary,fallback in [('publisherChecksum','checksumSource','checksumFallbackSource'),('archive','url','fallbackUrl')]:
        origin=origins[key]
        assert not origin['cached'] and origin['requestedUrl']==origin['finalUrl']==manifest[fallback]
        assert origin['transportFailures']==[{'requestedUrl':manifest[primary],'errorType':'HTTPError','httpStatus':403}]
    assert not list(tmp_path.glob('*.partial'))


@pytest.mark.parametrize('stage',['publisher','archive'])
def test_blender_content_mismatch_never_tries_another_origin(tmp_path,monkeypatch,blender_download_fixture,stage):
    manifest,publisher,archive=blender_download_fixture
    replies={manifest['checksumSource']:publisher if stage=='archive' else b'changed publisher bytes',
             manifest['url']:b'changed archive bytes'}
    calls=blender_responses(monkeypatch,replies)
    with pytest.raises(ValueError,match='Checksum mismatch'):installer.blender_archive(manifest,tmp_path)
    assert calls==[manifest['checksumSource']]+([manifest['url']] if stage=='archive' else [])
    assert not (tmp_path/manifest['archive']).exists()
    assert not list(tmp_path.glob('*.partial'))


@pytest.mark.parametrize('problem',['wrong-name','duplicate','wrong-hash'])
def test_blender_semantically_invalid_publisher_entry_never_fetches_archive(tmp_path,monkeypatch,blender_download_fixture,problem):
    manifest,publisher,_=blender_download_fixture
    if problem=='wrong-name':publisher=publisher.replace(manifest['archive'].encode(),b'other.tar.xz')
    elif problem=='duplicate':publisher*=2
    else:publisher=publisher.replace(manifest['sha256'].encode(),b'0'*64)
    manifest['checksumSha256']=hashlib.sha256(publisher).hexdigest()
    calls=blender_responses(monkeypatch,{manifest['checksumSource']:publisher})
    with pytest.raises(ValueError,match='publisher checksum|pinned Blender archive'):
        installer.blender_archive(manifest,tmp_path)
    assert calls==[manifest['checksumSource']]
    assert not (tmp_path/manifest['archive']).exists()


@pytest.mark.parametrize('stage',['publisher','archive'])
def test_blender_all_origins_fail_and_partial_download_is_removed(tmp_path,monkeypatch,blender_download_fixture,stage):
    import io
    from urllib.error import HTTPError
    manifest,publisher,_=blender_download_fixture
    primary=manifest['checksumSource' if stage=='publisher' else 'url']
    fallback=manifest['checksumFallbackSource' if stage=='publisher' else 'fallbackUrl']
    class Interrupted(io.BytesIO):
        reads=0
        def geturl(self):return primary
        def read(self,size=-1):
            self.reads+=1
            if self.reads==1:return b'incomplete transport bytes'
            raise ConnectionResetError('Synthetic connection interruption')
    replies={primary:Interrupted(),fallback:HTTPError(fallback,503,'Unavailable',{},None)}
    if stage=='archive':replies[manifest['checksumSource']]=publisher
    calls=blender_responses(monkeypatch,replies)
    with pytest.raises(OSError,match='Approved Blender sources unavailable'):
        installer.blender_archive(manifest,tmp_path)
    assert calls==([manifest['checksumSource']] if stage=='archive' else [])+[primary,fallback]
    assert not (tmp_path/manifest['archive']).exists()
    assert not list(tmp_path.glob('*.partial'))


def test_blender_verified_cache_has_no_invented_download_origin(tmp_path,monkeypatch,blender_download_fixture):
    manifest,publisher,archive=blender_download_fixture
    (tmp_path/'blender-publisher.sha256').write_bytes(publisher)
    (tmp_path/manifest['archive']).write_bytes(archive)
    calls=blender_responses(monkeypatch,{})
    path,origins=installer.blender_archive(manifest,tmp_path)
    assert path.read_bytes()==archive and calls==[]
    for key,payload in [('publisherChecksum',publisher),('archive',archive)]:
        origin=origins[key]
        assert origin['cached'] and origin['requestedUrl'] is None and origin['finalUrl'] is None
        assert origin['transportFailures']==[]
        assert origin['sha256']==hashlib.sha256(payload).hexdigest() and origin['byteCount']==len(payload)


@pytest.mark.parametrize('stage',['publisher','archive'])
def test_blender_corrupted_cache_is_preserved_and_never_redownloaded(tmp_path,monkeypatch,blender_download_fixture,stage):
    manifest,publisher,archive=blender_download_fixture
    publisher_path=tmp_path/'blender-publisher.sha256';publisher_path.write_bytes(publisher)
    archive_path=tmp_path/manifest['archive'];archive_path.write_bytes(archive)
    corrupted=publisher_path if stage=='publisher' else archive_path
    corrupted.write_bytes(b'corrupted cache bytes')
    calls=blender_responses(monkeypatch,{})
    with pytest.raises(ValueError,match='Checksum mismatch'):installer.blender_archive(manifest,tmp_path)
    assert calls==[] and corrupted.read_bytes()==b'corrupted cache bytes'


@pytest.mark.parametrize('kind',['authentication','tls'])
def test_blender_authentication_and_tls_failures_do_not_try_fallback(tmp_path,monkeypatch,blender_download_fixture,kind):
    import ssl
    from urllib.error import HTTPError,URLError
    manifest,_,_=blender_download_fixture
    error=HTTPError(manifest['checksumSource'],401,'Unauthorized',{},None) if kind=='authentication' else URLError(ssl.SSLCertVerificationError(1,'Synthetic invalid certificate'))
    calls=blender_responses(monkeypatch,{manifest['checksumSource']:error})
    with pytest.raises(type(error)):installer.blender_archive(manifest,tmp_path)
    assert calls==[manifest['checksumSource']] and list(tmp_path.iterdir())==[]


def test_blender_local_write_failure_does_not_try_fallback(tmp_path,monkeypatch,blender_download_fixture):
    manifest,publisher,_=blender_download_fixture
    calls=blender_responses(monkeypatch,{manifest['checksumSource']:publisher})
    def denied(**kwargs):raise PermissionError('Synthetic local filesystem denial')
    monkeypatch.setattr(installer.tempfile,'NamedTemporaryFile',denied)
    with pytest.raises(PermissionError):installer.blender_archive(manifest,tmp_path)
    assert calls==[manifest['checksumSource']] and list(tmp_path.iterdir())==[]


def test_blender_actual_redirect_origin_is_recorded(tmp_path,monkeypatch,blender_download_fixture):
    manifest,publisher,archive=blender_download_fixture
    calls=blender_responses(monkeypatch,{manifest['checksumSource']:(publisher,manifest['checksumFallbackSource']),manifest['url']:archive})
    _,origins=installer.blender_archive(manifest,tmp_path)
    assert origins['publisherChecksum']['requestedUrl']==manifest['checksumSource']
    assert origins['publisherChecksum']['finalUrl']==manifest['checksumFallbackSource']
    assert origins['publisherChecksum']['transportFailures']==[]
    assert calls==[manifest['checksumSource'],manifest['url']]


@pytest.mark.parametrize('target',['http://download.blender.org/release/Blender4.3/blender-4.3.2.sha256','https://unreviewed.example/blender-4.3.2.sha256'])
def test_blender_redirects_cannot_leave_reviewed_https_sources(tmp_path,monkeypatch,blender_download_fixture,target):
    manifest,publisher,_=blender_download_fixture
    urls=(manifest['checksumSource'],manifest['checksumFallbackSource'])
    handler=installer.BlenderRedirects(urls)
    request=installer.urllib.request.Request(urls[0])
    with pytest.raises(ValueError,match='Unapproved Blender redirect'):
        handler.redirect_request(request,None,302,'Found',{},target)
    assert handler.redirect_request(request,None,302,'Found',{},urls[1]).full_url==urls[1]
    calls=blender_responses(monkeypatch,{urls[0]:(publisher,target)})
    with pytest.raises(ValueError,match='Unapproved Blender response origin'):
        installer.blender_archive(manifest,tmp_path)
    assert calls==[urls[0]] and list(tmp_path.iterdir())==[]


def test_blender_configuration_cannot_add_an_unreviewed_mirror(tmp_path,monkeypatch,blender_download_fixture):
    manifest,_,_=blender_download_fixture
    manifest['checksumFallbackSource']='https://unreviewed.example/blender-4.3.2.sha256'
    calls=blender_responses(monkeypatch,{})
    with pytest.raises(ValueError,match='Unapproved Blender download URLs'):
        installer.blender_archive(manifest,tmp_path)
    assert calls==[] and list(tmp_path.iterdir())==[]
