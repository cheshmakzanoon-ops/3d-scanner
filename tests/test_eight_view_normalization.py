"""Synthetic ZIP/controller tamper controls; not Android or image reconstruction proof."""
import copy,hashlib,json,os,sys,types,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import controller as ctl
from tools.eight_view_host.runtime import Failure

def digest(raw):return hashlib.sha256(raw).hexdigest()
def write_zip(path,entries):
 with zipfile.ZipFile(path,'w',zipfile.ZIP_DEFLATED) as archive:
  for name,raw in entries:archive.writestr(name,raw)
 return path

def archive_record(path):
 entries=[]
 with zipfile.ZipFile(path) as archive:
  for info in archive.infolist():
   raw=archive.read(info);entries.append(dict(name=info.filename,directory=info.is_dir(),bytes=len(raw),sha256=digest(raw)))
 classes=sorted((e for e in entries if not e['directory'] and e['name'].endswith('.class')),key=lambda e:e['name'])
 resources=[e for e in entries if e not in classes]
 return dict(path=str(path),bytes=path.stat().st_size,sha256=digest(path.read_bytes()),entries=entries,
  classMapSha256=digest(''.join(e['sha256']+'  '+e['name']+'\n' for e in classes).encode()),
  resourceMapSha256=digest(''.join(('d' if e['directory'] else 'f')+' '+e['sha256']+' '+str(e['bytes'])+' '+e['name']+'\n' for e in resources).encode()))

@pytest.fixture
def ledger(tmp_path,monkeypatch):
 # OpenCV-only units isolate AGP projection; dedicated AGP binding tests use the real validator.
 monkeypatch.setattr(ctl,'agp_resources_check',lambda *args,**kwargs:None)
 classes=[('org/opencv/core/Core.class',b'core'),('org/opencv/core/Mat.class',b'mat')]
 resources=[('META-INF/',b''),('META-INF/MANIFEST.MF',b'Manifest-Version: 1.0\r\n\r\n'),('res/value.txt',b'source-resource')]
 source=write_zip(tmp_path/'source.jar',classes+resources);aar=write_zip(tmp_path/'source.aar',[('classes.jar',source.read_bytes())])
 runtime=write_zip(tmp_path/'runtime.jar',classes);original=tmp_path/'original.jar';original.write_bytes(source.read_bytes())
 projection=write_zip(tmp_path/'projection.jar',resources);unrelated=write_zip(tmp_path/'unrelated.jar',[('res/value.txt',b'interleaved-resource')])
 policy=tmp_path/'policy.json';policy.write_text(json.dumps({'opencvAarSha256':digest(aar.read_bytes())}));monkeypatch.setattr(ctl.capability,'POLICY',policy)
 providers=[{**archive_record(runtime),'index':0,'role':'runtime-classes'},{**archive_record(original),'index':2,'role':'aar-resources'}]
 cp=[{'path':str(p),'kind':'jar','sha256':digest(p.read_bytes())} for p in (runtime,unrelated,projection)]
 (tmp_path/'app/build').mkdir(parents=True)
 value=dict(schema=4,agpResources={"schema":1},appBuildDirectory=str(tmp_path/'app/build'),task=':app:eightPrepare',sourceSha='a'*40,opencvAar=str(aar),opencvClasses=str(source),classpath=cp,classpathString=os.pathsep.join(e['path'] for e in cp),owners={},
  opencvNormalization=dict(schema=1,mode='agp-two-role-resource-projection',sourceAar=dict(path=str(aar),bytes=aar.stat().st_size,sha256=digest(aar.read_bytes())),sourceClasses=archive_record(source),providers=providers,projection={**archive_record(projection),'index':2,'sourcePath':str(original)}))
 return value

def test_two_role_ledger_verifies_all_current_inputs(ledger):ctl.classpath_check(ledger)

@pytest.mark.parametrize('resource_mode',['source','class-only'])
def test_single_provider_preserves_valid_domain(ledger,resource_mode):
 n=ledger['opencvNormalization'];provider=n['providers'][1 if resource_mode=='source' else 0]
 provider.update(index=0,role='single-source-resources-identical' if resource_mode=='source' else 'single-class-only-no-resource-completeness');n.update(mode='single-class-equivalent-provider',providers=[provider],projection=None)
 ledger['classpath']=[{'path':provider['path'],'kind':'jar','sha256':provider['sha256']}];ledger['classpathString']=provider['path']
 ctl.classpath_check(ledger)

@pytest.mark.parametrize('field',['sourceAar','sourceClasses','projection','provider0','provider1'])
def test_mutated_original_and_effective_files_rejected(ledger,field):
 n=ledger['opencvNormalization'];item=n['providers'][int(field[-1])] if field.startswith('provider') else n[field]
 with open(item['path'],'ab') as out:out.write(b'drift')
 with pytest.raises(Failure):ctl.classpath_check(ledger)

@pytest.mark.parametrize('mutation',[
 lambda r:r.update(schema=1),lambda r:r['opencvNormalization'].update(schema=99),
 lambda r:r['opencvNormalization'].update(mode='deduplicate-anything'),
 lambda r:r.update(replacedOpenCvProvider='/old.jar'),
 lambda r:r['opencvNormalization'].update(extra='unreviewed'),
 lambda r:r['opencvNormalization']['sourceAar'].update(sha256='0'*64),
 lambda r:r['opencvNormalization']['sourceClasses'].update(classMapSha256='0'*64),
 lambda r:r['opencvNormalization']['providers'][0].update(role='aar-resources'),
 lambda r:r['opencvNormalization']['providers'][0].update(index=1),
 lambda r:r['opencvNormalization']['providers'].append(copy.deepcopy(r['opencvNormalization']['providers'][0])),
 lambda r:r['opencvNormalization']['projection'].update(sourcePath='/other'),
 lambda r:r['opencvNormalization']['projection'].update(index=0),
 lambda r:r['opencvNormalization']['projection']['entries'].reverse(),
 lambda r:r['opencvNormalization']['projection'].update(resourceMapSha256='0'*64),
 lambda r:r.update(opencvClasses=r['opencvAar']),
])
def test_ledger_field_tampering_rejected(ledger,mutation):
 mutation(ledger)
 with pytest.raises(Failure):ctl.classpath_check(ledger)

@pytest.mark.parametrize('name',['../escape','a//b','/absolute','a\\b','a/./b','a\nb','a\x00b','caf\xe9','a:b','x'*4097,'META-INF/X.SF','META-INF/versions/9/X.class'])
def test_unsafe_or_unsupported_zip_entries_rejected(tmp_path,name):
 path=write_zip(tmp_path/'bad.jar',[(name,b'payload')])
 # zipfile truncates NUL on writing, so directly assert the name policy on an
 # archive with NUL in its central-directory filename instead.
 if '\x00' in name:
  path=write_zip(path,[('a_b',b'payload')]);path.write_bytes(path.read_bytes().replace(b'a_b',b'a\x00b'))
 with pytest.raises(Failure):ctl.normalization_archive(archive_record(path))

@pytest.mark.parametrize('manifest',[b'Manifest-Version: 1.0\nClass-Path: other.jar\n\n',b'Sealed: true\n',b'Multi-Release: true\n',b'Unknown: value\n',b'Manifest-Version: 1.0\n\nName: org/\nSealed: true\n'])
def test_unsupported_manifests_rejected(tmp_path,manifest):
 path=write_zip(tmp_path/'manifest.jar',[('META-INF/MANIFEST.MF',manifest)])
 with pytest.raises(Failure):ctl.normalization_archive(archive_record(path))

def test_duplicate_entries_rejected(tmp_path):
 with pytest.warns(UserWarning):path=write_zip(tmp_path/'duplicate.jar',[('x',b'one'),('x',b'two')])
 with pytest.raises(Failure):ctl.normalization_archive(archive_record(path))

def test_symlink_provider_and_parent_alias_rejected(ledger,tmp_path):
 p=ledger['opencvNormalization']['providers'][0];alias=tmp_path/'alias.jar';alias.symlink_to(p['path']);p['path']=str(alias)
 with pytest.raises(Failure):ctl.classpath_check(ledger)

def test_original_resource_corruption_cannot_be_hidden_by_updating_ledger(ledger):
 n=ledger['opencvNormalization'];p=n['providers'][1];path=Path(p['path'])
 write_zip(path,[('org/opencv/core/Core.class',b'core'),('org/opencv/core/Mat.class',b'mat'),('res/value.txt',b'wrong')])
 n['providers'][1]={**archive_record(path),'index':2,'role':'aar-resources'}
 with pytest.raises(Failure):ctl.classpath_check(ledger)

def test_projection_corruption_cannot_be_hidden_by_updating_ledger(ledger):
 n=ledger['opencvNormalization'];p=n['projection'];path=Path(p['path']);write_zip(path,[('res/value.txt',b'wrong')])
 n['projection']={**archive_record(path),'index':2,'sourcePath':p['sourcePath']};ledger['classpath'][2]['sha256']=digest(path.read_bytes())
 with pytest.raises(Failure):ctl.classpath_check(ledger)

@pytest.mark.parametrize('filename',['third.jar','third.zip','extensionless'])
def test_undeclared_third_provider_rejected(ledger,tmp_path,filename):
 path=write_zip(tmp_path/filename,[('org/opencv/core/Core.class',b'core')]);ledger['classpath'].append(dict(path=str(path),kind='jar',sha256=digest(path.read_bytes())))
 ledger['classpathString']=os.pathsep.join(e['path'] for e in ledger['classpath'])
 with pytest.raises(Failure,match='Undeclared|suffix'):ctl.classpath_check(ledger)

def test_prepared_record_bound_before_and_after_fork(ledger,tmp_path):
 path=tmp_path/'prepared.json';path.write_text(json.dumps(ledger));expected=digest(path.read_bytes());run=types.SimpleNamespace(work=tmp_path,remaining=lambda *a,**k:10)
 ctl.prepared_record_check(run,ledger,expected)
 path.write_text(json.dumps(ledger)+' ')
 with pytest.raises(Failure,match='prepared'):ctl.prepared_record_check(run,ledger,expected)

@pytest.mark.parametrize('kind',['encryption','compression','symlink','multidisk','zip64','unknown-flags'])
def test_unsupported_central_metadata_rejected(tmp_path,kind):
 import struct
 path=write_zip(tmp_path/'metadata.jar',[('x',b'payload')]);record=archive_record(path);raw=bytearray(path.read_bytes());central=raw.index(b'PK\x01\x02');end=raw.index(b'PK\x05\x06')
 if kind=='encryption':struct.pack_into('<H',raw,central+8,1)
 if kind=='compression':struct.pack_into('<H',raw,central+10,99)
 if kind=='symlink':struct.pack_into('<I',raw,central+38,0o120777<<16)
 if kind=='multidisk':struct.pack_into('<H',raw,end+4,1)
 if kind=='zip64':struct.pack_into('<I',raw,central+20,0xffffffff)
 if kind=='unknown-flags':struct.pack_into('<H',raw,central+8,0x4000)
 path.write_bytes(raw)
 with pytest.raises(Failure,match='ZIP'):ctl.normalization_archive(record)

def test_provider_archive_drift_during_read_rejected(ledger,monkeypatch):
 record=ledger['opencvNormalization']['providers'][0];original=ctl.bounded_hash;calls=0
 def drift(path,*args,**kwargs):
  nonlocal calls
  result=original(path,*args,**kwargs);calls+=1
  if calls==1:
   with open(path,'ab') as stream:stream.write(b'race')
  return result
 monkeypatch.setattr(ctl,'bounded_hash',drift)
 with pytest.raises(Failure):ctl.normalization_archive(record,extra=('index','role'))

def test_pinned_aar_cannot_be_replaced_even_with_fresh_ledger(ledger):
 n=ledger['opencvNormalization'];aar=Path(n['sourceAar']['path'])
 write_zip(aar,[('classes.jar',Path(n['sourceClasses']['path']).read_bytes()),('extra',b'changed')])
 n['sourceAar'].update(bytes=aar.stat().st_size,sha256=digest(aar.read_bytes()))
 with pytest.raises(Failure,match='AAR identity'):ctl.classpath_check(ledger)

def test_effective_classpath_cannot_move_projection_ahead_of_other_resources(ledger):
 ledger['classpath'][1],ledger['classpath'][2]=ledger['classpath'][2],ledger['classpath'][1]
 ledger['classpathString']=os.pathsep.join(e['path'] for e in ledger['classpath'])
 with pytest.raises(Failure,match='effective'):ctl.classpath_check(ledger)

def test_parent_symlink_is_not_canonical(tmp_path):
 real=tmp_path/'real';real.mkdir();path=write_zip(real/'x.jar',[('x',b'x')]);record=archive_record(path)
 alias=tmp_path/'alias';alias.symlink_to(real,target_is_directory=True);record['path']=str(alias/'x.jar')
 with pytest.raises(Failure,match='alias'):ctl.normalization_archive(record)

@pytest.mark.parametrize('field',['schema','task','sourceSha'])
def test_capture_schema_source_task_not_unbound(ledger,field):
 ledger[field]=True if field=='schema' else 'unbound'
 with pytest.raises(Failure):ctl.classpath_check(ledger)

def test_source_classes_must_really_be_extracted_aar_bytes(ledger):
 n=ledger['opencvNormalization'];source=Path(n['sourceClasses']['path'])
 with zipfile.ZipFile(source) as archive:entries=[(i.filename,archive.read(i)) for i in archive.infolist()]
 with zipfile.ZipFile(source,'w',zipfile.ZIP_STORED) as archive:
  for name,raw in entries:archive.writestr(name,raw)
 n['sourceClasses']=archive_record(source)
 with pytest.raises(Failure,match='extracted'):ctl.classpath_check(ledger)

def test_archive_entry_limits_before_expansion(tmp_path):
 import struct
 path=write_zip(tmp_path/'oversize.jar',[('x',b'x')]);record=archive_record(path);raw=bytearray(path.read_bytes());central=raw.index(b'PK\x01\x02')
 struct.pack_into('<I',raw,central+24,16*1024**2+1);path.write_bytes(raw)
 with pytest.raises(Failure,match='entry size'):ctl.normalization_archive(record)

def test_prepared_record_drift_cannot_be_hidden_by_fresh_hash(ledger,tmp_path):
 path=tmp_path/'prepared.json';changed=copy.deepcopy(ledger);changed['sourceSha']='b'*40;path.write_text(json.dumps(changed))
 run=types.SimpleNamespace(work=tmp_path,remaining=lambda *a,**k:10)
 with pytest.raises(Failure,match='prepared'):ctl.prepared_record_check(run,ledger,digest(path.read_bytes()))

def test_unrelated_jar_owner_scan_streams_zip64_without_zipinfo_list(tmp_path,monkeypatch):
 path=tmp_path/'unrelated-zip64.jar';owner='org/opencv/core/Core.class'
 with zipfile.ZipFile(path,'w',zipfile.ZIP_STORED) as archive:
  for i in range(65535):archive.writestr(str(i),b'')
  archive.writestr(owner,b'fixture')
 monkeypatch.setattr(ctl.zipfile,'ZipFile',lambda *a,**k:pytest.fail('Unrelated metadata must stream before any ZipInfo allocation'))
 assert ctl.effective_owner_count(path,owner)==1

def test_unrelated_jar_metadata_limit_fails_before_entries(tmp_path):
 import struct
 path=write_zip(tmp_path/'bounded.jar',[('x',b'x')]);raw=bytearray(path.read_bytes());end=raw.index(b'PK\x05\x06');central=raw.index(b'PK\x01\x02')
 # ZIP64 end with deliberately excessive count, without allocating any entries.
 tail=struct.pack('<4sQHHIIQQQQ',b'PK\x06\x06',44,45,45,0,0,100001,100001,end-central,central)
 tail+=struct.pack('<4sIQI',b'PK\x06\x07',0,end,1)
 eocd=bytearray(raw[end:]);struct.pack_into('<HH',eocd,8,65535,65535)
 path.write_bytes(raw[:end]+tail+eocd)
 with pytest.raises(Failure,match='metadata count'):ctl.effective_owner_count(path,'org/opencv/core/Core.class')

@pytest.mark.parametrize('directory',[False,True])
def test_partial_noncore_provider_cannot_hide_from_ledger(ledger,tmp_path,directory):
 name='org/opencv/core/Mat.class'
 if directory:
  path=tmp_path/'partial';(path/name).parent.mkdir(parents=True);(path/name).write_bytes(b'mat')
  entry=dict(path=str(path),kind='directory',files=[dict(path=str(path/name),sha256=digest(b'mat'))])
 else:
  path=write_zip(tmp_path/'partial.jar',[(name,b'mat')]);entry=dict(path=str(path),kind='jar',sha256=digest(path.read_bytes()))
 ledger['classpath'].append(entry);ledger['classpathString']=os.pathsep.join(e['path'] for e in ledger['classpath'])
 with pytest.raises(Failure,match='overlap'):ctl.classpath_check(ledger)

@pytest.mark.parametrize('actual,claimed',[('source','single-class-only-no-resource-completeness'),('class-only','single-source-resources-identical')])
def test_single_resource_disposition_cannot_be_misrepresented(ledger,actual,claimed):
 n=ledger['opencvNormalization'];p=n['providers'][1 if actual=='source' else 0];p.update(index=0,role=claimed)
 n.update(mode='single-class-equivalent-provider',providers=[p],projection=None)
 ledger['classpath']=[dict(path=p['path'],kind='jar',sha256=p['sha256'])];ledger['classpathString']=p['path']
 with pytest.raises(Failure,match='disposition'):ctl.classpath_check(ledger)

def test_two_role_source_identity_cannot_be_ambiguous(ledger):
 n=ledger['opencvNormalization'];source=Path(n['sourceClasses']['path']);write_zip(source,[('org/opencv/core/Core.class',b'core'),('org/opencv/core/Mat.class',b'mat')]);n['sourceClasses']=archive_record(source)
 aar=Path(n['sourceAar']['path']);write_zip(aar,[('classes.jar',source.read_bytes())]);n['sourceAar'].update(bytes=aar.stat().st_size,sha256=digest(aar.read_bytes()))
 ctl.capability.POLICY.write_text(json.dumps({'opencvAarSha256':digest(aar.read_bytes())}))
 for i,p in enumerate(n['providers']):
  path=Path(p['path']);path.write_bytes(source.read_bytes());n['providers'][i]={**archive_record(path),'index':p['index'],'role':p['role']}
 projection=n['projection'];path=Path(projection['path']);write_zip(path,[]);n['projection']={**archive_record(path),'index':2,'sourcePath':projection['sourcePath']}
 for entry in ledger['classpath']:entry['sha256']=digest(Path(entry['path']).read_bytes())
 with pytest.raises(Failure,match='role content'):ctl.classpath_check(ledger)

@pytest.mark.parametrize('name,mode',[('file',0o040755),('dir/',0o100644)])
def test_unix_type_and_directory_marker_must_agree(tmp_path,name,mode):
 import struct
 path=write_zip(tmp_path/'type.jar',[(name,b'')]);record=archive_record(path);raw=bytearray(path.read_bytes());central=raw.index(b'PK\x01\x02')
 struct.pack_into('<I',raw,central+38,mode<<16);path.write_bytes(raw)
 with pytest.raises(Failure,match='type/name'):ctl.normalization_archive(record)

def test_effective_classpath_parent_alias_rejected(ledger,tmp_path):
 alias=tmp_path/'parent-alias';alias.symlink_to(tmp_path,target_is_directory=True)
 entry=ledger['classpath'][1];entry['path']=str(alias/Path(entry['path']).name);ledger['classpathString']=os.pathsep.join(e['path'] for e in ledger['classpath'])
 with pytest.raises(Failure,match='alias'):ctl.classpath_check(ledger)
