"""Closed profile policy controls; synthetic proofs are not reconstruction evidence."""
from dataclasses import FrozenInstanceError, replace
import hashlib
import pytest
from tools.eight_view_host import experiments as e, policy as p
from tools.eight_view_host.runtime import Failure


def proof(profile):
    selected=e.get_profile(profile)
    ids=[str(i) for i in range(selected.photo_count)]
    images={'images/'+i+'.png':hashlib.sha256(i.encode()).hexdigest() for i in ids}
    identity=[{'photoId':i,'fixturePath':'images/'+i+'.png','imageSha256':images['images/'+i+'.png']} for i in ids]
    result={'classification':selected.classification,'inputPhotoIds':ids,'registeredPhotoIds':ids.copy(),'unregisteredPhotoIds':[], 'geometry':{'finalSparseOutcome':'global_typed_ba_admitted12'}}
    pairs=[{'phase':'READY','pairCount':selected.pair_count,'pairNumber':i,'geometryEntered':True} for i in range(1,selected.pair_count+1)]
    return result,pairs,identity,{'images':images}

@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF])
def test_exact_closed_profile_measurement(profile):
    selected=e.get_profile(profile)
    result=p.measurement_proof(*proof(profile),profile=profile)
    assert result['registeredCameraCount']==selected.photo_count
    assert result['pairCount']==selected.pair_count
    assert result['classification']==selected.classification


def test_legacy_default_is_unchanged():
    assert p.measurement_proof(*proof(e.LEGACY))==p.measurement_proof(*proof(e.LEGACY),profile=e.LEGACY)
    assert e.get_profile(e.LEGACY).selector==p.SELECTOR
    with pytest.raises(Failure):p.measurement_proof(*proof(e.RELIEF))

@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF])
def test_cross_profile_measurement_refused(profile):
    other=e.RELIEF if profile==e.LEGACY else e.LEGACY
    with pytest.raises(Failure):p.measurement_proof(*proof(other),profile=profile)

@pytest.mark.parametrize('bad',[None,True,3,{},[], 'relief', 'RELIEF-THREE', 'relief-three ', 'eight-cuboid\n'])
def test_unknown_profile_refused(bad,tmp_path):
    with pytest.raises(Failure):e.get_profile(bad)
    with pytest.raises(Failure):p.measurement_proof(*proof(e.RELIEF),profile=bad)
    with pytest.raises(Failure):p.junit_proof(tmp_path/'missing',[],profile=bad)


def test_records_frozen_and_caller_thresholds_not_accepted():
    selected=e.get_profile(e.RELIEF)
    with pytest.raises(FrozenInstanceError):selected.photo_count=1
    forged=replace(selected,photo_count=1,pair_count=0)
    with pytest.raises(Failure):e.get_profile(forged)
    with pytest.raises(Failure):p.measurement_proof(*proof(e.RELIEF),profile=forged)
    assert e.ACTIVE==e.COMPLETE

@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF])
@pytest.mark.parametrize('mutation',['classification','outcome','missing-photo','unregistered','missing-pair','duplicate-pair','wrong-pair-count','bool-ordinal','failed-phase','identity','observations'])
def test_profile_measurement_tamper_refused(profile,mutation):
    result,pairs,identity,observations=proof(profile)
    if mutation=='classification':result['classification']='PASS'
    elif mutation=='outcome':result['geometry']['finalSparseOutcome']='fallback'
    elif mutation=='missing-photo':result['registeredPhotoIds'].pop()
    elif mutation=='unregistered':result['unregisteredPhotoIds']=['x']
    elif mutation=='missing-pair':pairs.pop()
    elif mutation=='duplicate-pair':pairs[-1]=pairs[0].copy()
    elif mutation=='wrong-pair-count':pairs[0]['pairCount']=28 if profile==e.RELIEF else 3
    elif mutation=='bool-ordinal':pairs[0]['pairNumber']=True
    elif mutation=='failed-phase':pairs[0]['phase']='FAILED'
    elif mutation=='identity':identity[0]['imageSha256']='0'*64
    elif mutation=='observations':observations['images']['images/foreign.png']='0'*64
    with pytest.raises(Failure):p.measurement_proof(result,pairs,identity,observations,profile=profile)

@pytest.mark.parametrize('profile',[e.LEGACY,e.RELIEF])
def test_junit_profile_identity_and_events(tmp_path,profile):
    selected=e.get_profile(profile);cls,method=selected.selector.rsplit('.',1)
    xml=tmp_path/'result.xml';xml.write_text(f'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase classname="{cls}" name="{method}"/></testsuite>')
    events=[{'event':'start','class':cls,'method':method},{'event':'finish','class':cls,'method':method,'result':'SUCCESS'}]
    assert p.junit_proof(xml,events,profile=profile)['selector']==selected.selector
    other=e.RELIEF if profile==e.LEGACY else e.LEGACY
    with pytest.raises(Failure):p.junit_proof(xml,events,profile=other)
    for bad in ([],events[:1],events+events,events[::-1]):
        with pytest.raises(Failure):p.junit_proof(xml,bad,profile=profile)
    xml.write_text(xml.read_text().replace('/></testsuite>','><failure/></testcase></testsuite>'))
    with pytest.raises(Failure):p.junit_proof(xml,events,profile=profile)


def test_profile_literals_do_not_follow_environment(monkeypatch):
    monkeypatch.setenv('RF_EXPERIMENT_PROFILE','eight-cuboid')
    monkeypatch.setenv('RF_PHOTO_COUNT','1')
    monkeypatch.setenv('RF_PAIR_COUNT','0')
    legacy=e.get_profile(e.LEGACY);relief=e.get_profile(e.RELIEF)
    assert (legacy.id,legacy.fixture,legacy.input_module,legacy.test_source,legacy.photo_count,legacy.pair_count,legacy.classification)==(
        'eight-cuboid','docs/development/experiments/new-cuboid-eight-view-160x120','inputs.py','EightViewSparsePrefixHostTest.kt',8,28,'ALL8_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY')
    assert (relief.id,relief.fixture,relief.input_module,relief.test_source,relief.photo_count,relief.pair_count,relief.classification)==(
        'relief-three','benchmarks/fixtures/relief','relief_inputs.py','ReliefThreeSparsePrefixHostTest.kt',3,3,'ALL3_SPARSE_ACCEPTED_NOT_SURFACE_QUALITY')
    assert relief.selector=='com.realityforge.scanner.hostintegration.ReliefThreeSparsePrefixHostTest.fixedThreePhotosPersistSparseOrRecordUnmodifiedProductionFailure'
    assert e.ACTIVE=='relief-three-complete'
    complete=e.get_profile(e.COMPLETE)
    assert complete.selector=='com.realityforge.scanner.hostintegration.ReliefThreeCompleteControllerHostTest.fixedThreePhotosCompletePublishReopenAndExportOrFail'
    assert complete.fixture==relief.fixture and complete.input_module==relief.input_module
    assert complete.photo_count==3 and complete.classification=='SYNTHETIC_HOST_FULL_CONTROLLER_EXPORT_NOT_DEVICE_QUALITY'
