from exact_geometry import *
import random

def require(ok,msg):
    if not ok:raise RuntimeError(msg)
def tiles(grid,hole=None):
    out=[]
    for y in range(len(grid)-1):
        for x in range(len(grid)-1):
            if (x,y)==hole:continue
            a=(grid[x],grid[y]);b=(grid[x+1],grid[y]);c=(grid[x+1],grid[y+1]);d=(grid[x],grid[y+1])
            out.extend([(len(out),[a,b,c]),(len(out)+1,[a,c,d])])
    return out

def run(B):
    results={};candidate=[(F(0),F(0)),(F(6),F(0)),(F(0),F(6))]
    grid=[F(0),F(1),F(3,2),F(6)];full=tiles(grid);hole=tiles(grid,(1,1))
    rem,covered=union_partition(candidate,full,B);require(not rem,'full union')
    rem,covered=union_partition(candidate,hole,B)
    require(sum(area(p,B) for p in rem)==F(1,4),'enclosed hole area')
    for p in candidate+[(F(2),F(2))]:
        require(any(all(cross2(t[i],t[(i+1)%3],p,B)>=0 for i in range(3)) for _,t in hole),'vertices/centroid unsupported')
    require((rem,covered)==union_partition(candidate,list(reversed(hole)),B),'ordering')
    results['strict_enclosed_hole']={'uncoveredArea':'1/4','verticesAndCentroidSupported':True,'refused':True}
    shifted=tiles([F(-2,3),F(1,3),F(4,3),F(7,3),F(10,3),F(13,3),F(16,3),F(19,3)])
    require(not union_partition(candidate,shifted,B)[0],'shifted grid union')
    results['shifted_multicell_union']=True
    poly=[(0,0),(1,0),(0,1)];ci=affine(poly,[F(1,5),F(1,6),F(1,7)],B);si=affine(poly,[F(1,5)]*3,B)
    require(ratio_bounds(poly,ci,si,B)==(F(1),F(7,5)),'depth extrema')
    results['perspectiveRatioExtrema']=['1','7/5']
    spec=sphere((0,0,0),(1,0,0),(0,1,0),1,B)
    for p in [(0,0,0),(1,0,0),(0,1,0),(1,1,0)]:
        require(ball_side(p,spec,1,B)==0 and ball_side(p,spec,-1,B)==0,'cospherical boundary')
    require(ball_side((F(1,2),F(1,2),0),spec,1,B)<0,'unknown-normal obstruction')
    require(ball_side((F(1,2),F(1,2),1),spec,1,B)<0 and ball_side((F(1,2),F(1,2),1),spec,-1,B)>0,'sphere sides')
    results['emptyBallUnknownNormalAndExactTies']=True
    require(cross2((0.,0.),(.1,0.),(0.,.1),B)==F(.1)**2,'premature float arithmetic')
    results['representedFloatNormalization']=True
    refusals={}
    cases=[('oversizedCancellingOperands',lambda:cross2((0,0),(2**100,0),(2**100,0),Budget(bits=4))),('clipping',lambda:union_partition(candidate,full,Budget(clips=0))),('fragments',lambda:union_partition(candidate,hole,Budget(fragments=0))),('balls',lambda:ball_side((0,0,0),spec,1,Budget(balls=0))),('collinear',lambda:sphere((0,0,0),(1,0,0),(2,0,0),1,B)),('radius',lambda:sphere((0,0,0),(1,0,0),(0,1,0),F(1,10),B)),('invalidDimension',lambda:dot((1,2),(1,2),B)),('duplicateIds',lambda:union_partition(candidate,[(0,poly),(0,poly)],B))]
    for name,fn in cases:
        try:fn()
        except Refusal as e:refusals[name]=str(e)
        else:raise RuntimeError('missing refusal '+name)
    results['expectedRefusals']=refusals
    rng=random.Random(317)
    for _ in range(500):
        t=[(F(rng.randrange(-20,21),7),F(rng.randrange(-20,21),7)) for _ in range(3)]
        if not area(t,B):continue
        outside,inside=partition(candidate,t,B)
        require(sum(area(p,B) for p in outside)+area(inside,B)==area(candidate,B),'random partition area')
    for _ in range(10000):
        a=F(rng.randrange(-50,51),13);b=F(rng.randrange(-50,51),17);r=F(rng.randrange(0,51),19)
        expected=a+b*r;require(algebraic_sign(a,b,r*r,B)==(expected>0)-(expected<0),'square radical sign')
    results['randomPartitionTrials']=500;results['squareRadicalTrials']=10000
    return results
