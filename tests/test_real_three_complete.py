"""Synthetic policy adversaries only; not controller or photographic runtime evidence."""
import hashlib
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import pytest
from tools.eight_view_host import controller as c, experiments as e, policy as p, runtime as r


def helpers():
    spec=importlib.util.spec_from_file_location('three_complete_fixture',Path(__file__).with_name('test_eight_view_complete_measurement.py'))
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module


def fixture():
    m=helpers();docs,a,i,o=m.fixture();decoded={k:json.loads(v) for k,v in docs.items()}
    decoded['job-settings.json']['focal35']=88.0
    settings_hash=m.sha(m.encode(decoded['job-settings.json']))
    decoded['complete-result.json'].update(synthetic=False,classification=e.get_profile(e.REAL_THREE).classification,settingsIdentitySha256=settings_hash)
    for phase in ('before-reopen','after-reopen'):
        pub=decoded[phase+'-publication.json'];pub['settingsIdentitySha256']=settings_hash;pub['setId']=m.publication_digest(pub)
    return {k:m.encode(v) for k,v in decoded.items()},a,i,o


def test_closed_three_profile_and_budgets():
    selected=e.get_profile(e.REAL_THREE)
    assert selected.photo_count==selected.pair_count==3
    assert 'PARTIAL_ARC' in selected.classification
    assert e.get_profile(e.REAL_THREE).id==e.REAL_THREE # preserved historical profile, independent of active diagnostic
    assert e.artifact_budget(e.REAL_THREE)==56*1024**2
    value=p.complete_measurement_proof(*fixture(),profile=e.REAL_THREE)
    assert value['registeredCameraCount']==3 and value['synthetic'] is False
    assert value['classification']==selected.classification
    with pytest.raises(r.Failure):p.measurement_proof({},[],[],{},profile=e.REAL_THREE)


@pytest.mark.parametrize('profile',[e.COMPLETE,e.REAL_STEREO,e.RELIEF,e.LEGACY])
def test_three_documents_cannot_masquerade_as_another_profile(profile):
    with pytest.raises(r.Failure):p.complete_measurement_proof(*fixture(),profile=profile)


@pytest.mark.parametrize('field,value',[('synthetic',True),('classification','SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY'),('projectRevision',2),('originalsPreserved',False),('state','FAILED')])
def test_three_result_adversaries(field,value):
    docs,a,i,o=fixture();helpers().edit(docs,'complete-result.json',lambda d:d.update({field:value}))
    with pytest.raises(r.Failure):p.complete_measurement_proof(docs,a,i,o,profile=e.REAL_THREE)


@pytest.mark.parametrize('kind',['missing','extra','partial-registration'])
def test_three_exact_registration_and_image_mapping(kind):
    docs,a,i,o=fixture()
    if kind=='missing':i.pop()
    elif kind=='extra':i.append(dict(i[-1]))
    else:
        # Keep all raw/hash/publication bindings internally valid: admission must
        # reject the missing third camera, not merely an unrelated stale digest.
        m=helpers();preview=json.loads(docs['before-reopen-preview.json'])
        old=m.sha(docs['before-reopen-preview.json'])
        preview.update(registeredCameras=2,cameras=preview['cameras'][:2])
        raw=m.encode(preview);digest=m.sha(raw)
        del a['artifact-'+old+'.bin'];a['artifact-'+digest+'.bin']=raw
        for phase in ('before-reopen','after-reopen'):
            docs[phase+'-preview.json']=raw
            m.edit(docs,phase+'-artifacts.json',lambda d:d.update(preview=digest))
            pub=json.loads(docs[phase+'-publication.json'])
            row=next(row for row in pub['entries'] if row['logicalName']=='preview')
            row.update(bytes=len(raw),maxBytes=len(raw),sha256=digest)
            pub['setId']=m.publication_digest(pub);docs[phase+'-publication.json']=m.encode(pub)
        m.edit(docs,'complete-result.json',lambda d:d['exportHashes'].update(preview=digest))
    with pytest.raises(r.Failure):p.complete_measurement_proof(docs,a,i,o,profile=e.REAL_THREE)


@pytest.mark.parametrize('rel,raw',[
 ('measurement/original.JPG',b'anything'),('measurement/private-exif.json',b'{}'),
 ('measurement/descriptors.json',b'{}'),('measurement/coordinates.json',b'{}'),
 ('measurement/match-replay.json',b'{}'),('measurement/scan.db',b'SQLite format'),
 ('measurement/artifact-'+'a'*64+'.bin',b'\xff\xd8original'),
 ('logs/test.log',b'BodySerialNumber=private'),('xml/TEST.xml',b'Exif\0\0'),
 ('receipt.json',b'private-canary'),
])
def test_three_private_and_unreviewed_evidence_rejected(rel,raw):
    run=SimpleNamespace(real_private_markers=(b'private-canary',))
    with pytest.raises(r.Failure):c.real_evidence_privacy_check(rel,raw,run,profile=e.REAL_THREE)


@pytest.mark.parametrize('missing',[True,False])
def test_three_license_required_for_derivatives(tmp_path,monkeypatch,missing):
    _,licensed=c.load_adapter(c.ROOT,e.REAL_THREE)
    run=r.Runner(tmp_path,tmp_path/'work');(run.work/'measurement').mkdir()
    (run.work/'measurement'/('artifact-'+hashlib.sha256(b'{}').hexdigest()+'.bin')).write_bytes(b'{}')
    if not missing:
        for name,raw in licensed.LICENSE_EVIDENCE.items():(run.work/name).write_bytes(raw+b'changed')
    monkeypatch.setattr(e,'ACTIVE',e.REAL_THREE)
    with pytest.raises(r.Failure,match='license notice|notice changed'):c.evidence_package(run,{'status':'FAIL'},tmp_path/'out')


def test_three_license_is_profile_specific():
    _,three=c.load_adapter(c.ROOT,e.REAL_THREE);_,two=c.load_adapter(c.ROOT,e.REAL_STEREO)
    for name,raw in three.LICENSE_EVIDENCE.items():c.real_evidence_privacy_check(name,raw,profile=e.REAL_THREE)
    for name in ('real-input-provenance.json','real-input-attribution.txt'):
        with pytest.raises(r.Failure):c.real_evidence_privacy_check(name,two.LICENSE_EVIDENCE[name],profile=e.REAL_THREE)


def test_three_private_values_withheld_normal_emergency_and_console(tmp_path,monkeypatch):
    _,licensed=c.load_adapter(c.ROOT,e.REAL_THREE)
    run=r.Runner(tmp_path,tmp_path/'work');run.real_private_markers=(b'private-canary',)
    (run.work/'logs/test.log').write_bytes(b'private-canary')
    (run.work/'measurement').mkdir();(run.work/'measurement/failure.json').write_bytes(b'private-canary')
    for name,raw in licensed.LICENSE_EVIDENCE.items():(run.work/name).write_bytes(raw)
    monkeypatch.setattr(e,'ACTIVE',e.REAL_THREE)
    out=tmp_path/'out'
    with pytest.raises(r.Failure):c.evidence_package(run,{'status':'FAIL'},out)
    assert all(b'private-canary' not in f.read_bytes() for f in out.rglob('*') if f.is_file())
    receipt={'status':'FAIL','firstFailure':{'type':'Failure','message':'private-canary'}}
    raw=c.emergency_terminal_downgrade(out,receipt,run)
    assert b'private-canary' not in raw and json.loads(raw)['status']=='FAIL'
    frames=[];monkeypatch.setattr(c,'frame',lambda label,raw:frames.append(raw))
    c.emit_incomplete_essential(run,receipt)
    assert frames and all(b'private-canary' not in raw for raw in frames)


def test_all_real_controller_paths_use_closed_profile_membership():
    source=Path(c.__file__).read_text()
    assert '==experiments.REAL_STEREO' not in source
    assert 'load_adapter(ROOT,experiments.REAL_STEREO)' not in source
    assert e.get_profile(e.REAL_THREE).test_source.removesuffix('.kt') in c.PROBE_CLASS_PREFIXES
