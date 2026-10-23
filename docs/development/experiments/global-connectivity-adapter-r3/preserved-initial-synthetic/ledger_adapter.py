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
    def __init__(self,pins,B,rho,max_views=8):
        self.B=B;self.rho=B.number(rho);need(self.rho>0,'RADIUS');self.pins=pins
        required={'samples','cameras','dimensions','triangles','witnesses','prepared','accumulators','memberships'}
        need(set(pins)==required,'INPUT_SET');self.paths={k:pathlib.Path(v['path']).resolve() for k,v in pins.items()};self.verify()
        dim=self.paths['dimensions'].read_text().split();need(len(dim)==3,'DIMENSIONS_WIDTH');self.ncam,self.width,self.height=map(int,dim)
        need(0<self.ncam<=max_views<=8 and 0<self.width<=384 and 0<self.height<=288,'DIMENSIONS_BUDGET')
        self.cameras=[];self.centers=[]
        for r in rows(self.paths['cameras'],max_views,16):
            a=tuple(B.number(finite(x)) for x in r);need(a[12]>0 and a[13]>0,'FOCAL_LENGTH');self.cameras.append(a)
            R=[a[i:i+3] for i in (0,3,6)];co=[cross(R[1],R[2],B),cross(R[2],R[0],B),cross(R[0],R[1],B)];det=dot(R[0],co[0],B);need(det!=0,'SINGULAR_CAMERA')
            center=tuple(B.number(-sum(co[j][i]*a[9+j] for j in range(3))/det) for i in range(3));need(self.camera_xyz(len(self.cameras)-1,center)==(0,0,0),'CAMERA_CENTER_BINDING');self.centers.append(center)
        need(len(self.cameras)==self.ncam,'CAMERA_COUNT')
        self.samples=[];self.source_q=[];self.projected=[];lookup={}
        for r in rows(self.paths['samples'],20000,8):
            cam,x,y=map(int,r[:3]);need(0<=cam<self.ncam and 0<=x<self.width and 0<=y<self.height,'PIXEL_BOUNDS');key=(cam,x,y);need(key not in lookup,'DUPLICATE_PIXEL');lookup[key]=len(self.samples)
            p=tuple(B.number(finite(v)) for v in r[3:6]);confidence=finite(r[6]);views=finite(r[7]);need(0<=confidence<=1 and views==int(views) and 2<=views<=self.ncam,'SAMPLE_RELIABILITY_FIELDS');self.samples.append((cam,x,y,p,confidence,views));q=self.camera_xyz(cam,p);need(q[2]>0,'SOURCE_CAMERA_DEPTH');self.source_q.append(q);self.projected.append(self.pixel(cam,q))
        self.triangles=[];self.bounds=[];self.triangle_index={}
        for r in rows(self.paths['triangles'],40000,4):
            cam,*ids=map(int,r);need(0<=cam<self.ncam and len(set(ids))==3 and all(0<=i<len(self.samples) and self.samples[i][0]==cam for i in ids),'TRIANGLE_BINDING')
            xy=[self.projected[i] for i in ids];need(cross2(*xy,B)!=0,'DEGENERATE_SOURCE');box=(min(p[0] for p in xy),max(p[0] for p in xy),min(p[1] for p in xy),max(p[1] for p in xy));tid=len(self.triangles);self.triangles.append((cam,tuple(ids)));self.bounds.append(box)
            for x,y in self.bins(box):self.triangle_index.setdefault((cam,x,y),[]).append(tid)
        self.edges=set();self.missing_endpoint_rows=0;self.witness_rows=0
        for r in rows(self.paths['witnesses'],100000,13):
            v=[finite(x) for x in r];self.witness_rows+=1
            if v[6]!=0:continue
            need(all(x==int(x) for x in v[:6]),'WITNESS_PIXEL_IDENTITY');aa=tuple(map(int,v[:3]));bb=tuple(map(int,v[3:6]));a=lookup.get(aa);b=lookup.get(bb)
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
            need(all(str(int(v))==v and int(v)>=0 for v in tail),'PREPARED_SUPPORT_ID')
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
            for j,k in enumerate(('preparedNx','preparedNy','preparedNz')):a['n'][j]+=float(p[k])*w
            for j,k in enumerate(('r','g','b')):a['rgb'][j]+=float(p[k])*w
        for aid,a in enumerate(sums):
            d,raw=self.attributes[aid];need(tuple(a['ids'])==self.members[aid],'ORDERED_MEMBER_BINDING')
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
