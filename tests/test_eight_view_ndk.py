"""Synthetic archive/license/ownership controls; never download or install an NDK."""
import hashlib, io, json, os, shutil, stat, struct, sys, time, zipfile
from pathlib import Path
from unittest.mock import Mock
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import tools
SOURCE=Path(os.environ.get('RF_SOURCE_ROOT',ROOT));sys.path.append(str(SOURCE));tools.__path__.append(str(SOURCE/'tools'))
from tools.eight_view_host import ndk as n, runtime as r, controller as c
from tools.device.software_emulator.diagnostic import license_snapshot

class Budget:
 def check(self):return 60

def run(tmp_path):
 obj=r.Runner(tmp_path,tmp_path/'work',seconds=900);obj.disk_guard=lambda:None;return obj

def sdk_fixture(tmp_path,accepted=True):
 sdk=tmp_path/'sdk';(sdk/'licenses').mkdir(parents=True);(sdk/'ndk').mkdir()
 (sdk/'licenses/android-sdk-license').write_text((n.LICENSE_SHA1 if accepted else '0'*40)+'\n')
 return sdk,license_snapshot(sdk)

def index_xml():
 return f'''<repository><license id="android-sdk-license">fixture license</license><remotePackage path="{n.PACKAGE}"><revision><major>28</major><minor>2</minor><micro>13676358</micro></revision><uses-license ref="android-sdk-license"/><archives><archive><host-os>linux</host-os><complete><size>{n.ARCHIVE_BYTES}</size><checksum type="sha1">{n.ARCHIVE_SHA1}</checksum><url>android-ndk-r28c-linux.zip</url></complete></archive></archives></remotePackage></repository>'''.encode()

def use_fixture_license(monkeypatch):monkeypatch.setattr(n,'LICENSE_SHA1',hashlib.sha1(b'fixture license').hexdigest())

def zip_fixture(tmp_path,extra=None):
 path=tmp_path/'fixture.zip';entries=[(n.PREFIX+'/',stat.S_IFDIR|0o755,b''),(n.PREFIX+'/bin/',stat.S_IFDIR|0o755,b''),(n.PREFIX+'/source.properties',stat.S_IFREG|0o644,b'Pkg.Revision = 28.2.13676358\n'),(n.PREFIX+'/bin/tool',stat.S_IFREG|0o755,b'fixture executable not run'),(n.PREFIX+'/bin/alias',stat.S_IFLNK|0o777,b'tool')]
 if extra:entries.extend(extra)
 with zipfile.ZipFile(path,'w') as z:
  for name,mode,data in entries:
   info=zipfile.ZipInfo(name);info.external_attr=mode<<16;z.writestr(info,data)
 raw=path.read_bytes();e=struct.unpack('<4s4H2IH',raw[-22:]);return path,e[-2],len(entries),sum(len(data) for _,_,data in entries),raw[e[-2]:e[-2]+e[-3]]

def admit_fixture(monkeypatch,path,offset,count,size,central):
 monkeypatch.setattr(n,'ENTRY_COUNT',count);monkeypatch.setattr(n,'CENTRAL_BYTES',len(central));monkeypatch.setattr(n,'CENTRAL_SHA256',hashlib.sha256(central).hexdigest())
 monkeypatch.setattr(n,'EXPANDED_BYTES',size)
 original=n.archive_plan
 monkeypatch.setattr(n,'archive_plan',lambda z,off,b:original(z,off,b,expected_count=count,expanded=size,links_count=1))

def test_actual_policy_is_exact_and_cmake_absent():
 assert n.PACKAGE=='ndk;28.2.13676358' and n.ARCHIVE_BYTES==722261334 and n.EXPANDED_BYTES==2294132951
 assert n.ENTRY_COUNT==8996 and n.SYMLINK_COUNT==35 and n.CENTRAL_BYTES==1540425
 assert n.ARCHIVE_SHA1=='a7b54a5de87fecd125a17d54f73c446199e72a64'
 assert n.LICENSE_SHA1=='24333f8a63b6825ea9c5514f83c2829b004d1fee'
 assert 'cmake;' not in Path(n.__file__).read_text() and c.BASE_PACKAGES==('platforms;android-36','build-tools;36.0.0')

def test_metadata_exact_fixture(monkeypatch):
 use_fixture_license(monkeypatch);assert n.metadata(index_xml())['revision']==n.REVISION

@pytest.mark.parametrize('old,new',[(b'28</major>',b'27</major>'),(b'13676358',b'1'),(b'android-sdk-license',b'foreign'),(b'sha1',b'sha256'),(b'r28c-linux.zip',b'r29-linux.zip'),(b'722261334',b'722261335'),(b'fixture license',b'new terms')])
def test_metadata_changes_block(monkeypatch,old,new):
 use_fixture_license(monkeypatch)
 with pytest.raises(r.Failure):n.metadata(index_xml().replace(old,new))

@pytest.mark.parametrize('raw',[b'<!DOCTYPE x><repository/>',b'<!ENTITY x "x">',b'x'*(1024**2+1)])
def test_metadata_bounds(raw):
 with pytest.raises(r.Failure):n.metadata(raw)

def test_metadata_duplicate_or_dependency_blocks(monkeypatch):
 use_fixture_license(monkeypatch);raw=index_xml()
 with pytest.raises(r.Failure):n.metadata(raw.replace(b'</repository>',raw[raw.index(b'<remotePackage'):raw.index(b'</repository>')]+b'</repository>'))
 with pytest.raises(r.Failure):n.metadata(raw.replace(b'<archives>',b'<dependencies/><archives>'))

def test_exact_license_before_network_and_never_written(tmp_path,monkeypatch):
 obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path,False);fetch=Mock(side_effect=AssertionError('network must not start'));monkeypatch.setattr(n,'fetch',fetch)
 with pytest.raises(r.Blocked,match='not preaccepted'):n.prepare(obj,sdk,licenses)
 assert not fetch.called and obj.ndk_state['licensePostcheck']=='PASS'
 assert license_snapshot(sdk)==licenses and not (sdk/'ndk'/n.REVISION).exists()

@pytest.mark.parametrize('kind',['alias','file'])
def test_existing_unsafe_target_never_replaced(tmp_path,monkeypatch,kind):
 use_fixture_license(monkeypatch);obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path);target=sdk/'ndk'/n.REVISION
 if kind=='alias':target.symlink_to(tmp_path)
 else:target.write_text('untouched')
 def fetch(_run,url,path,maximum,budget,record,**kw):assert url==n.INDEX_URL;path.write_bytes(index_xml())
 monkeypatch.setattr(n,'fetch',fetch)
 with pytest.raises(r.Failure):n.prepare(obj,sdk,licenses)
 assert target.is_symlink() if kind=='alias' else target.read_text()=='untouched'
 n.finish(obj);assert obj.ndk_state['ownedCleanup']=='NOT_OWNED_NOT_REMOVED'

def test_preinstalled_only_metadata_and_no_archive_download(tmp_path,monkeypatch):
 use_fixture_license(monkeypatch);obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path);target=sdk/'ndk'/n.REVISION;target.mkdir()
 identities={'source.properties':{'sha256':'1'*64}}
 monkeypatch.setattr(n,'package_identity',lambda *a:identities)
 calls=[]
 def fetch(_run,url,path,maximum,budget,record,**kw):calls.append(url);path.write_bytes(index_xml())
 monkeypatch.setattr(n,'fetch',fetch);assert n.prepare(obj,sdk,licenses)['status']=='PREINSTALLED_METADATA_VERIFIED_NOT_ARCHIVE_PROOF'
 n.finish(obj);assert calls==[n.INDEX_URL] and target.is_dir() and obj.ndk_state['configurationPostcheck']=='PASS'

def test_insufficient_space_stops_before_archive(tmp_path,monkeypatch):
 use_fixture_license(monkeypatch);obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path);calls=[]
 def fetch(_run,url,path,*a,**kw):calls.append(url);path.write_bytes(index_xml())
 monkeypatch.setattr(n,'fetch',fetch);monkeypatch.setattr(n.shutil,'disk_usage',lambda p:type('Space',(),{'free':n.FLOOR})())
 with pytest.raises(r.Blocked,match='free space'):n.prepare(obj,sdk,licenses)
 assert calls==[n.INDEX_URL] and not (sdk/'ndk'/n.REVISION).exists()

def test_central_verified_before_zipfile_allocation(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central)
 assert n.central_admission(path,Budget())==offset
 monkeypatch.setattr(n,'CENTRAL_SHA256','0'*64)
 monkeypatch.setattr(n.zipfile,'ZipFile',lambda *a:(_ for _ in ()).throw(AssertionError('parser must not allocate')))
 with pytest.raises(r.Failure,match='identity'):n.extract(run(tmp_path),path,tmp_path/'target',Budget(),{})

@pytest.mark.parametrize('kind',['comment','multidisk','count','size','offset'])
def test_central_bad_structure(tmp_path,monkeypatch,kind):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central);raw=bytearray(path.read_bytes());values=list(struct.unpack('<4s4H2IH',raw[-22:]));field={'comment':7,'multidisk':1,'count':4,'size':5,'offset':6}[kind];values[field]+=1;raw[-22:]=struct.pack('<4s4H2IH',*values);path.write_bytes(raw)
 with pytest.raises(r.Failure):n.central_admission(path,Budget())

@pytest.mark.parametrize('name,mode,data',[(n.PREFIX+'/../escape',stat.S_IFREG|0o644,b'x'),('/absolute',stat.S_IFREG|0o644,b'x'),(n.PREFIX+'/source.properties',stat.S_IFREG|0o644,b'x'),(n.PREFIX+'/bad',stat.S_IFIFO|0o644,b''),(n.PREFIX+'/bad',stat.S_IFREG|0o4755,b'x'),(n.PREFIX+'/bad',stat.S_IFLNK|0o777,b'../../escape'),(n.PREFIX+'/bad',stat.S_IFLNK|0o777,b'/etc/passwd'),(n.PREFIX+'/bad',stat.S_IFLNK|0o777,b'missing'),(n.PREFIX+'/missing/a',stat.S_IFREG|0o644,b'x'),(n.PREFIX+'/bin/tool/a',stat.S_IFREG|0o644,b'x')])
def test_archive_unsafe_rejected_before_target(tmp_path,name,mode,data):
 path,offset,count,size,_=zip_fixture(tmp_path,[(name,mode,data)])
 with zipfile.ZipFile(path) as z,pytest.raises(r.Failure):n.archive_plan(z,offset,Budget(),expected_count=count,expanded=size,links_count=2 if stat.S_ISLNK(mode) else 1)
 assert not (tmp_path/'target').exists()

def test_archive_overlap_and_aggregate_bounds(tmp_path):
 path,offset,count,size,_=zip_fixture(tmp_path)
 with zipfile.ZipFile(path) as z:
  with pytest.raises(r.Failure,match='expansion'):n.archive_plan(z,offset,Budget(),expected_count=count,expanded=1,links_count=1)
  z.infolist()[3].header_offset=z.infolist()[2].header_offset
  with pytest.raises(r.Failure):n.archive_plan(z,offset,Budget(),expected_count=count,expanded=size,links_count=1)

def test_real_small_extraction_preserves_files_links_modes_and_cleanup(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central)
 obj=run(tmp_path);target=tmp_path/'installed';record={'path':str(target),'owned':False};obj.ndk_state=record
 n.extract(obj,path,target,Budget(),record)
 assert (target/'bin/tool').read_bytes()==b'fixture executable not run'
 assert (target/'bin/tool').stat().st_mode & 0o777==0o755 and os.readlink(target/'bin/alias')=='tool'
 assert record['entries']==5 and record['symlinkCount']==1
 assert r.inventory(target,aliases=obj.aliases)[0]>0
 n.finish(obj);assert not target.exists() and record['ownedCleanup']=='REMOVED_OWN_PARTIAL_OR_INSTALLED_PACKAGE'

def test_extract_never_overwrites_preexisting_target(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central);target=tmp_path/'target';target.mkdir();(target/'keep').write_text('safe')
 with pytest.raises(FileExistsError):n.extract(run(tmp_path),path,target,Budget(),{})
 assert (target/'keep').read_text()=='safe'

def test_partial_extraction_failure_removes_only_owned_target(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central);obj=run(tmp_path);target=tmp_path/'target';record={'path':str(target),'owned':False};obj.ndk_state=record
 original=Path.open
 def fail(p,*a,**kw):
  if p.name=='tool' and 'x' in str(a):raise OSError('injected write failure')
  return original(p,*a,**kw)
 monkeypatch.setattr(Path,'open',fail)
 with pytest.raises(OSError,match='write failure'):n.extract(obj,path,target,Budget(),record)
 assert record['owned'] and target.exists()
 n.finish(obj);assert not target.exists()

def test_cleanup_replaced_root_is_not_deleted(tmp_path):
 obj=run(tmp_path);target=tmp_path/'target';target.mkdir();identity=n.directory_identity(target);target.rename(tmp_path/'original');target.mkdir();(target/'unrelated').write_text('safe')
 obj.ndk_state={'path':str(target),'owned':True,'ownedIdentity':identity}
 with pytest.raises(r.Failure,match='replaced'):n.finish(obj)
 assert (target/'unrelated').read_text()=='safe'

def test_cleanup_cancel_allowed_but_expired_deadline_not_hidden(tmp_path):
 now=[0];obj=r.Runner(tmp_path,tmp_path/'work',clock=lambda:now[0],seconds=180);target=tmp_path/'target';target.mkdir();(target/'f').write_text('x');identity=n.directory_identity(target)
 obj.signal=15;n.remove_owned(obj,target,identity);assert not target.exists()
 target.mkdir();identity=n.directory_identity(target);now[0]=181
 with pytest.raises(r.Deadline):n.remove_owned(obj,target,identity)
 assert target.exists()

def test_total_setup_budget_clipped_and_cancelled(tmp_path):
 now=[0];obj=r.Runner(tmp_path,tmp_path/'work',clock=lambda:now[0],seconds=180);budget=n.Budget(obj);assert budget.check()==60
 now[0]=61
 with pytest.raises(r.Deadline):budget.check()
 now[0]=0;obj.signal=15
 with pytest.raises(r.Cancelled):budget.check()

class Response(io.BytesIO):
 status=200
 def __init__(self,data,url,headers=None):super().__init__(data);self.url=url;self.headers={} if headers is None else headers
 def geturl(self):return self.url

def test_download_actual_bytes_hash_and_partial_overflow(tmp_path,monkeypatch):
 obj=run(tmp_path);payload=b'fixture';opener=Mock();opener.open=lambda *a,**kw:Response(payload,n.ARCHIVE_URL,{'Content-Length':'7'});monkeypatch.setattr(n.urllib.request,'build_opener',lambda *a:opener)
 rec={};n.fetch(obj,n.ARCHIVE_URL,tmp_path/'archive',7,Budget(),rec,exact=7,sha1=hashlib.sha1(payload).hexdigest());assert rec['complete'] and rec['sha256']==hashlib.sha256(payload).hexdigest()
 opener.open=lambda *a,**kw:Response(payload,n.ARCHIVE_URL)
 rec={}
 with pytest.raises(r.Failure,match='byte bound'):n.fetch(obj,n.ARCHIVE_URL,tmp_path/'partial',3,Budget(),rec)
 assert not rec['complete'] and rec['bytes']==0 and rec['sha256']==hashlib.sha256(b'').hexdigest()

@pytest.mark.parametrize('case',['redirect','declared','short','hash'])
def test_download_identity_failures(tmp_path,monkeypatch,case):
 obj=run(tmp_path);opener=Mock();opener.open=lambda *a,**kw:Response(b'abc','https://foreign.test/x' if case=='redirect' else n.ARCHIVE_URL,{'Content-Length':'4'} if case=='declared' else {})
 monkeypatch.setattr(n.urllib.request,'build_opener',lambda *a:opener);rec={}
 with pytest.raises(r.Failure):n.fetch(obj,n.ARCHIVE_URL,tmp_path/'archive',3,Budget(),rec,exact=4 if case=='short' else 3,sha1='0'*40 if case=='hash' else None)
 assert not rec['complete']

def test_download_redirect_handler_never_follows():
 with pytest.raises(r.Failure,match='redirect'):n.NoRedirect().redirect_request(None,None,None,None,None,None)

def test_setup_failure_preserves_first_error_and_license_change(tmp_path,monkeypatch):
 obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path)
 def fail(*a,**kw):
  (sdk/'licenses/android-sdk-license').write_text('0'*40+'\n');raise r.Deadline('original timeout')
 monkeypatch.setattr(n,'fetch',fail)
 with pytest.raises(r.Deadline,match='original timeout') as caught:n.prepare(obj,sdk,licenses)
 assert obj.ndk_state['licensePostcheck']=='FAIL' and obj.evidence_errors and caught.value.__notes__

def test_controller_exact_graph_and_cleanup_wiring():
 source=Path(c.__file__).read_text();assert "graph.get('ndkVersion')!=ndk.REVISION" in source
 assert source.index('ndk.finish(run)')<source.index("run.cleanup();receipt['ownedCleanup']")
 assert "'ndk-prerequisite.json'" in source and "receipt['ndkPrerequisite']=run.ndk_state" in source

def test_extra_owned_aliases_stay_exact(tmp_path,monkeypatch):
 obj=r.Runner(tmp_path,tmp_path/'work',clock=lambda:10,seconds=900);target=tmp_path/'ndk';target.mkdir();(target/'lib').write_text('fixture');(target/'alias').symlink_to('lib');obj.extra_owned_roots.append(target);obj.aliases[str(target/'alias')]=str(target/'lib')
 obj.disk_guard();obj.last_scan=0;(target/'unexpected').symlink_to('lib')
 with pytest.raises(r.Failure,match='Unexpected symlink'):obj.disk_guard()


def full_zip_fixture(tmp_path):
 extra=[(n.PREFIX+'/meta/',stat.S_IFDIR|0o755,b''),(n.PREFIX+'/meta/abis.json',stat.S_IFREG|0o644,b'{}'),(n.PREFIX+'/meta/platforms.json',stat.S_IFREG|0o644,b'{}'),(n.PREFIX+'/build/',stat.S_IFDIR|0o755,b''),(n.PREFIX+'/build/cmake/',stat.S_IFDIR|0o755,b''),(n.PREFIX+'/build/cmake/android.toolchain.cmake',stat.S_IFREG|0o644,b'# fixture')]
 return zip_fixture(tmp_path,extra)

def test_full_prepare_and_final_cleanup_is_one_archive_no_sdkmanager(tmp_path,monkeypatch):
 use_fixture_license(monkeypatch);path,offset,count,size,central=full_zip_fixture(tmp_path);payload=path.read_bytes();admit_fixture(monkeypatch,path,offset,count,size,central)
 monkeypatch.setattr(n,'ARCHIVE_BYTES',len(payload));monkeypatch.setattr(n,'ARCHIVE_SHA1',hashlib.sha1(payload).hexdigest())
 obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path);calls=[]
 opener=Mock()
 def open_request(request,**kw):
  calls.append(request.full_url);raw=index_xml() if request.full_url==n.INDEX_URL else payload
  return Response(raw,request.full_url,{'Content-Length':str(len(raw))})
 opener.open=open_request;monkeypatch.setattr(n.urllib.request,'build_opener',lambda *a:opener)
 record=n.prepare(obj,sdk,licenses)
 assert calls==[n.INDEX_URL,n.ARCHIVE_URL] and record['status']=='INSTALLED_OFFICIAL_ARCHIVE_VERIFIED'
 assert record['archiveDownload']['sha256']==hashlib.sha256(payload).hexdigest() and record['archiveRemovedAfterVerification']
 target=sdk/'ndk'/n.REVISION;assert target.is_dir() and not (target/'package.xml').exists()
 n.finish(obj);assert record['configurationPostcheck']=='PASS' and not target.exists() and not obj.aliases
 assert license_snapshot(sdk)==licenses and not obj.commands

def test_configuration_parent_alias_refused(tmp_path):
 target=tmp_path/'ndk';target.mkdir();(target/'source.properties').write_text('Pkg.Revision = '+n.REVISION+'\n');outside=tmp_path/'outside';outside.mkdir();(outside/'abis.json').write_text('{}');(target/'meta').symlink_to(outside)
 with pytest.raises(r.Failure,match='path is an alias'):n.package_identity(target,Budget())

def test_partial_install_first_failure_survives_receipt_error(tmp_path,monkeypatch):
 obj=run(tmp_path);sdk,licenses=sdk_fixture(tmp_path)
 monkeypatch.setattr(n,'fetch',lambda *a:(_ for _ in ()).throw(r.Deadline('original setup deadline')))
 monkeypatch.setattr(n,'atomic_json',lambda *a:(_ for _ in ()).throw(OSError('receipt disk error')))
 with pytest.raises(r.Deadline,match='original setup deadline') as caught:n.prepare(obj,sdk,licenses)
 assert obj.evidence_errors and any('receipt disk error' in x for x in caught.value.__notes__)


def test_interrupted_link_chain_cleanup_unlinks_owned_dangling_alias(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path,[(n.PREFIX+'/bin/second',stat.S_IFLNK|0o777,b'tool')])
 # Turn the first alias into a link to the second; both are fully contained in the admitted archive.
 replacement=tmp_path/'chain.zip'
 with zipfile.ZipFile(path) as old,zipfile.ZipFile(replacement,'w') as new:
  for entry in old.infolist():new.writestr(entry,b'second' if entry.filename.endswith('/alias') else old.read(entry))
 path=replacement;raw=path.read_bytes();e=struct.unpack('<4s4H2IH',raw[-22:]);offset=e[-2];central=raw[offset:offset+e[-3]];size+=len(b'second')-len(b'tool')
 original=n.archive_plan;admit_fixture(monkeypatch,path,offset,count,size,central)
 # Explicit two-link fixture policy, leaving the production exact35-link pin unchanged.
 monkeypatch.setattr(n,'archive_plan',lambda z,off,b:original(z,off,b,expected_count=count,expanded=size,links_count=2))
 obj=run(tmp_path);target=tmp_path/'target';record={'path':str(target),'owned':False};obj.ndk_state=record
 symlink=Path.symlink_to
 def fail_second(p,value,*a,**kw):
  if p.name=='second':raise r.Cancelled('interrupted while creating official links')
  return symlink(p,value,*a,**kw)
 monkeypatch.setattr(Path,'symlink_to',fail_second)
 with pytest.raises(r.Cancelled):n.extract(obj,path,target,Budget(),record)
 assert (target/'bin/alias').is_symlink() and not (target/'bin/alias').exists()
 obj.signal=15;n.finish(obj)
 assert not target.exists() and record['ownedCleanup']=='REMOVED_OWN_PARTIAL_OR_INSTALLED_PACKAGE'

def test_owned_cleanup_never_follows_dangling_or_external_link(tmp_path):
 obj=run(tmp_path);target=tmp_path/'target';target.mkdir();outside=tmp_path/'unrelated';outside.mkdir();(outside/'keep').write_text('safe')
 (target/'dangling').symlink_to('not-created');(target/'external').symlink_to(outside)
 n.remove_owned(obj,target,n.directory_identity(target))
 assert not target.exists() and (outside/'keep').read_text()=='safe'


def test_network_trickle_hits_budget_between_single_reads(tmp_path,monkeypatch):
 now=[0];obj=r.Runner(tmp_path,tmp_path/'work',clock=lambda:now[0],seconds=900);obj.disk_guard=lambda:None
 class Trickle(Response):
  def read(self,*a):raise AssertionError('fill-buffer network read is forbidden')
  def read1(self,maximum):
   now[0]+=601;return b'x'
 opener=Mock();opener.open=lambda *a,**kw:Trickle(b'',n.ARCHIVE_URL)
 monkeypatch.setattr(n.urllib.request,'build_opener',lambda *a:opener);record={}
 with pytest.raises(r.Deadline,match='600-second'):n.fetch(obj,n.ARCHIVE_URL,tmp_path/'partial',100,n.Budget(obj),record)
 assert not record['complete'] and record['bytes']==1 and record['sha256']==hashlib.sha256(b'x').hexdigest()

def test_official_directory_modes_preserved_under_restrictive_umask(tmp_path,monkeypatch):
 path,offset,count,size,central=zip_fixture(tmp_path);admit_fixture(monkeypatch,path,offset,count,size,central);obj=run(tmp_path);target=tmp_path/'target';record={'path':str(target),'owned':False};obj.ndk_state=record
 old=os.umask(0o077)
 try:n.extract(obj,path,target,Budget(),record)
 finally:os.umask(old)
 assert target.stat().st_mode & 0o777==0o755 and (target/'bin').stat().st_mode & 0o777==0o755
 n.finish(obj)
