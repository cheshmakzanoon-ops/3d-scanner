# PUBLIC PATH-REDACTED SOURCE; not the exact executed file.
"""Read-only fixed indexed stage audits. Link function copied c4b, return set only."""
import sys,json,pathlib,collections,os
if not __debug__:raise RuntimeError("Optimized Python refused")
sys.path.insert(0,os.environ['DIAGNOSTIC_DEPENDENCY_ROOT'])
import numpy as np
from collections import Counter
sys.path.insert(0,os.environ['DIAGNOSTIC_REPOSITORY_ROOT'])
from tools.qa.geometry_quality.__main__ import load
from tools.qa.geometry_quality.metrics import topology,_nonmanifold_vertices
def invalid_vertex_ids(faces, edges):
    """Exact indexed links, O(faces) storage/work; no coordinate welding.

    Each incident triangle contributes its opposite edge to the vertex link.
    A manifold link is one simple cycle (interior) or one simple path whose
    endpoints are exactly the two boundary neighbors. Repeated-index faces
    invalidate all their vertices; duplicate link edges are never collapsed.
    Geometric zero area with distinct indices is audited separately.
    """
    links={};invalid=set()
    for a,b,c in faces:
        if len({a,b,c})!=3:
            invalid.update((a,b,c))
            continue
        for center,i,j in ((a,b,c),(b,c,a),(c,a,b)):
            link=links.setdefault(center,Counter())
            link[tuple(sorted((i,j)))]+=1
    for center,link in links.items():
        if center in invalid:continue
        if any(n!=1 for n in link.values()):
            invalid.add(center);continue
        neighbors={}
        for i,j in link:
            neighbors.setdefault(i,set()).add(j)
            neighbors.setdefault(j,set()).add(i)
        boundary={i for i in neighbors if edges[tuple(sorted((center,i)))]==1}
        endpoints={i for i,adjacent in neighbors.items() if len(adjacent)==1}
        if (any(len(adjacent) not in (1,2) for adjacent in neighbors.values()) or
                (boundary and (len(boundary)!=2 or endpoints!=boundary)) or
                (not boundary and endpoints)):
            invalid.add(center);continue
        unseen=set(neighbors);stack=[unseen.pop()]
        while stack:
            for i in neighbors[stack.pop()]:
                if i in unseen:unseen.remove(i);stack.append(i)
        if unseen:invalid.add(center)
    return invalid

def ids(f):
 edges=Counter(tuple(sorted((a,b))) for row in f for a,b in zip(row,np.roll(row,-1)))
 found=invalid_vertex_ids(f,edges)
 assert len(found)==_nonmanifold_vertices(f,edges)
 return set(map(int,found))
def selftest():
 # One closed cyclic fan becomes two disconnected retained arcs; no new faces.
 pre=np.array([[0,1,2],[0,2,3],[0,3,4],[0,4,1]])
 post=pre[[0,2]]
 assert 0 not in ids(pre) and 0 in ids(post)
 assert set(map(tuple,post)).issubset(set(map(tuple,pre)))
 print('indexed fan split guard PASS')
def main():
 if sys.argv[1:]==['--selftest']:selftest();return
 assert len(sys.argv)==3
 directory=pathlib.Path(sys.argv[1]);output=pathlib.Path(sys.argv[2]);assert not output.exists()
 v,p=load(directory/'pre-witness.ply');w,q=load(directory/'post-witness.ply')
 assert np.array_equal(v,w) and 0<len(v)<=40000 and 0<len(q)<=len(p)<=80000
 assert set(map(tuple,q)).issubset(set(map(tuple,p)))
 before,after=ids(p),ids(q);activepre=set(map(int,p.ravel()));activepost=set(map(int,q.ravel()))
 pretop,posttop=topology(v,p),topology(w,q)
 assert posttop['nonmanifold_vertices']==107 and posttop['nonmanifold_edges']==0 and posttop['components']==521
 beforecounts=Counter(map(int,p.ravel()));aftercounts=Counter(map(int,q.ravel()))
 changes=Counter((int(i in before),int(i in after),int(i in activepost),int(aftercounts[i]<beforecounts[i])) for i in activepre)
 report={'status':'INDEXED_STAGE_AUDIT_COMPLETE_NOT_QUALITY_ACCEPTANCE','preWitness':pretop,'postWitness':posttop,'stableIdTransitions':[{'nonmanifoldBefore':a,'nonmanifoldAfter':b,'presentAfter':c,'lostIncidentFaces':d,'vertices':n} for (a,b,c,d),n in sorted(changes.items())],'newlyNonmanifoldRetainedVertices':len((after-before)&activepost),'resolvedNonmanifoldRetainedVertices':len((before-after)&activepost),'removedVertices':len(activepre-activepost),'limitations':['Same indexed edges, no coordinate welding; pre and post differ only by original face filtering.','Link-status transitions are association with filtering, not recovered coverage or repair proof.','Crossing default5000-face budget status retained; separate native6190 not compared.']}
 output.write_text(json.dumps(report,indent=2,allow_nan=False)+'\n')
if __name__=='__main__':main()
