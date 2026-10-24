"""Owned OpenCV cache admission; optional exact upstream-module CMake replay."""
import ast,hashlib,inspect,json,os,subprocess
from pathlib import Path
import pytest
from tools.eight_view_host import capability as c,policy as p,runtime as r
from test_eight_view_capability import fixture,SHA

@pytest.mark.parametrize('flags',[[],['-DOPENCV_DOWNLOAD_PATH=/foreign'],['-DOPENCV_DOWNLOAD_PATH=/a','-DOPENCV_DOWNLOAD_PATH=/b'],['-D','OPENCV_DOWNLOAD_PATH=/a'],['-DOPENCV_DOWNLOAD_PATH:PATH=/a']],ids=['missing','foreign','duplicate','split','typed-other'])
def test_configure_cache_binding_is_authoritative(tmp_path,monkeypatch,flags):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch)
 for records in (run.commands,value['producer']['commands']):
  record=next(x for x in records if x['label']=='opencv-configure')
  record['argv']=[arg for arg in record['argv'] if 'OPENCV_DOWNLOAD_PATH' not in arg]+flags
 manifest.write_text(json.dumps(value))
 with pytest.raises(r.Failure,match='bind owned download cache'):p.verify_capability(manifest,SHA,policy,run,prepared)

@pytest.mark.parametrize('mutation',['foreign','missing','duplicate','wrong-type','alias'])
def test_effective_cache_cannot_override_owned_configure(tmp_path,monkeypatch,mutation):
 run,prepared,policy,manifest,value=fixture(tmp_path,monkeypatch);out=run.work/'capability';file=out/'build/CMakeCache.txt';cache=out/'build/download-cache'
 if mutation=='foreign':file.write_text('OPENCV_DOWNLOAD_PATH:PATH=/foreign\n')
 elif mutation=='missing':file.write_text('UNRELATED:BOOL=ON\n')
 elif mutation=='duplicate':file.write_text(file.read_text()*2)
 elif mutation=='wrong-type':file.write_text(file.read_text().replace(':PATH=',':STRING='))
 else:cache.rmdir();cache.symlink_to(out)
 with pytest.raises(r.Failure,match='download cache'):p.verify_capability(manifest,SHA,policy,run,prepared)


def test_source_integrity_equality_and_download_denial_remain_exact(tmp_path):
 source=tmp_path/'source';source.mkdir();(source/'original.cpp').write_text('original');before=c.source_inventory(source)
 # Execute the actual production source-check closure with only its bound context supplied.
 tree=ast.parse(inspect.getsource(c.build));closure=next(n for n in ast.walk(tree) if isinstance(n,ast.FunctionDef) and n.name=='source_check')
 namespace={'source':source,'before':before,'source_inventory':c.source_inventory,'Failure':r.Failure}
 exec(compile(ast.fix_missing_locations(ast.Module(body=[closure],type_ignores=[])),'production-source-check','exec'),namespace)
 namespace['source_check']()
 (source/'.cache').mkdir();(source/'.cache/.gitignore').write_text('*\n')
 with pytest.raises(r.Failure,match='Official source changed'):namespace['source_check']()
 body=inspect.getsource(c.build)
 assert 'Implicit dependency download forbidden: ${DL_ID} ${DL_FILENAME}' in body
 assert "'-DOPENCV_DOWNLOAD_TRIES_LIST='" in body
 assert "'-DFETCHCONTENT_FULLY_DISCONNECTED=ON'" in body
 assert "f'-DOPENCV_DOWNLOAD_PATH={builddir}/download-cache'" in body
 assert 'if source_inventory(source)!=before' in body


def test_exact_upstream_module_real_cmake_source_cache_replay(tmp_path):
 module=os.environ.get('RF_OPENCV_DOWNLOAD_MODULE');cmake=os.environ.get('RF_CMAKE_3316')
 if not module or not cmake:pytest.skip('Explicit pinned upstream module and official CMake3.31.6 required; no test downloads')
 module=Path(module);assert hashlib.sha256(module.read_bytes()).hexdigest()=='8e2879c217b88e3d65a449765e0817a07a259c97c778186b2e2309627266127e'
 version=subprocess.run([cmake,'--version'],check=True,capture_output=True,timeout=10).stdout
 assert version.startswith(b'cmake version 3.31.6\n')
 script=tmp_path/'repro.cmake';script.write_text('function(ocv_check_environment_variables)\nendfunction()\ninclude("'+module.as_posix()+'")\n')
 env={k:v for k,v in os.environ.items() if k!='OPENCV_DOWNLOAD_PATH'}
 for fixed in (False,True):
  source=tmp_path/('fixed-source' if fixed else 'before-source');build=tmp_path/('fixed-build' if fixed else 'before-build');source.mkdir();build.mkdir()
  (source/'original.cpp').write_text('original');before=c.source_inventory(source)
  args=[cmake,f'-DOpenCV_SOURCE_DIR={source}',f'-DOpenCV_BINARY_DIR={build}']
  if fixed:args.append(f'-DOPENCV_DOWNLOAD_PATH={build}/download-cache')
  subprocess.run([*args,'-P',str(script)],env={**env,**({'OPENCV_DOWNLOAD_PATH':str(tmp_path/'forbidden-env-cache')} if fixed else {})},check=True,capture_output=True,timeout=15)
  if fixed:
   assert c.source_inventory(source)==before and not (source/'.cache').exists()
   assert (build/'download-cache/.gitignore').read_bytes()==b'*\n'
  else:
   assert c.source_inventory(source)!=before
   assert (source/'.cache/.gitignore').read_bytes()==b'*\n'

 out=tmp_path/'configure';source=out/'source';source.mkdir(parents=True);build=out/'build'
 (source/'CMakeLists.txt').write_text('cmake_minimum_required(VERSION 3.20)\nproject(cache_replay NONE)\nset(OpenCV_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}")\nset(OpenCV_BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}")\nfunction(ocv_check_environment_variables)\nendfunction()\ninclude("'+module.as_posix()+'")\n')
 before=c.source_inventory(source)
 subprocess.run([cmake,'-S',str(source),'-B',str(build),f'-DOPENCV_DOWNLOAD_PATH={build}/download-cache'],env={**env,'OPENCV_DOWNLOAD_PATH':str(tmp_path/'forbidden-env-cache')},check=True,capture_output=True,timeout=15)
 c.verify_download_cache(out)
 assert c.source_inventory(source)==before
 assert not (tmp_path/'forbidden-env-cache').exists()


def test_cache_read_respects_work_deadline_and_cleanup_reserve(tmp_path):
 run=r.Runner(tmp_path,tmp_path/'work');out=run.work/'capability';cache=out/'build/download-cache';cache.mkdir(parents=True)
 (out/'build/CMakeCache.txt').write_text(f'OPENCV_DOWNLOAD_PATH:PATH={cache}\n')
 run.work_end=run.clock()-1
 with pytest.raises(r.Deadline):c.verify_download_cache(out,run,cleanup=False)
 c.verify_download_cache(out,run)
 run.end=run.clock()-1
 with pytest.raises(r.Deadline):c.verify_download_cache(out,run)
