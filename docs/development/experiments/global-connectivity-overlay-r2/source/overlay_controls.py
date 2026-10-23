from closed_certificate import *
from orientation import *
from controls import require,tiles

def record(i,cam,t,z,cand,B):return source_record(i,cam,(3*i,3*i+1,3*i+2),[(x*z,y*z,z) for x,y in t],cand,B)
def run_overlay(B):
    out={};c=[(F(0),F(0),F(1)),(F(1),F(0),F(1)),(F(0),F(1),F(1))]
    a=record(0,0,SIMPLEX,F(1),c,B);b=record(1,1,SIMPLEX,F(1),c,B)
    edges={(i,i+3) for i in range(3)}|{(i+3,i) for i in range(3)}
    good=closed_certificate([a,b],edges,B);require(good['accepted_support_hypothesis'],'closed two-view support')
    weak=closed_certificate([a,b],{(0,3),(3,0)},B)
    require(weak['positive_area']['accepted_support_hypothesis'] and not weak['accepted_support_hypothesis'],'zero-weight boundary witness')
    out['positiveWeightBoundaryWitnessRequired']=True
    touch=[(F(-1),F(0)),(F(0),F(-1)),(F(0),F(0))]
    touch_front=record(2,2,touch,F(2),c,B);bad=closed_certificate([a,b,touch_front],edges,B)
    require(bad['closedFrontRecords']==[2],'zero-area free-space intersection missed')
    out['closedPointTouchFreeSpaceRefused']=True
    front=record(2,2,SIMPLEX,F(2),c,B);bad=closed_certificate([a,b,front],edges,B)
    require('OBSERVED_FREE_SPACE' in bad['refusals'],'late overlapping contradiction ignored')
    behind=record(2,2,SIMPLEX,F(1,2),c,B);occ=closed_certificate([a,b,behind],edges,B)
    require(occ['accepted_support_hypothesis'],'occluded camera globally vetoed')
    require(bad==closed_certificate([front,b,a],edges,B),'record order changed result')
    out['lateOverlappingEvidenceAndOcclusion']=True
    boundary=record(2,2,SIMPLEX,F(1)/(1-TOL),c,B);edge3=edges|{(i,i+6) for i in range(3)}|{(i+6,i) for i in range(3)}
    eq=closed_certificate([a,b,boundary],edge3,B);require(eq['accepted_support_hypothesis'],'exact tolerance boundary')
    require(sum(F(p['area']) for p in eq['positive_area']['patches'])==F(1,2),'boundary duplicated area')
    edgeon=[(F(0),F(0),F(1)),(F(1,10),F(0),F(1)),(F(0),F(0),F(101,100))]
    ea=record(0,0,SIMPLEX,F(1),edgeon,B);eb=record(1,1,SIMPLEX,F(1),edgeon,B)
    require(not closed_certificate([ea,eb],edges,B)['accepted_support_hypothesis'],'edge-on cameras counted')
    out['exactDepthEqualityAndEdgeOnExclusion']=True
    touch_support=record(2,2,touch,F(1),c,B)
    cert=closed_certificate([a,b,touch_support],edges|{(0,8),(8,0)},B)
    require(cert['accepted_support_hypothesis'] and cert['positive_area']['contributingSourceIds']==[0,1] and cert['contributingSourceIds']==[0,1,2],'boundary-only contributor identity lost')
    blocked=apply_normal_constraints(cert,{0:True,1:True,2:False});require(not blocked['accepted_support_hypothesis'] and blocked['observedNormalConflicts']==[2],'boundary-only normal constraint omitted')
    try:apply_normal_constraints(cert,{0:True,1:True})
    except Refusal:pass
    else:raise RuntimeError('missing boundary normal accepted')
    out['boundaryOnlyNormalConstraintPropagation']='PASS_API_PROVENANCE_CONTROL_NOT_PHYSICAL_NORMAL_MEASUREMENT'
    n,s=choose_orientation(c,[[(0,0,0)]]*3,B);nr,sr=choose_orientation([c[0],c[2],c[1]],[[(0,0,0)]]*3,B)
    require(n==nr and s==-sr,'winding orientation');require(source_normal_constraint(n,c,(0,0,0),B),'same source plane')
    require(not source_normal_constraint(n,c,(0,0,2),B),'opposing source normal set')
    for rays in ([[(0,0,0)],[(0,0,2)],[(0,0,0)]],[[(0,0,1)]]*3,[[],[(0,0,0)],[(0,0,0)]]):
        try:choose_orientation(c,rays,B)
        except Refusal:pass
        else:raise RuntimeError('ambiguous ray constraint accepted')
    out['orientationConstraintsNotPointNormals']=True
    # Fixed four-camera abstract depth/reciprocal controls: two genuinely linked
    # source-corner sets per layer; opposite cameras see nearer opposing layer.
    four_edges={(i,i+3) for i in range(3)}|{(i+3,i) for i in range(3)}|{(i+6,i+9) for i in range(3)}|{(i+9,i+6) for i in range(3)}
    layers=[]
    for layer in (5,6):
        records=[]
        for cam in range(4):
            depth=F(layer if cam<2 else 11-layer)
            # A positive camera-space projected face per view; this is an abstract
            # support test, not a recovered common-world calibration/mesh fixture.
            cand=[(u*depth,v*depth,depth) for u,v in SIMPLEX]
            records.append(record(cam,cam,SIMPLEX,F(5),cand,B))
        result=closed_certificate(records,four_edges,B);require(result['accepted_support_hypothesis'],'resolved layer support')
        layers.append(result['contributingSourceIds'])
    require(layers==[[0,1],[2,3]],'layers lost distinct actual corner supports')
    out['fourViewResolvedLayerSupport']={'contributors':layers,'scope':'abstract per-view source/depth/witness unit control; not calibrated observation pipeline or extracted geometry'}
    # True interior hole: both camera unions miss [1,1.5]^2 inside x+y<=6.
    large=[(F(0),F(0),F(1)),(F(6),F(0),F(1)),(F(0),F(6),F(1))]
    records=[]
    for cam in range(2):
        for _,tri in tiles([F(0),F(1),F(3,2),F(6)],(1,1)):
            records.append(record(len(records),cam,tri,F(1),large,B))
    area_result,_=certify(records,set(),B)
    missing=sum(F(p['area']) for p in area_result['patches'] if not p['labels'])
    require(missing==F(1,144),'union overlay interior hole')
    out['strictInteriorHoleParameterArea']='1/144'
    return out
