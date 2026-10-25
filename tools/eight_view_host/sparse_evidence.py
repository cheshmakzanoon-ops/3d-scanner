"""Closed numeric summaries for the fixed eight-photo sparse diagnostic.

No cache envelope, descriptor, original image, arbitrary text or artifact bytes.
Runtime-provider paths are the existing own-JVM identity exception; the controller
must additionally bind them to its authoritative prepared capability.
"""
import json
import math
import re
from .runtime import Failure

CLASSIFICATION = 'REAL_LICENSED_EIGHT_WIDER_ORBIT_HOST_SPARSE_PREFIX_NOT_DEVICE_OR_SURFACE_QUALITY'
UUID = r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
# Closed public capture identities, independently matched to the pinned input adapter.
INPUT_HASHES = dict(zip(('images/%02d.JPG'%i for i in range(1,16,2)), (
    '49cc69b7e2e8999252c71536cc5a8a0ee32ae25d56d896969542653fa93e4858',
    '3aae3bbf830469ea8711915545627692b9655de9f881fa0b9a128543b8fb4be1',
    '762c2fe0f5780bc57ae7c3e65bafc5046c60756b65428576aa47d54df03e994c',
    'cef6c51017e11d2c35f359f80920a28d866c6bc7d1d4f6e876e1718a328ec79c',
    '6190f02734af4cd137426afba19352d2c3b412e4016ec844eae61c882e341eb4',
    'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c',
    '06074dbb72eaf56786bfcb9d69da66aa47657f076090d9e470ba2e5c19169700',
    '3366c2efb3f7b70114b94792b9c6b85141b4631111f8bb0f447d370ca0fb098f')))
JSON_FILES = frozenset(('input-identity.json','failure.json','sparse-result.json',
    'pair-events.json','cache-pair-summary.json','sparse-diagnostics.json','sparse-timing.json','artifact-inventory.json',
    'runtime-providers-after-bitmap-room.json','runtime-providers-after-room-reopen.json'))

def require(ok):
    if not ok: raise Failure('Invalid eight-orbit sparse evidence')

def fields(v, required, optional=()):
    require(type(v) is dict and set(required)<=set(v)<=set(required)|set(optional))

def integer(v, lo=0, hi=100000):
    require(type(v) is int and lo<=v<=hi)

def number(v, lo=-1e12, hi=1e12):
    require(type(v) in (int,float) and lo<=v<=hi and math.isfinite(v))

def text(v, pattern):
    require(type(v) is str and re.fullmatch(pattern,v) is not None)

def rows(v, lo=0, hi=100000):
    require(type(v) is list and lo<=len(v)<=hi)

def ids(v, lo=0, hi=8):
    rows(v,lo,hi)
    for item in v: text(item,UUID)
    require(len(set(v))==len(v))

def numeric_array(v,n):
    rows(v,n,n)
    for item in v:number(item)

def parse(raw):
    require(type(raw) is bytes and 0<len(raw)<=16*1024**2)
    def unique(pairs):
        out={}
        for k,v in pairs:
            require(k not in out);out[k]=v
        return out
    def reject(_):raise ValueError()
    try:return json.loads(raw,object_pairs_hook=unique,parse_constant=reject)
    except (ValueError,UnicodeError,RecursionError,OverflowError):
        raise Failure('Invalid eight-orbit JSON evidence') from None

def result_record(v):
    fields(v,('schema','synthetic','classification','inputPhotoIds','registeredPhotoIds','unregisteredPhotoIds','geometry'))
    require(type(v['schema']) is int and v['schema']==1 and v['synthetic'] is False)
    require(v['classification'] in (CLASSIFICATION,'INCOMPLETE_OR_FALLBACK'))
    ids(v['inputPhotoIds'],8,8);ids(v['registeredPhotoIds'],2,8);ids(v['unregisteredPhotoIds'])
    selected=set(v['inputPhotoIds']);registered=set(v['registeredPhotoIds'])
    require(registered<=selected and set(v['unregisteredPhotoIds'])==selected-registered)
    g=v['geometry'];fields(g,('finalSparseOutcome','pointCount'),('bundleAdjustment',))
    require(g['finalSparseOutcome'] in ('global_typed_ba_admitted12','verified_anchor_fallback'))
    integer(g['pointCount'],1)
    if 'bundleAdjustment' in g:
        b=g['bundleAdjustment'];fields(b,('inputObservations','usableObservations','rejectedObservations','acceptedSteps','attemptedIterations','robustCostBefore','robustCostAfter','medianBeforeNormalized','medianAfterNormalized'))
        for k in ('inputObservations','usableObservations','rejectedObservations'):integer(b[k],0,800000)
        require(b['inputObservations']==b['usableObservations']+b['rejectedObservations'])
        for k in ('acceptedSteps','attemptedIterations'):integer(b[k],0,1000)
        require(b['acceptedSteps']<=b['attemptedIterations'])
        for k in ('robustCostBefore','robustCostAfter','medianBeforeNormalized','medianAfterNormalized'):number(b[k],0)
    if g['finalSparseOutcome']=='global_typed_ba_admitted12':require('bundleAdjustment' in g)
    full=len(registered)==8 and g['finalSparseOutcome']=='global_typed_ba_admitted12'
    require(v['classification']==(CLASSIFICATION if full else 'INCOMPLETE_OR_FALLBACK'))

def identity(v):
    rows(v,8,8);seen=set();names=set();hashes=set()
    for row in v:
        fields(row,('photoId','imageSha256','fixturePath'));text(row['photoId'],UUID);text(row['imageSha256'],r'[0-9a-f]{64}')
        require(row['fixturePath'] in INPUT_HASHES and row['imageSha256']==INPUT_HASHES[row['fixturePath']])
        seen.add(row['photoId']);names.add(row['fixturePath']);hashes.add(row['imageSha256'])
    require(len(seen)==len(names)==len(hashes)==8)

def diagnostics(v,result=None):
    fields(v,('schema','graphScope','allInputVerifiedEdgeIdentities','registeredPhotoIds','trackCount','observationCount','verifiedMatchGraph','cameras','tracks'))
    require(type(v['schema']) is int and v['schema']==1)
    require(v['graphScope']=='REGISTERED_CAMERA_CHECKPOINT_GRAPH_NOT_ALL_INPUT_INITIALIZER_EDGES')
    require(v['allInputVerifiedEdgeIdentities']=='NOT_EXPOSED_BY_PRODUCTION_DIAGNOSTICS')
    selected=v['registeredPhotoIds'];ids(selected,2,8)
    if result is not None:
        require(set(selected)==set(result['registeredPhotoIds']))
        require(v['trackCount']==result['geometry']['pointCount'])
    integer(v['trackCount'],1);integer(v['observationCount'],2,800000)
    rows(v['cameras'],len(selected),len(selected))
    for i,c in enumerate(v['cameras']):
        fields(c,('photoId','width','height','fx','fy','cx','cy','rotationWorldToCamera','translationWorldToCamera'))
        require(c['photoId']==selected[i]);integer(c['width'],3,4272);integer(c['height'],3,4272)
        for k in ('fx','fy'):number(c[k],1,100000)
        number(c['cx'],0,c['width']);number(c['cy'],0,c['height'])
        numeric_array(c['rotationWorldToCamera'],9);numeric_array(c['translationWorldToCamera'],3)
        for x in c['rotationWorldToCamera']:number(x,-1.001,1.001)
    edges=v['verifiedMatchGraph'];rows(edges,1,28);seen=set()
    for edge in edges:
        fields(edge,('firstCamera','secondCamera','firstPhotoId','secondPhotoId','verifiedMatches','inliers','medianErrorNormalized'))
        a,b=edge['firstCamera'],edge['secondCamera'];integer(a,0,len(selected)-1);integer(b,a+1,len(selected)-1)
        require((a,b) not in seen);seen.add((a,b))
        require(edge['firstPhotoId']==selected[a] and edge['secondPhotoId']==selected[b])
        integer(edge['verifiedMatches'],1,100000);integer(edge['inliers'],1,edge['verifiedMatches']);number(edge['medianErrorNormalized'],0)
    rows(v['tracks'],v['trackCount'],v['trackCount']);observations=0
    for i,t in enumerate(v['tracks']):
        fields(t,('ordinal','x','y','z','reprojectionRmsNormalized','observations'));integer(t['ordinal'],i,i)
        for k in ('x','y','z'):number(t[k])
        number(t['reprojectionRmsNormalized'],0);rows(t['observations'],2,len(selected));seen=set()
        for obs in t['observations']:
            fields(obs,('cameraIndex','featureIndex','xNormalized','yNormalized'))
            integer(obs['cameraIndex'],0,len(selected)-1);integer(obs['featureIndex'],0,3999)
            require(obs['cameraIndex'] not in seen);seen.add(obs['cameraIndex'])
            number(obs['xNormalized'],-10,10);number(obs['yNormalized'],-10,10)
        observations+=len(t['observations'])
    require(observations==v['observationCount'])

def timings(v):
    fields(v,('schema','elapsedMillis','scope','featureEvents','pairEvents'))
    require(type(v['schema']) is int and v['schema']==1 and v['scope']=='HOST_MONOTONIC_NOT_DEVICE_PERFORMANCE');integer(v['elapsedMillis'],0,4200000)
    phases={'CACHE_LOOKUP','CACHE_HIT','PREPARING_IMAGE','NATIVE_RUNNING','NATIVE_FINISHED','CACHE_WRITE','READY','CANCELLED','FAILED'}
    for group in ('featureEvents','pairEvents'):
        rows(v[group])
        for row in v[group]:
            if group=='featureEvents':
                fields(row,('photoNumber','photoCount','phase','elapsedMillis'),('nativeElapsedMillis','cacheHit'))
                integer(row['photoNumber'],1,8);integer(row['photoCount'],8,8);require(row['phase'] in phases)
                require('cacheHit' not in row or type(row['cacheHit']) is bool);duration=('elapsedMillis','nativeElapsedMillis')
            else:
                fields(row,('pairNumber','phase','elapsedMillis','loadElapsedMillis','matchElapsedMillis','geometryElapsedMillis'))
                integer(row['pairNumber'],1,28);require(row['phase'] in {'LOAD_FIRST','LOAD_SECOND','MATCHING','GEOMETRY','READY','CANCELLED','FAILED'})
                duration=('elapsedMillis','loadElapsedMillis','matchElapsedMillis','geometryElapsedMillis')
            for k in duration:
                if k in row:integer(row[k],0,4200000)

def pairs(v):
    rows(v)
    for row in v:
        fields(row,('pairNumber','pairCount','phase','geometryEntered'),('firstDescriptorRows','secondDescriptorRows','matchCacheHit'))
        integer(row['pairNumber'],1,28);integer(row['pairCount'],28,28)
        require(row['phase'] in {'LOAD_FIRST','LOAD_SECOND','MATCHING','GEOMETRY','READY','CANCELLED','FAILED'})
        require(type(row['geometryEntered']) is bool)
        for k in ('firstDescriptorRows','secondDescriptorRows'):
            if k in row:integer(row[k],0,4000)
        require('matchCacheHit' not in row or type(row['matchCacheHit']) is bool)

def inventory(v):
    rows(v,1,100);seen=set()
    for row in v:
        fields(row,('stage','sha256','bytes'));require(row['stage'] in {'FEATURES','MATCHES','ALIGNMENT'})
        text(row['sha256'],r'[0-9a-f]{64}');integer(row['bytes'],1,128*1024**2)
        key=(row['stage'],row['sha256']);require(key not in seen);seen.add(key)

def cache_summary(v):
    fields(v,('schema','scope','snapshotRecordSha256','matchingStageSha256','cacheProofs',
        'features','pairs','coordinatePairDomain','metadataPolicy','originalNativeCategory','replay'))
    integer(v['schema'],1,1)
    require(v['scope']=='FAILED_INITIALIZER_CACHE_DIAGNOSTIC')
    require(v['coordinatePairDomain']=='OBSERVED_PIXEL_QUADRUPLE_EXACT')
    require(v['metadataPolicy']=='CONTENT_IDENTITY_SIZE_CREATION_MODIFIED_UNIX_OWNER_MODE_CHANGE_TIME')
    require(v['originalNativeCategory']=='NOT_RETAINED')
    for k in ('snapshotRecordSha256','matchingStageSha256'):text(v[k],r'[0-9a-f]{64}')
    fields(v['replay'],('status','reason'))
    require(v['replay']=={'status':'NOT_RUN','reason':'REPLAY_UNAVAILABLE'})
    rows(v['cacheProofs'],13,13);roles={}
    for proof in v['cacheProofs']:
        fields(proof,('role','index','bytes','beforeSha256','afterSha256','accessTimeChanged'))
        require(type(proof['accessTimeChanged']) is bool)
        require(proof['role'] in ('FEATURE','MATCHES','MATCHES_CHECKSUM','MANIFEST','INPUT_RECORD'))
        integer(proof['index'],0,7)
        limits={'FEATURE':12*1024**2,'MATCHES':128+28*(12+4000*64),'MATCHES_CHECKSUM':64,'MANIFEST':1024**2,'INPUT_RECORD':2*1024**2}
        integer(proof['bytes'],64 if proof['role']=='MATCHES_CHECKSUM' else 1,limits[proof['role']])
        for k in ('beforeSha256','afterSha256'):text(proof[k],r'[0-9a-f]{64}')
        require(proof['beforeSha256']==proof['afterSha256'])
        roles.setdefault(proof['role'],[]).append(proof['index'])
    require(set(roles)=={'FEATURE','MATCHES','MATCHES_CHECKSUM','MANIFEST','INPUT_RECORD'})
    require(roles['FEATURE']==list(range(8)))
    for role in ('MATCHES','MATCHES_CHECKSUM','INPUT_RECORD'):require(roles[role]==[0])
    require(roles['MANIFEST']==[0,1])
    require(sum(p['bytes'] for p in v['cacheProofs'] if p['role']=='FEATURE')<=32*1024**2)
    require(next(p['beforeSha256'] for p in v['cacheProofs'] if p['role']=='INPUT_RECORD')==v['snapshotRecordSha256'])
    rows(v['features'],8,8);photo_ids=[];hashes=[]
    for f in v['features']:
        fields(f,('photoId','imageSha256','width','height','featureCount'))
        text(f['photoId'],UUID);text(f['imageSha256'],r'[0-9a-f]{64}')
        integer(f['width'],3,1600);integer(f['height'],3,1600);integer(f['featureCount'],0,4000)
        photo_ids.append(f['photoId']);hashes.append(f['imageSha256'])
    require(len(set(photo_ids))==8 and set(hashes)==set(INPUT_HASHES.values()))
    rows(v['pairs'],28,28);seen=set();features={f['photoId']:f for f in v['features']}
    for ordinal,p in enumerate(v['pairs'],1):
        fields(p,('ordinal','firstPhotoId','secondPhotoId','firstDescriptorRows','secondDescriptorRows',
            'reciprocalMatchCount','distinctKeyPairCount','distinctCoordinatePairCount','distinctNormalizedRayPairCount','geometryEntered'))
        integer(p['ordinal'],ordinal,ordinal)
        a,b=p['firstPhotoId'],p['secondPhotoId']
        require(a in features and b in features and photo_ids.index(a)<photo_ids.index(b))
        require((a,b) not in seen);seen.add((a,b))
        for k in ('firstDescriptorRows','secondDescriptorRows','reciprocalMatchCount','distinctKeyPairCount','distinctCoordinatePairCount','distinctNormalizedRayPairCount'):integer(p[k],0,4000)
        require(p['firstDescriptorRows']==features[a]['featureCount'] and p['secondDescriptorRows']==features[b]['featureCount'])
        count=p['reciprocalMatchCount']
        require(count<=min(p['firstDescriptorRows'],p['secondDescriptorRows']))
        require(p['distinctKeyPairCount']==count and 0<=p['distinctCoordinatePairCount']<=count)
        require((p['distinctCoordinatePairCount']==0)==(count==0))
        require(0<=p['distinctNormalizedRayPairCount']<=count and (p['distinctNormalizedRayPairCount']==0)==(count==0))
        require(type(p['geometryEntered']) is bool and p['geometryEntered']==(count>=20))


def validate_cache_records(summary,identities,events):
    cache_summary(summary);identity(identities);pairs(events)
    require([(f['photoId'],f['imageSha256']) for f in summary['features']]==
            [(f['photoId'],f['imageSha256']) for f in identities])
    ready=[p for p in events if p['phase']=='READY']
    require(len(ready)==28 and [p['pairNumber'] for p in ready]==list(range(1,29)))
    for row,event in zip(summary['pairs'],ready):
        for k in ('firstDescriptorRows','secondDescriptorRows','geometryEntered'):require(row[k]==event.get(k))
        require(event.get('matchCacheHit') is False)
    require(sorted(p['pairNumber'] for p in events if p['phase']=='GEOMETRY')==
            [p['ordinal'] for p in summary['pairs'] if p['geometryEntered']])

def provider(v,name):
    fields(v,('schema','phase','status','capabilitySha256','policySha256','sourceSha','sqliteVersion','activity','observedMappings','scannerElfDependencies','scannerProviders','androidRuntimeProvider'))
    integer(v['schema'],1,1);require(name=='runtime-providers-'+v['phase']+'.json')
    require(v['status']=='OBSERVED_PROVIDER_IDENTITIES_MATCH')
    for k in ('capabilitySha256','policySha256'):text(v[k],r'[0-9a-f]{64}')
    text(v['sourceSha'],r'[0-9a-f]{40}');text(v['sqliteVersion'],r'[0-9]+\.[0-9]+\.[0-9]+')
    require(v['activity']==['actual Bitmap pixel roundtrip','actual Room SELECT sqlite_version()'])
    def path(p):
        text(p,r'/[A-Za-z0-9_./+@=-]{1,4095}');require('..' not in p.split('/') and '//' not in p)
    for group,keys,lo,hi in [('observedMappings',('path','inode'),4,4),('scannerElfDependencies',('path','sha256','inode'),1,32),('scannerProviders',('role','path','sha256','inode'),3,3)]:
        rows(v[group],lo,hi)
        for row in v[group]:
            fields(row,keys);path(row['path']);integer(row['inode'],1,2**63-1)
            if 'sha256' in row:text(row['sha256'],r'[0-9a-f]{64}')
        require(len({r['path'] for r in v[group]})==len(v[group]))
    require({r['role'] for r in v['scannerProviders']}=={'opencv','shim','engine'})
    p=v['androidRuntimeProvider'];fields(p,('path','sha256','bytes','inode','archivePath','archiveSha256','archiveEntry'))
    path(p['path']);path(p['archivePath']);text(p['sha256'],r'[0-9a-f]{64}');text(p['archiveSha256'],r'[0-9a-f]{64}')
    integer(p['bytes'],1,32*1024**2);integer(p['inode'],1,2**63-1);require(p['archiveEntry']=='native/linux/x86_64/libandroid_runtime.so')
    require({(r['path'],r['inode']) for r in v['observedMappings']}=={(r['path'],r['inode']) for r in v['scannerProviders']}|{(p['path'],p['inode'])})

def validate_path(name,raw):
    require(name in JSON_FILES)
    if name=='cache-pair-summary.json':require(len(raw)<=32768)
    v=parse(raw)
    try:
        if name=='sparse-result.json':result_record(v)
        elif name=='input-identity.json':identity(v)
        elif name=='sparse-diagnostics.json':diagnostics(v)
        elif name=='sparse-timing.json':timings(v)
        elif name=='pair-events.json':pairs(v)
        elif name=='artifact-inventory.json':inventory(v)
        elif name=='cache-pair-summary.json':cache_summary(v)
        elif name=='failure.json':
            fields(v,('schema','status','phase'));integer(v['schema'],1,1)
            require(v['status']=='FAILED_NOT_QUALITY_PASS' and v['phase']=='BEFORE_OR_AT_SPARSE')
        else:provider(v,name)
    except (KeyError,TypeError,ValueError,OverflowError,RecursionError):
        raise Failure('Invalid eight-orbit sparse evidence') from None
    return v

def validate_records(result,diagnostic,identities):
    result_record(result);identity(identities);diagnostics(diagnostic,result)
    require(set(result['inputPhotoIds'])=={row['photoId'] for row in identities})
