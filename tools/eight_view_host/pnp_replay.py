"""Strict public-fixture replay evidence only; never a full-three acceptance signal."""
import hashlib
import json
import math
import re
import struct
from .runtime import Failure

CLASSIFICATION='READ_ONLY_REPLAY_NOT_ORIGINAL_CALL_NOT_QUALITY'
ORIGINALS=['9f034fe4bac3fa57ce6df66f949db69709b27e16e25ee31dd4358233d441dc5e',
'dcec8cd3c1036abc0e2e9a1231c207367098bedbddcc39c25d3777861547db6c',
'f771973ad8f3092d6e869d29c4cb88a0ad67d30c6cf03dfe9dce70c98cdd9bb1']

def validate(raw,profile):
    def require(ok):
        if not ok:raise Failure('Invalid fixed-fixture PnP replay evidence')
    require(profile=='real-licensed-three-complete' and type(raw) is bytes and 0<len(raw)<=65536)
    def unique(pairs):
        result={}
        for k,v in pairs:
            require(k not in result);result[k]=v
        return result
    try:v=json.loads(raw,object_pairs_hook=unique,parse_int=lambda text: -0.0 if text=="-0" else int(text))
    except (ValueError,UnicodeError,RecursionError):raise Failure('Invalid PnP replay JSON') from None
    require(type(v) is dict and type(v.get('schema')) is int and v['schema']==1 and v.get('classification')==CLASSIFICATION)
    if v.get('status')=='DIAGNOSTIC_FAILURE':
        require(v=={'schema':1,'classification':CLASSIFICATION,'status':'DIAGNOSTIC_FAILURE','code':'READ_ONLY_REPLAY_UNAVAILABLE'})
        return v
    fields={'schema','classification','status','originalHashes','cacheHashes','matchingStage','sparseStage','candidateLabel',
        'projections','anchorPoses','provenance','count','threshold','numericFailure','rowsSha256','poseRowsSha256','triangulationRowsSha256','pointsSha256','rows'}
    require(set(v)==fields and v['status']=='VERIFIED_REPLAY' and v['originalHashes']==ORIGINALS)
    def digest(x):return type(x) is str and re.fullmatch('[a-f0-9]{64}',x) is not None
    require(all(digest(v[k]) for k in ['matchingStage','sparseStage','rowsSha256','poseRowsSha256','triangulationRowsSha256','pointsSha256']))
    require(type(v['cacheHashes']) is dict and set(v['cacheHashes'])=={'feature10','feature11','feature12','matches','matchesChecksum','frontier'} and all(digest(x) for x in v['cacheHashes'].values()))
    require(type(v['candidateLabel']) is int and v['candidateLabel'] in (10,11,12))
    n=v['count'];require(type(n) is int and 16<=n<=128)
    require(type(v['threshold']) in (int,float) and 0<v['threshold']<=.05 and math.isfinite(v['threshold']))
    require(type(v['rows']) is list and len(v['rows'])==n)
    packed=bytearray()
    for row in v['rows']:
        require(type(row) is list and len(row)==5)
        for i,x in enumerate(row):
            require(type(x) in (int,float) and abs(x)<=(100000 if i<3 else 10) and math.isfinite(x))
            packed.extend(struct.pack('>d',x))
    require(hashlib.sha256(packed).hexdigest()==v['rowsSha256'])
    require(len({tuple(row) for row in v['rows']})==n)
    def finite(x,bound):return type(x) in (int,float) and abs(x)<=bound and math.isfinite(x)
    require(type(v['projections']) is list and len(v['projections'])==3)
    optics={}
    for row in v['projections']:
        require(type(row) is list and len(row)==12 and all(finite(x,100000) for x in row))
        label,w,h,fx,fy,cx,cy,*distortion=row
        require(type(label) is int and label in (10,11,12) and label not in optics)
        require(type(w) is int and type(h) is int and 32<=w<=1600 and 32<=h<=1600)
        require(fx==fy and math.isclose(fx,88*math.hypot(w,h)/math.hypot(36,24),rel_tol=1e-12,abs_tol=0))
        require(cx==(w-1)/2 and cy==(h-1)/2 and distortion==[0]*5)
        optics[label]=row
    require(set(optics)=={10,11,12} and v['threshold']==2/optics[v['candidateLabel']][3])
    require(type(v['anchorPoses']) is list and len(v['anchorPoses'])==2)
    poses={};packed_poses=bytearray()
    for row in v['anchorPoses']:
        require(type(row) is list and len(row)==13 and all(finite(x,100000) for x in row))
        label=row[0];require(type(label) is int and label in optics and label!=v['candidateLabel'] and label not in poses)
        r=row[1:10];t=row[10:13]
        determinant=r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6])
        require(abs(determinant-1)<.01)
        require(all(abs(sum(r[a*3+k]*r[b*3+k] for k in range(3))-(1 if a==b else 0))<.01 for a in range(3) for b in range(3)))
        poses[label]=(r,t);packed_poses.extend(b''.join(struct.pack('>d',x) for x in row[1:]))
    require(hashlib.sha256(packed_poses).hexdigest()==v['poseRowsSha256'])
    require(type(v['provenance']) is list and len(v['provenance'])==n)
    all_tracks=set();all_keys=set();all_sources=set()
    for xyzuv,entry in zip(v['rows'],v['provenance']):
        require(type(entry) is dict and set(entry)=={'ambiguous','tracks'} and type(entry['ambiguous']) is bool)
        tracks=entry['tracks'];require(type(tracks) is list and 1<=len(tracks)<=8 and entry['ambiguous']==(len(tracks)>1))
        seen_tracks=set();seen_keys=set()
        for track in tracks:
            require(type(track) is dict and set(track)=={'track','candidateKey','sources'})
            require(type(track['track']) is int and 0<=track['track']<3500 and track['track'] not in seen_tracks and track['track'] not in all_tracks)
            require(type(track['candidateKey']) is int and 0<=track['candidateKey']<3500 and track['candidateKey'] not in seen_keys and track['candidateKey'] not in all_keys)
            seen_tracks.add(track['track']);seen_keys.add(track['candidateKey']);all_tracks.add(track['track']);all_keys.add(track['candidateKey'])
            require(type(track['sources']) is list and len(track['sources'])==2)
            seen=set();linked=False
            for observation in track['sources']:
                require(type(observation) is list and len(observation)==5)
                label,key,u,w,target=observation
                require(type(label) is int and label in poses and label not in seen)
                require(type(key) is int and 0<=key<3500 and finite(u,10) and finite(w,10))
                require(type(target) is int and target in (-1,track['candidateKey']))
                require((label,key) not in all_sources);all_sources.add((label,key))
                linked |= target>=0;seen.add(label)
                r,t=poses[label];camera=[sum(r[a*3+k]*xyzuv[k] for k in range(3))+t[a] for a in range(3)]
                require(camera[2]>1e-8 and math.hypot(camera[0]/camera[2]-u,camera[1]/camera[2]-w)<=6/min(optics[label][3] for label in poses))
            require(seen==set(poses) and linked)

    require(type(v['numericFailure']) is str and len(v['numericFailure'])<=200)
    m=re.fullmatch(r'\[pnp1 raw=([0-9]+) distinct=([0-9]+) required=([0-9]+) best=([0-9]+) tested=([0-9]+) valid=([0-9]+) model=(dlt|plane|none)(?: planarity=([0-9]+\.[0-9]{6}))?\]',v['numericFailure'])
    require(m is not None)
    a,b,required,best,tested,valid=map(int,m.groups()[:6])
    require(a==b==n and required==max(16,math.ceil(n*.28)) and 0<=best<required and 0<=valid<=tested<=450)
    require((m[7]=='none' and m[8] is None) or (m[7] in ('dlt','plane') and m[8] is not None and 0<=float(m[8])<=1))
    return v
