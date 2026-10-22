"""Exact host-model differential check; not Android runtime/performance qualification.

Requires the project's pinned optional QA OpenCV/NumPy environment. No downloads.
"""
import cv2, numpy as np, time, json, pathlib
cv2.setNumThreads(1)
rng=np.random.default_rng(738291)

def accepted(q,t,ratio,block,selected=None):
    ids=np.arange(len(q)) if selected is None else np.asarray(selected,dtype=int)
    out=[]; bf=cv2.BFMatcher(cv2.NORM_L2,False)
    for start in range(0,len(ids),block):
        ix=ids[start:start+block]
        query=q[start:start+block] if selected is None else np.ascontiguousarray(q[ix])
        for pair in bf.knnMatch(query,t,k=2):
            if len(pair)<2: continue
            x,y=pair
            if np.isfinite(x.distance) and np.isfinite(y.distance) and x.distance>=0 and x.distance<ratio*y.distance:
                out.append((int(ix[x.queryIdx]),x.trainIdx,x.distance,y.distance))
    return out

def match(a,b,ratio=.67,block=256,sparse=False):
    if min(len(a),len(b))<2:return [],0
    if sparse:
        f=accepted(a,b,ratio,block); ids=sorted(set(x[1] for x in f))
        r=accepted(b,a,ratio,block,ids); work=len(a)*len(b)+len(ids)*len(a)
    else:
        r=accepted(b,a,ratio,block); f=accepted(a,b,ratio,block); work=2*len(a)*len(b)
    rev={x[0]:x for x in r}
    return [(x,rev[x[1]]) for x in f if x[1] in rev and rev[x[1]][1]==x[0]],work

cases=[]
for n,m in [(0,4),(1,8),(2,2),(2,19),(19,2),(17,33),(257,311),(400,90)]:
    for seed in range(12):
        a=rng.uniform(0,512,(n,128)).astype('float32'); b=rng.uniform(0,512,(m,128)).astype('float32')
        if n and m:
            k=min(n,m)//2;b[:k]=a[:k]+rng.uniform(-.01,.01,(k,128)).astype('float32');np.clip(b,0,512,out=b)
        cases.append((f'random-{n}-{m}-{seed}',a,b,.67))
for value in [0,1,512]:cases.append((f'duplicate-{value}',np.full((33,128),value,'float32'),np.full((65,128),value,'float32'),.67))
# Exactly representable distances: distance(first)=1, second=2, strict ratio .5 rejects equality.
a=np.zeros((5,128),'float32');b=np.zeros((7,128),'float32')
a[:,0]=[0,8,16,32,64];b[:,0]=[1,2,9,17,33,65,80]
for ratio in [.5,np.nextafter(.5,0),np.nextafter(.5,1),.67,.1,.95]:cases.append((f'ratio-{ratio}',a,b,ratio))
# Equal nearest distances/tied second nearest and duplicate targets.
a=np.zeros((9,128),'float32');b=np.zeros((11,128),'float32');a[:,0]=[0,0,4,8,12,16,20,24,28];b[:,0]=[1,1,3,5,7,9,13,17,21,25,29]
cases.append(('ties',a,b,.67))
checks=0
for name,a,b,ratio in cases:
    baseline,_=match(a,b,ratio,4096,False)
    for block in [1,2,17,128,256]:
        old,_=match(a,b,ratio,block,False);new,_=match(a,b,ratio,block,True)
        if not (old==baseline==new):
            raise AssertionError((name,block,old,new))
        checks+=1
report={'opencv':cv2.__version__,'threads':cv2.getNumThreads(),'checks':checks,'cases':len(cases),'comparison':'exact ordered forward/reverse identities AND both nearest distances','syntheticHostOnly':True,'timingScope':'Python/OpenCV host model including NumPy block gather; excludes Kotlin per-row Mat JNI gather overhead. Not Android implementation runtime.','pairs':[]}
repository=pathlib.Path(__file__).resolve().parents[1]
root=repository/'benchmarks/fixtures/relief'
sift=cv2.SIFT_create(3500);desc=[]
for i in range(1,4):
    im=cv2.imread(str(root/f'view-0{i}.jpg')); kp,d=sift.detectAndCompute(cv2.cvtColor(im,cv2.COLOR_BGR2GRAY),None);desc.append(d[:3500])
report['featureCounts']=[len(x) for x in desc]
for i,j in [(0,1),(0,2),(1,2)]:
    a,b=desc[i],desc[j];old,w0=match(a,b);new,w1=match(a,b,sparse=True)
    if not old==new:
        raise AssertionError
    times={}
    for sparse in [False,True]:
        samples=[]
        for _ in range(7):
            begin=time.perf_counter();match(a,b,sparse=sparse);samples.append((time.perf_counter()-begin)*1000)
        times['sparseMs' if sparse else 'oldMs']=float(np.median(samples))
    report['pairs'].append({'pair':[i+1,j+1],'matches':len(old),'oldDistanceEvaluations':w0,'sparseDistanceEvaluations':w1,'reduction':1-w1/w0,**times})
(repository/'reports/cloud-sparse-reverse-host.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
