"""Bounded terminal framing; raw geometry remains authoritative artifact evidence."""
import hashlib,json,os,sys,zipfile
from pathlib import Path
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import controller as c,runtime as r,experiments as e

@pytest.fixture(autouse=True)
def retained_sparse_profile_scope(monkeypatch):
 # These retained97a/synthetic fixtures exercise the unchanged sparse-prefix contract.
 # Complete-controller reference/sealing is independently exercised in its own module.
 monkeypatch.setattr(e,'ACTIVE',e.RELIEF)

FILES=('measurement/sparse-result.json','measurement/pair-events.json','measurement/input-identity.json','observation-proof.json')
def stage(tmp_path,actual=False):
 work=tmp_path/'work';work.mkdir();(work/'measurement').mkdir()
 if actual:
  artifact=Path(os.environ.get('RF_MEASUREMENT_REPLAY_ZIP','/missing'))
  if not artifact.is_file():pytest.skip('Retained 97a artifact explicitly required for actual replay')
  assert hashlib.sha256(artifact.read_bytes()).hexdigest()=='845a19351f20041c1d1e8719474a1e2169e20884107c587848e8e1a687370f77'
  with zipfile.ZipFile(artifact) as z:
   for name in z.namelist():
    if name in ('receipt.json','SHA256SUMS','TERMINAL_INVALIDATION.json') or name.endswith('/'):continue
    assert not Path(name).is_absolute() and '..' not in Path(name).parts
    target=work/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(z.read(name))
 else:
  ids=['one','two','three'];images={f'{i}.jpg':hashlib.sha256(i.encode()).hexdigest() for i in ids}
  result={'classification':e.get_profile(e.RELIEF).classification,'inputPhotoIds':ids,'registeredPhotoIds':ids,'unregisteredPhotoIds':[],
          'geometry':{'finalSparseOutcome':'global_typed_ba_admitted12','points':['x'*1000]*350}}
  pairs=[{'pairNumber':i,'pairCount':3,'phase':'READY','geometryEntered':True} for i in range(1,4)]
  identity=[{'photoId':i,'fixturePath':i+'.jpg','imageSha256':images[i+'.jpg']} for i in ids]
  for name,value in zip(FILES,[result,pairs,identity,{'images':images}]):r.atomic_json(work/name,value)
 return work

def receipt(work):
 if not (work/'commands.json').exists():(work/'commands.json').write_text('[]\n')
 raw=(work/'commands.json').read_bytes()
 return {'status':'PASS','experiment':e.ACTIVE,'result':{'measurement':c.measurement_reference(work,e.ACTIVE)},
         'commandsReference':{'path':'commands.json','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest(),'count':len(json.loads(raw))},'evidence':{'complete':True,'files':[{'path':str(p.relative_to(work)),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in sorted(work.rglob('*')) if p.is_file()]}}

@pytest.mark.parametrize('actual',[False,True],ids=['oversized-synthetic-geometry','exact-97a-retained-result'])
def test_complete_seal_and_console_uses_reference_without_geometry_duplication(tmp_path,monkeypatch,actual):
 work=stage(tmp_path,actual);value=receipt(work);before=(work/FILES[0]).read_bytes();assert len(before)>256*1024
 c.seal_receipt(work,value);frames=[];monkeypatch.setattr(c,'frame',lambda label,raw,*a:frames.append((label,raw)))
 c.emit_terminal_frames(work,value)
 assert value['status']=='PASS' and value['evidence']['complete']
 assert len((work/'receipt.json').read_bytes())<256*1024 and (work/FILES[0]).read_bytes()==before
 assert [x[0] for x in frames]==['terminal-receipt','measurement-reference']+(['test-events-jsonl'] if actual else [])
 assert all(len(raw)<256*1024 for _,raw in frames)
 assert len(frames[1][1])<4096
 assert 'geometry' not in value['result']['measurement']
 assert value['result']['measurement']['reference']['sha256']==hashlib.sha256(before).hexdigest()

@pytest.mark.parametrize('mutation',['missing','bytes','hash','path','summary','bool-count','missing-reference','missing-result','oversized','secret','pairs','identity','observation','profile','symlink'])
def test_missing_or_unbound_measurement_cannot_seal_complete_pass(tmp_path,mutation):
 work=stage(tmp_path);value=receipt(work);m=value['result']['measurement'];target=work/FILES[0]
 if mutation=='missing':target.unlink()
 elif mutation=='bytes':m['reference']['bytes']+=1
 elif mutation=='hash':m['reference']['sha256']='0'*64
 elif mutation=='path':m['reference']['path']='../outside.json'
 elif mutation=='summary':m['registeredCameraCount']=8
 elif mutation=='bool-count':m['pairCount']=True
 elif mutation=='missing-reference':m.pop('reference')
 elif mutation=='missing-result':value.pop('result')
 elif mutation=='oversized':target.write_bytes(b' '*(4*1024**2+1))
 elif mutation=='secret':target.write_text('{"password":"password=secret"}')
 elif mutation=='pairs':r.atomic_json(work/FILES[1],[])
 elif mutation=='identity':r.atomic_json(work/FILES[2],[])
 elif mutation=='observation':r.atomic_json(work/FILES[3],{'images':{}})
 elif mutation=='profile':value['experiment']=e.LEGACY
 elif mutation=='symlink':target.rename(work/'outside.json');target.symlink_to(work/'outside.json')
 if mutation=='symlink':
  with pytest.raises(r.Failure,match='symlink'):c.seal_receipt(work,value)
  return
 c.seal_receipt(work,value)
 assert value['status']=='FAIL' and value['evidence']['complete'] is False
 assert 'measurementReferenceFailure' in value


def test_console_rechecks_after_seal_and_never_frames_changed_raw(tmp_path,monkeypatch):
 work=stage(tmp_path);value=receipt(work);c.seal_receipt(work,value)
 (work/FILES[0]).write_text('{}');frames=[];monkeypatch.setattr(c,'frame',lambda label,raw,*a:frames.append(label))
 with pytest.raises(r.Failure,match='invalid before console'):c.emit_terminal_frames(work,value)
 assert 'measurement-reference' not in frames


def test_still_valid_supporting_proof_drift_is_not_silently_rebound(tmp_path):
 work=stage(tmp_path);value=receipt(work)
 pairs=json.loads((work/FILES[1]).read_bytes());pairs[0]['firstDescriptorRows']=123
 r.atomic_json(work/FILES[1],pairs)  # Still policy-valid, but not the original validated bytes.
 c.seal_receipt(work,value)
 assert value['status']=='FAIL' and 'measurementReferenceFailure' in value


def test_creation_cannot_replace_staged_observation_authority(tmp_path):
 work=stage(tmp_path)
 with pytest.raises(r.Failure,match='staged input authority'):
  c.measurement_reference(work,e.ACTIVE,observed={'images':{}})

def test_missing_result_after_seal_never_emits_pass(tmp_path,monkeypatch):
 work=stage(tmp_path);value=receipt(work);c.seal_receipt(work,value);value.pop('result');frames=[]
 monkeypatch.setattr(c,'frame',lambda *a:frames.append(a))
 with pytest.raises(r.Failure,match='Sealed receipt changed'):c.emit_terminal_frames(work,value)
 assert not frames


@pytest.mark.parametrize('status',['CANCELLED','TIMEOUT'])
def test_invalid_reference_preserves_original_terminal_status(tmp_path,status):
 work=stage(tmp_path);value=receipt(work);value['status']=status;value['firstFailure']={'message':'original cause'}
 value['result']['measurement']['reference']['sha256']='0'*64
 c.seal_receipt(work,value)
 assert value['status']==status and value['firstFailure']['message']=='original cause'
 assert value['evidence']['complete'] is False and 'measurementReferenceFailure' in value
