"""Reconstructed immutable-ledger adapter; no photographic invocation/front."""
import pathlib,hashlib,math,struct
from exact_geometry import *
from support_overlay import source_record
from closed_certificate import closed_certificate,apply_normal_constraints
from orientation import choose_orientation,source_normal_constraint

def need(b,m):
    if not b:raise Refusal(m)
def finite(x):
    x=float(x);need(math.isfinite(x),'NONFINITE_INPUT');return x
def f32(x):return struct.unpack('f',struct.pack('f',x))[0]
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def rows(p,cap,width):
    with pathlib.Path(p).open() as f:
        for i,line in enumerate(f):
            r=line.split();need(i<cap and len(r)==width,'INPUT_ROW_BUDGET_OR_WIDTH');yield r

def table(p,cap,fixed,tail):
    with pathlib.Path(p).open() as f:
        header=f.readline().split();need(len(header)==fixed+1,'TABLE_HEADER_WIDTH')
        for i,line in enumerate(f):
            r=line.split();need(i<cap and len(r)>=fixed,'TABLE_ROW_BUDGET_OR_WIDTH');d=dict(zip(header[:fixed],r[:fixed]));n=int(d[tail]);need(len(r)==fixed+n,'TABLE_TAIL_WIDTH');yield d,r[fixed:],line.rstrip('\n')

class Ledger:
    def __init__(self,pins,B,rho,max_views=8,normal_step=None):
        need(type(normal_step) is int and 1<=normal_step<=64,'DECLARED_NORMAL_STEP_REQUIRED');self.normal_step=normal_step
        self.B=B;self.rho=B.number(rho);need(self.rho>0,'RADIUS');self.pins=pins
        required={'samples','cameras','dimensions','triangles','witnesses','prepared','accumulators','memberships'}
        need(set(pins)==required,'INPUT_SET');self.paths={k:pathlib.Path(v['path']).resolve() for k,v in pins.items()};self.verify()
        dim=self.paths['dimensions'].read_text().split();need(len(dim)==3,'DIMENSIONS_WIDTH');self.ncam,self.width,self.height=map(int,dim)
        need(0<self.ncam<=max_views<=8 and 0<self.width<=384 and 0<self.height<=288,'DIMENSIONS_BUDGET')
        self.cameras=[];self.centers=[]
        for r in rows(self.paths['cameras'],max_views,16):
            a=tuple(B.number(finite(x)) for x in r);need(a[12]>0 and a[13]>0,'FOCAL_LENGTH')
            rr=list(map(float,a[:9]));orth=[sum(rr[3*i+k]*rr[3*j+k] for k in range(3)) for i in range(3) for j in range(3)]
            determinant=rr[0]*(rr[4]*rr[8]-rr[5]*rr[7])-rr[1]*(rr[3]*rr[8]-rr[5]*rr[6])+rr[2]*(rr[3]*rr[7]-rr[4]*rr[6])
            need(all(abs(v-(1. if i%4==0 else 0.))<=1e-5 for i,v in enumerate(orth)) and abs(determinant-1.)<=1e-5,'NATIVE_NORMAL_ROTATION_DOMAIN')
            need(math.sqrt(sum(float(v)*float(v) for v in a[9:12]))<=1e6,'CAMERA_TRANSLATION_DOMAIN');self.cameras.append(a)
            R=[a[i:i+3] for i in (0,3,6)];co=[cross(R[1],R[2],B),cross(R[2],R[0],B),cross(R[0],R[1],B)];det=dot(R[0],co[0],B);need(det!=0,'SINGULAR_CAMERA')
            center=tuple(B.number(-sum(co[j][i]*a[9+j] for j in range(3))/det) for i in range(3));need(self.camera_xyz(len(self.cameras)-1,center)==(0,0,0),'CAMERA_CENTER_BINDING');self.centers.append(center)
        need(len(self.cameras)==self.ncam,'CAMERA_COUNT')
        self.samples=[];self.source_q=[];self.projected=[];lookup={}
        for r in rows(self.paths['samples'],20000,8):
            cam,x,y=map(int,r[:3]);need(0<=cam<self.ncam and 0<=x<self.width and 0<=y<self.height,'PIXEL_BOUNDS');key=(cam,x,y);need(key not in lookup,'DUPLICATE_PIXEL');lookup[key]=len(self.samples)
            p=tuple(B.number(finite(v)) for v in r[3:6]);confidence=finite(r[6]);views=finite(r[7]);need(0<=confidence<=1 and views==int(views) and 2<=views<=self.ncam,'SAMPLE_RELIABILITY_FIELDS');need(f32(confidence)==confidence,'CONFIDENCE_NOT_FLOAT32');self.samples.append((cam,x,y,p,confidence,views));q=self.camera_xyz(cam,p);self.native_sample_domain(cam,p,x,y);xy=self.pixel(cam,q);self.source_q.append(q);self.projected.append(xy)
        self.triangles=[];self.bounds=[];self.triangle_index={}
        for r in rows(self.paths['triangles'],40000,4):
            cam,*ids=map(int,r);need(0<=cam<self.ncam and len(set(ids))==3 and all(0<=i<len(self.samples) and self.samples[i][0]==cam for i in ids),'TRIANGLE_BINDING')
            xy=[self.projected[i] for i in ids];need(cross2(*xy,B)!=0,'DEGENERATE_SOURCE');box=(min(p[0] for p in xy),max(p[0] for p in xy),min(p[1] for p in xy),max(p[1] for p in xy));tid=len(self.triangles);self.triangles.append((cam,tuple(ids)));self.bounds.append(box)
            for x,y in self.bins(box):self.triangle_index.setdefault((cam,x,y),[]).append(tid)
        self.edges=set();self.missing_endpoint_rows=0;self.witness_rows=0
        for r in rows(self.paths['witnesses'],100000,13):
            v=[finite(x) for x in r];self.witness_rows+=1
            if v[6]!=0:continue
            need(all(x==int(x) for x in v[:6]),'WITNESS_PIXEL_IDENTITY');aa=tuple(map(int,v[:3]));bb=tuple(map(int,v[3:6]));need(all(0<=cam<self.ncam and 0<=x<self.width and 0<=y<self.height for cam,x,y in (aa,bb)) and aa[0]!=bb[0],'WITNESS_PIXEL_BOUNDS');a=lookup.get(aa);b=lookup.get(bb)
            if a is None or b is None:self.missing_endpoint_rows+=1;continue
            self.edges.add((a,b))
        parent=list(range(len(self.triangles)));incident=[[] for _ in self.samples];edge_faces={}
        def root(i):
            while parent[i]!=i:parent[i]=parent[parent[i]];i=parent[i]
            return i
        def join(a,b):parent[root(a)]=root(b)
        for tid,(cam,ids) in enumerate(self.triangles):
            for i,j in zip(ids,ids[1:]+ids[:1]):
                B.clip();incident[i].append(tid);key=tuple(sorted((i,j)))
                if key in edge_faces:join(edge_faces[key],tid)
                else:edge_faces[key]=tid
        for a,b in sorted(self.edges):
            B.clip()
            if a<b and (b,a) in self.edges and incident[a] and incident[b]:
                for tid in incident[a]+incident[b]:B.clip();join(incident[a][0],tid)
        components={};self.triangle_components=[];self.component_counts={}
        for tid in range(len(self.triangles)):
            r=root(tid)
            if r not in components:components[r]=len(components)
            c=components[r];self.triangle_components.append(c);self.component_counts[c]=self.component_counts.get(c,0)+1
        self.prepared=[];self.prepared_fields=[]
        for d,tail,raw in table(self.paths['prepared'],20000,36,'supportIdCount'):
            sid=int(d['sourceId']);need(sid==len(self.prepared) and sid<len(self.samples),'PREPARED_ID');s=self.samples[sid]
            need((int(d['camera']),int(d['x']),int(d['y']))==s[:3] and tuple(B.number(finite(d[k])) for k in ('px','py','pz'))==s[3],'PREPARED_BINDING')
            need(all(math.isfinite(float(v)) for v in d.values()),'PREPARED_NONFINITE')
            need(finite(d['confidence'])==s[4] and int(d['supportingViews'])==s[5],'PREPARED_RELIABILITY_BINDING')
            need(d['preparedSupported'] in ('0','1'),'PREPARED_SUPPORTED_FLAG')
            need(all(float(d[k]).is_integer() and 0<=float(d[k])<=255 for k in ('r','g','b')),'PREPARED_RGB_UINT8')
            need(len(tail)<=9 and len(set(tail))==len(tail) and all(str(int(v))==v and 0<=int(v)<self.width*self.height for v in tail),'PREPARED_SUPPORT_ID')
            step=self.normal_step;cam,x,y=s[:3]
            expected={(y+dy*step)*self.width+x+dx*step for dy in (-1,0,1) for dx in (-1,0,1)} if step<=x<self.width-step and step<=y<self.height-step else set()
            need(set(map(int,tail))<=expected,'PREPARED_SUPPORT_PIXEL_BINDING')
            if d['preparedSupported']=='1':need(len(tail)==9 and set(map(int,tail))==expected,'PREPARED_SUPPORTED_NEIGHBORHOOD')
            self.prepared_fields.append(d.copy());self.prepared.append((finite(d['confidence']),int(d['supportingViews']),raw))
        need(len(self.prepared)==len(self.samples),'PREPARED_COUNT')
        self.points=[];self.members=[];self.attributes=[];self.unknown=[];self.point_index={}
        for d,tail,raw in table(self.paths['accumulators'],40000,31,'sourceCount'):
            aid=int(d['accumulatorId']);need(aid==len(self.points),'ACCUMULATOR_ID');ids=tuple(map(int,tail));need(ids and len(set(ids))==len(ids) and all(0<=i<len(self.samples) for i in ids),'MEMBER_IDS')
            need(all(math.isfinite(float(v)) for v in d.values()),'ACCUMULATOR_NONFINITE')
            need(all(float(d[k]).is_integer() and 0<=float(d[k])<=255 for k in ('r','g','b')) and 0<=float(d['confidence'])<=1,'ACCUMULATOR_ATTRIBUTE_RANGE')
            need(all(float(d[k])==0 for k in ('preFaceNx','preFaceNy','preFaceNz')),'NOT_PRE_FACE_NORMAL_STAGE')
            p=tuple(B.number(finite(d[k])) for k in ('px','py','pz'));self.points.append(p);self.members.append(ids);self.attributes.append((d.copy(),raw));need(d['supportedNormal'] in ('0','1'),'NORMAL_FLAG');self.unknown.append(d['supportedNormal']=='0');cell=tuple(x//(2*self.rho) for x in p);self.point_index.setdefault(cell,[]).append(aid)
        seen=set();self.membership_rows=[]
        with self.paths['memberships'].open() as f:
            need(f.readline().split()==['sequence','accumulatorId','sourceId','actualWeight'],'MEMBERSHIP_HEADER')
            for line in f:
                r=line.split();need(len(r)==4 and len(self.membership_rows)<20000,'MEMBERSHIP_ROW');seq,aid,sid=map(int,r[:3]);need(seq==len(self.membership_rows) and 0<=aid<len(self.points) and sid in self.members[aid] and sid not in seen,'MEMBERSHIP_BINDING');seen.add(sid)
                conf,views,_=self.prepared[sid];weight=f32(max(f32(.005),conf)*views);need(finite(r[3])==weight,'WEIGHT_RECOMPUTATION');self.membership_rows.append((aid,sid,weight,line.rstrip('\n')))
        need(len(self.membership_rows)==sum(map(len,self.members)),'MEMBERSHIP_COMPLETENESS');self.unaccepted=sorted(set(range(len(self.samples)))-seen);self.audit_accumulators();self.verify()
    def audit_accumulators(self):
        sums=[{'w':0.,'p':[0.,0.,0.],'n':[0.,0.,0.],'rgb':[0.,0.,0.],'max':0.,'mask':0,'ids':[]} for _ in self.points]
        for aid,sid,w,raw in self.membership_rows:
            a=sums[aid];p=self.prepared_fields[sid];sample=self.samples[sid];a['w']+=w;a['ids'].append(sid);a['mask']|=1<<sample[0];a['max']=max(a['max'],float(p['confidence']))
            for j,k in enumerate(('px','py','pz')):a['p'][j]+=float(p[k])*w
            if p['preparedSupported']=='1':
                for j,k in enumerate(('preparedNx','preparedNy','preparedNz')):a['n'][j]+=float(p[k])*w
            for j,k in enumerate(('r','g','b')):a['rgb'][j]+=float(p[k])*w
        observed_order=[sid for aid,sid,w,raw in self.membership_rows]
        ordered=sorted(observed_order,key=lambda sid:(*self.samples[sid][3],self.samples[sid][0],self.samples[sid][2],self.samples[sid][1]))
        need(observed_order==ordered,'NATIVE_SOURCE_ORDER')
        for aid,a in enumerate(sums):
            d,raw=self.attributes[aid];need(tuple(a['ids'])==self.members[aid],'ORDERED_MEMBER_BINDING')
            supported=d['supportedNormal']=='1'
            need(all((self.prepared_fields[s]['preparedSupported']=='1')==supported for s in a['ids']),'NORMAL_SUPPORT_MEMBERSHIP')
            need(supported or len(a['ids'])==1,'UNSUPPORTED_ACCUMULATOR_NOT_SINGLETON')
            need(a['w']>0 and float(d['weight'])==a['w'] and int(d['viewMask'])==a['mask'],'ACCUMULATOR_WEIGHT_MASK')
            need(int(d['supportingViews'])==a['mask'].bit_count() and a['mask'].bit_count()==len(a['ids']),'ACCUMULATOR_DISTINCT_CAMERAS')
            need(float(d['maxConfidence'])==a['max'] and float(d['confidence'])==a['max'],'ACCUMULATOR_CONFIDENCE')
            for j,k in enumerate(('weightedPx','weightedPy','weightedPz')):need(float(d[k])==a['p'][j],'WEIGHTED_POSITION')
            for j,k in enumerate(('weightedNx','weightedNy','weightedNz')):need(float(d[k])==a['n'][j],'WEIGHTED_NORMAL')
            for j,k in enumerate(('weightedR','weightedG','weightedB')):need(float(d[k])==a['rgb'][j],'WEIGHTED_COLOR')
            for j,k in enumerate(('px','py','pz')):need(float(d[k])==a['p'][j]*(1./a['w']),'MATERIALIZED_POSITION')
            for j,k in enumerate(('r','g','b')):need(int(d[k])==min(255,max(0,math.floor(a['rgb'][j]/a['w']+.5))),'MATERIALIZED_COLOR')
    def verify(self):
        for k,p in self.paths.items():need(p.is_file() and p.stat().st_size==self.pins[k]['bytes'] and sha(p)==self.pins[k]['sha256'],'INPUT_IDENTITY:'+k)
    def native_sample_domain(self,cam,p,x,y):
        # Match the native scalar double operation order for domain gates; exact
        # rational overlay geometry remains separate, not float32 depth replay.
        a=list(map(float,self.cameras[cam]));p=list(map(float,p))
        q=[((a[3*i]*p[0]+a[3*i+1]*p[1])+a[3*i+2]*p[2])+a[9+i] for i in range(3)]
        need(all(math.isfinite(v) for v in q) and .01<q[2]<=1e6 and math.sqrt(p[0]*p[0]+p[1]*p[1]+p[2]*p[2])<=1e6,'SOURCE_CAMERA_DEPTH_OR_POSITION')
        need(abs(a[12]*q[0]/q[2]+a[14]-x)<=.25 and abs(a[13]*q[1]/q[2]+a[15]-y)<=.25,'SOURCE_PIXEL_PROJECTION_BINDING')
    def camera_xyz(self,cam,p):
        a=self.cameras[cam];return tuple(self.B.number(a[9+i]+sum(a[3*i+j]*p[j] for j in range(3))) for i in range(3))
    def pixel(self,cam,q):
        a=self.cameras[cam];return self.B.number(a[12]*q[0]/q[2]+a[14]),self.B.number(a[13]*q[1]/q[2]+a[15])
    def bins(self,box):
        x0,x1,y0,y1=box
        for x in range(x0//2,x1//2+1):
            for y in range(y0//2,y1//2+1):self.B.clip();yield x,y
    def overlapping(self,cam,points):
        q=[self.camera_xyz(cam,p) for p in points];need(all(p[2]>0 for p in q),'CANDIDATE_CAMERA_DEPTH');xy=[self.pixel(cam,p) for p in q];box=(min(p[0] for p in xy),max(p[0] for p in xy),min(p[1] for p in xy),max(p[1] for p in xy));ids=set()
        for x,y in self.bins(box):
            for tid in self.triangle_index.get((cam,x,y),[]):
                self.B.clip();b=self.bounds[tid]
                if b[0]<=box[1] and b[1]>=box[0] and b[2]<=box[3] and b[3]>=box[2]:ids.add(tid)
        return sorted(ids),q
    def candidate_certificate(self,ids):
        need(len(ids)==3 and len(set(ids))==3 and all(0<=i<len(self.points) for i in ids),'CANDIDATE_IDS');points=[self.points[i] for i in ids]
        rays=[[self.centers[self.samples[s][0]] for s in self.members[i]] for i in ids];normal,sign=choose_orientation(points,rays,self.B);records=[];compat={}
        for cam in range(self.ncam):
            tids,q=self.overlapping(cam,points)
            for tid in tids:
                _,corners=self.triangles[tid];records.append(source_record(tid,cam,corners,[self.source_q[i] for i in corners],q,self.B,max_views=self.ncam));compat[tid]=source_normal_constraint(normal,[self.samples[i][3] for i in corners],self.centers[cam],self.B)
        result=apply_normal_constraints(closed_certificate(records,self.edges,self.B),compat);result.update(pointIds=list(ids),orientationSign=sign,enumeratedSourceTriangles=[r['id'] for r in records]);return result
