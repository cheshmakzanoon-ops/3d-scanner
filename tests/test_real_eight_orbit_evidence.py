"""Synthetic closed-schema/privacy adversaries; no eight-photo runtime claim."""
import copy
import json
from types import SimpleNamespace
import pytest
from tools.eight_view_host import sparse_evidence as s, controller as c, experiments as e
from tools.eight_view_host.runtime import Failure

IDS=['00000000-0000-0000-0000-%012d'%i for i in range(8)]
def records():
    result={'schema':1,'synthetic':False,'classification':'INCOMPLETE_OR_FALLBACK',
        'inputPhotoIds':IDS,'registeredPhotoIds':IDS[:2],'unregisteredPhotoIds':IDS[2:],
        'geometry':{'finalSparseOutcome':'verified_anchor_fallback','pointCount':1}}
    cameras=[{'photoId':i,'width':1600,'height':1067,'fx':3911.,'fy':3911.,'cx':799.5,'cy':533.,
        'rotationWorldToCamera':[1,0,0,0,1,0,0,0,1],'translationWorldToCamera':[0,0,0]} for i in IDS[:2]]
    diag={'schema':1,'graphScope':'REGISTERED_CAMERA_CHECKPOINT_GRAPH_NOT_ALL_INPUT_INITIALIZER_EDGES',
        'allInputVerifiedEdgeIdentities':'NOT_EXPOSED_BY_PRODUCTION_DIAGNOSTICS',
        'registeredPhotoIds':IDS[:2],'trackCount':1,'observationCount':2,'cameras':cameras,
        'tracks':[{'ordinal':0,'x':1.,'y':2.,'z':3.,'reprojectionRmsNormalized':.001,
            'observations':[{'cameraIndex':i,'featureIndex':7,'xNormalized':.01,'yNormalized':.02} for i in range(2)]}],
        'verifiedMatchGraph':[{'firstCamera':0,'secondCamera':1,'firstPhotoId':IDS[0],'secondPhotoId':IDS[1],
            'verifiedMatches':30,'inliers':25,'medianErrorNormalized':.001}]}
    identity=[{'photoId':IDS[i],'imageSha256':s.INPUT_HASHES['images/%02d.JPG'%(i*2+1)],'fixturePath':'images/%02d.JPG'%(i*2+1)} for i in range(8)]
    return result,diag,identity

def encode(v):return json.dumps(v).encode()
def validate(name,v):return s.validate_path(name,encode(v))

def test_partial_numeric_record_is_not_success():
    r,d,i=records();s.validate_records(r,d,i)
    for name,value in [('sparse-result.json',r),('sparse-diagnostics.json',d),('input-identity.json',i)]:validate(name,value)
    with pytest.raises(Failure):validate('sparse-result.json',{**r,'classification':s.CLASSIFICATION})

def test_full_result_requires_global_ba_and_typed_stats():
    r,_,_=records();r.update(registeredPhotoIds=IDS,unregisteredPhotoIds=[])
    r['classification']=s.CLASSIFICATION
    with pytest.raises(Failure):validate('sparse-result.json',r)
    r['geometry']['finalSparseOutcome']='global_typed_ba_admitted12'
    with pytest.raises(Failure):validate('sparse-result.json',r)
    r['geometry']['bundleAdjustment']={'inputObservations':8,'usableObservations':7,'rejectedObservations':1,
        'acceptedSteps':2,'attemptedIterations':12,'robustCostBefore':.01,'robustCostAfter':.001,
        'medianBeforeNormalized':.01,'medianAfterNormalized':.001}
    validate('sparse-result.json',r)
    r['geometry']['bundleAdjustment']['acceptedSteps']=True
    with pytest.raises(Failure):validate('sparse-result.json',r)

@pytest.mark.parametrize('bad',[True,None,'private value',float('nan'),float('inf'),10**400],ids=['bool','null','text','nan','inf','huge-int'])
def test_numeric_nonfinite_and_type_refusal(bad):
    _,d,_=records();d['tracks'][0]['x']=bad
    with pytest.raises(Failure):validate('sparse-diagnostics.json',d)

@pytest.mark.parametrize('where',['root','camera','track','observation','edge'])
def test_extra_fields_cannot_carry_private_data(where):
    _,d,_=records()
    target={'root':d,'camera':d['cameras'][0],'track':d['tracks'][0],
        'observation':d['tracks'][0]['observations'][0],'edge':d['verifiedMatchGraph'][0]}[where]
    target['private']='/private/owner/path'
    with pytest.raises(Failure):validate('sparse-diagnostics.json',d)

@pytest.mark.parametrize('name',['rebound-alignment.rfac','manifest-MATCHES.rfsm','input-'+'a'*64+'.rfis','artifact-'+'a'*64+'.bin','complete-result.json','original.JPG','texture.png'])
def test_no_opaque_cache_image_export_retention(name):
    with pytest.raises(Failure):s.validate_path(name,b'{}')

@pytest.mark.parametrize('raw',[b'{"schema":1,"schema":1}',b'{"x":NaN}',b'[]',b'null',b'{}',b'\xff',b'['*1100],ids=['duplicate','nan','list','null','empty','unicode','depth'])
def test_failure_requires_exact_closed_record(raw):
    with pytest.raises(Failure):s.validate_path('failure.json',raw)

def test_fixed_failure_and_marker_filter_on_failed_retention():
    value={'schema':1,'status':'FAILED_NOT_QUALITY_PASS','phase':'BEFORE_OR_AT_SPARSE'}
    run=SimpleNamespace(real_private_markers=(b'secret-owner',))
    c.real_evidence_privacy_check('measurement/failure.json',encode(value),run,profile=e.REAL_EIGHT_ORBIT)
    for extra in ({'message':'arbitrary exception'}, {'message':'secret-owner'}, {'type':'java.lang.Error'}, {'path':'/private/path'}):
        with pytest.raises(Failure):c.real_evidence_privacy_check('measurement/failure.json',encode({**value,**extra}),run,profile=e.REAL_EIGHT_ORBIT)
    with pytest.raises(Failure):c.real_evidence_privacy_check('measurement/failure.json',encode(value),profile=e.REAL_EIGHT_ORBIT)

def test_uuid_ids_and_counts_cross_bound():
    r,d,i=records()
    for mutate in [lambda x:x['cameras'][0].update(photoId=IDS[3]),
        lambda x:x.update(observationCount=3),lambda x:x.update(trackCount=2),
        lambda x:x['tracks'][0]['observations'][1].update(cameraIndex=0),
        lambda x:x['tracks'][0]['observations'][1].update(featureIndex=-1),
        lambda x:x['tracks'][0].update(ordinal=1),
        lambda x:x['verifiedMatchGraph'][0].update(secondCamera=8)]:
        altered=copy.deepcopy(d);mutate(altered)
        with pytest.raises(Failure):validate('sparse-diagnostics.json',altered)
    wrong=copy.deepcopy(r);wrong['geometry']['pointCount']=2
    with pytest.raises(Failure):s.validate_records(wrong,d,i)
    r['inputPhotoIds']=list(IDS);r['inputPhotoIds'][0]='/private/path'
    with pytest.raises(Failure):validate('sparse-result.json',r)

def test_hash_inventory_has_no_path_or_untyped_content():
    v=[{'stage':'ALIGNMENT','sha256':'a'*64,'bytes':123}];validate('artifact-inventory.json',v)
    for extra in ({'path':'private.db'},{'bytes':True},{'stage':'DENSE'},{'sha256':'bad'}):
        with pytest.raises(Failure):validate('artifact-inventory.json',[{**v[0],**extra}])
    with pytest.raises(Failure):validate('artifact-inventory.json',v*2)

def test_failure_pair_timings_are_retained_but_no_text_fields():
    v={'schema':1,'elapsedMillis':90,'scope':'HOST_MONOTONIC_NOT_DEVICE_PERFORMANCE','featureEvents':[],
        'pairEvents':[{'pairNumber':1,'phase':'FAILED','elapsedMillis':3,'loadElapsedMillis':1,'matchElapsedMillis':1,'geometryElapsedMillis':1}]}
    validate('sparse-timing.json',v)
    v['pairEvents'][0]['message']='private failure'
    with pytest.raises(Failure):validate('sparse-timing.json',v)


def test_identity_fixed_hashes_apply_on_failure_retention_too():
    _,_,identity=records();identity[0]['imageSha256']='a'*64
    with pytest.raises(Failure):validate('input-identity.json',identity)


def test_validator_capture_hashes_match_input_adapter():
    import importlib.util
    spec=importlib.util.spec_from_file_location('eight_inputs',c.ROOT/c.ADAPTER/'real_eight_orbit_inputs.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    assert s.INPUT_HASHES=={'images/'+k:v for k,v in module.HASHES.items()}

def runtime_record():
    scanner=[{'role':role,'path':'/owned/'+role+'.so','sha256':'a'*64,'inode':i+1} for i,role in enumerate(('opencv','shim','engine'))]
    provider={'path':'/owned/runtime-tmp/libandroid_runtime.so','sha256':'b'*64,'bytes':15526048,'inode':4,
        'archivePath':'/owned/android-all.jar','archiveSha256':'c'*64,'archiveEntry':'native/linux/x86_64/libandroid_runtime.so'}
    return {'schema':1,'phase':'after-bitmap-room','status':'OBSERVED_PROVIDER_IDENTITIES_MATCH',
        'capabilitySha256':'a'*64,'policySha256':'b'*64,'sourceSha':'c'*40,'sqliteVersion':'3.45.0',
        'activity':['actual Bitmap pixel roundtrip','actual Room SELECT sqlite_version()'],
        'observedMappings':[{'path':r['path'],'inode':r['inode']} for r in [*scanner,provider]],
        'scannerProviders':scanner,'scannerElfDependencies':[{'path':'/lib/libc.so','sha256':'d'*64,'inode':5}],
        'androidRuntimeProvider':provider}

def test_runtime_exact_structure_not_arbitrary_json_or_failure_text():
    v=runtime_record();name='runtime-providers-after-bitmap-room.json';validate(name,v)
    for bad in ({**v,'failure':'private exception'},{**v,'status':'UNQUALIFIED'},
                {**v,'observedMappings':[]},{**v,'sourceSha':'wrong'}):
        with pytest.raises(Failure):validate(name,bad)
    for path in ('/owned/../private','/owned/secret\nowner','relative'):
        bad=copy.deepcopy(v);bad['scannerProviders'][0]['path']=path
        with pytest.raises(Failure):validate(name,bad)
    bad=copy.deepcopy(v);bad['scannerElfDependencies'][0]['raw']='metadata'
    with pytest.raises(Failure):validate(name,bad)

@pytest.mark.parametrize('name',sorted(s.JSON_FILES))
def test_all_paths_reject_unknown_root_field_or_wrong_container(name):
    with pytest.raises(Failure):validate(name,{'unreviewed':'private value'})

@pytest.mark.parametrize('field,value',[('fx',0),('width',True),('cx',999999),('rotationWorldToCamera',[1]*8)])
def test_camera_bounds(field,value):
    _,d,_=records();d['cameras'][0][field]=value
    with pytest.raises(Failure):validate('sparse-diagnostics.json',d)
