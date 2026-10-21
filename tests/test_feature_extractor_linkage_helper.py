"""Mocked tool orchestration, not native signature or JNI evidence."""
import importlib.util
import json
from pathlib import Path
import pytest

HELPER=Path(__file__).resolve().parents[1]/'tools/feature_extractor_validation/verify_linkage.py'

@pytest.fixture
def setup(tmp_path,monkeypatch):
 spec=importlib.util.spec_from_file_location('extractor_linkage',HELPER)
 m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
 java=tmp_path/'jdk/bin/java';java.parent.mkdir(parents=True);java.write_text('java');java.with_name('javap').write_text('javap')
 cap=tmp_path/'cap'
 for rel in ['build/lib/libopencv_java4120.so','validation.json','smoke/aar-classes.jar','build/bin/opencv-4120.jar']:
  p=cap/rel;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(rel)
 out=tmp_path/'evidence'
 def call(args,**kw):
  if args[0]=='nm':return '\n'.join('00 T Java_'+x.replace('.','_')+'_delete' for x in m.CLASSES)
  return '  private static native void delete(long);\n    descriptor: (J)V\n'
 monkeypatch.setattr(m.subprocess,'check_output',call)
 def run(target=out):
  monkeypatch.setattr('sys.argv',['linkage','--java',str(java),'--capability-dir',str(cap),'--out',str(target)])
  m.main()
 return m,run,cap,out,call

def test_success_separate_atomic_receipt(setup):
 m,run,cap,out,_=setup;before=(cap/'validation.json').read_bytes();run()
 receipt=json.loads((out/'sift-linkage.json').read_text());assert receipt['nativeDeclarations']==3
 assert all(x['equal'] and not x['missingSymbols'] for x in receipt['classes'].values())
 assert before==(cap/'validation.json').read_bytes();assert not list(out.glob('*.partial'))

@pytest.mark.parametrize('failure',['mismatch','missing_symbol','tool_error','input_change'])
def test_failure_invalidates_previous_linkage_receipt(setup,monkeypatch,failure):
 m,run,cap,out,call=setup;run()
 def fail(args,**kw):
  text=call(args,**kw)
  if failure=='mismatch' and 'opencv-4120.jar' in ' '.join(args):return text.replace('(J)V','(I)V')
  if failure=='missing_symbol' and args[0]=='nm':return ''
  if failure=='tool_error':raise m.subprocess.CalledProcessError(1,args)
  if failure=='input_change':(cap/'build/lib/libopencv_java4120.so').write_text('changed')
  return text
 monkeypatch.setattr(m.subprocess,'check_output',fail)
 with pytest.raises((ValueError,m.subprocess.CalledProcessError)):run()
 assert not (out/'sift-linkage.json').exists();assert list(out.glob('superseded-*-sift-linkage.json'))

@pytest.mark.parametrize('where',['capability','source'])
def test_forbidden_output_tree_is_read_only(setup,where):
 m,run,cap,out,_=setup
 with pytest.raises(ValueError):run(cap if where=='capability' else Path(m.__file__).resolve().parents[2])
 assert not (cap/'sift-linkage.json').exists()
