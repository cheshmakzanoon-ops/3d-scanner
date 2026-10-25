"""Typed synthetic cache diagnostics, never production matching/reconstruction evidence."""
import copy
import hashlib
import itertools
import json
from pathlib import Path
import runpy
from types import SimpleNamespace

import pytest
from tools.eight_view_host import sparse_evidence as s
from tools.eight_view_host.runtime import Failure

ROOT=Path(__file__).resolve().parents[1]
NAME='cache-pair-summary.json'


def fixture():
    identities=[{'photoId':f'00000000-0000-4000-8000-{i:012d}',
                 'fixturePath':name,'imageSha256':sha} for i,(name,sha) in enumerate(s.INPUT_HASHES.items())]
    features=[{'photoId':r['photoId'],'imageSha256':r['imageSha256'],
               'width':1600,'height':1067,'featureCount':100+i} for i,r in enumerate(identities)]
    proof=[]
    for role,count in [('FEATURE',8),('MATCHES',1),('MATCHES_CHECKSUM',1),('MANIFEST',2),('INPUT_RECORD',1)]:
        for i in range(count):
            digest=hashlib.sha256(f'{role}{i}'.encode()).hexdigest()
            proof.append({'role':role,'index':i,'bytes':64,'beforeSha256':digest,'afterSha256':digest,'accessTimeChanged':False})
    pairs=[];events=[]
    for ordinal,(i,j) in enumerate(itertools.combinations(range(8),2),1):
        count=25 if ordinal==14 else ordinal%19
        pairs.append({'ordinal':ordinal,'firstPhotoId':features[i]['photoId'],'secondPhotoId':features[j]['photoId'],
            'firstDescriptorRows':features[i]['featureCount'],'secondDescriptorRows':features[j]['featureCount'],
            'reciprocalMatchCount':count,'distinctKeyPairCount':count,'distinctCoordinatePairCount':max(0,count-1),
            'distinctNormalizedRayPairCount':max(1,count-1) if count else 0,'geometryEntered':count>=20})
        if count==1:pairs[-1]['distinctCoordinatePairCount']=1
        event={'pairNumber':ordinal,'pairCount':28,'phase':'READY','firstDescriptorRows':features[i]['featureCount'],
               'secondDescriptorRows':features[j]['featureCount'],'geometryEntered':count>=20,'matchCacheHit':False}
        if count>=20:events.append({**event,'phase':'GEOMETRY'})
        events.append(event)
    summary={'schema':1,'scope':'FAILED_INITIALIZER_CACHE_DIAGNOSTIC',
        'snapshotRecordSha256':proof[-1]['beforeSha256'],'matchingStageSha256':'b'*64,
        'cacheProofs':proof,'features':features,'pairs':pairs,
        'coordinatePairDomain':'OBSERVED_PIXEL_QUADRUPLE_EXACT',
        'metadataPolicy':'CONTENT_IDENTITY_SIZE_CREATION_MODIFIED_UNIX_OWNER_MODE_CHANGE_TIME',
        'originalNativeCategory':'NOT_RETAINED','replay':{'status':'NOT_RUN','reason':'REPLAY_UNAVAILABLE'}}
    return summary,identities,events


def encode(v):return json.dumps(v).encode()


def test_closed_summary_and_original_event_crossbinding():
    summary,identity,events=fixture()
    assert len(encode(summary))<=32768
    s.validate_path(NAME,encode(summary));s.validate_cache_records(summary,identity,events)
    assert summary['pairs'][13]['firstDescriptorRows'] != summary['pairs'][13]['reciprocalMatchCount']


@pytest.mark.parametrize('target',['root','feature','pair','proof','replay'])
def test_no_private_text_extension(target):
    value,_,_=fixture()
    item={'root':value,'feature':value['features'][0],'pair':value['pairs'][0],
          'proof':value['cacheProofs'][0],'replay':value['replay']}[target]
    item['unreviewed']='/private/owner'
    with pytest.raises(Failure):s.validate_path(NAME,encode(value))


@pytest.mark.parametrize('mutation',[
    'missing-feature','foreign-hash','foreign-id','duplicate-pair','reversed-pair','wrong-ordinal',
    'rows-too-large','distinct-key-mismatch','distinct-coordinate-overflow','distinct-ray-overflow','geometry-mismatch',
    'cache-change','missing-proof','duplicate-proof','proof-bytes','input-proof-mismatch',
    'native-text','native-replay','coordinate-domain','duplicate-json','oversize'],ids=str)
def test_malformed_cache_summary_rejected(mutation):
    v,_,_=fixture()
    if mutation=='missing-feature':v['features'].pop()
    elif mutation=='foreign-hash':v['features'][0]['imageSha256']='f'*64
    elif mutation=='foreign-id':v['features'][0]['photoId']='/private/photo'
    elif mutation=='duplicate-pair':v['pairs'][1]=copy.deepcopy(v['pairs'][0]);v['pairs'][1]['ordinal']=2
    elif mutation=='reversed-pair':
        p=v['pairs'][0];p['firstPhotoId'],p['secondPhotoId']=p['secondPhotoId'],p['firstPhotoId']
    elif mutation=='wrong-ordinal':v['pairs'][0]['ordinal']=2
    elif mutation=='rows-too-large':v['pairs'][0]['reciprocalMatchCount']=4001
    elif mutation=='distinct-key-mismatch':v['pairs'][0]['distinctKeyPairCount']=0
    elif mutation=='distinct-coordinate-overflow':v['pairs'][0]['distinctCoordinatePairCount']=2
    elif mutation=='distinct-ray-overflow':v['pairs'][0]['distinctNormalizedRayPairCount']=2
    elif mutation=='geometry-mismatch':v['pairs'][0]['geometryEntered']=True
    elif mutation=='cache-change':v['cacheProofs'][0]['afterSha256']='f'*64
    elif mutation=='missing-proof':v['cacheProofs'].pop(0)
    elif mutation=='duplicate-proof':v['cacheProofs'][1]=copy.deepcopy(v['cacheProofs'][0])
    elif mutation=='proof-bytes':v['cacheProofs'][0]['bytes']=12*1024**2+1
    elif mutation=='input-proof-mismatch':v['snapshotRecordSha256']='c'*64
    elif mutation=='native-text':v['originalNativeCategory']='arbitrary original error'
    elif mutation=='native-replay':v['replay']={'status':'CALLED','reason':'REPLAY_UNAVAILABLE'}
    elif mutation=='coordinate-domain':v['coordinatePairDomain']='DESCRIPTOR_ROWS'
    raw=encode(v)
    if mutation=='duplicate-json':raw=raw.replace(b'"schema": 1',b'"schema": 1, "schema": 1')
    if mutation=='oversize':raw+=b' '*32768
    with pytest.raises(Failure):s.validate_path(NAME,raw)


@pytest.mark.parametrize('mutation',['identity-order','identity-id','ready-missing','ready-duplicate','descriptor-count','geometry-missing','geometry-extra','geometry-duplicate','cache-hit'])
def test_actual_input_and_event_disagreement_rejected(mutation):
    v,i,e=fixture()
    if mutation=='identity-order':i.reverse()
    elif mutation=='identity-id':i[0]['photoId']='ffffffff-ffff-ffff-ffff-ffffffffffff'
    elif mutation=='ready-missing':e.pop()
    elif mutation=='ready-duplicate':e.append(copy.deepcopy(e[-1]))
    elif mutation=='descriptor-count':e[-1]['firstDescriptorRows']+=1
    elif mutation=='geometry-missing':e=[p for p in e if p['phase']!='GEOMETRY']
    elif mutation=='geometry-extra':e.append({**e[-1],'phase':'GEOMETRY'})
    elif mutation=='geometry-duplicate':e.append(copy.deepcopy(next(p for p in e if p['phase']=='GEOMETRY')))
    elif mutation=='cache-hit':e[-1]['matchCacheHit']=True
    with pytest.raises(Failure):s.validate_cache_records(v,i,e)


def script_package(tmp_path,*,private=False):
    module=runpy.run_path(str(ROOT/'tools/eight_view_host/controller.py'),run_name='cache_diagnostic_script')
    assert module['__package__']==''
    summary,identity,events=fixture()
    directory=tmp_path/'work/measurement';directory.mkdir(parents=True)
    docs={NAME:summary,'input-identity.json':identity,'pair-events.json':events,
          'failure.json':{'schema':1,'status':'FAILED_NOT_QUALITY_PASS','phase':'BEFORE_OR_AT_SPARSE'}}
    if private:summary['pairs'][0]['private']='private-owner-marker'
    for name,value in docs.items():(directory/name).write_bytes(encode(value))
    run=SimpleNamespace(work=directory.parent,real_private_markers=(b'private-owner-marker',),
                        remaining=lambda maximum,**kwargs:maximum,commands=[])
    return module,run,directory


def test_script_failed_package_retains_only_safe_cache_summary(tmp_path):
    module,run,directory=script_package(tmp_path)
    out=module['evidence_package'](run,{'status':'FAIL'},tmp_path/'out')
    assert (out/'measurement'/NAME).read_bytes()==(directory/NAME).read_bytes()
    receipt=json.loads((out/'receipt.json').read_bytes())
    assert receipt['status']=='FAIL' and receipt['evidence']['complete']


@pytest.mark.parametrize('mutation',['private','no-identity','no-events','no-failure','event-drift','artifact-inventory'])
def test_script_package_context_refusal(tmp_path,mutation):
    module,run,directory=script_package(tmp_path,private=mutation=='private')
    if mutation.startswith('no-'):
        name={'no-identity':'input-identity.json','no-events':'pair-events.json','no-failure':'failure.json'}[mutation]
        (directory/name).unlink()
    elif mutation=='event-drift':
        p=directory/'pair-events.json';v=json.loads(p.read_bytes());v[-1]['firstDescriptorRows']+=1;p.write_bytes(encode(v))
    elif mutation=='artifact-inventory':
        (directory/'artifact-inventory.json').write_bytes(encode([{'stage':'FEATURES','sha256':'a'*64,'bytes':1}]))
    with pytest.raises(Failure):module['evidence_package'](run,{'status':'FAIL'},tmp_path/'out')
    assert not list((tmp_path/'out').rglob('*'))


@pytest.mark.parametrize('status',['PASS','CANCELLED','TIMEOUT','PENDING_RESTORATION'])
def test_cache_summary_never_qualifies_other_terminal_status(tmp_path,status):
    module,run,_=script_package(tmp_path)
    with pytest.raises(Failure,match='requires failed eight-orbit'):
        module['evidence_package'](run,{'status':status},tmp_path/'out')
    assert not (tmp_path/'out').exists()


def test_kotlin_capture_is_counts_only_bounded_and_preserves_primary():
    text=(ROOT/'docs/development/experiments/eight-view-sparse-runtime-successor/RealLicensedEightOrbitSparsePrefixHostTest.kt').read_text()
    helper=text.split('private class FailureCacheSummary',1)[1].split('private class Fixture',1)[0]
    for production in ('FeatureCacheFormat.decode','FeatureMatchCache(', 'matchCache.load(checkActive)',
                       'PhotoFeatureSummary.siftMean','AdmittedPairPlan.build','CalibratedInitializerInput.create',
                       'reader.verify(matching)','reader.input(plan.snapshot.recordHash)'):
        assert production in helper
    assert 'NativeCore.' not in helper and 'SparsePipeline(' not in helper
    assert '.save(' not in helper and '.store(' not in helper and '.setLastModified(' not in helper
    assert '.artifactFile(' not in helper and 'SparseFeatureCache(File' not in helper
    assert 'require(bytes.size <= 32 * 1024)' in helper
    assert 'check(System.nanoTime() - experimentStartedNanos < 90_000_000_000L)' in text
    assert 'check(worker?.isCompleted == true' in text
    assert 'failure.addSuppressed(IllegalStateException("CACHE_SUMMARY_UNAVAILABLE"))' in text
    assert 'failure.addSuppressed(diagnostic)' not in text
    assert 'require(after.sameProtectedMetadata(item.before))' in helper
    assert 'unix:uid,gid,mode,ctime' in helper
    assert '.put("accessTimeChanged", accessTimeChanged)' in helper
    assert '.put("reason", "REPLAY_UNAVAILABLE")' in helper


def test_access_time_movement_is_explicit_read_bookkeeping_not_mutation():
    summary,identity,events=fixture()
    summary['cacheProofs'][0]['accessTimeChanged']=True
    s.validate_path(NAME,encode(summary));s.validate_cache_records(summary,identity,events)
    summary['cacheProofs'][0]['accessTimeChanged']='private text'
    with pytest.raises(Failure):s.validate_path(NAME,encode(summary))


@pytest.mark.parametrize('count',[1,3])
def test_exactly_two_feature_matching_manifest_proofs(count):
    v,_,_=fixture()
    v['cacheProofs']=[p for p in v['cacheProofs'] if p['role']!='MANIFEST']
    for index in range(count):
        v['cacheProofs'].append({'role':'MANIFEST','index':index,'bytes':64,'beforeSha256':'a'*64,
                               'afterSha256':'a'*64,'accessTimeChanged':False})
    with pytest.raises(Failure):s.validate_path(NAME,encode(v))
