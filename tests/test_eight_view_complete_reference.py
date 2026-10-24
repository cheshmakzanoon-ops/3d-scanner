"""Complete-profile receipt/collector composition with synthetic semantic proof fixtures."""
import importlib.util,json,hashlib
from pathlib import Path
import pytest
from tools.eight_view_host import controller as c,experiments as e,runtime as r

def fixture():
 p=Path(__file__).with_name('test_eight_view_complete_measurement.py');s=importlib.util.spec_from_file_location('complete_policy_fixture',p);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m.fixture()

def prepare(tmp_path,monkeypatch):
 docs,artifacts,identity,observed=fixture();run=r.Runner(tmp_path,tmp_path/'work');measurement=run.work/'measurement';measurement.mkdir()
 for name,raw in {**docs,**artifacts}.items():(measurement/name).write_bytes(raw)
 r.atomic_json(measurement/'input-identity.json',identity);r.atomic_json(run.work/'observation-proof.json',observed)
 monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
 proof=c.measurement_reference(run.work,e.COMPLETE,run,observed=observed)
 value={'status':'PASS','experiment':e.COMPLETE,'pipelineAttempts':1,'result':{'measurement':proof},'commandsReference':c.command_reference(run)}
 return run,value,observed

def test_complete_package_reference_and_console_preserve_all_nine_raw_artifacts(tmp_path,monkeypatch):
 run,value,observed=prepare(tmp_path,monkeypatch);out=c.evidence_package(run,value,tmp_path/'evidence');frames=[]
 monkeypatch.setattr(c,'frame',lambda *a:frames.append(a));c.emit_terminal_frames(out,value,run)
 assert value['status']=='PASS' and value['evidence']['complete']
 assert value['result']['measurement']['artifactCount']==9
 assert len(list((out/'measurement').glob('artifact-*.bin')))==9
 assert len((out/'receipt.json').read_bytes())<256*1024
 for path in (run.work/'measurement').glob('artifact-*.bin'):assert (out/'measurement'/path.name).read_bytes()==path.read_bytes()
 assert [x[0] for x in frames]==['terminal-receipt','measurement-reference']

@pytest.mark.parametrize('mode',['missing-artifact','changed-artifact','missing-doc','changed-doc','cross-profile','missing-ref','changed-summary'])
def test_complete_proof_seal_rejects_missing_changed_or_mixed_evidence(tmp_path,monkeypatch,mode):
 run,value,_=prepare(tmp_path,monkeypatch)
 if mode=='missing-artifact':next((run.work/'measurement').glob('artifact-*.bin')).unlink()
 elif mode=='changed-artifact':next((run.work/'measurement').glob('artifact-*.bin')).write_bytes(b'changed')
 elif mode=='missing-doc':(run.work/'measurement/before-reopen-publication.json').unlink()
 elif mode=='changed-doc':(run.work/'measurement/after-reopen-preview.json').write_bytes(b'{}')
 elif mode=='cross-profile':value['experiment']=e.RELIEF
 elif mode=='missing-ref':value['result']['measurement'].pop('reference')
 else:value['result']['measurement']['artifactCount']=8
 if mode=='changed-artifact':
  with pytest.raises(r.Failure,match='Content-addressed checkpoint mismatch'):c.evidence_package(run,value,tmp_path/'evidence')
  return
 out=c.evidence_package(run,value,tmp_path/'evidence')
 assert value['status']=='FAIL' and value['evidence']['complete'] is False
 assert 'measurementReferenceFailure' in value


def test_complete_profile_cannot_rebind_changed_original_input_authority(tmp_path,monkeypatch):
 run,_,_=prepare(tmp_path,monkeypatch)
 with pytest.raises(r.Failure,match='staged input authority'):
  c.measurement_reference(run.work,e.COMPLETE,run,observed={'images':{}})


def test_complete_profile_cannot_accept_sparse_prefix_documents(tmp_path,monkeypatch):
 run,_,_=prepare(tmp_path,monkeypatch)
 with pytest.raises(r.Failure):c.measurement_reference(run.work,e.RELIEF,run)
