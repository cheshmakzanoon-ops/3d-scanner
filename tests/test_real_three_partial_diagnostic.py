"""Synthetic failure-tail adversaries, NOT photographic controller evidence."""
import hashlib,json
from types import SimpleNamespace
import pytest
from tools.eight_view_host import controller as c,experiments as e,runtime as r


def marker():
    return {'schema':1,'classification':'NOT_FULL_THREE','profile':e.REAL_THREE,
        'requiredCameraCount':3,'registeredCameraCount':2,'finalSparseOutcome':'verified_anchor_fallback',
        'roundTripVerified':True,'originalFailureType':'java.lang.AssertionError'}


def raw(value=None):return json.dumps(marker() if value is None else value).encode()


def test_closed_failure_label_not_full_proof():
    c.validate_real_three_partial_roundtrip(raw(),e.REAL_THREE)
    c.real_evidence_privacy_check('measurement/partial-roundtrip.json',raw(),profile=e.REAL_THREE)
    assert 'partial-roundtrip.json' not in c.policy.COMPLETE_DOCUMENTS


@pytest.mark.parametrize('profile',[e.REAL_STEREO,e.COMPLETE,e.RELIEF,e.LEGACY,'other',None])
def test_other_profiles_refused(profile):
    with pytest.raises(r.Failure):c.validate_real_three_partial_roundtrip(raw(),profile)


@pytest.mark.parametrize('field,value',[
 ('schema',True),('classification','PASS'),('classification','REAL_LICENSED_THREE_PARTIAL_ARC_HOST_FULL_CONTROLLER_NOT_DEVICE_QUALITY'),
 ('profile',e.REAL_STEREO),('requiredCameraCount',2),('registeredCameraCount',3),
 ('registeredCameraCount',2.0),('finalSparseOutcome','global_typed_ba_admitted12'),
 ('roundTripVerified',False),('roundTripVerified',1),('originalFailureType','none'),
 ('rawCoordinates',[]),('status','PASS'),('privateOwner','private-canary'),
])
def test_marker_cannot_mislabel_partial_or_add_private_fields(field,value):
    value_dict=marker();value_dict[field]=value
    with pytest.raises(r.Failure):c.validate_real_three_partial_roundtrip(raw(value_dict),e.REAL_THREE)


@pytest.mark.parametrize('value',[b'',b' '*4097,b'{',b'[]',b'null',raw().replace(b'"schema": 1',b'"schema": 1, "schema": 1')])
def test_marker_parse_and_size_fail_closed(value):
    with pytest.raises(r.Failure):c.validate_real_three_partial_roundtrip(value,e.REAL_THREE)


def setup_work(tmp_path,monkeypatch,*,license=True):
    monkeypatch.setattr(e,'ACTIVE',e.REAL_THREE)
    run=r.Runner(tmp_path,tmp_path/'work');run.real_private_markers=(b'private-canary',)
    (run.work/'measurement').mkdir()
    (run.work/'measurement/partial-roundtrip.json').write_bytes(raw())
    _,licensed=c.load_adapter(c.ROOT,e.REAL_THREE)
    if license:
        for name,value in licensed.LICENSE_EVIDENCE.items():(run.work/name).write_bytes(value)
    return run


@pytest.mark.parametrize('status',['PASS','RUNNING','BLOCKED','CANCELLED',None])
def test_partial_package_cannot_be_success_or_nonfailure(tmp_path,monkeypatch,status):
    run=setup_work(tmp_path,monkeypatch)
    with pytest.raises(r.Failure,match='failed receipt'):c.evidence_package(run,{'status':status},tmp_path/'out')


def test_complete_result_cannot_coexist_with_partial_marker(tmp_path,monkeypatch):
    run=setup_work(tmp_path,monkeypatch);(run.work/'measurement/complete-result.json').write_bytes(b'{}')
    with pytest.raises(r.Failure,match='no complete result'):c.evidence_package(run,{'status':'FAIL'},tmp_path/'out')
    with pytest.raises(r.Failure,match='cannot qualify as complete'):c.complete_measurement_reference(run.work,profile=e.REAL_THREE)


def test_partial_assets_still_require_license(tmp_path,monkeypatch):
    run=setup_work(tmp_path,monkeypatch,license=False)
    (run.work/'measurement'/('artifact-'+hashlib.sha256(b'{}').hexdigest()+'.bin')).write_bytes(b'{}')
    with pytest.raises(r.Failure,match='license notice'):c.evidence_package(run,{'status':'FAIL'},tmp_path/'out')


@pytest.mark.parametrize('payload',[b'glTFprivate-canary',b'\xff\xd8original',b'glTFExif\0\0private'])
def test_partial_payload_privacy_unchanged(tmp_path,monkeypatch,payload):
    run=setup_work(tmp_path,monkeypatch)
    name='artifact-'+hashlib.sha256(payload).hexdigest()+'.bin'
    (run.work/'measurement'/name).write_bytes(payload)
    out=tmp_path/'out'
    with pytest.raises(r.Failure):c.evidence_package(run,{'status':'FAIL'},out)
    assert not (out/'measurement'/name).exists()


def test_partial_marker_does_not_bypass_private_markers():
    with pytest.raises(r.Failure,match='Private source metadata'):
        c.real_evidence_privacy_check('measurement/partial-roundtrip.json',raw(),
            SimpleNamespace(real_private_markers=(b'NOT_FULL_THREE',)),profile=e.REAL_THREE)


def test_labeled_failed_diagnostic_package_can_be_retained(tmp_path,monkeypatch):
    run=setup_work(tmp_path,monkeypatch)
    out=tmp_path/'out';c.evidence_package(run,{'status':'FAIL'},out)
    assert (out/'measurement/partial-roundtrip.json').read_bytes()==raw()
    assert not (out/'measurement/complete-result.json').exists()


def test_partial_payload_per_file_limit_unchanged(tmp_path,monkeypatch):
    run=setup_work(tmp_path,monkeypatch)
    file=run.work/'measurement'/('artifact-'+'a'*64+'.bin')
    with file.open('wb') as stream:stream.truncate(16*1024**2+1)
    with pytest.raises(r.Failure):c.evidence_package(run,{'status':'FAIL'},tmp_path/'out')


def test_partial_assets_reject_modified_attribution(tmp_path,monkeypatch):
    run=setup_work(tmp_path,monkeypatch)
    (run.work/'real-input-attribution.txt').write_bytes(b'changed attribution')
    with pytest.raises(r.Failure,match='notice changed'):c.evidence_package(run,{'status':'FAIL'},tmp_path/'out')
