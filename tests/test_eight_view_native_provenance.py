"""Actual small CMake/Ninja provenance fixtures; not OpenCV/reconstruction qualification."""
import copy,hashlib,json,os,shutil,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import native_provenance as p,capability as c
from tools.eight_view_host.runtime import Runner,Failure,sha
from tools.native_process import native_compile_env


def native_tools(version):
 base=os.environ.get('RF_NINJA_FIXTURE_ROOT','');ninja=Path(base)/version/'ninja'
 cmake=os.environ.get('RF_CMAKE_EXECUTABLE') or shutil.which('cmake');cc=shutil.which('gcc');cxx=shutil.which('g++')
 if not (ninja.is_file() and cmake and cc and cxx):
  if os.environ.get('RF_REQUIRE_NATIVE_PROVENANCE_TOOLS')=='1':pytest.fail('Required real CMake/Ninja provenance tools missing')
  pytest.skip('Real CMake/Ninja tools unavailable; no qualification')
 return Path(cmake).resolve(),ninja.resolve(),Path(cc).resolve(),Path(cxx).resolve()


def configure_source(source,target):
 source.mkdir(parents=True)
 (source/'main.cpp').write_text('extern "C" int generated(); extern "C" int result(){return generated();}\n')
 (source/'generate.cmake').write_text('file(WRITE "${OUT}/generated.hpp" "#define VALUE 19\\n")\nfile(WRITE "${OUT}/generated.cpp" "#include \\\"generated.hpp\\\"\\nextern \\\"C\\\" int generated(){return VALUE;}\\n")\n')
 (source/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(bound_native CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_custom_command(OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/generated.cpp ${CMAKE_CURRENT_BINARY_DIR}/generated.hpp
 COMMAND ${CMAKE_COMMAND} -DOUT=${CMAKE_CURRENT_BINARY_DIR} -P ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake
 DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake)
add_library(bits STATIC ${CMAKE_CURRENT_BINARY_DIR}/generated.cpp)
add_library('''+target+''' SHARED main.cpp)
target_link_libraries('''+target+''' PRIVATE bits m)
''')


def configure_opencv_side_effects(source):
 configure_source(source,'opencv_java')
 # Mirror pinned upstream: Java generator declares only its stamp; OpenCL declares .cpp only.
 (source/'openclgen.cmake').write_text('file(WRITE "${OUT}.hpp" "#define OCL_VALUE 3\\n")\nfile(WRITE "${OUT}.cpp" "#include \\\"opencl_kernels_${MODULE}.hpp\\\"\\nint ocl_${MODULE}(){return OCL_VALUE;}\\n")\n')
 (source/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(bound_native CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
add_custom_command(OUTPUT ${CMAKE_CURRENT_BINARY_DIR}/java.stamp
 COMMAND ${CMAKE_COMMAND} -DOUT=${CMAKE_CURRENT_BINARY_DIR} -P ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake
 COMMAND ${CMAKE_COMMAND} -E touch ${CMAKE_CURRENT_BINARY_DIR}/java.stamp
 DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake)
add_custom_target(gen_opencv_java_source DEPENDS ${CMAKE_CURRENT_BINARY_DIR}/java.stamp)
set_source_files_properties(${CMAKE_CURRENT_BINARY_DIR}/generated.cpp PROPERTIES GENERATED TRUE)
add_library(bits STATIC ${CMAKE_CURRENT_BINARY_DIR}/generated.cpp)
add_dependencies(bits gen_opencv_java_source)
foreach(module core imgproc features2d)
 set(out ${CMAKE_CURRENT_BINARY_DIR}/modules/${module}/opencl_kernels_${module})
 add_custom_command(OUTPUT ${out}.cpp
 COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_CURRENT_BINARY_DIR}/modules/${module}
 COMMAND ${CMAKE_COMMAND} -DOUT=${out} -DMODULE=${module} -P ${CMAKE_CURRENT_SOURCE_DIR}/openclgen.cmake
 DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/openclgen.cmake)
 target_sources(bits PRIVATE ${out}.cpp)
endforeach()
add_library(opencv_java SHARED main.cpp)
target_link_libraries(opencv_java PRIVATE bits m)
''')


@pytest.mark.parametrize('version',p.VERSIONS)
def test_real_current_graph_and_system_link_provenance(tmp_path,version):
 cmake,ninja,cc,cxx=native_tools(version);env=dict(os.environ);env['PATH']=str(cmake.parent)+os.pathsep+str(ninja.parent)+os.pathsep+env['PATH']
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=360);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);before={str(Path('/usr/include/stdc-predef.h').resolve()):sha('/usr/include/stdc-predef.h')};consumed={}
  # A fixture system-header snapshot is exact observed /usr include leaves, not full-host qualification.
  for domain,(dirname,target) in p.DOMAINS.items():
   source=root/domain
   if domain=='opencv':configure_opencv_side_effects(source)
   else:configure_source(source,target)
   build=out/dirname
   run.command(domain+'-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
   p.graph_before(run,state,build,target,domain)
   if domain=='opencv':p.pregenerate(run,state,cmake)
   before.update(c.prebuild_snapshot(run,[source,build]))
   run.command(domain+'-build',[cmake,'--build',build,'--target',target,'--parallel','2'],env=native_compile_env(env))
   p.graph_after(run,state,build,domain)
   part=c.consumed_inputs(run,ninja,build,domain);consumed.update(part)
  rolemap=p.finish(run,state,consumed,before)
  assert any(x['scope']=='CURRENT_SUCCESSFUL_GRAPH_POSTBUILD_NOT_PRECONSUMPTION' for x in rolemap.values())
  assert any(x['scope']=='SYSTEM_LINK_PREBUILD_AND_POSTBUILD_IDENTICAL' for x in rolemap.values())
  assert any('libbits.a' in x for x in rolemap)
  assert any('generated.hpp' in x for x in rolemap)
  assert rolemap[str(out/'build/generated.hpp')]['scope']=='GENERATION_PHASE_PRECONSUMPTION_AND_POSTBUILD_IDENTICAL'
  assert all(rolemap[str(out/'build/modules'/m/('opencl_kernels_'+m+'.hpp'))]['scope']=='GENERATION_PHASE_PRECONSUMPTION_AND_POSTBUILD_IDENTICAL' for m in ('core','imgproc','features2d'))
  assert p.validate(run,state,consumed,before,'a'*40)==rolemap
  # Exercise the real bounded collector with actual generated proof/log bytes.
  from tools.eight_view_host import controller as ctl
  reference={'path':str(out/p.FILENAME),'sha256':sha(out/p.FILENAME)}
  (out/'capability.json').write_text(json.dumps({'nativeInputProvenance':reference})+'\n')
  receipt={'status':'FAIL','classification':'REAL_FIXTURE_NOT_HOST_QUALIFICATION','pipelineAttempts':0}
  destination=ctl.evidence_package(run,receipt,tmp_path/'evidence')
  assert receipt['evidence']['complete'] and receipt['evidence']['bytes']<32*1024**2
  assert (destination/'capability'/p.FILENAME).read_bytes()==(out/p.FILENAME).read_bytes()
  assert (destination/'capability/capability.json').stat().st_size<1024
  evidence=os.environ.get('RF_NATIVE_PROVENANCE_EVIDENCE_DIR')
  if evidence:
   dest=Path(evidence)/version;dest.mkdir(parents=True,exist_ok=True)
   (dest/'package-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
  # Actual output, immutable source, system bytes/proof and graph tampering are all rejected.
  for key in ('sourceSha','runOwner','ninjaVersion'):
   bad=copy.deepcopy(state);bad[key]='forged'
   with pytest.raises(Failure):p.validate(run,bad,consumed,before,'a'*40)
  # Mutate actual immutable source and pre-generated undeclared header separately.
  for file in (root/'opencv/main.cpp',out/'build/generated.hpp'):
   original=file.read_bytes();file.write_bytes(original+b'changed')
   with pytest.raises(Failure):p.validate(run,state,consumed,before,'a'*40)
   file.write_bytes(original)
  wrong=copy.deepcopy(state);wrong['generation']['targets'].append('not-reviewed')
  with pytest.raises(Failure):p.validate(run,wrong,consumed,before,'a'*40)
  stale=copy.deepcopy(state);stale['graphs']['opencv']['absent'].remove(str(out/'build/libbits.a'))
  with pytest.raises(Failure,match='predates'):p.graph_after(run,stale,out/'build','opencv')
  # Restoring the exact original retained log is test-only, after the refusal above.
  (out/'native-opencv-build-log.txt').write_bytes((out/'build/.ninja_log').read_bytes())
  output=out/'build/libbits.a';original=output.read_bytes();output.write_bytes(original+b'changed')
  with pytest.raises(Failure,match='drift'):p.validate(run,state,consumed,before,'a'*40)
  evidence=os.environ.get('RF_NATIVE_PROVENANCE_EVIDENCE_DIR')
  if evidence:
   dest=Path(evidence)/version;dest.mkdir(parents=True,exist_ok=True)
   (dest/'proof.json').write_text(json.dumps(state,indent=2)+'\n');(dest/'roles.json').write_text(json.dumps(rolemap,indent=2)+'\n')
   for path in (run.work/'logs').glob('*.raw.log'):shutil.copyfile(path,dest/path.name)
 finally:run.cleanup()


def test_link_depfile_requires_full_primary_and_compatibility_rules():
 raw=b'/out: /a /b /a\n\n/a:\n/b:\n/a:\n';assert p.link_deps(raw,Path('/out'))==['/a','/b','/a']
 for bad in (b'/out: /a /b\n/a:\n',b'/wrong: /a\n/a:\n',b'/out:\n',b'/out: /a\n/a: /evil\n'):
  with pytest.raises(Failure):p.link_deps(bad,Path('/out'))


def test_ninja_log_rejects_stale_duplicate_and_unknown_format(tmp_path):
 good=b'# ninja log v7\n0\t1\t123\tobj.o\tabc\n';assert len(p.ninja_log(good,tmp_path))==1
 for bad in (good+good.split(b'\n')[1]+b'\n',good.replace(b'v7',b'v5'),good.replace(b'0\t1',b'9\t1'),good.replace(b'123',b'0')):
  with pytest.raises(Failure):p.ninja_log(bad,tmp_path)


def test_grouped_deps_refuses_partial_stale_or_orphan(tmp_path):
 good=b'obj.o: #deps 1, deps mtime 123 (VALID)\n    a.cpp\n\n';assert len(p.grouped_deps(good,tmp_path))==1
 for bad in (good.replace(b'#deps 1',b'#deps 2'),good.replace(b'VALID',b'STALE'),b'    orphan\n',good+good):
  with pytest.raises(Failure):p.grouped_deps(bad,tmp_path)


def small_role_state(tmp_path):
 state={'graphs':{},'systemLink':{'inputs':{}}};before={};consumed={}
 for domain,(dirname,target) in p.DOMAINS.items():
  root=tmp_path/dirname;root.mkdir();src=root/'source.c';src.write_text('int value;');obj=root/'out.o';obj.write_bytes(b'fixture object')
  source=str(src);output=str(obj);paths={target:str(root/target),'source.c':source,'out.o':output}
  nodes={target:{'rule':'phony','inputs':[{'name':'out.o','kind':'explicit'}]},'source.c':{'rule':None,'inputs':[]},'out.o':{'rule':'COMPILE','inputs':[{'name':'source.c','kind':'explicit'}]}}
  state['graphs'][domain]={'target':target,'nodes':nodes,'paths':paths,'producers':{'out.o':'edge:one'},'aliases':{v:[k] for k,v in paths.items()},'reachable':list(paths.values()),'outputs':{output:sha(obj)}}
  before[source]=sha(src);consumed[output]=sha(obj)
 return state,before,consumed


@pytest.mark.parametrize('case',['unlisted','unreachable','phony','unbound-leaf','source-drift','cycle','missing-output'],ids=str)
def test_role_graph_refuses_unproved_outputs_or_inputs(tmp_path,case):
 state,before,consumed=small_role_state(tmp_path);g=state['graphs']['opencv'];obj=g['paths']['out.o'];src=g['paths']['source.c']
 if case=='unlisted':
  extra=tmp_path/'build/unlisted.hpp';extra.write_bytes(b'x');consumed[str(extra)]=sha(extra)
 elif case=='unreachable':g['reachable'].remove(obj)
 elif case=='phony':g['nodes']['out.o']['rule']='phony'
 elif case=='unbound-leaf':before.pop(src)
 elif case=='source-drift':Path(src).write_text('changed')
 elif case=='cycle':g['nodes']['out.o']['inputs']=[{'name':'out.o','kind':'explicit'}]
 elif case=='missing-output':g['outputs'].pop(obj)
 with pytest.raises(Failure):p.roles(state,consumed,before,{})


def test_role_graph_explicit_output_scope_is_not_preconsumption(tmp_path):
 state,before,consumed=small_role_state(tmp_path);roles=p.roles(state,consumed,before,{})
 assert {row['scope'] for row in roles.values()}=={'CURRENT_SUCCESSFUL_GRAPH_POSTBUILD_NOT_PRECONSUMPTION'}


def test_ambiguous_canonical_aliases_are_not_deduplicated():
 nodes={'relative':{'rule':'R','inputs':[]},'/absolute':{'rule':'R','inputs':[]}};paths={k:'/one' for k in nodes}
 with pytest.raises(Failure,match='ambiguous'):p.aliases_for(nodes,paths,{'relative':'edge:one','/absolute':'edge:two'})
 assert p.aliases_for(nodes,paths,{k:'edge:one' for k in nodes})=={'/one':['/absolute','relative']}
 nodes['/absolute']['inputs']=[{'name':'different','kind':'explicit'}]
 with pytest.raises(Failure,match='mismatch'):p.aliases_for(nodes,paths,{k:'edge:one' for k in nodes})


@pytest.mark.parametrize('raw',[b'obj: rule\nobj: rule\n',b'obj: unknown rule\n',b'obj\n',b'-option: rule\n'],ids=['duplicate','unknown-rule-format','missing-colon','option-like-path'])
def test_target_inventory_fails_closed(raw):
 with pytest.raises(Failure):p.target_names(raw)


def test_partial_query_and_unknown_sections_refused():
 for raw in (b'obj:\n  input: COMPILE\n    source\n  unknown:\n',b'obj:\n  outputs:\n'):
  with pytest.raises(Failure):p.parse_query(raw,['obj','source'])


def test_same_path_multiple_logged_producers_refused(tmp_path):
 raw=b'# ninja log v7\n0\t2\t123\tobj.o\taaa\n1\t3\t124\t'+str(tmp_path/'obj.o').encode()+b'\tbbb\n'
 with pytest.raises(Failure,match='conflicting'):p.ninja_log(raw,tmp_path)


def test_query_and_target_caps(monkeypatch):
 monkeypatch.setattr(p,'MAX_NODES',1)
 with pytest.raises(Failure):p.target_names(b'a: R\nb: R\n')
 monkeypatch.setattr(p,'MAX_EDGES',0)
 with pytest.raises(Failure):p.parse_query(b'obj:\n  input: R\n    source\n  outputs:\n',['obj'])


def test_system_input_mutation_is_not_admitted_by_path(tmp_path):
 state,before,consumed=small_role_state(tmp_path)
 external=tmp_path/'external-system-linker-script';external.write_text('GROUP ( fixture )')
 state['systemLink']['inputs'][str(external)]=sha(external);consumed[str(external)]=sha(external)
 assert p.roles(state,consumed,before,{})[str(external)]['scope']=='SYSTEM_LINK_PREBUILD_AND_POSTBUILD_IDENTICAL'
 external.write_text('GROUP ( changed )')
 # A dependency edge forces revalidation of the actual leaf, not only ledger equality.
 state['graphs']['opencv']['nodes']['out.o']['inputs'].append({'name':'external','kind':'implicit'})
 state['graphs']['opencv']['nodes']['external']={'rule':None,'inputs':[]}
 state['graphs']['opencv']['paths']['external']=str(external)
 with pytest.raises(Failure,match='system input drift'):p.roles(state,consumed,before,{})


def test_real_linker_script_and_transitive_archive_are_discovered(tmp_path):
 import subprocess
 cmake,ninja,cc,cxx=native_tools('1.13.2');source=tmp_path/'source.c';source.write_text('int archive_value(){return 3;}')
 obj=tmp_path/'source.o';archive=tmp_path/'libfixture.a';script=tmp_path/'libfixture.so';output=tmp_path/'probe.so';dep=tmp_path/'probe.d'
 ar=shutil.which('ar')
 if not ar:pytest.fail('Actual archive tool unavailable')
 for argv in ([cc,'-fPIC','-c',source,'-o',obj],[ar,'rcs',archive,obj]):subprocess.run(list(map(str,argv)),check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 script.write_text('INPUT ( '+str(archive)+' )\n')
 subprocess.run(list(map(str,[cxx,'-shared',obj,script,'-Wl,--dependency-file='+str(dep),'-o',output])),check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 inputs=p.link_deps(dep.read_bytes(),output)
 assert str(script) in inputs and str(archive) in inputs
 snapshot={str(Path(name).resolve()):sha(Path(name).resolve()) for name in inputs}
 archive.write_bytes(archive.read_bytes()+b'tamper')
 assert snapshot[str(archive)]!=sha(archive)


def test_actual_owned_log_new_record_is_required_even_for_identical_output_bytes(tmp_path):
 # Isolated real Ninja custom command; no fixed-provider/whole-capability qualification claim.
 import subprocess
 _,ninja,_,_=native_tools('1.13.2');build=tmp_path/'build';build.mkdir()
 (build/'build.ninja').write_text('rule gen\n command = printf same > out.hpp && touch stamp\n restat = 1\nbuild stamp: gen\nbuild generated: phony stamp\n')
 args=[str(ninja),'-C',str(build),'generated']
 subprocess.run(args,check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 first=(build/'.ninja_log').read_bytes();digest=sha(build/'out.hpp')
 (build/'stamp').unlink()  # Only a disposable fixture output, never repository/user data.
 subprocess.run(args,check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 assert sha(build/'out.hpp')==digest and (build/'.ninja_log').read_bytes()!=first
 with pytest.raises(Failure,match='duplicate|conflicting'):p.ninja_log((build/'.ninja_log').read_bytes(),build)


def test_unreviewed_generation_target_refuses_before_command(tmp_path):
 from types import SimpleNamespace
 run=SimpleNamespace(command=lambda *a,**k:pytest.fail('Unreviewed target executed'))
 with pytest.raises(Failure,match='target absent'):
  p.pregenerate(run,{'graphs':{'opencv':{'buildDirectory':str(tmp_path),'nodes':{}}}},Path('/cmake'))


def test_real_failed_generator_cannot_produce_a_generation_attestation(tmp_path):
 cmake,ninja,cc,cxx=native_tools('1.13.2');env=dict(os.environ);env['PATH']=str(cmake.parent)+os.pathsep+str(ninja.parent)+os.pathsep+env['PATH']
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=240);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);source=root/'opencv';configure_opencv_side_effects(source)
  (source/'generate.cmake').write_text('message(FATAL_ERROR "intentional fixture generator failure")\n')
  build=out/'build';run.command('opencv-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
  p.graph_before(run,state,build,'opencv_java','opencv')
  with pytest.raises(Failure):p.pregenerate(run,state,cmake)
  assert 'generation' not in state and not any(r['label']=='opencv-build' for r in run.commands)
  assert next(r for r in run.commands if r['label']=='native-opencv-generate')['exit']!=0
 finally:run.cleanup()


def test_existing_opencv_budget_is_partitioned_not_added():
 import inspect
 source=inspect.getsource(c.build)
 assert 'opencv_deadline=run.clock()+run.remaining(1620)' in source
 assert 'min(120,opencv_deadline-run.clock())' in source
 assert 'seconds=max(0,opencv_deadline-run.clock())' in source
 assert source.index('native_provenance.pregenerate(')<source.index('immutable_before.update(prebuild_snapshot(run,[source,builddir]))')<source.index("run.command('opencv-build'")


def test_manifest_discovery_and_arg_budget_fail_closed(tmp_path,monkeypatch):
 from types import SimpleNamespace
 (tmp_path/'build.ninja').write_text('include /outside/foreign.ninja\n');(tmp_path/'CMakeCache.txt').write_text('fixture')
 with pytest.raises(Failure,match='include'):p.manifests(tmp_path)
 run=SimpleNamespace(env={},command=lambda *a,**k:pytest.fail('Oversized argv executed'))
 monkeypatch.setattr(p.os,'sysconf',lambda key:65540)
 with pytest.raises(Failure,match='argument'):p.command(run,'fixture',['/tool','large'])


def test_manifest_observation_is_cancellable(tmp_path):
 from types import SimpleNamespace
 def cancelled(_):raise Failure('cancelled fixture')
 with pytest.raises(Failure,match='cancelled'):p.manifests(tmp_path,SimpleNamespace(remaining=cancelled))


@pytest.mark.parametrize('path',['/home/runner/custom/libm.so','/workspace/repo/libm.so','/tmp/work/libm.so','/usr/local/lib/libm.so'],ids=['home','repository','work','unreviewed-system-location'])
def test_system_discovery_location_is_not_arbitrary(path):
 with pytest.raises(Failure,match='discovery locations'):p.system_location(Path(path),Path('/tmp/work'))


def test_system_discovery_checks_resolved_alias_without_reading_target(tmp_path):
 outside=tmp_path/'outside';outside.write_bytes(b'not a system input');alias=tmp_path/'alias';alias.symlink_to(outside)
 with pytest.raises(Failure,match='discovery locations'):p.system_location(alias.resolve(strict=True),tmp_path/'work')
