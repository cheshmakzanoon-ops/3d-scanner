"""Independent identity/accounting audit of actual executed native branch records."""
import pathlib,struct,math,collections

def need(b,m):
    if not b:raise RuntimeError(m)
def f32(x):return struct.unpack('<f',struct.pack('<f',x))[0]
def d64(x):return struct.unpack('<d',struct.pack('<Q',x))[0]
def flt(x):return struct.unpack('<f',struct.pack('<I',x))[0]
class Reader:
    def __init__(self,path):self.data=pathlib.Path(path).read_bytes();self.pos=0
    def take(self,fmt):
        s=struct.Struct('<'+fmt);need(self.pos+s.size<=len(self.data),'BITS_TRUNCATED');v=s.unpack_from(self.data,self.pos);self.pos+=s.size;return v
    def integer(self):return self.take('Q')[0]
    def skip(self,n):self.pos+=n;need(self.pos<=len(self.data),'BITS_TRUNCATED')
def audit(directory):
    p=pathlib.Path(directory);inp=Reader(p/'inputs.bits');need(inp.integer()==8,'INPUT_CAMERAS');cameras=[]
    for _ in range(8):
        need(inp.take('QQ')==(160,120),'INPUT_DIMENSIONS');cameras.append(inp.take('16d'));need(inp.integer()==76800,'INPUT_RGBA');inp.skip(76800)
    need(inp.pos==len(inp.data),'INPUT_TRAILING')
    def multiply(a,v,transpose=False):return tuple(tuple(a[(j*3+i) if transpose else (i*3+j)]*v[j] for j in range(3)) for i in range(3))
    def product(a,v,transpose=False):
        terms=multiply(a,v,transpose);return tuple((t0+t1)+t2 for t0,t1,t2 in terms)
    def world(cam,x,y,z):
        c=cameras[cam];q=(((x-c[14])/c[12])*z-c[9],((y-c[15])/c[13])*z-c[10],z-c[11]);return product(c,q,True)
    def camera(cam,p):
        c=cameras[cam];q=product(c,p);return tuple(q[i]+c[9+i] for i in range(3))
    def pixel(cam,q):
        c=cameras[cam];return c[12]*q[0]/q[2]+c[14],c[13]*q[1]/q[2]+c[15]
    r=Reader(p/'dense.bits');need(r.integer()==8,'MAP_COUNT');maps=[]
    for _ in range(8):
        need(r.take('QQ')==(160,120),'MAP_DIMENSIONS');need(r.integer()==19200,'MAP_DEPTH_SIZE');depth=r.take('19200f');need(r.integer()==19200,'MAP_CONF_SIZE');confidence=r.take('19200f');maps.append((depth,confidence))
    points=r.integer();need(points<=20000,'POINT_CAP');r.skip(points*32)
    n=r.integer();need(n<=35112,'SAMPLE_CAP');samples=[]
    for _ in range(n):
        xyz=r.take('3d');rgb=r.take('3B');confidence=r.take('f')[0];views,cam,x,y=r.take('BBHH');normal=r.take('3d');normalConfidence=r.take('f')[0];normalViews=r.take('B')[0];samples.append({'xyz':xyz,'confidence':confidence,'views':views,'cam':cam,'x':x,'y':y})
    sampled,photometric,consistent=r.take('3Q');r.take('d');need(r.integer()==8,'PLAN_COUNT');plan=[r.take(str(r.integer())+'Q') for _ in range(8)];need(all(len(s)<=3 and len(set(s))==len(s) and all(0<=v<8 and v!=ref for v in s) for ref,s in enumerate(plan)),'SOURCE_PLAN_CAP_IDENTITIES')
    need(r.integer()==8,'PER_VIEW_COUNT');perView=r.take('8Q');diagnostics=r.take('19Q');need(r.pos==len(r.data),'DENSE_BITS_TRAILING');need(consistent==n and sum(perView)==n and n<=photometric<=sampled<=35112,'DENSE_COUNTER_BINDING')
    actualplan=[list(map(int,line.split())) for line in (p/'source-plan.tsv').read_text().splitlines()];need(actualplan==[[i,len(s),*s] for i,s in enumerate(plan)],'ACTUAL_PLAN_BINDING')
    expectedCount,expectedGenerated=map(int,(p/'witness-counts.tsv').read_text().split());need(expectedGenerated==n and expectedCount<=105336,'COUNT_BINDING')
    data=(p/'witness.bin').read_bytes() if (p/'witness.bin').exists() else b'';need(len(data)==192*expectedCount,'FIXED_RECORD_WIDTH');rows=list(struct.iter_unpack('<24Q',data));counts=collections.Counter();confirmed=collections.defaultdict(list);attemptKeys=set();forward={};maxRelativeDelta=0
    for seq,row in enumerate(rows):
        i,ref,src,x,y,status,ix,iy=row[:8];flags=row[22];need(i==seq and ref<8 and src in plan[ref] and x in range(3,156,2) and y in range(3,116,2),'ATTEMPT_ID');key=(ref,x,y,src);need(key not in attemptKeys,'DUPLICATE_ATTEMPT');attemptKeys.add(key);counts[str(status)]+=1;need(status<=5,'UNCLASSIFIED_ATTEMPT');need(flags&1,'MISSING_Q')
        need(flags&~255==0 and row[13]<=0xffffffff and row[14]<=0xffffffff and row[23]<=0xffffffff,'FLAG_OR_FLOAT_WORD_DOMAIN')
        required={0:255,1:1,3:231,4:239,5:255}
        if status in required:need(flags==required[status],'STATUS_FLAG_BINDING')
        else:need(flags in (7,39,103),'MISSING_DEPTH_FLAG_BINDING')
        for flag,indices in ((2,(11,12)),(4,(13,)),(8,(15,16,17,18)),(16,(19,20,21)),(32,(6,7)),(128,(14,))):
            if not flags&flag:need(all(row[j]==0 for j in indices),'UNEVALUATED_WORD_NOT_ZERO')
        referenceDepth=flt(row[23]);need(referenceDepth==maps[ref][0][y*160+x] and referenceDepth>0,'REFERENCE_DEPTH_BINDING')
        if flags&32:ix=ix if ix<2**63 else ix-2**64;iy=iy if iy<2**63 else iy-2**64
        observedWorld=world(ref,x,y,referenceDepth);q=camera(src,observedWorld);need(tuple(d64(v) for v in row[8:11])==q,'ORIGINAL_CAMERA_Q_BINDING')
        if flags&2:need(tuple(d64(v) for v in row[11:13])==pixel(src,q),'ORIGINAL_PROJECTION_BINDING')
        if flags&8:
            back=world(src,ix,iy,flt(row[13]));backCamera=camera(ref,back);need(tuple(d64(v) for v in row[15:18])==back and d64(row[18])==backCamera[2],'ORIGINAL_REVERSE_BINDING')
            if flags&16:
                px,py=pixel(ref,backCamera);need((d64(row[19]),d64(row[20]))==(px,py),'ORIGINAL_REPROJECTION_BINDING');rt=math.hypot(px-x,py-y);actual=d64(row[21]);need(abs(rt-actual)<=8*max(math.ulp(rt),math.ulp(actual)),'ROUNDTRIP_NUMERIC_AUDIT')
        if flags&2:
            sx,sy=d64(row[11]),d64(row[12]);initialValid=math.isfinite(sx) and math.isfinite(sy) and 0<=sx<160 and 0<=sy<120
            need(bool(flags&32)==initialValid,'NEIGHBOR_INITIAL_BOUND_BRANCH')
            selectedValid=initialValid and 0<=ix<160 and 0<=iy<120 and abs(ix-sx)<=1.3 and abs(iy-sy)<=1.3
            need(bool(flags&64)==selectedValid,'NEIGHBOR_SELECTED_BOUND_BRANCH')
            expectedDepth=maps[src][0][iy*160+ix] if selectedValid else 0.
            need(flt(row[13])==expectedDepth and bool(flags&128)==(expectedDepth>0),'NEIGHBOR_RETURN_SHORT_CIRCUIT')
        if flags&32:
            sx,sy=d64(row[11]),d64(row[12]);roundaway=lambda z:math.floor(z+.5) if z>=0 else math.ceil(z-.5)
            need(ix==roundaway((sx-3)/2)*2+3 and iy==roundaway((sy-3)/2)*2+3,'ACTUAL_NEAREST_GRID_ID')
        if flags&64:need(0<=ix<160 and 0<=iy<120 and abs(ix-d64(row[11]))<=1.3 and abs(iy-d64(row[12]))<=1.3 and flt(row[13])==maps[src][0][iy*160+ix],'SOURCE_DEPTH_BINDING')
        if flags&128:
            qz=d64(row[10]);depth=flt(row[13]);expected=f32(abs(qz-depth)/max(qz,depth));need(struct.pack('<f',expected)==struct.pack('<f',flt(row[14])),'FLOAT_RELATIVE_ERROR');maxRelativeDelta=max(maxRelativeDelta,abs(expected-flt(row[14])))
        if status==0:
            need(flags==255 and flt(row[13])>0 and flt(row[14])<=.055 and d64(row[21])<=3.2,'CONFIRMED_GATES');confirmed[(ref,x,y)].append(src);forward[(ref,x,y,src,ix,iy)]=seq
        elif status==1:need(d64(row[10])<=.01 or not all(math.isfinite(d64(q)) for q in row[8:11]),'BAD_Q_REASON')
        elif status==2:need(flt(row[13])<=0,'MISSING_DEPTH_REASON')
        elif status==3:need(flags&128 and flt(row[14])>.055,'RELATIVE_REJECTION_REASON')
        elif status==4:need(flags&8 and (d64(row[18])<=0 or not all(math.isfinite(d64(q)) for q in row[15:19])),'REVERSE_REJECTION_REASON')
        elif status==5:need(flags&16 and d64(row[21])>3.2,'ROUNDTRIP_REJECTION_REASON')
    expectedKeys={(ref,x,y,src) for ref in range(8) for y in range(3,116,2) for x in range(3,156,2) if maps[ref][0][y*160+x]>0 for src in plan[ref]};need(attemptKeys==expectedKeys,'ALL_ACTUAL_REFERENCE_ATTEMPTS')
    generatedData=(p/'generated.bin').read_bytes() if (p/'generated.bin').exists() else b'';need(len(generatedData)==40*n,'GENERATED_WIDTH');generated=list(struct.iter_unpack('<5Q',generatedData));lookup={(s['cam'],s['x'],s['y']):i for i,s in enumerate(samples)};need(len(lookup)==n,'DUPLICATE_SAMPLE')
    for sid,(i,ref,x,y,c) in enumerate(generated):
        s=samples[sid];need(s['xyz']==world(ref,x,y,maps[ref][0][y*160+x]),'GENERATED_WORLD_BINDING');need(i==sid and (ref,x,y)==(s['cam'],s['x'],s['y']) and c==len(confirmed[(ref,x,y)]) and s['views']==c+1,'ACTUAL_GENERATED_CONFIRMATIONS');need(s['confidence']==f32(maps[ref][1][y*160+x]*min(1.,c/2.+.5)),'GENERATED_CONFIDENCE')
    need(set(confirmed)==set(lookup),'CONFIRMED_SAMPLE_COMPLETENESS')
    need(tuple(sum(s['cam']==ref for s in samples) for ref in range(8))==perView,'PER_VIEW_GENERATED_COUNTS')
    need(diagnostics[14]==0 and diagnostics[15]==0 and diagnostics[16:]==(0,0,0),'UNEXPECTED_REFINEMENT_NORMAL_DIAGNOSTICS')
    links=collections.Counter()
    for ref,x,y,src,ix,iy in forward:
        if (src,ix,iy) not in lookup:links['missingVerifiedEndpoint']+=1
        elif (src,ix,iy,ref,x,y) in forward:links['exactReverseConfirmedWithVerifiedEndpoints']+=1
        elif (src,ix,iy,ref) not in attemptKeys:links['reverseNotAttempted']+=1
        else:links['reverseNotExactConfirmed']+=1
    return {'attempts':len(rows),'generatedSamples':n,'sampledPixels':sampled,'photometricPixels':photometric,'consistentPixels':consistent,'consistentPixelsPerView':list(perView),'trailingNativeDiagnostics':list(diagnostics),'reasonCounts':dict(counts),'strictIdentityDiagnostics':dict(links),'maxFloatRelativeErrorDelta':maxRelativeDelta,'scope':'actual native nearest-grid branch; not certified same-sheet or full image-derived SfM'}
