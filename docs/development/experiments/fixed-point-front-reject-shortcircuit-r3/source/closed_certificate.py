"""Closed witness/visibility boundaries; retains all contributing source identities."""
from support_overlay import *

def closed_clip(poly,line,B):
    B.clip();out=[]
    for p,q in zip(poly,poly[1:]+poly[:1]):
        dp=evaluate(line,p,B);dq=evaluate(line,q,B)
        if dp>=0:out.append(p)
        if (dp>=0)!=(dq>=0):
            t=B.number(dp/(dp-dq));out.append(tuple(B.number(p[k]+t*(q[k]-p[k])) for k in range(2)))
    unique=[]
    for p in out:
        if p not in unique:unique.append(p)
    return unique

def point_evidence(p,records,edges,B):
    support=[];front=[];behind=[];camera_ratios={};conflict=False
    for r in records:
        B.clip();vals=[evaluate(l,p,B) for l in r['lines']]
        if min(vals)<0:continue
        ratio=evaluate(r['ratio'],p,B)
        if r['camera'] in camera_ratios and camera_ratios[r['camera']]!=ratio:conflict=True
        camera_ratios[r['camera']]=ratio
        if ratio<1-TOL:front.append(r['id'])
        elif ratio>1/(1-TOL):behind.append(r['id'])
        elif r['surface_support']:
            active=[r['corners'][i] for i,k in enumerate((1,2,0)) if vals[k]>0]
            support.append((r['camera'],r['id'],active))
    why=graph_refusals(support,edges,B)
    if front:why.append('OBSERVED_FREE_SPACE')
    if conflict:why.append('SAME_CAMERA_DEPTH_CONFLICT')
    return why,{'support':support,'front':front,'behind':behind}

def closed_certificate(records,edges,B,reject_only=False):
    records=sorted(records,key=lambda r:r['id'])
    base,patches=certify(records,edges,B)
    # A positive-area failure is permanent: later checks only add refusals.
    # This is reject-only; every potentially accepted candidate continues below.
    if reject_only and base['refusals']:
        return {'accepted_support_hypothesis':False,'refusals':list(base['refusals']),'positive_area':base,'contributingSourceIds':list(base['contributingSourceIds']),'rejectionReasonsComplete':False,'boundaryValidation':'NOT_EVALUATED_ALREADY_REJECTED','observedNormalValidation':'NOT_EVALUATED_ALREADY_REJECTED'}
    reasons=set(base['refusals']);contributors=set(base['contributingSourceIds']);points=set();all_lines=[]
    for r in records:all_lines.extend(r['lines']+list(depth_lines(r,B)))
    for poly,labels in patches:
        for a,b in zip(poly,poly[1:]+poly[:1]):
            ts={F(0),F(1)}
            for line in all_lines:
                B.clip();da=evaluate(line,a,B);db=evaluate(line,b,B)
                if da!=db:
                    t=B.number(da/(da-db))
                    if 0<t<1:ts.add(t)
            ts=sorted(ts)
            for t in ts+[B.number((x+y)/2) for x,y in zip(ts,ts[1:])]:
                points.add(tuple(B.number(a[k]+t*(b[k]-a[k])) for k in range(2)))
                B.parts(points)
    front=[]
    for r in records:
        poly=SIMPLEX
        for line in r['lines']:poly=closed_clip(poly,line,B)
        if poly and min(evaluate(r['ratio'],p,B) for p in poly)<1-TOL:front.append(r['id']);reasons.add('OBSERVED_FREE_SPACE_CLOSED')
    failures=[]
    for p in sorted(points):
        why,evidence=point_evidence(p,records,edges,B)
        contributors.update(tid for cam,tid,ids in evidence['support'])
        if why:reasons.update(why);failures.append({'point':[str(x) for x in p],'refusals':sorted(set(why)),'evidence':evidence})
    return {'accepted_support_hypothesis':not reasons,'refusals':sorted(reasons),'positive_area':base,'boundaryTests':len(points),'boundaryFailures':failures,'closedFrontRecords':front,'contributingSourceIds':sorted(contributors)}

def apply_normal_constraints(certificate,normal_compatibility):
    """Explicitly includes successful boundary-only support, not just area labels."""
    missing=set(certificate['contributingSourceIds'])-normal_compatibility.keys()
    if missing:raise Refusal('MISSING_CONTRIBUTING_NORMAL_CONSTRAINT')
    conflicts=[i for i in certificate['contributingSourceIds'] if not normal_compatibility[i]]
    result=dict(certificate);result['observedNormalConflicts']=conflicts
    if conflicts:
        result['accepted_support_hypothesis']=False
        result['refusals']=sorted(set(result['refusals'])|{'OPPOSING_OBSERVED_TRIANGLE_NORMAL'})
    return result
