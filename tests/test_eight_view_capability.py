"""Synthetic proof-schema fixtures; real ELF/JNI compilation tests live in host guard suite."""
import copy,hashlib,json,os,shutil,sys,types,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import tools
SOURCE=Path(os.environ.get('RF_SOURCE_ROOT',ROOT));sys.path.append(str(SOURCE));tools.__path__.append(str(SOURCE/'tools'))
from tools.eight_view_host import runtime as r,policy as p,capability as c,header_provider as hp,native_provenance as np
SHA='b'*40

def fixture(tmp_path,monkeypatch):
 run=r.Runner(tmp_path/'repo',tmp_path/'work');run.root.mkdir();run.disk_guard=lambda:None
 java=tmp_path/'synthetic-jdk';run.env['JAVA_HOME']=str(java)
 out=run.work/'capability';out.mkdir();original=json.loads(c.POLICY.read_text());original['nativeClasses']=['fixture.Owner'];original['opencvCommit']='a'*40
 def put(path,data=b'fixture'):
  path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
 # Identity/schema-only fixture: these tool/JDK files are never executed.
 for name in ('bin/java','bin/javap','bin/javac','release','include/jni.h'):put(java/name)
 tools={name:put(tmp_path/'synthetic-tools'/name) for name in ('g++','gcc','cmake','ninja','nm','readelf','ldd')}
 original_which=shutil.which
 monkeypatch.setattr(shutil,'which',lambda name,**kw:str(tools[name]) if name in tools else original_which(name,**kw))
 providers=tuple({**role,'driver':put(tmp_path/f"synthetic-clang{role['major']}/bin/clang"),
  'root':tmp_path/f"synthetic-clang{role['major']}/lib/clang/{role['major']}/include"} for role in hp.PROVIDERS)
 headers=[put(role['root']/'fixture.h') for role in providers]
 monkeypatch.setattr(hp,'PROVIDERS',providers)
 header_proof={'schema':2,'sourceSha':SHA,'runOwner':run.owner,'workDir':str(run.work),'status':'SYNTHETIC_SCHEMA_FIXTURE_NOT_INSTALLED_PROVIDER',
  'providers':[{'major':role['major'],'driver':c.path_record(role['driver'])} for role in providers],'filesystem':[]}
 expected_header_proof=copy.deepcopy(header_proof)
 def validate_header(proof,source,context):
  if proof!=expected_header_proof or source!=SHA or context is not run:raise r.Failure('Synthetic provider proof/source/authority mismatch')
  return [role['root'] for role in providers]
 monkeypatch.setattr(hp,'validate',validate_header)
 policy=put(tmp_path/'policy.json');archive=put(out/'opencv.tar.gz');aar=out/'aar.aar';classes=put(out/'classes.jar',b'original-classes')
 with zipfile.ZipFile(aar,'w') as z:z.writestr('classes.jar',classes.read_bytes())
 original['opencvArchiveSha256']=r.sha(archive);original['opencvAarSha256']=r.sha(aar)
 provider=put(out/'native-dist.jar');original['robolectricNativeRuntimeSha256']=r.sha(provider)
 framework=put(run.root/'app/build/robolectric-sdk/android-all-15-robolectric-12650502.jar',b'fixture-framework')
 original['androidAllSha256']=r.sha(framework);original['robolectricNativeProviderEntryBytes']=20
 policy.write_text(json.dumps(original))
 source=out/'source'/('opencv-'+original['opencvCommit']);license=put(source/'LICENSE')
 jni=put(run.root/'reconstruction/src/jni.cpp');owner=put(run.work/'owner.class')
 generated=put(out/'build/bin/opencv-4120.jar')
 declarations=[('beginReconstruction','()J'),('bundleAdjustProblemCancellable','()Ljava/lang/String;'),('reconstructCalibratedCancellable','()Ljava/lang/String;')]
 contract=put(out/'scanner-jni-contract.h',c.scanner_contract(declarations).encode())
 provider_record={'path':str(framework),'sha256':r.sha(framework),'entry':'native/linux/x86_64/libandroid_runtime.so','entrySha256':'f'*64,'entryBytes':20,'mappingScope':'UNIT_FIXTURE_NOT_RUNTIME'}
 prepared={'opencvAar':str(aar),'opencvClasses':str(classes),'owners':{'com.realityforge.scanner.engine.NativeCore':{'file':str(owner)}},'robolectricNativeDistribution':{'path':str(provider),'sha256':r.sha(provider)},'offlineFramework':{'path':str(framework),'sha256':r.sha(framework)},'runtimeProvider':provider_record}
 consumed={str((java/'include/jni.h').resolve()):r.sha(java/'include/jni.h'),'/usr/include/stdio.h':r.sha('/usr/include/stdio.h'),**{str(header):r.sha(header) for header in headers}}
 native_proof={'schema':2,'sourceSha':SHA,'runOwner':run.owner,'workDir':str(run.work),'systemLink':{'inputs':{}},'classification':'SYNTHETIC_POLICY_FIXTURE_NOT_NATIVE_PROOF'}
 expected_native_proof=copy.deepcopy(native_proof)
 def validate_native(context,proof,used,before,source):
  if context is not run or proof!=expected_native_proof or source!=SHA:raise r.Failure('Synthetic native proof/source/authority mismatch')
  return {name:{'sha256':digest,'scope':'PREBUILD_AND_POSTBUILD_IDENTICAL'} for name,digest in used.items()}
 monkeypatch.setattr(np,'validate',validate_native)
 monkeypatch.setattr(np,'input_paths',lambda proof:{np.FILENAME:out/np.FILENAME,'native-tool-fixture':tools['ninja']})
 monkeypatch.setattr(np,'labels',lambda proof:['native-fixture-probe'])
 proof_files={
  np.FILENAME:native_proof,
  'header-provider.json':header_proof,
  'source-files.json':{'LICENSE':r.sha(license)},'scanner-source.json':{'src/jni.cpp':r.sha(jni)},'consumed-inputs.json':consumed,'prebuild-inputs.json':consumed,'consumed-provenance.json':{name:{'sha256':digest,'scope':'PREBUILD_AND_POSTBUILD_IDENTICAL'} for name,digest in consumed.items()},
  'opencv-abi.json':{'fixture.Owner':{'declarations':[['hello','()J']],'matching':True}},
  'scanner-abi.json':{'declarations':[list(x) for x in declarations],'symbols':sorted(c.jni_symbol('com.realityforge.scanner.engine.NativeCore',name) for name,_ in declarations)}}
 for name,value in proof_files.items():put(out/name,(json.dumps(value)+'\n').encode())
 inputs={'native-tool-fixture':tools['ninja'],**{name:Path(shutil.which(tool,path=run.env['PATH']) or '/missing').resolve() for name,tool in [('compiler','g++'),('cc','gcc'),('cmake','cmake'),('ninja','ninja'),('nm','nm'),('readelf','readelf'),('ldd','ldd')]},'java':java/'bin/java','javap':java/'bin/javap','javac':java/'bin/javac','javaRelease':java/'release',**{'headerProviderDriver-'+str(role['major']):role['driver'] for role in providers},'policy':policy,'smokeSource':c.HERE/'HostOpenCvSmoke.java','archive':archive,'license':license,'aar':aar,'classes':classes,'nativeOwner':owner,'generatedJar':generated,'jniSource':jni,'jniContract':contract,'robolectricNativeDistribution':provider,'androidAll':framework,**{name:out/name for name in proof_files},**{'consumed-'+str(i):Path(path) for i,path in enumerate(sorted(consumed))}}
 native={key:put(out/rel) for key,rel in [('opencv','build/lib/libopencv_java4120.so'),('shim','librealityforge_jni.so'),('engine','scanner/librealityforge.so')]}
 logs={'native-fixture-probe':b'SYNTHETIC_NATIVE_POLICY_PROBE',**{f"header-provider-{role['major']}-version":f"Ubuntu clang version {role['major']}.0.0\n".encode() for role in providers},**{f"header-provider-{role['major']}-resource-dir":(str(role['root'].parent)+'\n').encode() for role in providers},'compiler-version':b'14.2.0\n','opencv-configure':b'To be built: core features2d imgproc java\nJava wrappers: YES (JAVA)\n','opencv-build':b'done','opencv-symbols':b'000 T Java_fixture_Owner_hello\n','opencv-consumed-deps':b'deps','opencv-smoke-compile':b'', 'opencv-smoke':b'OPENCV_REAL_SMOKE_PASS version=4.12.0 descriptors=12 gray=76,150,29,255\n','scanner-configure':b'done','scanner-build':b'done','scanner-javap':''.join(' public native long '+name+'();\n descriptor: '+desc+'\n' for name,desc in declarations).encode(),'scanner-shim':b'','scanner-consumed-deps':b'deps','scanner-symbols':''.join('000 T '+symbol+'\n' for symbol in proof_files['scanner-abi.json']['symbols']).encode(),'opencv-aar-0':b' public native long hello();\n descriptor: ()J\n','opencv-generated-0':b' public native long hello();\n descriptor: ()J\n'}
 for prefix in ('opencv','scanner-engine','scanner-shim'):
  for part in ('elf-header','elf-dynamic','ldd'):logs[prefix+'-'+part]=b'UNIT_FIXTURE_ELF_PROOF'
 producer=[]
 for label,raw in logs.items():
  file=put(run.work/'logs'/(label+'.raw.log'),raw);record={'label':label,'complete':True,'exit':0,'bytes':len(raw),'sha256':r.sha(file),'argv':['unit-fixture'],'runOwner':run.owner,'starttime':123,'environmentBoundary':'INHERITED_RUNTIME'}
  if label in hp.LABELS:
   role=next(role for role in providers if label.startswith(f"header-provider-{role['major']}-"))
   record.update(argv=[str(role['driver']),'--version' if label.endswith('-version') else '-print-resource-dir'],environmentBoundary='EXPLICIT_COMPILATION_TOOL')
  if label=='opencv-configure':record['argv']=['cmake','-DCMAKE_SKIP_RPATH=ON']
  if label=='scanner-shim':record['argv']=['compiler','-include',str(contract),str(jni)]
  run.commands.append(record);producer.append({**record,'log':c.path_record(file)})
 value={'schema':1,'classification':'HOST_SOURCE_BUILT_CAPABILITY','sourceSha':SHA,'policySha256':r.sha(policy),'headerProvider':header_proof,'nativeInputProvenance':c.path_record(out/np.FILENAME),'checks':{key:True for key in original['requiredChecks']},'native':{key:c.path_record(path) for key,path in native.items()},'inputs':{key:c.path_record(path) for key,path in inputs.items()},'producer':{'runOwner':run.owner,'workDir':str(run.work),'sourceSha':SHA,'commands':producer},'runtimeProvider':provider_record,'opencvElf':{'unitFixture':'opencv'},'scannerElf':{'engine':{'unitFixture':'engine'},'shim':{'unitFixture':'shim'}}}
 # ELF parsing and provider admission are explicitly stubbed schema units, not installed-provider proof. Separate suites test real process/header-provider contracts.
 monkeypatch.setattr(c,'parse_elf_closure',lambda path,*args:{'unitFixture':next(key for key,v in native.items() if path==v)})
 manifest=out/'capability.json';manifest.write_text(json.dumps(value))
 return run,prepared,policy,manifest,value

def test_complete_synthetic_producer_schema(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 assert p.verify_capability(manifest,SHA,policy,run,prepared)==value

@pytest.mark.parametrize('mutation',[
 lambda value:value['inputs'].pop('jniContract'),lambda value:value['inputs'].clear(),
 lambda value:value['native']['shim'].update(path=value['native']['opencv']['path'],sha256=value['native']['opencv']['sha256']),
 lambda value:value['producer']['commands'].pop(),lambda value:value['producer']['commands'][0].update(exit=7),
 lambda value:value['producer']['commands'][0].update(complete=False),lambda value:value['producer']['commands'][0].update(sha256='0'*64),
 lambda value:value['checks'].update(scannerAbi=False),lambda value:value.update(sourceSha='c'*40),
 lambda value:value.update(runtimeProvider={}),lambda value:value['inputs']['jniSource'].update(path='/etc/passwd'),
])
def test_forged_or_partial_capability_rejected(tmp_path,monkeypatch,mutation):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);mutation(value);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_changed_consumed_header_rejected_before_native_admission(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 file=run.work/'capability/consumed-inputs.json';data=json.loads(file.read_text());data['/usr/include/stdio.h']='0'*64;file.write_text(json.dumps(data));value['inputs']['consumed-inputs.json']=c.path_record(file);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='Consumed compilation input changed'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_authoritative_build_record_not_manifest_only(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 run.commands[0]['exit']=99
 with pytest.raises(r.Failure,match='authoritative'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_header_prebuild_change_or_unknown_generation_is_rejected(tmp_path):
 header=tmp_path/'include/header.h';header.parent.mkdir();header.write_text('before');before={str(header):r.sha(header)};header.write_text('after')
 with pytest.raises(r.Failure,match='changed between'):c.bind_consumed({str(header):r.sha(header)},before,[tmp_path/'build'])
 generated=tmp_path/'build/modules/java_bindings_generator/gen/cpp/core.inl.hpp';generated.parent.mkdir(parents=True);generated.write_text('generated')
 with pytest.raises(r.Failure,match='requires verified native producer provenance'):c.bind_consumed({str(generated):r.sha(generated)},before,[tmp_path/'build'])
 foreign=tmp_path/'build/arbitrary.h';foreign.write_text('unknown')
 with pytest.raises(r.Failure,match='requires verified native producer provenance'):c.bind_consumed({str(foreign):r.sha(foreign)},before,[tmp_path/'build'])

def test_header_snapshot_bounded_canonical_alias_and_escape(tmp_path):
 root=tmp_path/'headers';root.mkdir();file=root/'header.h';file.write_text('immutable');(root/'alias.h').symlink_to(file)
 run=types.SimpleNamespace(remaining=lambda seconds:seconds)
 assert c.prebuild_snapshot(run,[root])=={str(file):r.sha(file)}
 (root/'escape.h').symlink_to('/etc/passwd')
 with pytest.raises(r.Failure,match='escapes'):c.prebuild_snapshot(run,[root])

def test_offline_framework_mutation_invalidates_capability(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 Path(prepared['offlineFramework']['path']).write_text('changed framework after initial preparation')
 with pytest.raises(r.Failure,match='changed'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_compat_archive_cannot_impersonate_api35_provider(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 wrong={**value['runtimeProvider'],**prepared['robolectricNativeDistribution']}
 prepared['runtimeProvider']=wrong;value['runtimeProvider']=wrong;manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='exact pinned framework'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('directory,chain',[(False,False),(True,False),(False,True)])
def test_header_escape_diagnostic_metadata_only(tmp_path,monkeypatch,directory,chain):
 root=tmp_path/'headers';root.mkdir();outside=tmp_path/'outside'
 if directory:outside.mkdir();(outside/'secret.h').write_text('do not read')
 else:outside.write_text('do not read')
 link=root/'escape.h';target=outside
 if chain:
  target=tmp_path/'second-link';target.symlink_to(outside)
 link.symlink_to(target)
 monkeypatch.setattr(c,'sha',lambda path:pytest.fail('escaped target content must not be read'))
 with pytest.raises(r.Failure,match='escapes') as error:c.prebuild_snapshot(types.SimpleNamespace(remaining=lambda n:n),[root])
 record=json.loads(str(error.value).split('EIGHT_VIEW_HEADER_ESCAPE ',1)[1])
 assert record['lexicalCandidate']['text']==str(link)
 assert record['resolvedTarget']['text']==str(outside)
 assert record['linkTarget']['text']==str(target)
 assert record['admittedRoots'][0]['text']==str(root)
 assert record['rootsOmitted']==0 and record['linkMetadataError'] is None


def test_header_escape_diagnostic_bounded_unicode_and_packaging(tmp_path,monkeypatch):
 value='\U0001f600\n\x00'*5000
 monkeypatch.setattr(c.os,'readlink',lambda path:value)
 marker=c.header_escape_diagnostic(value,value,[value]*100)
 raw=marker.split(' ',1)[1];assert len(raw.encode('ascii'))<=16*1024
 record=json.loads(raw)
 assert record['rootsTotal']==100
 assert record['rootsOmitted']+len(record['admittedRoots'])==100
 for key in ['lexicalCandidate','resolvedTarget','linkTarget']:
  assert record[key]['truncated'] and record[key]['utf8Bytes']==len(value.encode())
  assert record[key]['sha256']==hashlib.sha256(value.encode()).hexdigest()
 # Existing terminal receipt stores the whole message as JSON; no new artifact channel.
 receipt={'firstFailure':{'type':'Failure','message':marker,'status':'FAIL'}}
 out=tmp_path/'receipt.json';r.atomic_json(out,receipt)
 assert json.loads(out.read_text())==receipt
 assert r.safe_display(out.read_bytes())[1]!='WITHHELD_SECRET_MARKER'
 from tools.eight_view_host import controller
 controller.seal_receipt(tmp_path,receipt)
 assert json.loads(out.read_text())==receipt
 assert (tmp_path/'SHA256SUMS').is_file()


def test_header_escape_diagnostic_metadata_race_is_still_refusal(tmp_path):
 record=json.loads(c.header_escape_diagnostic(tmp_path/'vanished',tmp_path/'outside',[tmp_path]).split(' ',1)[1])
 assert record['linkTarget'] is None
 assert record['linkMetadataError']['type']=='FileNotFoundError'

@pytest.mark.parametrize('field',['headerProviderDriver-16','headerProviderDriver-17','headerProviderDriver-18','header-provider.json'])
def test_clang_provider_inputs_are_required_by_exact_capability_schema(tmp_path,monkeypatch,field):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);value['inputs'].pop(field);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='Missing/unexpected capability inputs'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_clang_provider_json_and_manifest_must_be_identical(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);value['headerProvider']['sourceSha']='f'*40;manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='capability/input proof differs'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_clang_provider_actual_validator_cannot_be_bypassed(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 def refuse(*args):raise r.Failure('synthetic current provider identity changed')
 monkeypatch.setattr(hp,'validate',refuse)
 with pytest.raises(r.Failure,match='current provider identity changed'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_clang_proof_source_run_binding_is_revalidated_even_if_file_and_manifest_agree(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);value['headerProvider']['runOwner']='foreign';proof=run.work/'capability/header-provider.json';proof.write_text(json.dumps(value['headerProvider']));value['inputs']['header-provider.json']=c.path_record(proof);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='provider proof/source/authority'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('kind',['oversize','alias'])
def test_clang_proof_input_is_bounded_and_not_aliased(tmp_path,monkeypatch,kind):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);proof=run.work/'capability/header-provider.json'
 if kind=='oversize':proof.write_text(' '*32769)
 else:
  saved=proof.with_name('other.json');proof.rename(saved);proof.symlink_to(saved)
 with pytest.raises(r.Failure):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('major',[16,17,18])
def test_clang_driver_bytes_are_bound_capability_input(tmp_path,monkeypatch,major):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);next(role['driver'] for role in hp.PROVIDERS if role['major']==major).write_bytes(b'changed driver')
 with pytest.raises(r.Failure,match='input/output changed'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('label',hp.LABELS)
def test_clang_probe_authoritative_transcripts_are_required(tmp_path,monkeypatch,label):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);value['producer']['commands']=[v for v in value['producer']['commands'] if v['label']!=label];manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='Incomplete producer transcript'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('aliased',[False,True])
def test_clang_consumed_closure_does_not_admit_neighbor_or_symlink_escape(tmp_path,monkeypatch,aliased):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);outside=hp.PROVIDERS[0]['root'].parent/'not-in-include.h';outside.write_bytes(b'foreign header')
 path=outside
 if aliased:path=hp.PROVIDERS[0]['root']/'alias.h';path.symlink_to(outside)
 file=run.work/'capability/consumed-inputs.json';data=json.loads(file.read_text());data[str(path)]=r.sha(outside);file.write_text(json.dumps(data))
 original=p.sha
 def no_outside_read(candidate):
  if Path(candidate) in (outside,path):pytest.fail('outside header content read before root admission')
  return original(candidate)
 monkeypatch.setattr(p,'sha',no_outside_read)
 with pytest.raises(r.Failure,match='outside source/system/JDK closure'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_provider_validator_cannot_return_a_broader_root(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 monkeypatch.setattr(hp,'validate',lambda *a:tmp_path)
 with pytest.raises(r.Failure,match='Unexpected admitted Clang header root'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('mutation',['missing','duplicate','reordered','extra','legacy-single'])
def test_policy_requires_exact_ordered_closed_provider_roots(tmp_path,monkeypatch,mutation):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);roots=[role['root'] for role in hp.PROVIDERS]
 if mutation=='missing':roots=roots[:-1]
 elif mutation=='duplicate':roots=[roots[0],roots[0],roots[2]]
 elif mutation=='reordered':roots=roots[::-1]
 elif mutation=='extra':roots=[*roots,tmp_path]
 else:roots=roots[0]
 monkeypatch.setattr(hp,'validate',lambda *a:roots)
 with pytest.raises(r.Failure,match='Unexpected admitted Clang header root'):p.verify_capability(manifest,SHA,policy,run,prepared)


def test_legacy_single_driver_input_is_not_accepted(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 value['inputs']['headerProviderDriver']=value['inputs'].pop('headerProviderDriver-18');manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='Missing/unexpected capability inputs'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('flags',[
 [],['-DCMAKE_SKIP_RPATH=OFF'],['-DCMAKE_SKIP_RPATH=ON','-DCMAKE_SKIP_RPATH=OFF'],
 ['-DCMAKE_SKIP_RPATH=ON','-DCMAKE_SKIP_RPATH=ON'],['-DCMAKE_SKIP_RPATH:BOOL=ON'],
 ['-DCMAKE_SKIP_RPATH=ON','-D','CMAKE_SKIP_RPATH=OFF'],
],ids=['missing','off','conflicting','duplicate','unapproved-spelling','split-override'])
def test_opencv_rpath_config_bound_to_authoritative_command(tmp_path,monkeypatch,flags):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 for record in run.commands:
  if record['label']=='opencv-configure':record['argv']=['cmake',*flags]
 for record in value['producer']['commands']:
  if record['label']=='opencv-configure':record['argv']=['cmake',*flags]
 manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='explicitly omit'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('field',[np.FILENAME,'native-tool-fixture'])
def test_native_provenance_inputs_required(tmp_path,monkeypatch,field):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 value['inputs'].pop(field);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='Missing/unexpected capability inputs'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('rehash',[False,True])
def test_native_provenance_forgery_rejected(tmp_path,monkeypatch,rehash):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 proof=run.work/'capability'/np.FILENAME;data=json.loads(proof.read_text());data['sourceSha']='f'*40;proof.write_text(json.dumps(data))
 if rehash:
  value['nativeInputProvenance']=c.path_record(proof);value['inputs'][np.FILENAME]=c.path_record(proof)
 manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='reference differs|proof/source/authority'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('mutation',['path','hash','extra','embedded','missing'])
def test_native_proof_reference_exact_shape_path_and_hash(tmp_path,monkeypatch,mutation):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);ref=value['nativeInputProvenance']
 if mutation=='path':ref['path']=str(tmp_path/'foreign.json')
 elif mutation=='hash':ref['sha256']='0'*64
 elif mutation=='extra':ref['schema']=1
 elif mutation=='embedded':value['nativeInputProvenance']=json.loads((run.work/'capability'/np.FILENAME).read_text())
 else:value.pop('nativeInputProvenance')
 manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='reference differs'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_native_proof_is_referenced_without_double_embedding(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 assert set(value['nativeInputProvenance'])=={'path','sha256'}
 assert value['nativeInputProvenance']==value['inputs'][np.FILENAME]
 assert p.verify_capability(manifest,SHA,policy,run,prepared)==value

@pytest.mark.parametrize('kind',['oversize','alias'])
def test_native_provenance_file_bounded_unaliased(tmp_path,monkeypatch,kind):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);proof=run.work/'capability'/np.FILENAME
 if kind=='oversize':proof.write_bytes(b' '*(16*1024**2+1))
 else:
  saved=proof.with_name('other-native.json');proof.rename(saved);proof.symlink_to(saved)
 with pytest.raises(r.Failure):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('mutation',['missing','failed','non-authoritative'])
def test_native_producer_label_is_required_authoritative_success(tmp_path,monkeypatch,mutation):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 if mutation=='missing':value['producer']['commands']=[v for v in value['producer']['commands'] if v['label']!='native-fixture-probe']
 elif mutation=='failed':next(v for v in run.commands if v['label']=='native-fixture-probe')['exit']=1
 else:run.commands=[v for v in run.commands if v['label']!='native-fixture-probe']
 manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='transcript|authoritative'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_native_validator_refusal_precedes_external_payload_read(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 def refuse(*args):raise r.Failure('native owned-state refusal')
 monkeypatch.setattr(np,'validate',refuse)
 with pytest.raises(r.Failure,match='native owned-state refusal'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('member',[True,False])
def test_only_exact_verified_system_link_member_admitted(tmp_path,monkeypatch,member):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);out=run.work/'capability'
 external=tmp_path/'system-link-fixture'/'libfixture.so';external.parent.mkdir();external.write_bytes(b'SYNTHETIC_LINK_INPUT_NOT_ELF')
 proof=json.loads((out/np.FILENAME).read_text());proof['systemLink']['inputs']={str(external):r.sha(external)} if member else {str(external.parent/'neighbor.so'):'0'*64}
 (out/np.FILENAME).write_text(json.dumps(proof));value['inputs'][np.FILENAME]=c.path_record(out/np.FILENAME);value['nativeInputProvenance']=c.path_record(out/np.FILENAME)
 consumed=json.loads((out/'consumed-inputs.json').read_text());consumed[str(external)]=r.sha(external)
 rolemap={name:{'sha256':digest,'scope':'SYNTHETIC_VERIFIED_ROLE'} for name,digest in consumed.items()}
 monkeypatch.setattr(np,'validate',lambda *args:rolemap)  # Policy boundary only, not real system-link qualification.
 for name,data in [('consumed-inputs.json',consumed),('consumed-provenance.json',rolemap)]:
  (out/name).write_text(json.dumps(data));value['inputs'][name]=c.path_record(out/name)
 value['inputs']={k:v for k,v in value['inputs'].items() if not k.startswith('consumed-') or k.endswith('.json')}
 value['inputs'].update({'consumed-'+str(i):c.path_record(Path(path)) for i,path in enumerate(sorted(consumed))})
 manifest.write_text(json.dumps(value))
 if member:assert p.verify_capability(manifest,SHA,policy,run,prepared)==value
 else:
  original=p.sha
  def guard(path):
   if Path(path)==external:pytest.fail('unadmitted external payload read')
   return original(path)
  monkeypatch.setattr(p,'sha',guard)
  with pytest.raises(r.Failure,match='outside source/system/JDK closure'):p.verify_capability(manifest,SHA,policy,run,prepared)

def test_consumed_rolemap_must_equal_verified_native_result(tmp_path,monkeypatch):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);file=run.work/'capability/consumed-provenance.json'
 file.write_text('{}');value['inputs']['consumed-provenance.json']=c.path_record(file);manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='prebuild provenance mismatch'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('aliased',[False,True])
def test_native_evidence_collector_retains_exact_eleven_files_only(tmp_path,aliased):
 from tools.eight_view_host import controller as ctl
 run=r.Runner(tmp_path,tmp_path/'work');out=run.work/'capability';out.mkdir()
 phases=('generate-java-core','build-core','generate-imgproc','build-imgproc','generate-features2d','build-java')
 names=[np.FILENAME,*[f'native-opencv-phase-{phase}-log.txt' for phase in phases],*[f'native-{domain}-{phase}-log.txt' for domain in ('opencv','scanner') for phase in ('baseline','build')]]
 for name in names:(out/name).write_text('{}\n' if name.endswith('.json') else '# ninja log v6\n')
 (out/'unlisted-native-proof.json').write_text('do not retain')
 (out/'native-opencv-generation-log.txt').write_text('obsolete single-generation evidence must not be retained')
 (out/'native-opencv-phase-unreviewed-log.txt').write_text('unknown phase must not be retained')
 deps=[f'native-opencv-phase-{phase}-deps.raw.log' for phase in ('build-core','build-imgproc','build-java')]
 for name in deps:(run.work/'logs'/name).write_text('synthetic authoritative dependency transcript')
 (out/'build').mkdir();(out/'build/native.o').write_bytes(b'NOT AN UPLOAD')
 if aliased:
  (out/names[0]).unlink();(out/names[0]).symlink_to(out/'unlisted-native-proof.json')
  with pytest.raises(r.Failure,match='symlink'):ctl.evidence_package(run,{'status':'FAIL'},tmp_path/'evidence')
 else:
  receipt={'status':'FAIL'};dest=ctl.evidence_package(run,receipt,tmp_path/'evidence')
  assert len(names)==11 and {f.name for f in (dest/'capability').iterdir()}==set(names)
  assert {f.name for f in (dest/'logs').iterdir()}==set(deps)
  assert all((dest/'capability'/name).read_bytes()==(out/name).read_bytes() for name in names)

@pytest.mark.parametrize('aggregate',[False,True])
def test_native_proof_packaging_keeps_existing_size_caps(tmp_path,aggregate):
 from tools.eight_view_host import controller as ctl
 run=r.Runner(tmp_path,tmp_path/'work');out=run.work/'capability';out.mkdir()
 # A logically bounded compact proof is not a promise that a duplicated,
 # pretty-printed receipt fits the independent artifact byte caps.
 (out/np.FILENAME).write_bytes(b' '*(16*1024**2 if aggregate else 16*1024**2+1))
 if aggregate:(out/'capability.json').write_bytes(b' '*(16*1024**2))
 with pytest.raises(r.Failure,match='size|byte/count'):ctl.evidence_package(run,{'status':'FAIL'},tmp_path/'evidence')


def traversal_record(error):
 assert isinstance(error,r.Failure) and str(error).startswith('Prebuild header traversal limit; ')
 payload=str(error).split('EIGHT_VIEW_HEADER_TRAVERSAL ',1)[1]
 assert len(payload.encode('ascii'))<=3072
 return json.loads(payload)


def test_snapshot_elapsed_predicate_reports_actual_allocated_budget(tmp_path,monkeypatch):
 root=tmp_path/'headers';root.mkdir()
 ticks=iter((10.0,10.25));monkeypatch.setattr(c.time,'monotonic',lambda:next(ticks))
 monkeypatch.setattr(c,'sha',lambda _:pytest.fail('no content read after deadline'))
 with pytest.raises(r.Failure) as error:c.prebuild_snapshot(types.SimpleNamespace(remaining=lambda _:0.25),[root])
 value=traversal_record(error.value)
 assert value['predicates']=={'countExceeded':False,'elapsedExceeded':True}
 assert value['limits']=={'entries':60000,'seconds':90,'allocatedSeconds':0.25}
 assert value['elapsedSeconds']==0.25 and value['counts']=={'attempted':1,'pending':0,'seen':0,'hashedFiles':0,'observedFileBytes':0}
 assert value['currentRoot']['index']==0 and value['scope']=='CURRENT_INVOCATION_NOT_RETROACTIVE'


@pytest.mark.parametrize('expired',[False,True],ids=['count-only','count-and-time'])
def test_snapshot_count_and_combined_predicates_without_cap_override(tmp_path,monkeypatch,expired):
 root=tmp_path/'headers';root.mkdir();child=root/'empty';child.mkdir()
 class Entries:
  def __init__(self,path):self.path=path
  def __enter__(self):return iter(types.SimpleNamespace(path=str(child)) for _ in range(60000 if self.path==root else 0))
  def __exit__(self,*args):pass
 # Simulated repeated directory entries exercise the existing exact60001 attempted guard;
 # production count, queue conditions and limits are not monkeypatched or increased.
 monkeypatch.setattr(c.os,'scandir',lambda path:Entries(path))
 calls=0
 def clock():
  nonlocal calls
  calls+=1
  return 91.0 if expired and calls>=60002 else 0.0
 monkeypatch.setattr(c.time,'monotonic',clock)
 monkeypatch.setattr(c,'sha',lambda _:pytest.fail('empty directories have no payload'))
 with pytest.raises(r.Failure) as error:c.prebuild_snapshot(types.SimpleNamespace(remaining=lambda n:n),[root])
 value=traversal_record(error.value)
 assert value['predicates']=={'countExceeded':True,'elapsedExceeded':expired}
 assert value['counts']=={'attempted':60001,'pending':0,'seen':2,'hashedFiles':0,'observedFileBytes':0}
 assert value['currentPath']['sha256']==hashlib.sha256(str(child).encode()).hexdigest()


def test_snapshot_progress_counts_hashed_files_without_disclosing_content(tmp_path,monkeypatch):
 root=tmp_path/'headers';root.mkdir();first=root/'first-secret-name.h';second=root/'second-secret-name.h'
 first.write_bytes(b'private header contents');second.write_bytes(b'other confidential bytes')
 class Entries:
  def __enter__(self):return iter([types.SimpleNamespace(path=str(first)),types.SimpleNamespace(path=str(second))])
  def __exit__(self,*args):pass
 monkeypatch.setattr(c.os,'scandir',lambda _:Entries())
 ticks=iter([0.0,0.0,0.0,90.0]);monkeypatch.setattr(c.time,'monotonic',lambda:next(ticks))
 with pytest.raises(r.Failure) as error:c.prebuild_snapshot(types.SimpleNamespace(remaining=lambda n:n),[root])
 value=traversal_record(error.value)
 assert value['counts']=={'attempted':3,'pending':0,'seen':2,'hashedFiles':1,'observedFileBytes':len(b'other confidential bytes')}
 assert set(value['currentPath'])=={'sha256','utf8Bytes'}
 assert 'secret-name' not in str(error.value) and 'confidential' not in str(error.value) and 'private header' not in str(error.value)


@pytest.mark.parametrize('root_text',['/'+('\U0001f600'*4000),'/password=hidden-sensitive-value','/ordinary'],ids=['unicode-bounded','secret-withheld','normal'])
def test_snapshot_diagnostic_metadata_bounded_sanitized_without_filesystem(tmp_path,monkeypatch,root_text):
 root=Path(root_text);path=root/'private-filename.h'
 monkeypatch.setattr(Path,'resolve',lambda *a,**k:pytest.fail('failure formatting must not resolve paths'))
 monkeypatch.setattr(c,'sha',lambda _:pytest.fail('failure formatting must not read payload'))
 text=c.header_traversal_diagnostic([root],path,60001,[],set(),{},0,0.0,91.0,90)
 assert len(text.encode('ascii'))<3200
 value=json.loads(text.split(' ',1)[1]);identity=value['currentRoot']['identity']
 assert identity['sha256']==hashlib.sha256(root_text.encode()).hexdigest()
 assert len(identity['preview'].encode())<=128 and 'private-filename' not in text
 if 'password=' in root_text:assert identity['withheld'] and identity['preview']=='' and 'hidden-sensitive-value' not in text
 if '\U0001f600' in root_text:assert identity['truncated']


def test_snapshot_root_association_is_lexical_not_origin():
 root=Path('/headers');nested=root/'nested';path=nested/'file.h'
 value=json.loads(c.header_traversal_diagnostic([root,nested],path,1,[],set(),{},0,0,90,90).split(' ',1)[1])
 assert value['currentRoot']['index']==0
 assert value['rootAssociation']=='FIRST_LEXICAL_CONTAINING_ADMITTED_ROOT_NOT_TRAVERSAL_ORIGIN'
