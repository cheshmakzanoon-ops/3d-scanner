"""Synthetic proof-schema fixtures; real ELF/JNI compilation tests live in host guard suite."""
import copy,hashlib,json,os,shutil,sys,types,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
import tools
SOURCE=Path(os.environ.get('RF_SOURCE_ROOT',ROOT));sys.path.append(str(SOURCE));tools.__path__.append(str(SOURCE/'tools'))
from tools.eight_view_host import runtime as r,policy as p,capability as c
SHA='b'*40

def fixture(tmp_path,monkeypatch):
 run=r.Runner(tmp_path/'repo',tmp_path/'work');run.root.mkdir();run.disk_guard=lambda:None
 java=Path(os.environ.get('JAVA_HOME','/missing'))
 if not (java/'include/jni.h').is_file():pytest.skip('NOT_RUN: actual JDK headers unavailable')
 run.env['JAVA_HOME']=str(java)
 out=run.work/'capability';out.mkdir();original=json.loads(c.POLICY.read_text());original['nativeClasses']=['fixture.Owner'];original['opencvCommit']='a'*40
 def put(path,data=b'fixture'):
  path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data);return path
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
 consumed={str((java/'include/jni.h').resolve()):r.sha(java/'include/jni.h'),'/usr/include/stdio.h':r.sha('/usr/include/stdio.h')}
 proof_files={
  'source-files.json':{'LICENSE':r.sha(license)},'scanner-source.json':{'src/jni.cpp':r.sha(jni)},'consumed-inputs.json':consumed,'prebuild-inputs.json':consumed,'consumed-provenance.json':{name:{'sha256':digest,'scope':'PREBUILD_AND_POSTBUILD_IDENTICAL'} for name,digest in consumed.items()},
  'opencv-abi.json':{'fixture.Owner':{'declarations':[['hello','()J']],'matching':True}},
  'scanner-abi.json':{'declarations':[list(x) for x in declarations],'symbols':sorted(c.jni_symbol('com.realityforge.scanner.engine.NativeCore',name) for name,_ in declarations)}}
 for name,value in proof_files.items():put(out/name,(json.dumps(value)+'\n').encode())
 inputs={**{name:Path(shutil.which(tool,path=run.env['PATH']) or '/missing').resolve() for name,tool in [('compiler','g++'),('cc','gcc'),('cmake','cmake'),('ninja','ninja'),('nm','nm'),('readelf','readelf'),('ldd','ldd')]},'java':java/'bin/java','javap':java/'bin/javap','javac':java/'bin/javac','javaRelease':java/'release','policy':policy,'smokeSource':c.HERE/'HostOpenCvSmoke.java','archive':archive,'license':license,'aar':aar,'classes':classes,'nativeOwner':owner,'generatedJar':generated,'jniSource':jni,'jniContract':contract,'robolectricNativeDistribution':provider,'androidAll':framework,**{name:out/name for name in proof_files},**{'consumed-'+str(i):Path(path) for i,path in enumerate(sorted(consumed))}}
 native={key:put(out/rel) for key,rel in [('opencv','build/lib/libopencv_java4120.so'),('shim','librealityforge_jni.so'),('engine','scanner/librealityforge.so')]}
 logs={'compiler-version':b'14.2.0\n','opencv-configure':b'To be built: core features2d imgproc java\nJava wrappers: YES (JAVA)\n','opencv-build':b'done','opencv-symbols':b'000 T Java_fixture_Owner_hello\n','opencv-consumed-deps':b'deps','opencv-smoke-compile':b'', 'opencv-smoke':b'OPENCV_REAL_SMOKE_PASS version=4.12.0 descriptors=12 gray=76,150,29,255\n','scanner-configure':b'done','scanner-build':b'done','scanner-javap':''.join(' public native long '+name+'();\n descriptor: '+desc+'\n' for name,desc in declarations).encode(),'scanner-shim':b'','scanner-consumed-deps':b'deps','scanner-symbols':''.join('000 T '+symbol+'\n' for symbol in proof_files['scanner-abi.json']['symbols']).encode(),'opencv-aar-0':b' public native long hello();\n descriptor: ()J\n','opencv-generated-0':b' public native long hello();\n descriptor: ()J\n'}
 for prefix in ('opencv','scanner-engine','scanner-shim'):
  for part in ('elf-header','elf-dynamic','ldd'):logs[prefix+'-'+part]=b'UNIT_FIXTURE_ELF_PROOF'
 producer=[]
 for label,raw in logs.items():
  file=put(run.work/'logs'/(label+'.raw.log'),raw);record={'label':label,'complete':True,'exit':0,'bytes':len(raw),'sha256':r.sha(file),'argv':['unit-fixture'],'runOwner':run.owner,'starttime':123,'environmentBoundary':'INHERITED_RUNTIME'}
  if label=='scanner-shim':record['argv']=['compiler','-include',str(contract),str(jni)]
  run.commands.append(record);producer.append({**record,'log':c.path_record(file)})
 value={'schema':1,'classification':'HOST_SOURCE_BUILT_CAPABILITY','sourceSha':SHA,'policySha256':r.sha(policy),'checks':{key:True for key in original['requiredChecks']},'native':{key:c.path_record(path) for key,path in native.items()},'inputs':{key:c.path_record(path) for key,path in inputs.items()},'producer':{'runOwner':run.owner,'workDir':str(run.work),'sourceSha':SHA,'commands':producer},'runtimeProvider':provider_record,'opencvElf':{'unitFixture':'opencv'},'scannerElf':{'engine':{'unitFixture':'engine'},'shim':{'unitFixture':'shim'}}}
 # Only ELF parsing is stubbed in this synthetic schema fixture. Separate tests invoke real compiler/readelf/ldd.
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
 result=c.bind_consumed({str(generated):r.sha(generated)},before,[tmp_path/'build'])
 assert result[str(generated)]['scope']=='POST_GENERATION_ONLY_NOT_PRECONSUMPTION'
 foreign=tmp_path/'build/arbitrary.h';foreign.write_text('unknown')
 with pytest.raises(r.Failure,match='lacks prebuild'):c.bind_consumed({str(foreign):r.sha(foreign)},before,[tmp_path/'build'])

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
