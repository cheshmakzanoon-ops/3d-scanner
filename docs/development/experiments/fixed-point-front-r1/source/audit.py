"""Independent ordered accumulator arithmetic on actual hook rows; no estimator."""
import pathlib,struct,json,math

def need(b,m):
    if not b:raise RuntimeError(m)
def f32(x):return struct.unpack('<f',struct.pack('<f',x))[0]
def bits(x):return struct.pack('<d',x)
def audit(directory):
    directory=pathlib.Path(directory)
    prepared=[r.split() for r in (directory/'prepared.tsv').read_text().splitlines()]
    accum=[r.split() for r in (directory/'accumulators.tsv').read_text().splitlines()]
    members=[r.split() for r in (directory/'memberships.tsv').read_text().splitlines()]
    need(len(prepared)<=35112 and len(accum)<=35112 and len(members)<=35112,'ROW_BUDGET')
    totals=[{'weight':0.,'p':[0.,0.,0.],'n':[0.,0.,0.],'rgb':[0.,0.,0.],'mask':0,'max':0.,'ids':[]} for _ in accum];seen=set()
    for i,row in enumerate(prepared):need(int(row[0])==i and len(row)==36+int(row[35]),'PREPARED_SCHEMA')
    for seq,row in enumerate(members):
        need(len(row)==4 and int(row[0])==seq,'MEMBERSHIP_SCHEMA');aid,sid=int(row[1]),int(row[2]);w=float(row[3]);need(0<=aid<len(accum) and 0<=sid<len(prepared) and sid not in seen,'MEMBERSHIP_ID');seen.add(sid)
        p=prepared[sid];need(bits(w)==bits(float(f32(max(f32(.005),float(p[10]))*int(p[11])))),'NATIVE_FLOAT32_WEIGHT');a=totals[aid]
        # Native new-accumulator branch assigns products directly; adding them
        # to +0 would erase a first member's negative-zero bit.
        first=not a['ids'];a['ids'].append(sid)
        if first:
            a['weight']=w;a['max']=float(p[10]);a['mask']=1<<int(p[1])
            a['p']=[float(p[4+j])*w for j in range(3)];a['rgb']=[float(p[7+j])*w for j in range(3)]
            a['n']=[float(p[22+j])*w for j in range(3)] if p[21]=='1' else [0.,0.,0.]
        else:
            a['weight']+=w;a['max']=max(a['max'],float(p[10]));a['mask']|=1<<int(p[1])
            for j in range(3):a['p'][j]+=float(p[4+j])*w;a['rgb'][j]+=float(p[7+j])*w
            if p[21]=='1':
                for j in range(3):a['n'][j]+=float(p[22+j])*w
    unknown=0
    for i,(r,a) in enumerate(zip(accum,totals)):
        need(len(r)==31+int(r[30]) and int(r[0])==i and list(map(int,r[31:]))==a['ids'],'ACCUMULATOR_SCHEMA_MEMBERS')
        need(r[1] in ('0','1') and all(prepared[s][21]==r[1] for s in a['ids']),'NORMAL_MEMBERSHIP');unknown+=r[1]=='0';need(r[1]=='1' or len(a['ids'])==1,'UNKNOWN_NOT_SINGLETON')
        expected=[*a['p'],*a['n'],*a['rgb'],a['weight'],a['max']]
        need(all(bits(float(r[2+j]))==bits(v) for j,v in enumerate(expected)),'ORDERED_WEIGHTED_FIELDS_BIT_PARITY')
        need(int(r[13])==a['mask'] and int(r[26])==a['mask'].bit_count(),'CAMERA_MASK')
        need(all(bits(float(r[19+j]))==bits(a['p'][j]*(1./a['weight'])) for j in range(3)),'MATERIALIZED_POSITION_BITS')
        need([int(v) for v in r[22:25]]==[min(255,max(0,math.floor(v/a['weight']+.5))) for v in a['rgb']],'MATERIALIZED_COLORS')
        need(float(r[25])==a['max'] and all(float(v)==0 for v in r[27:30]),'PREFACE_ATTRIBUTES')
    unaccepted=sorted(set(range(len(prepared)))-seen)
    need(all(float(prepared[s][10])<f32(.12) for s in unaccepted),'UNEXPLAINED_UNACCEPTED_SOURCE')
    order=[int(r[2]) for r in members];need(order==sorted(order,key=lambda s:(*map(float,prepared[s][4:7]),int(prepared[s][1]),int(prepared[s][3]),int(prepared[s][2]))),'NATIVE_SOURCE_ORDER')
    # Parse explicit little-endian field schema, never native struct padding.
    data=(directory/'mesh.bits').read_bytes();offset=0
    def integer(n=8):
        nonlocal offset
        need(offset+n<=len(data),'MESH_BITS_TRUNCATED');v=int.from_bytes(data[offset:offset+n],'little');offset+=n;return v
    vertices=integer();offset+=vertices*56;faces=integer();offset+=faces*13
    counters=[integer() for _ in range(5)];offset+=4
    attached=[]
    for _ in range(integer()):
        vertex=integer(4);camera=integer(1);x=integer(2);y=integer(2);sid=integer();relation=integer(1)
        need(vertex<vertices and sid in seen and (camera,x,y)==tuple(map(int,prepared[sid][1:4])) and relation in (0,1),'FINAL_ATTACHED_BINDING');attached.append(sid)
    unattached=[]
    for _ in range(integer()):
        camera=integer(1);x=integer(2);y=integer(2);sid=integer();reason=integer(1)
        need(sid in seen and (camera,x,y)==tuple(map(int,prepared[sid][1:4])) and reason in (0,1),'FINAL_UNATTACHED_BINDING');unattached.append(sid)
    topology=[integer() for _ in range(6)];incomplete=integer(1)
    need(offset==len(data) and len(set(attached+unattached))==len(attached+unattached) and set(attached+unattached)==seen,'FINAL_LEDGER_COMPLETENESS')
    need(counters[0]==len(prepared) and counters[1]==len(members),'FINAL_INPUT_ACCOUNTING')
    final={'vertices':vertices,'faces':faces,'attached':len(attached),'unattached':len(unattached),'unattachedSourceIds':unattached,'boundaryEdges':topology[0],'nonmanifoldEdges':topology[2],'intersections':topology[3],'topologyIncomplete':bool(incomplete)}
    normals=json.loads((directory/'normal-audit.json').read_text())
    return {'finalMesh':final,'inputSamples':len(prepared),'acceptedMemberships':len(members),'accumulators':len(accum),'unknownNormalAccumulators':unknown,'unacceptedSourceIds':sorted(set(range(len(prepared)))-seen),'normalRegeneration':normals,'orderedAccumulatorBitParity':True,'reciprocalWitnessRegeneration':'NOT_VERIFIED'}
