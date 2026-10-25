"""Admission tests only; no production reconstruction or Android execution claims."""
import hashlib
import json
from pathlib import Path
import struct
from types import SimpleNamespace
import pytest
from tools.eight_view_host import experiments as e, controller as c, sparse_evidence as s
from tools.eight_view_host.runtime import Failure
ROOT=Path(__file__).resolve().parents[1]

def test_separate_closed_profile_and_unchanged_active_caps():
    p=e.get_profile(e.REAL_EIGHT_ORBIT)
    assert (p.photo_count,p.pair_count)==(8,28)
    assert p.id in e.REAL_PROFILES and p.id not in e.COMPLETE_PROFILES
    assert e.ACTIVE==e.REAL_EIGHT_ORBIT
    assert e.artifact_budget(p.id)==32*1024**2
    assert e.artifact_budget(e.REAL_THREE)==56*1024**2
    assert c.source_names(p.id)[1]=='RealLicensedEightOrbitSparsePrefixHostTest.kt'

def env(magic=b'RFAC',payload=b'abc'):
    return magic+struct.pack('>ii',1,len(payload))+hashlib.sha256(payload).digest()+payload

@pytest.mark.parametrize('name,raw', [('original.JPG',b'\xff\xd8x'),('texture.png',b'\x89PNG\r\n\x1a\n'),('complete-result.json',b'{}'),('manifest-MATCHES.rfsm',env(b'RFSM')),('rebound-alignment.rfac',env()),('artifact-'+'a'*64+'.bin',b'RFM3'),('sparse-timing.json',b'{"x":NaN}')])
def test_unreviewed_raw_or_dense_files_rejected(name,raw):
    with pytest.raises(Failure):s.validate_path(name,raw)

def test_privacy_preparation_and_source_images_refused():
    with pytest.raises(Failure):c.real_evidence_privacy_check('measurement/pair-events.json',b'[]',profile=e.REAL_EIGHT_ORBIT)
    run=SimpleNamespace(real_private_markers=(b'private-camera-tag',))
    c.real_evidence_privacy_check('measurement/pair-events.json',b'[]',run,profile=e.REAL_EIGHT_ORBIT)
    for raw in (b'\xff\xd8original',b'{"x":"private-camera-tag"}',b'{"x":"MakerNote"}'):
        with pytest.raises(Failure):c.real_evidence_privacy_check('measurement/sparse-result.json',raw,run,profile=e.REAL_EIGHT_ORBIT)

def test_timing_is_host_not_device():
    d={'schema':1,'elapsedMillis':10,'scope':'HOST_MONOTONIC_NOT_DEVICE_PERFORMANCE','featureEvents':[],'pairEvents':[]};s.timings(d)
    with pytest.raises(Failure):s.timings({**d,'scope':'DEVICE'})

def test_new_test_keeps_stop_and_strict_partial_boundary():
    text=(ROOT/c.ADAPTER/'RealLicensedEightOrbitSparsePrefixHostTest.kt').read_text()
    assert '@Test(timeout = 120_000)' in text and 'withTimeout(90_000)' in text
    assert 'awaitCancellation()' in text and 'stage.order <= ReconstructionStage.SPARSE.order' in text
    assert text.index('captureImmutable(evidence, "sparse-result.json"')<text.index('val strict =')
    assert text.index('fixture.reopen()')<text.index('val strict =')
    assert 'global_typed_ba_admitted12' in text and 'allInputVerifiedEdgeIdentities' in text


def test_python_kotlin_gradle_selector_agree():
    profile=e.get_profile(e.REAL_EIGHT_ORBIT)
    method=profile.selector.rsplit('.',1)[1]
    source=(ROOT/c.ADAPTER/profile.test_source).read_text()
    assert 'fun '+method+'()' in source
    gradle=(ROOT/'tools/eight_view_host/probe.init.gradle').read_text()
    assert "'"+profile.id+"':[type:'"+Path(profile.test_source).stem+"',method:'"+method+"']" in gradle


def test_failure_package_identity_binding(monkeypatch,tmp_path):
    import importlib.util
    path=ROOT/c.ADAPTER/'real_eight_orbit_inputs.py'
    spec=importlib.util.spec_from_file_location('fixed_orbit',path);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    monkeypatch.setattr(c,'load_adapter',lambda *a:(path.parent,module))
    measurement=tmp_path/'measurement';measurement.mkdir()
    ids=[{'photoId':f'00000000-0000-4000-8000-{i:012d}','fixturePath':'images/'+name,'imageSha256':digest} for i,(name,digest) in enumerate(module.HASHES.items())]
    file=measurement/'input-identity.json';file.write_text(json.dumps(ids))
    (measurement/'failure.json').write_text(json.dumps({'schema':1,'status':'FAILED_NOT_QUALITY_PASS','phase':'BEFORE_OR_AT_SPARSE'}))
    run=SimpleNamespace(work=tmp_path,real_private_markers=())
    monkeypatch.setattr(c,'phase_budget',lambda *a,**k:100)
    c.validate_real_sparse_package(run)
    ids[0]['imageSha256']='f'*64;file.write_text(json.dumps(ids))
    with pytest.raises(Failure):c.validate_real_sparse_package(run)


def test_partial_records_require_full_crossbinding(monkeypatch,tmp_path):
    measurement=tmp_path/'measurement';measurement.mkdir()
    monkeypatch.setattr(c,'phase_budget',lambda *a,**k:100)
    monkeypatch.setattr(c,'real_evidence_privacy_check',lambda *a,**k:None)
    monkeypatch.setattr(s,'validate_path',lambda *a:{'registeredPhotoIds':[]})
    (measurement/'sparse-result.json').write_text('{}')
    with pytest.raises(Failure,match='Incomplete retained sparse record binding'):
        c.validate_real_sparse_package(SimpleNamespace(work=tmp_path,real_private_markers=()))
