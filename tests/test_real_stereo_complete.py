"""Synthetic proof fixtures for real-profile admission; NOT real reconstruction evidence."""
import importlib.util,json,hashlib
from pathlib import Path
import pytest
from tools.eight_view_host import controller as c, experiments as e, policy as p, runtime as r

def helpers():
    path=Path(__file__).with_name('test_eight_view_complete_measurement.py')
    spec=importlib.util.spec_from_file_location('complete_fixture',path)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

def fixture(settings_bytes=None):
    m=helpers();docs,artifacts,identity,observations=m.fixture()
    decoded={k:json.loads(v) for k,v in docs.items()}
    identity=identity[:2];observations={'images':{row['fixturePath']:row['imageSha256'] for row in identity}}
    decoded['job-settings.json']['focal35']=88.0
    settings_hash=m.sha(settings_bytes or m.encode(decoded['job-settings.json']))
    result=decoded['complete-result.json'];result.update(synthetic=False,classification=e.get_profile(e.REAL_STEREO).classification,projectRevision=2,settingsIdentitySha256=settings_hash)
    old_preview=docs['before-reopen-preview.json']
    preview=decoded['before-reopen-preview.json'];preview.update(revision=2,registeredCameras=2,cameras=preview['cameras'][:2])
    preview_bytes=m.encode(preview);preview_hash=m.sha(preview_bytes)
    del artifacts['artifact-'+m.sha(old_preview)+'.bin'];artifacts['artifact-'+preview_hash+'.bin']=preview_bytes
    for phase in ('before-reopen','after-reopen'):
        decoded[phase+'-preview.json']=preview
        decoded[phase+'-artifacts.json']['preview']=preview_hash
        pub=decoded[phase+'-publication.json'];pub.update(projectRevision=2,settingsIdentitySha256=settings_hash)
        row=pub['entries'][0];assert row['logicalName']=='preview'
        row.update(bytes=len(preview_bytes),maxBytes=len(preview_bytes),sha256=preview_hash)
        pub['setId']=m.publication_digest(pub)
    result['exportHashes']['preview']=preview_hash
    output={k:m.encode(v) for k,v in decoded.items()}
    if settings_bytes is not None:output['job-settings.json']=settings_bytes
    return output,artifacts,identity,observations

def test_real_profile_full_binding_and_unchanged_synthetic():
    value=p.complete_measurement_proof(*fixture(),profile=e.REAL_STEREO)
    assert value['registeredCameraCount']==value['projectRevision']==2
    assert value['synthetic'] is False and value['classification']==e.get_profile(e.REAL_STEREO).classification
    assert e.ACTIVE==e.REAL_STEREO
    assert e.artifact_budget(e.REAL_STEREO)==e.artifact_budget(e.COMPLETE)==56*1024**2
    assert p.complete_measurement_proof(*helpers().fixture())['registeredCameraCount']==3

@pytest.mark.parametrize('profile',[e.COMPLETE,e.RELIEF,e.LEGACY])
def test_real_documents_rejected_by_other_profile(profile):
    with pytest.raises(r.Failure):p.complete_measurement_proof(*fixture(),profile=profile)

@pytest.mark.parametrize('field,value',[('synthetic',True),('classification','PASS'),('projectRevision',3),('originalsPreserved',False),('state','FAILED')])
def test_real_result_claims_cannot_be_changed(field,value):
    docs,a,i,o=fixture();helpers().edit(docs,'complete-result.json',lambda d:d.update({field:value}))
    with pytest.raises(r.Failure):p.complete_measurement_proof(docs,a,i,o,profile=e.REAL_STEREO)

def test_real_cannot_use_prefix_measurement():
    with pytest.raises(r.Failure):p.measurement_proof({},[],[],{},profile=e.REAL_STEREO)

@pytest.mark.parametrize('rel,raw',[
 ('measurement/original.JPG',b'anything'),('measurement/private-exif.json',b'{}'),
 ('measurement/scan.db',b'SQLite format'),('measurement/artifact-'+'a'*64+'.bin',b'\xff\xd8original'),
 ('measurement/artifact-'+'a'*64+'.bin',b'glTFpayloadExif\0\0private'),
 ('logs/test.log',b'BodySerialNumber=redacted'),('logs/test.log',b'Exif\0\0'),
])
def test_real_original_or_private_metadata_refused(rel,raw):
    with pytest.raises(r.Failure):c.real_evidence_privacy_check(rel,raw)

def test_real_publication_outputs_allowed_without_rewriting():
    for raw in (b'glTFpayload',b'ply\npayload',b'{}'):
        c.real_evidence_privacy_check('measurement/artifact-'+'a'*64+'.bin',raw)

@pytest.mark.parametrize('missing',[False,True])
def test_real_collector_requires_exact_license_for_assets(tmp_path,monkeypatch,missing):
    _,licensed=c.load_adapter(c.ROOT,e.REAL_STEREO)
    run=r.Runner(tmp_path,tmp_path/'work');measurement=run.work/'measurement';measurement.mkdir()
    (measurement/('artifact-'+hashlib.sha256(b'{}').hexdigest()+'.bin')).write_bytes(b'{}')
    for name,raw in licensed.LICENSE_EVIDENCE.items():
        if not missing:(run.work/name).write_bytes(raw+b'changed')
    monkeypatch.setattr(e,'ACTIVE',e.REAL_STEREO)
    with pytest.raises(r.Failure,match='license notice|notice changed'):
        c.evidence_package(run,{'status':'FAIL'},tmp_path/'evidence')

@pytest.mark.parametrize('kind',[b'eXIf',b'tEXt',b'iTXt',b'zTXt'])
@pytest.mark.parametrize('prefix',[b'',b'glTFembedded'])
def test_real_png_private_chunks_refused(kind,prefix):
    raw=prefix+b'\x89PNG\r\n\x1a\n'+(0).to_bytes(4,'big')+kind+b'crc!'
    with pytest.raises(r.Failure):c.real_evidence_privacy_check('measurement/artifact-'+'a'*64+'.bin',raw)

@pytest.mark.parametrize('rel',['logs/eight-view-test.raw.log','xml/TEST-real.xml','measurement/failure.json','receipt.json'])
@pytest.mark.parametrize('raw',[b'untagged-private-serial',b'{"message":"untagged-private-serial"}'])
def test_private_source_values_rejected_without_exif_keys(rel,raw):
    from types import SimpleNamespace
    run=SimpleNamespace(real_private_markers=(b'untagged-private-serial',))
    with pytest.raises(r.Failure,match='Private source metadata'):
        c.real_evidence_privacy_check(rel,raw,run)

def test_private_values_never_reach_fallback_frames(tmp_path,monkeypatch):
    run=r.Runner(tmp_path,tmp_path/'work');run.real_private_markers=(b'untagged-private-serial',)
    (run.work/'measurement').mkdir();(run.work/'measurement/failure.json').write_bytes(b'{"message":"untagged-private-serial"}')
    monkeypatch.setattr(e,'ACTIVE',e.REAL_STEREO)
    frames=[];monkeypatch.setattr(c,'frame',lambda label,raw:frames.append(raw))
    c.emit_incomplete_essential(run,{'status':'FAIL','firstFailure':{'type':'Failure','message':'untagged-private-serial'}})
    assert frames and all(b'untagged-private-serial' not in raw for raw in frames)

def test_private_values_never_reach_emergency_receipt(tmp_path,monkeypatch):
    run=r.Runner(tmp_path,tmp_path/'work');run.real_private_markers=(b'untagged-private-serial',)
    out=tmp_path/'evidence';out.mkdir();monkeypatch.setattr(e,'ACTIVE',e.REAL_STEREO)
    raw=c.emergency_terminal_downgrade(out,{'status':'FAIL','firstFailure':{'type':'Failure','message':'untagged-private-serial'}},run)
    assert b'untagged-private-serial' not in raw and json.loads(raw)['status']=='FAIL'

def test_actual_kotlin_producer_settings_bytes_are_hash_bound_without_reencoding():
    # Captured by executing unchanged Kotlin enqueue expression + resource-selection
    # producer with project-pinned org.json20250517; not an app/native runtime result.
    raw=b'{"schema":2,"focal35":88,"resourceSelection":{"schema":1,"tier":"standard","identitySha256":"438dda8c065b497a09ea9f1e2fd9e06d6c7c4dd9e7610889a89d4d52604409ec"}}'
    assert hashlib.sha256(raw).hexdigest()=='7ccd2db5d64857a5aa1e944a94c133a8ae00846d5effac0feb1cc624643cfb6f'
    assert p.complete_measurement_proof(*fixture(raw),profile=e.REAL_STEREO)['registeredCameraCount']==2
    docs,a,i,o=fixture(raw)
    docs['job-settings.json']=raw.replace(b':88,',b':88.0,')
    with pytest.raises(r.Failure,match='settings bytes differ'):
        p.complete_measurement_proof(docs,a,i,o,profile=e.REAL_STEREO)

def test_license_name_is_not_a_private_data_bypass():
    from types import SimpleNamespace
    run=SimpleNamespace(real_private_markers=(b'private-value',))
    with pytest.raises(r.Failure):
        c.real_evidence_privacy_check('real-input-attribution.txt',b'private-value',run)

def test_private_log_is_not_copied_to_public_package(tmp_path,monkeypatch):
    _,licensed=c.load_adapter(c.ROOT,e.REAL_STEREO)
    run=r.Runner(tmp_path,tmp_path/'work');run.real_private_markers=(b'untagged-private-serial',)
    (run.work/'logs/eight-view-test.raw.log').write_bytes(b'untagged-private-serial')
    for name,raw in licensed.LICENSE_EVIDENCE.items():(run.work/name).write_bytes(raw)
    monkeypatch.setattr(e,'ACTIVE',e.REAL_STEREO)
    out=tmp_path/'evidence'
    with pytest.raises(r.Failure,match='Private source metadata'):
        c.evidence_package(run,{'status':'FAIL'},out)
    assert not (out/'logs/eight-view-test.raw.log').exists()
    assert all(b'untagged-private-serial' not in file.read_bytes() for file in out.rglob('*') if file.is_file())
