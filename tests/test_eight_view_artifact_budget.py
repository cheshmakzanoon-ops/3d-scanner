"""Real bounded collector/seal reporting capacity; synthetic FAIL is never qualification."""
import hashlib
import json
from pathlib import Path
import sys
import pytest
ROOT=Path(__file__).resolve().parents[1];sys.path.insert(0,str(ROOT))
from tools.eight_view_host import controller as c,experiments as e,runtime as r
MIB=1024**2


def receipt(profile):
    return {'schema':1,'status':'FAIL','experiment':profile,'pipelineAttempts':0,
            'firstFailure':{'type':'SyntheticBoundary','message':'Reporting-capacity test, not reconstruction qualification'}}


def sparse(path,size):
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('wb') as stream:stream.truncate(size)


def zero_hash(size):
    h=hashlib.sha256();block=b'\0'*65536
    while size:
        n=min(size,len(block));h.update(block[:n]);size-=n
    return h.hexdigest()


def payloads(run):
    # ASCII NUL fixture bytes: source holes occupy almost no physical disk.
    # Three unequal sizes ensure distinct content-addressed identities.
    result={}
    for size in (12*MIB,11*MIB,10*MIB):
        digest=zero_hash(size);name='artifact-'+digest+'.bin'
        path=run.work/'measurement'/name;sparse(path,size);result[name]=(size,digest)
    return result


def test_complete_retains_real_package_above_old_limit(tmp_path,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
    run=r.Runner(tmp_path,tmp_path/'work');expected=payloads(run);value=receipt(e.COMPLETE)
    out=c.evidence_package(run,value,tmp_path/'evidence')
    sealed=json.loads((out/'receipt.json').read_text())
    assert sealed['status']=='FAIL' and sealed['pipelineAttempts']==0
    assert sealed['evidence']['complete'] is True
    assert 32*MIB<sealed['evidence']['bytes']<55*MIB
    for name,(size,digest) in expected.items():
        path=out/'measurement'/name
        assert path.stat().st_size==size
        with path.open('rb') as stream:
            h=hashlib.sha256()
            for block in iter(lambda:stream.read(65536),b''):h.update(block)
        assert h.hexdigest()==digest
    total,_=r.inventory(out,56*MIB,2000)
    assert total<=56*MIB
    sums=(out/'SHA256SUMS').read_text()
    assert all(digest+'  measurement/'+name in sums for name,(_,digest) in expected.items())
    import shutil
    shutil.rmtree(out)  # Remove only owned synthetic copies after verified assertions.


@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF],ids=['legacy32','relief32'])
def test_old_profiles_still_refuse_same_real_package(tmp_path,profile,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',profile)
    run=r.Runner(tmp_path,tmp_path/'work');payloads(run)
    with pytest.raises(r.Failure,match='size limit|byte/count limit'):
        c.evidence_package(run,receipt(profile),tmp_path/'evidence')
    assert not (tmp_path/'evidence/receipt.json').exists()
    assert e.artifact_budget(profile)==32*MIB


@pytest.mark.parametrize('profile,selected',[(e.LEGACY,31),(e.RELIEF,31),(e.COMPLETE,55)],ids=['legacy31','relief31','complete55'])
def test_selected_capacity_refuses_one_byte_over(tmp_path,profile,selected,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',profile)
    run=r.Runner(tmp_path,tmp_path/'work');directory=run.work/'logs';directory.mkdir(exist_ok=True)
    remaining=selected*MIB+1;index=0
    while remaining:
        count=min(16*MIB,remaining);sparse(directory/f'piece-{index}.txt',count);remaining-=count;index+=1
    with pytest.raises(r.Failure,match='size limit|byte/count limit'):
        c.evidence_package(run,receipt(profile),tmp_path/'evidence')
    assert not (tmp_path/'evidence/receipt.json').exists()


@pytest.mark.parametrize('profile,maximum',[(e.LEGACY,32),(e.RELIEF,32),(e.COMPLETE,56)],ids=['legacy32','relief32','complete56'])
def test_total_seal_refuses_one_byte_over(tmp_path,profile,maximum,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',profile)
    out=tmp_path/'evidence';out.mkdir();remaining=maximum*MIB+1;index=0
    while remaining:
        count=min(16*MIB,remaining);sparse(out/f'piece-{index}',count);remaining-=count;index+=1
    with pytest.raises(r.Failure,match='size limit'):
        c.seal_receipt(out,receipt(profile))
    assert not (out/'receipt.json').exists()


@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF,e.COMPLETE],ids=['legacy','relief','complete'])
def test_per_file_limit_stays_16mib(tmp_path,profile,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',profile)
    run=r.Runner(tmp_path,tmp_path/'work');sparse(run.work/'prepared.json',16*MIB+1)
    with pytest.raises(r.Failure,match='per-file size'):
        c.evidence_package(run,receipt(profile),tmp_path/'evidence')


def test_complete_seal_count_limit_unchanged(tmp_path,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
    out=tmp_path/'evidence';out.mkdir()
    for i in range(2001):sparse(out/f'{i:04d}',0)
    with pytest.raises(r.Failure,match='count|entry|file limit'):
        c.seal_receipt(out,receipt(e.COMPLETE))
    assert not (out/'receipt.json').exists()


def test_complete_selected_count_reservation_unchanged(tmp_path,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
    run=r.Runner(tmp_path,tmp_path/'work');directory=run.work/'logs';directory.mkdir(exist_ok=True)
    for i in range(1991):sparse(directory/f'{i:04d}',0)
    with pytest.raises(r.Failure,match='count|entry|file limit'):
        c.evidence_package(run,receipt(e.COMPLETE),tmp_path/'evidence')


@pytest.mark.parametrize('unknown',['foreign',None,{},1],ids=['foreign','none','object','integer'])
def test_unknown_profile_cannot_select_larger_budget(tmp_path,unknown,monkeypatch):
    monkeypatch.setattr(e,'ACTIVE',unknown)
    with pytest.raises(r.Failure):e.artifact_budget(unknown)
    out=tmp_path/'evidence';out.mkdir()
    with pytest.raises(r.Failure):c.seal_receipt(out,receipt(unknown))
    run=r.Runner(tmp_path,tmp_path/'work')
    with pytest.raises(r.Failure):c.evidence_package(run,receipt(unknown),tmp_path/'package')


def test_receipt_cannot_promote_source_selected_legacy_budget(tmp_path,monkeypatch):
    # Budget comes from the closed source-selected profile, not editable receipt text.
    monkeypatch.setattr(e,'ACTIVE',e.LEGACY)
    run=r.Runner(tmp_path,tmp_path/'work');payloads(run)
    with pytest.raises(r.Failure,match='size limit|byte/count limit'):
        c.evidence_package(run,receipt(e.COMPLETE),tmp_path/'evidence')
    assert not (tmp_path/'evidence/receipt.json').exists()


def test_selected_exact55_including_policy_succeeds(tmp_path,monkeypatch):
    import shutil
    monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
    run=r.Runner(tmp_path,tmp_path/'work')
    policy_bytes=c.capability.POLICY.stat().st_size
    sizes=[16*MIB,16*MIB-1,16*MIB-2,7*MIB+3-policy_bytes]
    assert sum(sizes)+policy_bytes==55*MIB
    for size in sizes:
        sparse(run.work/'measurement'/('artifact-'+zero_hash(size)+'.bin'),size)
    value=receipt(e.COMPLETE)
    out=c.evidence_package(run,value,tmp_path/'evidence')
    sealed=json.loads((out/'receipt.json').read_text())
    assert sealed['status']=='FAIL' and sealed['pipelineAttempts']==0
    assert sealed['evidence']['complete'] and sealed['evidence']['bytes']==55*MIB
    assert max(row['bytes'] for row in sealed['evidence']['files'])==16*MIB
    assert r.inventory(out,56*MIB,2000)[0]<56*MIB
    shutil.rmtree(out)  # Only owned synthetic copy; keep scratch physical use bounded.


@pytest.mark.parametrize('extra',[0,1],ids=['exact56-with-framing','one-byte-over-final-framing'])
def test_final56_boundary_includes_receipt_and_manifest(tmp_path,monkeypatch,extra):
    monkeypatch.setattr(e,'ACTIVE',e.COMPLETE)
    out=tmp_path/'evidence';out.mkdir()
    value=receipt(e.COMPLETE)
    expected=dict(value);expected.update(c.bounded_failures(expected))
    receipt_bytes=len((json.dumps(expected,sort_keys=True,indent=2)+'\n').encode())
    names=['piece-0','piece-1','piece-2','piece-3']
    # Fixed64hex digest, two spaces, relativeASCII name and newline for every file.
    manifest_bytes=sum(64+2+len(name)+1 for name in names+['receipt.json'])
    payload_bytes=56*MIB-receipt_bytes-manifest_bytes+extra
    sizes=[16*MIB,16*MIB,16*MIB,payload_bytes-48*MIB]
    for name,size in zip(names,sizes):sparse(out/name,size)
    assert r.inventory(out,56*MIB,2000)[0]==payload_bytes
    if extra:
        with pytest.raises(r.Failure,match='size limit'):c.seal_receipt(out,value)
    else:
        c.seal_receipt(out,value)
    # Proves overrun is detected AFTER actual framing, not an initial payload refusal.
    assert (out/'receipt.json').stat().st_size==receipt_bytes
    assert (out/'SHA256SUMS').stat().st_size==manifest_bytes
    assert sum(p.stat().st_size for p in out.iterdir())==56*MIB+extra
    assert json.loads((out/'receipt.json').read_text())['status']=='FAIL'
