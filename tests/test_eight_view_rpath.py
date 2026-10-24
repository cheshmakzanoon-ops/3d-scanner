"""Real small CMake ELF fixture, not OpenCV/JNI or reconstruction qualification."""
import ast,ctypes,hashlib,inspect,json,os,re,shutil,subprocess,sys
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import capability as c
from tools.eight_view_host.runtime import Failure
from tools.native_process import native_compile_env
PREFIX='/home/runner/work/_temp/rf-eight-work/capability/not-installed'


def tools():
 cmake=os.environ.get('RF_CMAKE_EXECUTABLE') or shutil.which('cmake')
 found={'cmake':cmake,**{name:shutil.which(name) for name in ('cc','make','readelf','ldd')}}
 if not all(found.values()):
  if os.environ.get('RF_REQUIRE_RPATH_TOOLS')=='1':pytest.fail('Required real CMake/ELF fixture tools unavailable')
  pytest.skip('Actual CMake/ELF toolchain unavailable; no runtime qualification')
 return found


def flag_from_production():
 source=inspect.getsource(c.build);tree=ast.parse(source)
 flags=[node.value for node in ast.walk(tree) if isinstance(node,ast.Constant) and isinstance(node.value,str) and node.value.startswith('-DCMAKE_SKIP_RPATH')]
 assert flags==['-DCMAKE_SKIP_RPATH=ON']
 opencv=source.split('args=[cmake',1)[1].split('configure,_=',1)[0]
 assert flags[0] in opencv
 scanner=source.split("run.command('scanner-configure'",1)[1]
 assert 'CMAKE_SKIP_RPATH' not in scanner
 return flags[0]


def test_opencv_only_configures_no_rpath_and_parser_remains_strict():
 assert flag_from_production()=='-DCMAKE_SKIP_RPATH=ON'
 source=inspect.getsource(c.parse_elf_closure)
 assert "if rpath or runpath!=expected:raise Failure('Unexpected ELF RPATH/RUNPATH')" in source
 assert "expected=[str(engine_dir).encode()] if engine_dir else []" in source


def test_real_cmake_install_padding_removed_at_link_time(tmp_path):
 t=tools();source=tmp_path/'source';source.mkdir();logs={}
 (source/'CMakeLists.txt').write_text('''cmake_minimum_required(VERSION 3.20)
project(rpath_placeholder C)
set(CMAKE_INSTALL_RPATH "${CMAKE_INSTALL_PREFIX}/lib")
set(CMAKE_INSTALL_RPATH_USE_LINK_PATH TRUE)
add_library(rpath_fixture SHARED fixture.c)
target_link_libraries(rpath_fixture PRIVATE m)
install(TARGETS rpath_fixture LIBRARY DESTINATION lib)
''')
 (source/'fixture.c').write_text('#include <math.h>\n#include <stdio.h>\ndouble probe(double value) { puts("fixture"); return cos(value); }\n')
 def command(label,args,compile=False):
  result=subprocess.run(list(map(str,args)),cwd=tmp_path,stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,timeout=60,env=native_compile_env() if compile else None)
  logs[label]=result.stdout;assert result.returncode==0,(label,result.stdout.decode(errors='replace'));return result.stdout
 version=command('cmake-version',[t['cmake'],'--version'])
 records={}
 for mode,flags in [('before',[]),('fixed',[flag_from_production()])]:
  build=tmp_path/mode
  command(mode+'-configure',[t['cmake'],'-S',source,'-B',build,'-G','Unix Makefiles',f"-DCMAKE_MAKE_PROGRAM={t['make']}",f"-DCMAKE_C_COMPILER={t['cc']}",f'-DCMAKE_INSTALL_PREFIX={PREFIX}',*flags],True)
  command(mode+'-build',[t['cmake'],'--build',build,'--parallel','2'],True)
  library=build/'librpath_fixture.so'
  header=command(mode+'-header',[t['readelf'],'-h',library]);dynamic=command(mode+'-dynamic',[t['readelf'],'-d',library]);closure=command(mode+'-ldd',[t['ldd'],'-r',library])
  needed=re.findall(rb'\(NEEDED\).*?\[([^]]+)\]',dynamic)
  records[mode]={'path':str(library),'sha256':hashlib.sha256(library.read_bytes()).hexdigest(),'needed':[value.decode() for value in needed]}
  assert b'not found' not in closure and b'undefined symbol' not in closure
  if mode=='before':
   assert re.findall(rb'\(RUNPATH\).*?\[([^]]*)\]',dynamic)==[b':'*len(PREFIX+'/lib')]
   assert len(PREFIX+'/lib')==66
   with pytest.raises(Failure,match='RPATH/RUNPATH'):c.parse_elf_closure(library,header,dynamic,closure)
  else:
   assert not re.search(rb'\((?:RPATH|RUNPATH)\)',dynamic)
   proof=c.parse_elf_closure(library,header,dynamic,closure);assert proof['runpath']==[]
   loaded=ctypes.CDLL(str(library));loaded.probe.argtypes=[ctypes.c_double];loaded.probe.restype=ctypes.c_double
   assert loaded.probe(0.0)==1.0
   # Empty components remain rejected even after the construction fix.
   for invalid in (b'',b':',b'::::',b'/tmp:'):
    bad=dynamic+b' (RUNPATH) Library runpath: ['+invalid+b']\n'
    with pytest.raises(Failure,match='RPATH/RUNPATH'):c.parse_elf_closure(library,header,bad,closure)
   install=tmp_path/'installed'
   command('fixed-install',[t['cmake'],'--install',build,'--prefix',install],True)
   installed=install/'lib/librpath_fixture.so'
   installed_dynamic=command('installed-dynamic',[t['readelf'],'-d',installed])
   assert not re.search(rb'\((?:RPATH|RUNPATH)\)',installed_dynamic)
   assert re.findall(rb'\(NEEDED\).*?\[([^]]+)\]',installed_dynamic)==needed
 assert records['before']['needed']==records['fixed']['needed']
 evidence=os.environ.get('RF_RPATH_EVIDENCE_DIR')
 if evidence:
  out=Path(evidence);out.mkdir(parents=True,exist_ok=True)
  for label,raw in logs.items():(out/(label+'.log')).write_bytes(raw)
  (out/'receipt.json').write_text(json.dumps({'classification':'REAL_SMALL_CMAKE_ELF_NOT_OPENCV_QUALIFICATION','cmakeVersion':version.decode(),'records':records,'sameNeeded':True,'fixedBuildAndInstallNoRpath':True},indent=2)+'\n')
