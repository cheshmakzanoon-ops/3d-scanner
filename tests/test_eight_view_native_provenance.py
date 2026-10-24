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


def phase_java_home():
 root=Path(os.environ.get('RF_PHASE_JAVA_HOME',os.environ.get('JAVA_HOME','/missing')))
 if not all((root/'bin'/name).is_file() for name in ('java','javac','jar')):
  if os.environ.get('RF_REQUIRE_NATIVE_PROVENANCE_TOOLS')=='1':pytest.fail('Required real phase JDK unavailable')
  pytest.skip('Real phase JDK unavailable; no packaging qualification')
 return root.resolve()


def phase_env(cmake,ninja):
 root=phase_java_home();env=dict(os.environ);env['JAVA_HOME']=str(root)
 env['PATH']=os.pathsep.join(map(str,[root/'bin',cmake.parent,ninja.parent]))+os.pathsep+env['PATH']
 return env


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
 (source/'main.cpp').write_text('#include "generated.hpp"\nextern "C" int result(){return VALUE;}\n')
 # Mirror actual upstream side effects AND target-order library prerequisites.
 # A single old wrapper request compiles core/imgproc before later generators.
 (source/'openclgen.cmake').write_text('file(WRITE "${OUT}.hpp" "#define OCL_VALUE 3\\n")\nfile(WRITE "${OUT}.cpp" "#include \\\"opencl_kernels_${MODULE}.hpp\\\"\\nint ocl_${MODULE}(){return OCL_VALUE;}\\n")\n')
 (source/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(bound_native CXX)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_CURRENT_BINARY_DIR}/lib)
set(stamp ${CMAKE_CURRENT_BINARY_DIR}/CMakeFiles/dephelper/gen_opencv_java_source)
add_custom_command(OUTPUT ${stamp}
 COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_CURRENT_BINARY_DIR}/CMakeFiles/dephelper
 COMMAND ${CMAKE_COMMAND} -DOUT=${CMAKE_CURRENT_BINARY_DIR} -P ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake
 COMMAND ${CMAKE_COMMAND} -E touch ${stamp}
 DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/generate.cmake)
add_custom_target(gen_opencv_java_source DEPENDS ${stamp})
foreach(module core imgproc features2d)
 set(out ${CMAKE_CURRENT_BINARY_DIR}/modules/${module}/opencl_kernels_${module})
 add_custom_command(OUTPUT ${out}.cpp
 COMMAND ${CMAKE_COMMAND} -E make_directory ${CMAKE_CURRENT_BINARY_DIR}/modules/${module}
 COMMAND ${CMAKE_COMMAND} -DOUT=${out} -DMODULE=${module} -P ${CMAKE_CURRENT_SOURCE_DIR}/openclgen.cmake
 DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/openclgen.cmake)
 add_library(opencv_${module} STATIC ${out}.cpp)
endforeach()
target_link_libraries(opencv_imgproc PRIVATE opencv_core)
target_link_libraries(opencv_features2d PRIVATE opencv_imgproc opencv_core)
add_library(opencv_java SHARED main.cpp)
target_include_directories(opencv_java PRIVATE ${CMAKE_CURRENT_BINARY_DIR})
add_dependencies(opencv_java gen_opencv_java_source)
target_link_libraries(opencv_java PRIVATE opencv_features2d opencv_imgproc opencv_core m)
''')
 configure_java_packaging(source)


def configure_java_packaging(source):
 """Synthetic Java payload and short copy/list scripts; actual pinned UseJava tools."""
 java=phase_java_home();jar=source/'modules/java/jar';jar.mkdir(parents=True);(source/'cmake').mkdir()
 (source/'cmake/copy_files.cmake').write_text('include("${CONFIG_FILE}")\nfile(COPY "${FIXTURE_JAVA_SOURCE}/" DESTINATION "${FIXTURE_JAVA_DEST}")\nfile(TOUCH "${DEPHELPER}")\n')
 (jar/'list_java_sources.cmake').write_text('file(GLOB_RECURSE sources "${OPENCV_JAVA_DIR}/*.java")\nstring(REPLACE ";" "\\n" lines "${sources}")\nfile(WRITE "${OUTPUT}" "${lines}\\n")\n')
 with (source/'generate.cmake').open('a') as f:
  f.write('file(MAKE_DIRECTORY "${OUT}/modules/java_bindings_generator/gen/java")\nfile(WRITE "${OUT}/modules/java_bindings_generator/gen/java/Fixture.java" "public final class Fixture { public static int value() { return 19; } }\\n")\n')
 with (source/'CMakeLists.txt').open('a') as f:
  f.write('\nset(Java_JAVA_EXECUTABLE "'+str(java/'bin/java')+'" CACHE FILEPATH "")\nset(Java_JAVAC_EXECUTABLE "'+str(java/'bin/javac')+'" CACHE FILEPATH "")\nset(Java_JAR_EXECUTABLE "'+str(java/'bin/jar')+'" CACHE FILEPATH "")\nset(Java_JAVADOC_EXECUTABLE "'+str(java/'bin/javadoc')+'" CACHE FILEPATH "")\nfind_package(Java 17 REQUIRED COMPONENTS Development)\ninclude(UseJava)\nadd_subdirectory(modules/java/jar)\nadd_dependencies(opencv_java opencv_java_jar)\n')
 (jar/'CMakeLists.txt').write_text('''set(OPENCV_JAVA_DIR "${CMAKE_CURRENT_BINARY_DIR}/opencv")
set(OPENCV_DEPHELPER "${CMAKE_BINARY_DIR}/CMakeFiles/dephelper")
file(MAKE_DIRECTORY "${OPENCV_JAVA_DIR}/java")
file(WRITE "${OPENCV_JAVA_DIR}/MANIFEST.MF" "Manifest-Version: 1.0\\n\\n")
set(config "${CMAKE_CURRENT_BINARY_DIR}/copyfiles-JAVA_SRC_COPY.cmake")
file(WRITE "${config}" "set(FIXTURE_JAVA_SOURCE \\\"${CMAKE_BINARY_DIR}/modules/java_bindings_generator/gen/java\\\")\\nset(FIXTURE_JAVA_DEST \\\"${OPENCV_JAVA_DIR}/java\\\")\\n")
add_custom_command(OUTPUT "${OPENCV_DEPHELPER}/opencv_java_jar_source_copy"
 COMMAND ${CMAKE_COMMAND} "-DCONFIG_FILE:PATH=${config}" "-DCOPYLIST_VAR:STRING=JAVA_SRC_COPY" "-DDEPHELPER=${OPENCV_DEPHELPER}/opencv_java_jar_source_copy" -P "${CMAKE_SOURCE_DIR}/cmake/copy_files.cmake"
 WORKING_DIRECTORY ${CMAKE_BINARY_DIR}
 DEPENDS "${CMAKE_SOURCE_DIR}/cmake/copy_files.cmake" gen_opencv_java_source "${OPENCV_DEPHELPER}/gen_opencv_java_source")
add_custom_target(opencv_java_jar_source_copy DEPENDS "${OPENCV_DEPHELPER}/opencv_java_jar_source_copy")
add_custom_command(OUTPUT "${OPENCV_DEPHELPER}/opencv_java_jar"
 BYPRODUCTS "${OPENCV_JAVA_DIR}/java_sources"
 DEPENDS opencv_java_jar_source_copy "${OPENCV_DEPHELPER}/opencv_java_jar_source_copy" "${OPENCV_JAVA_DIR}/MANIFEST.MF"
 COMMAND ${CMAKE_COMMAND} -E touch "${OPENCV_DEPHELPER}/opencv_java_jar"
 COMMAND ${CMAKE_COMMAND} -D OPENCV_JAVA_DIR="${OPENCV_JAVA_DIR}/java" -D OUTPUT="${OPENCV_JAVA_DIR}/java_sources" -P "${CMAKE_CURRENT_SOURCE_DIR}/list_java_sources.cmake")
add_custom_target(opencv_java_jar_sources DEPENDS "${OPENCV_DEPHELPER}/opencv_java_jar")
list(APPEND CMAKE_JAVA_COMPILE_FLAGS -encoding utf-8)
add_jar(opencv_java_jar SOURCES "@${OPENCV_JAVA_DIR}/java_sources" MANIFEST "${OPENCV_JAVA_DIR}/MANIFEST.MF" OUTPUT_NAME "opencv-4120" OUTPUT_DIR "${CMAKE_BINARY_DIR}/bin")
add_dependencies(opencv_java_jar opencv_java_jar_sources)
''')


@pytest.mark.parametrize('version',p.VERSIONS)
def test_real_current_graph_and_system_link_provenance(tmp_path,version):
 cmake,ninja,cc,cxx=native_tools(version);env=phase_env(cmake,ninja)
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
   before.update(c.prebuild_snapshot(run,[source,build]))
   if domain=='opencv':
    source_snapshot=c.source_inventory(source)
    def source_check():
     if c.source_inventory(source)!=source_snapshot:raise Failure('Fixture source changed during phase')
    p.opencv_phases(run,state,cmake,before,source_check=source_check)
    phases=state['generation']['phases'];rules=p.phase_rules(state['graphs']['opencv'])
    assert [(row['id'],row['kind']) for row in phases]==[(name,kind) for name,kind,_ in p.PHASES]
    packages=p.package_outputs(state['graphs']['opencv'])
    for row in phases:
     assert row['records']
     assert all((rules[path]=='CUSTOM_COMMAND')==(row['kind']=='G') or (row['id']=='build-java' and path in packages) for path in row['records'])
    assert packages<=set(phases[-1]['records'])
    assert not packages.intersection(state['generation']['frozen'])
    import zipfile
    with zipfile.ZipFile(build/'bin/opencv-4120.jar') as jar:assert 'Fixture.class' in jar.namelist()
    assert state['generation']['firstAdmission'][str(build/'generated.hpp')]==0
    for module,phase_index in [('core',0),('imgproc',2),('features2d',4)]:
     header=str(build/'modules'/module/('opencl_kernels_'+module+'.hpp'))
     assert state['generation']['firstAdmission'][header]==phase_index
     assert header not in state['generation']['initialPaths']
     assert phases[phase_index+1]['inputs'][header]['scope']=='PRECONSUMPTION'
   else:
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
  packaging=state['generation']['packaging']
  for index in range(len(p.PACKAGE_GROUPS)):
   raw=(run.work/'logs'/('native-opencv-package-recipe-'+str(index)+'.raw.log')).read_bytes()
   assert p.package_recipe(raw,index,packaging,out/'build')==packaging['recipes'][index]
   for bad in (raw+b'\nextra-command\n',raw.rstrip()+b' && /unapproved/tool\n',raw.rstrip()+b' ; /unapproved/tool\n',raw.rstrip()+b' | /unapproved/tool\n',raw.rstrip()+b' $(unapproved)\n',raw.replace(str(cmake).encode(),b'/unapproved/cmake'),b'x'*65537):
    with pytest.raises(Failure):p.package_recipe(bad,index,packaging,out/'build')
  assert not set(packaging['outputs']).intersection(state['generation']['frozen'])
  # Mutate only private fixture scripts, never shared official CMake/JDK files.
  for key in ('copy','list'):
   script=Path(packaging['inputs'][key]['path']);original=script.read_bytes();stamp=script.stat()
   script.write_bytes(original+b'# fixture drift\n')
   with pytest.raises(Failure):p.validate(run,state,consumed,before,'a'*40)
   script.write_bytes(original);os.utime(script,ns=(stamp.st_atime_ns,stamp.st_mtime_ns))
   assert p.validate(run,state,consumed,before,'a'*40)==rolemap
  # Simulate changed installed-module identity without writing shared tool files.
  from unittest.mock import patch
  identity=p.identity;module=packaging['inputs']['UseJava']['path'];seen=[]
  def changed_module(path):
   result=identity(path)
   if str(path)==module:seen.append(module);result={**result,'sha256':'0'*64}
   return result
  with patch.object(p,'identity',changed_module):
   with pytest.raises(Failure):p.validate(run,state,consumed,before,'a'*40)
  assert seen
  for output in packaging['outputs']:
   if output in rolemap:assert rolemap[output]['scope']=='CURRENT_SUCCESSFUL_JAVA_PACKAGE_POSTBUILD_NOT_PRECONSUMPTION'
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
  wrong=copy.deepcopy(state);wrong['generation']['phases'][0]['targets'].append('not-reviewed')
  with pytest.raises(Failure):p.validate(run,wrong,consumed,before,'a'*40)
  stale=copy.deepcopy(state);stale['graphs']['opencv']['absent'].remove(str(out/'build/lib/libopencv_core.a'))
  with pytest.raises(Failure,match='predates'):p.graph_after(run,stale,out/'build','opencv')
  # Restoring the exact original retained log is test-only, after the refusal above.
  (out/'native-opencv-build-log.txt').write_bytes((out/'build/.ninja_log').read_bytes())
  output=out/'build/lib/libopencv_core.a';original=output.read_bytes();output.write_bytes(original+b'changed')
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
 state={'graphs':{},'systemLink':{'inputs':{}},'generation':{'packaging':{'outputs':[]}}};before={};consumed={}
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
  p.phase_cone({'nodes':{}},p.PHASES[0][2])


def test_real_failed_generator_cannot_produce_a_generation_attestation(tmp_path):
 cmake,ninja,cc,cxx=native_tools('1.13.2');env=phase_env(cmake,ninja)
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=240);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);source=root/'opencv';configure_opencv_side_effects(source)
  (source/'generate.cmake').write_text('message(FATAL_ERROR "intentional fixture generator failure")\n')
  build=out/'build';run.command('opencv-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
  p.graph_before(run,state,build,'opencv_java','opencv')
  before=c.prebuild_snapshot(run,[source,build])
  with pytest.raises(Failure):p.opencv_phases(run,state,cmake,before)
  assert not any(r['label']=='native-opencv-phase-build-core' for r in run.commands)
  assert next(r for r in run.commands if r['label']=='native-opencv-phase-generate-java-core')['exit']!=0
 finally:run.cleanup()


def test_existing_opencv_budget_is_partitioned_not_added():
 import inspect
 source=inspect.getsource(p.opencv_phases);caller=inspect.getsource(c.build)
 assert 'deadline=min(deadline,run.clock()+run.remaining(1620)) if deadline is not None else run.clock()+run.remaining(1620)' in source
 assert 'generation_remaining=120.0' in source
 assert 'limit=min(limit,generation_remaining)' in source
 assert 'generation_remaining-=elapsed' in source
 assert caller.index('opencv_deadline=run.clock()+run.remaining(1620)')<caller.index('immutable_before.update(prebuild_snapshot(run,[source,builddir]))')<caller.index('native_provenance.opencv_phases(')
 assert 'deadline=opencv_deadline' in caller
 assert "run.command('opencv-build'" not in caller


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


def generation_alias_graph():
 base='/fixture/build';nodes={};paths={};producers={}
 def node(name,rule,inputs=()):
  nodes[name]={'rule':rule,'inputs':[{'name':i,'kind':'explicit'} for i in inputs]};paths[name]=base+'/'+name
  if rule not in (None,'phony'):producers[name]='edge:'+name
 node('source','');nodes['source']['rule']=None
 node('java.stamp','CUSTOM_COMMAND',['source'])
 node('nested-java','phony',['java.stamp'])
 node(p.GEN_TARGETS[0],'phony',['nested-java'])
 for name in p.GEN_TARGETS[1:]:node(name,'CUSTOM_COMMAND',['source'])
 node('opencv_java','phony',['nested-java',*p.GEN_TARGETS[1:]])
 return {'nodes':nodes,'paths':paths,'producers':producers,'reachable':p.reachable(nodes,paths,producers,'opencv_java')}


def test_only_approved_selector_own_phony_identity_can_differ():
 graph=generation_alias_graph();allowed,bindings=p.generation_selection(graph)
 assert graph['paths'][p.GEN_TARGETS[0]] not in allowed
 assert bindings[p.GEN_TARGETS[0]]['selectorOnlyPhonyException'] is True
 assert bindings[p.GEN_TARGETS[0]]['producerEdges']==['edge:java.stamp']
 assert all(not bindings[name]['selectorOnlyPhonyException'] for name in p.GEN_TARGETS[1:])
 assert allowed<=set(graph['reachable'])


@pytest.mark.parametrize('case',['leaf','producer','nested-phony','nonphony-selector','no-producer'],ids=str)
def test_selector_equivalence_never_expands_dependency_or_producer_cone(case):
 graph=generation_alias_graph();selector=p.GEN_TARGETS[0]
 if case=='nonphony-selector':graph['nodes'][selector]['rule']='CUSTOM_COMMAND';graph['producers'][selector]='edge:selector'
 elif case=='no-producer':graph['nodes'][selector]['inputs']=[{'name':'source','kind':'explicit'}]
 else:
  rule={'leaf':None,'producer':'CUSTOM_COMMAND','nested-phony':'phony'}[case]
  graph['nodes']['extra']={'rule':rule,'inputs':[] if case=='leaf' else [{'name':'java.stamp','kind':'explicit'}]}
  graph['paths']['extra']='/fixture/build/extra'
  if rule=='CUSTOM_COMMAND':graph['producers']['extra']='edge:extra'
  graph['nodes'][selector]['inputs'].append({'name':'extra','kind':'explicit'})
 with pytest.raises(Failure,match='outside selected|no concrete producer'):p.generation_selection(graph)


def test_actual_e1fee_graph_replay_binds_selector_without_new_producers():
 import zipfile
 filename=os.environ.get('RF_NATIVE_REPLAY_ZIP')
 if not filename:
  if os.environ.get('RF_REQUIRE_NATIVE_REPLAY')=='1':pytest.fail('Required retained actual graph replay artifact missing')
  pytest.skip('Retained actual-run artifact unavailable; no hosted replay claim')
 archive=Path(filename)
 assert archive.stat().st_size<=32*1024**2
 assert sha(archive)=='594ac0907f5f99633a242a6ac4ab3229b63263178c2b698586f87cc84911f075'
 with zipfile.ZipFile(archive) as z:
  def raw(name):
   assert z.getinfo(name).file_size<=8*1024**2
   return z.read(name)
  names,producers=p.graph_info(raw('logs/native-opencv-graph.raw.log'));nodes={}
  for index in range(0,len(names),64):nodes.update(p.parse_query(raw('logs/native-opencv-query-'+str(index//64)+'.raw.log'),names[index:index+64]))
  build=Path('/home/runner/work/_temp/rf-eight-work/capability/build');paths={n:p.canonical(n,build) for n in names}
  p.aliases_for(nodes,paths,producers)
  selected=p.reachable(nodes,paths,producers,'opencv_java')
  graph={'nodes':nodes,'paths':paths,'producers':producers,'reachable':selected}
  allowed,bindings=p.generation_selection(graph)
  assert len(names)==1810 and len(selected)==754
  assert set(p.reachable(nodes,paths,producers,p.GEN_TARGETS[0]))-set(selected)=={paths[p.GEN_TARGETS[0]]}
  assert bindings[p.GEN_TARGETS[0]]['selectorOnlyPhonyException'] is True
  assert allowed<=set(selected)
  assert all(not bindings[t]['selectorOnlyPhonyException'] for t in p.GEN_TARGETS[1:])
  # All recursive inputs are retained; mutate actual graph with an extra leaf and refuse.
  graph=copy.deepcopy(graph);graph['nodes']['foreign']={'rule':None,'inputs':[]};graph['paths']['foreign']='/foreign/unapproved'
  graph['nodes'][p.GEN_TARGETS[0]]['inputs'].append({'name':'foreign','kind':'explicit'})
  with pytest.raises(Failure,match='outside selected'):p.generation_selection(graph)


def test_real_upstream_like_wrapper_also_compiles_product_prerequisites(tmp_path):
 """Reproduce why the former all-generators request was not pre-compilation."""
 import subprocess
 cmake,ninja,_,cxx=native_tools('1.13.2');source=tmp_path/'source';build=tmp_path/'build'
 configure_opencv_side_effects(source)
 subprocess.run(list(map(str,[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'])),check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 query=subprocess.run(list(map(str,[ninja,'-C',build,'-t','query','modules/imgproc/opencl_kernels_imgproc.cpp','modules/features2d/opencl_kernels_features2d.cpp'])),check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env()).stdout
 assert b'    || lib/libopencv_core.a' in query and b'    || lib/libopencv_imgproc.a' in query
 targets=['gen_opencv_java_source',*[f'modules/{m}/opencl_kernels_{m}.cpp' for m in ('core','imgproc','features2d')]]
 subprocess.run(list(map(str,[ninja,'-C',build,*targets])),check=True,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=30,env=native_compile_env())
 records=p.ninja_log((build/'.ninja_log').read_bytes(),build)
 assert str(build/'lib/libopencv_core.a') in records and str(build/'lib/libopencv_imgproc.a') in records
 assert any(name.endswith('.cpp.o') for name in records)
 assert not (build/'lib/libopencv_features2d.a').exists()


def test_actual_0104_graph_requires_staged_generation_not_selector_only():
 import zipfile
 filename=os.environ.get('RF_PHASE_REPLAY_ZIP')
 if not filename:
  if os.environ.get('RF_REQUIRE_NATIVE_REPLAY')=='1':pytest.fail('Required retained staged-generation replay artifact missing')
  pytest.skip('Retained actual-run artifact unavailable; no hosted replay claim')
 archive=Path(filename);assert archive.stat().st_size<=32*1024**2
 assert sha(archive)=='02e1749c3857fa47b2009d7aad880139d6ac25941538aa01b174a99933d6f912'
 with zipfile.ZipFile(archive) as z:
  def raw(name):
   assert z.getinfo(name).file_size<=8*1024**2
   return z.read(name)
  names,producers=p.graph_info(raw('logs/native-opencv-graph.raw.log'));nodes={}
  for index in range(0,len(names),64):nodes.update(p.parse_query(raw('logs/native-opencv-query-'+str(index//64)+'.raw.log'),names[index:index+64]))
  build=Path('/home/runner/work/_temp/rf-eight-work/capability/build');paths={n:p.canonical(n,build) for n in names}
  p.aliases_for(nodes,paths,producers)
  def rules_for(target):
   cone=set(p.reachable(nodes,paths,producers,target))
   return {nodes[n]['rule'] for n in names if paths[n] in cone and nodes[n]['rule'] not in (None,'phony')}
  assert rules_for('CMakeFiles/dephelper/gen_opencv_java_source')=={'CUSTOM_COMMAND'}
  assert rules_for('modules/core/opencl_kernels_core.cpp')=={'CUSTOM_COMMAND'}
  imgproc=nodes['modules/imgproc/opencl_kernels_imgproc.cpp']['inputs']
  features=nodes['modules/features2d/opencl_kernels_features2d.cpp']['inputs']
  assert {'name':'lib/libopencv_core.a','kind':'order'} in imgproc
  assert {'name':'lib/libopencv_core.a','kind':'order'} in features
  assert {'name':'lib/libopencv_imgproc.a','kind':'order'} in features
  assert any(rule.startswith('CXX_COMPILER') for rule in rules_for('modules/imgproc/opencl_kernels_imgproc.cpp'))
  assert any(rule.startswith('CXX_STATIC_LIBRARY_LINKER') for rule in rules_for('modules/features2d/opencl_kernels_features2d.cpp'))
  # Exact archive cones never require a later OpenCL generator.
  for module,expected in [('core',{'core'}),('imgproc',{'core','imgproc'})]:
   cone=set(p.reachable(nodes,paths,producers,'lib/libopencv_'+module+'.a'))
   actual={m for m in ('core','imgproc','features2d') if str(build/f'modules/{m}/opencl_kernels_{m}.cpp') in cone}
   assert actual==expected
  # Source-derived epoch simulation, not a claim that the timed-out run finished.
  graph={'buildDirectory':str(build),'nodes':nodes,'paths':paths,'producers':producers,'reachable':p.reachable(nodes,paths,producers,'opencv_java'),'baselineLogOutputs':[]}
  graph['absent']=sorted(set(paths[n] for n in names if nodes[n]['rule'] not in (None,'phony')))
  packages=p.package_outputs(graph);previous={};counts=[]
  for index,(phase,kind,targets) in enumerate(p.PHASES):
   cone=p.phase_precheck(graph,kind,targets,previous,packages)
   outputs={paths[n] for n in names if paths[n] in cone and nodes[n]['rule'] not in (None,'phony')}
   current=dict(previous)
   for path in outputs-set(previous):current[path]={'startMs':0,'endMs':1,'mtimeNs':1,'commandHash':'source-derived-simulation','outputs':[n for n in names if paths[n]==path]}
   new=p.phase_delta(graph,kind,cone,previous,current,1,packages,allow_packages=(phase=='build-java'))
   assert not packages.intersection(new) or phase=='build-java'
   counts.append(len(new));previous=current
  assert counts==[2,92,1,68,1,42]
  assert packages<=set(previous) and len(packages)==6


def phase_fixture_graph(tmp_path):
 src=tmp_path/'source.cpp';src.write_text('int fixture;')
 generated=tmp_path/'generated.cpp';generated.write_text('int generated;')
 obj=tmp_path/'object.o';obj.write_bytes(b'fixture object')
 paths={'source':str(src),'generated':str(generated),'object':str(obj),'target':str(tmp_path/'target')}
 nodes={'source':{'rule':None,'inputs':[]},'generated':{'rule':'CUSTOM_COMMAND','inputs':[{'name':'source','kind':'explicit'}]},'object':{'rule':'COMPILE','inputs':[{'name':'generated','kind':'explicit'}]},'target':{'rule':'phony','inputs':[{'name':'object','kind':'explicit'}]}}
 return {'nodes':nodes,'paths':paths,'producers':{'generated':'edge:g','object':'edge:o'},'reachable':list(paths.values()),'absent':[str(generated),str(obj)],'baselineLogOutputs':[],'dependencyRules':{'CUSTOM_COMMAND':None,'COMPILE':'gcc'}}


def test_generation_cannot_run_unbuilt_product_prerequisite(tmp_path):
 graph=phase_fixture_graph(tmp_path)
 with pytest.raises(Failure,match='prerequisite not previously produced'):p.phase_precheck(graph,'G',['target'],{})
 assert p.phase_precheck(graph,'G',['target'],{graph['paths']['object']:object()})


def test_compilation_cannot_first_run_unfrozen_generator(tmp_path):
 graph=phase_fixture_graph(tmp_path)
 with pytest.raises(Failure,match='prerequisite not previously produced'):p.phase_precheck(graph,'B',['target'],{})
 assert p.phase_precheck(graph,'B',['target'],{graph['paths']['generated']:object()})


@pytest.mark.parametrize('case',['rerun','missing-prior','unknown','wrong-kind','outside-cone','stale','late-record'],ids=str)
def test_phase_log_epoch_refuses_dirty_or_unowned_records(tmp_path,case):
 graph=phase_fixture_graph(tmp_path);generated=graph['paths']['generated'];obj=graph['paths']['object'];r={'startMs':0,'endMs':1,'mtimeNs':1,'commandHash':'abc','outputs':['generated']}
 previous={generated:dict(r)};current={generated:dict(r),obj:dict(r,outputs=['object'])};cone=set(graph['paths'].values());kind='B'
 if case=='rerun':current[generated]['mtimeNs']=2
 elif case=='missing-prior':current.pop(generated)
 elif case=='unknown':current[str(tmp_path/'foreign')]=dict(r)
 elif case=='wrong-kind':kind='G'
 elif case=='outside-cone':cone.remove(obj)
 elif case=='stale':graph['absent'].remove(obj)
 elif case=='late-record':current[obj]['endMs']=999999
 with pytest.raises(Failure):p.phase_delta(graph,kind,cone,previous,current,1)


@pytest.mark.parametrize('case',['early-use','changed-bound','changed-producer','missing-deps'],ids=str)
def test_first_consumer_does_not_accept_later_header_snapshot(tmp_path,case):
 graph=phase_fixture_graph(tmp_path);obj=graph['paths']['object'];header=tmp_path/'future.hpp';header.write_text('future header');name=str(header)
 deps={obj:[name]};bound={};prior={}
 if case=='changed-bound':bound[name]='0'*64
 elif case=='changed-producer':prior[name]={'sha256':'0'*64}
 elif case=='missing-deps':deps={}
 # Existence or a future phase's ability to snapshot this file is not prior binding.
 with pytest.raises(Failure):p.phase_dependencies(graph,deps,{obj:{}},bound,prior)


def test_first_consumer_distinguishes_prior_producer_from_immutable_input(tmp_path):
 graph=phase_fixture_graph(tmp_path);obj=graph['paths']['object'];generated=graph['paths']['generated'];digest=sha(generated)
 assert p.phase_dependencies(graph,{obj:[generated]},{obj:{}},{generated:digest},{})[generated]['scope']=='PRECONSUMPTION'
 assert p.phase_dependencies(graph,{obj:[generated]},{obj:{}},{},{generated:{'sha256':digest}})[generated]['scope']=='PRIOR_SUCCESSFUL_PRODUCER'


def test_real_compile_created_future_header_is_not_backfilled(tmp_path):
 """A build-time side effect must fail even though a later snapshot could hash it."""
 cmake,ninja,cc,cxx=native_tools('1.13.2');env=phase_env(cmake,ninja)
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=240);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);source=root/'opencv';configure_opencv_side_effects(source);build=out/'build';late=build/'not-yet-bound.hpp'
  launcher=source/'fixture-launcher.py'
  launcher.write_text('import os,sys\nfrom pathlib import Path\nPath(sys.argv[1]).write_text("#define LATE_VALUE 7\\n")\nos.execv(sys.argv[2],sys.argv[2:])\n')
  with (source/'CMakeLists.txt').open('a') as f:
   f.write('\nset_property(TARGET opencv_core PROPERTY CXX_COMPILER_LAUNCHER "'+str(Path(sys.executable).resolve())+';'+str(launcher)+';'+str(late)+'")\n')
  with (source/'openclgen.cmake').open('a') as f:
   f.write('if(MODULE STREQUAL "core")\nfile(APPEND "${OUT}.cpp" "#include \\\"'+str(late)+'\\\"\\n")\nendif()\n')
  run.command('opencv-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
  p.graph_before(run,state,build,'opencv_java','opencv');before=c.prebuild_snapshot(run,[source,build]);before[str(Path('/usr/include/stdc-predef.h').resolve())]=sha('/usr/include/stdc-predef.h')
  assert not late.exists() and str(late) not in before
  with pytest.raises(Failure,match='before first binding'):p.opencv_phases(run,state,cmake,before)
  assert late.is_file() and str(late) not in before
  assert not any(r['label']=='native-opencv-phase-generate-imgproc' for r in run.commands)
  assert next(r for r in run.commands if r['label']=='native-opencv-phase-build-core')['exit']==0
 finally:run.cleanup()


@pytest.mark.parametrize('case',['valid','producer-late','not-in-cone','self','custom-generator'],ids=str)
def test_same_compile_phase_output_is_postbuild_role_not_source_snapshot(tmp_path,case):
 source=tmp_path/'main.cpp';source.write_text('int f;');obj=tmp_path/'main.o';obj.write_bytes(b'object');archive=tmp_path/'lib.a';archive.write_bytes(b'archive')
 paths={'source':str(source),'object':str(obj),'archive':str(archive)}
 nodes={'source':{'rule':None,'inputs':[]},'object':{'rule':'COMPILE','inputs':[{'name':'source','kind':'explicit'}]},'archive':{'rule':'ARCHIVE','inputs':[{'name':'object','kind':'explicit'}]}}
 graph={'nodes':nodes,'paths':paths,'producers':{'object':'edge:object','archive':'edge:archive'},'reachable':list(paths.values()),'dependencyRules':{'COMPILE':'gcc','ARCHIVE':None}}
 new={str(obj):{'startMs':0,'endMs':1},str(archive):{'startMs':2,'endMs':3}};deps={str(obj):[str(source)],str(archive):[str(obj)]};bound={str(source):sha(source)}
 if case=='producer-late':new[str(obj)]['endMs']=3
 elif case=='not-in-cone':nodes['archive']['inputs']=[]
 elif case=='self':deps[str(archive)]=[str(archive)]
 elif case=='custom-generator':nodes['object']['rule']='CUSTOM_COMMAND';graph['dependencyRules']['CUSTOM_COMMAND']=None
 if case=='valid':
  result=p.phase_dependencies(graph,deps,new,bound,{})
  assert result[str(obj)]['scope']=='CURRENT_PHASE_SUCCESSFUL_PRODUCER_POSTBUILD_NOT_PRECONSUMPTION'
  assert str(obj) not in bound
 else:
  with pytest.raises(Failure):p.phase_dependencies(graph,deps,new,bound,{})


@pytest.mark.parametrize('case',['source-drift','prior-drift','system-drift','unbound'],ids=str)
def test_phase_immutable_leaves_are_verified_before_execution(tmp_path,case):
 graph=phase_fixture_graph(tmp_path);source=graph['paths']['source'];bound={};prior={};system={}
 if case=='source-drift':bound[source]='0'*64
 elif case=='prior-drift':prior[source]={'sha256':'0'*64}
 elif case=='system-drift':system[source]='0'*64
 with pytest.raises(Failure):p.phase_bound_leaves(graph,set(graph['reachable']),bound,prior,system)


def test_phase_system_link_member_has_distinct_prebound_role(tmp_path):
 graph=phase_fixture_graph(tmp_path);obj=graph['paths']['object'];system_input=tmp_path/'fixture-crt.o';system_input.write_bytes(b'explicit fixture system identity')
 # Parser/role unit only: this temporary path is not an admitted installed-system provider.
 name=str(system_input);digest=sha(system_input);before={}
 result=p.phase_dependencies(graph,{obj:[name]},{obj:{}},before,{},system={name:digest})
 assert result[name]['scope']=='SYSTEM_LINK_PREBUILD' and name not in before
 system_input.write_bytes(b'drift')
 with pytest.raises(Failure):p.phase_dependencies(graph,{obj:[name]},{obj:{}},before,{},system={name:digest})


def package_fixture_graph():
 build=Path('/fixture/build');nodes={};paths={};producers={}
 for index,group in enumerate(p.PACKAGE_GROUPS):
  for name in group:
   nodes[name]={'rule':'CUSTOM_COMMAND','inputs':[]};paths[name]=str(build/name);producers[name]='edge:package-'+str(index)
 nodes['opencv_java']={'rule':'phony','inputs':[{'name':group[0],'kind':'explicit'} for group in p.PACKAGE_GROUPS]};paths['opencv_java']=str(build/'opencv_java')
 return {'buildDirectory':str(build),'nodes':nodes,'paths':paths,'producers':producers,'reachable':list(paths.values()),'absent':list(paths.values()),'baselineLogOutputs':[]}


@pytest.mark.parametrize('case',['missing','wrong-rule','extra-sibling','split-siblings','unselected','shared-edge'],ids=str)
def test_java_package_binding_requires_exact_declared_sibling_groups(case):
 graph=package_fixture_graph();name=p.PACKAGE_GROUPS[0][0]
 if case=='missing':del graph['nodes'][name]
 elif case=='wrong-rule':graph['nodes'][name]['rule']='NATIVE_COMPILE'
 elif case=='extra-sibling':
  graph['nodes']['unexpected']={'rule':'CUSTOM_COMMAND','inputs':[]};graph['paths']['unexpected']='/fixture/build/unexpected';graph['producers']['unexpected']=graph['producers'][name]
 elif case=='split-siblings':graph['producers'][p.PACKAGE_GROUPS[1][1]]='edge:split'
 elif case=='unselected':graph['reachable'].remove(graph['paths'][name])
 elif case=='shared-edge':graph['producers'][name]=graph['producers'][p.PACKAGE_GROUPS[1][0]]
 with pytest.raises(Failure):p.package_outputs(graph)


def test_java_packages_are_final_build_only_not_native_generation():
 graph=package_fixture_graph();packages=p.package_outputs(graph);assert len(packages)==6
 assert p.phase_precheck(graph,'B',('opencv_java',),{},packages)
 with pytest.raises(Failure):p.phase_precheck(graph,'G',('opencv_java',),{},packages)
 name=p.PACKAGE_GROUPS[0][0]
 with pytest.raises(Failure):p.phase_precheck(graph,'B',(name,),{},packages)
 record={'startMs':0,'endMs':1,'mtimeNs':1,'commandHash':'fixture','outputs':[name]};current={graph['paths'][name]:record}
 assert p.phase_delta(graph,'B',set(graph['reachable']),{},current,1,packages,allow_packages=True)==current
 with pytest.raises(Failure):p.phase_delta(graph,'G',set(graph['reachable']),{},current,1,packages)
 with pytest.raises(Failure):p.phase_delta(graph,'B',set(graph['reachable']),{},current,1)
 with pytest.raises(Failure):p.phase_delta(graph,'B',set(graph['reachable']),{},current,1,packages)


def test_actual_phase_wiring_rejects_java_package_dependency_in_generation(tmp_path):
 """An altered G cone must refuse packaging even though those roles are known."""
 cmake,ninja,cc,cxx=native_tools('1.13.2');env=phase_env(cmake,ninja)
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=240);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);source=root/'opencv';configure_opencv_side_effects(source);build=out/'build'
  # Exact known Java package chain, but moved before core generation by a target-order dependency.
  # No cycle: package inputs require Java generation, not the native core library.
  with (source/'CMakeLists.txt').open('a') as f:f.write('\nadd_dependencies(opencv_core opencv_java_jar)\n')
  run.command('opencv-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
  p.graph_before(run,state,build,'opencv_java','opencv');before=c.prebuild_snapshot(run,[source,build])
  packages=p.package_outputs(state['graphs']['opencv'])
  cone=p.phase_cone(state['graphs']['opencv'],p.PHASES[0][2]);assert packages<=cone
  with pytest.raises(Failure,match='prerequisite not previously produced'):p.opencv_phases(run,state,cmake,before)
  assert not any(r['label'].startswith('native-opencv-phase-') or r['label']=='opencv-build' for r in run.commands)
  assert not (build/'.ninja_log').exists()
 finally:run.cleanup()


def test_real_expired_outer_deadline_refuses_before_native_phase(tmp_path):
 cmake,ninja,cc,cxx=native_tools('1.13.2');env=phase_env(cmake,ninja)
 root=tmp_path/'repo';root.mkdir();run=Runner(root,tmp_path/'work',env,seconds=240);out=run.work/'capability';out.mkdir()
 try:
  state=p.begin(run,'a'*40,cxx,cc,ninja,out);source=root/'opencv';configure_opencv_side_effects(source);build=out/'build'
  run.command('opencv-configure',[cmake,'-S',source,'-B',build,'-G','Ninja',f'-DCMAKE_CXX_COMPILER={cxx}',f'-DCMAKE_MAKE_PROGRAM={ninja}'],env=native_compile_env(env))
  p.graph_before(run,state,build,'opencv_java','opencv');before=c.prebuild_snapshot(run,[source,build])
  with pytest.raises(Failure,match='shared native phase budget exhausted'):
   p.opencv_phases(run,state,cmake,before,deadline=run.clock()-1)
  assert not any(r['label'].startswith('native-opencv-phase-') or r['label']=='opencv-build' for r in run.commands)
  assert not (build/'.ninja_log').exists()
 finally:run.cleanup()
