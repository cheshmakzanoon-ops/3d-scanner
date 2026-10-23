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
    need(len(prepared)<=162 and len(accum)<=162 and len(members)<=162,'ROW_BUDGET')
    totals=[{'weight':0.,'p':[0.,0.,0.],'n':[0.,0.,0.],'rgb':[0.,0.,0.],'mask':0,'max':0.,'ids':[]} for _ in accum];seen=set()
    for i,row in enumerate(prepared):need(int(row[0])==i and len(row)==36+int(row[35]),'PREPARED_SCHEMA')
    for seq,row in enumerate(members):
        need(len(row)==4 and int(row[0])==seq,'MEMBERSHIP_SCHEMA');aid,sid=int(row[1]),int(row[2]);w=float(row[3]);need(0<=aid<len(accum) and 0<=sid<len(prepared) and sid not in seen,'MEMBERSHIP_ID');seen.add(sid)
        p=prepared[sid];need(bits(w)==bits(float(f32(max(f32(.005),float(p[10]))*int(p[11])))),'NATIVE_FLOAT32_WEIGHT');a=totals[aid];a['ids'].append(sid);a['weight']+=w;a['max']=max(a['max'],float(p[10]));a['mask']|=1<<int(p[1])
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
    need(len(seen)==len(prepared),'UNEXPECTED_UNACCEPTED_SOURCE_IN_TWO_CASES')
    normals=json.loads((directory/'normal-audit.json').read_text())
    return {'inputSamples':len(prepared),'acceptedMemberships':len(members),'accumulators':len(accum),'unknownNormalAccumulators':unknown,'unacceptedSourceIds':sorted(set(range(len(prepared)))-seen),'normalRegeneration':normals,'orderedAccumulatorBitParity':True,'reciprocalWitnessRegeneration':'NOT_VERIFIED'}
