"""Isolated QA chart-arrangement feasibility kernel; NOT production code."""
import math
import numpy as np
from shapely import Polygon,LineString,Point,STRtree,unary_union,polygonize,constrained_delaunay_triangles
class Refused(RuntimeError):pass
class Cancelled(Refused):pass
LIMITS={'inputTriangles':4096,'candidatePairs':100000,'cells':32768,'vertices':40000,'faces':80000,'links':2000000}
def overlay(data,cancel=lambda:False,limits=None):
    caps=LIMITS|({} if limits is None else limits)
    def check():
        if cancel():raise Cancelled('cancelled before publication')
    def bounded(name,count):
        if count>caps[name]:raise Refused('budget:'+name)
    check();src=data['triangles'];bounded('inputTriangles',len(src))
    observations=data['observations'];ids=[o['id'] for o in observations]
    if len(ids)!=len(set(ids)):raise Refused('repeated source identity')
    byid={o['id']:o for o in observations}
    # Fixed calibration ordering, independent of the caller's view enumeration.
    anchor=min(data['cameras'],key=lambda c:tuple(c['rotation']+c['translation']))
    R=np.array(anchor['rotation']).reshape(3,3);t=np.array(anchor['translation'])
    inverse=np.linalg.inv(R);camera_tri=[];polys=[];orientation=None
    for k,tri in enumerate(src):
        if k%64==0:check()
        p=np.array([byid[i]['xyz'] for i in tri['sources']],float)
        if not np.isfinite(p).all():raise Refused('nonfinite source')
        q=p@R.T+t
        if np.any(q[:,2]<=0):raise Refused('chart horizon')
        uv=q[:,:2]/q[:,2,None]
        signed=(uv[1,0]-uv[0,0])*(uv[2,1]-uv[0,1])-(uv[1,1]-uv[0,1])*(uv[2,0]-uv[0,0])
        if signed==0:raise Refused('degenerate chart triangle')
        current=1 if signed>0 else -1
        if orientation is None:orientation=current
        elif orientation!=current:raise Refused('opposite sheet orientation or chart fold')
        poly=Polygon(uv)
        if not poly.is_valid or poly.area==0:raise Refused('degenerate chart triangle')
        camera_tri.append(q);polys.append(poly)
    tree=STRtree(polys);pairs=0
    for i,p in enumerate(polys):
        check()
        for j in tree.query(p):
            j=int(j)
            if j<=i:continue
            pairs+=1;bounded('candidatePairs',pairs)
            if src[i]['view']==src[j]['view']:
                check();overlap=p.intersection(polys[j]);check()
                # Any positive overlap in one original view is a chart fold.
                if overlap.area>0:raise Refused('same-view chart overlap')
    check();network=unary_union([LineString(p.exterior.coords) for p in polys]);check()
    cells=list(polygonize(list(network.geoms)).geoms);bounded('cells',len(cells));check()
    vertices=[];indices={};incident=[];faces=[];face_sources=[];links=0;covered_cells=[]
    for cell in cells:
        check();point=cell.representative_point()
        contributors=[int(i) for i in tree.query(point) if polys[int(i)].covers(point)]
        if not contributors:continue # unknown support stays a hole
        covered_cells.append(cell)
        check();triangulated=constrained_delaunay_triangles(cell);check()
        for tri in triangulated.geoms:
            if not cell.covers(tri):raise Refused('triangulation left support cell')
            face=[]
            for xy in list(tri.exterior.coords)[:3]:
                key=tuple(xy)
                if key not in indices:
                    indices[key]=len(vertices);vertices.append(key);incident.append(set());bounded('vertices',len(vertices))
                index=indices[key];face.append(index);incident[index].update(contributors)
            faces.append(face);face_sources.append(contributors);links+=len(contributors);bounded('faces',len(faces));bounded('links',links)
    # A common chart vertex must have ONE lift; no independently lifted seams.
    xyz=[];maximum_spread=0
    for index,(u,v) in enumerate(vertices):
        if index%64==0:check()
        ray=np.array([u,v,1.]);depths=[];errors=[]
        for source in sorted(incident[index]):
            q=camera_tri[source];a=q[1]-q[0];b=q[2]-q[0];n=np.cross(a,b)
            denominator=float(n@ray);numerator=float(n@q[0])
            if denominator==0:raise Refused('ill-conditioned chart lift')
            depth=numerator/denominator
            # Roundoff bound only. Not a physical depth/noise merge tolerance.
            condition=(abs(n)*abs(ray)).sum()/abs(denominator)
            errors.append(128*np.finfo(float).eps*max(1.,abs(depth))*max(1.,condition))
            depths.append(depth)
        spread=max(depths)-min(depths);maximum_spread=max(maximum_spread,spread)
        if spread>max(errors):raise Refused('ambiguous distinct chart-ray depths; no calibrated sheet/noise decision')
        depth=math.fsum(depths)/len(depths);xyz.append((inverse@(ray*depth-t)).tolist())
    # Every immutable source record survives, even when it contributes no face.
    contributing_triangles=set(i for group in face_sources for i in group)
    represented_sources=set(s for i in contributing_triangles for s in src[i]['sources'])
    ledger=[dict(o,supportRelation='observed_triangle_support' if o['id'] in represented_sources else 'unmeshed_observation') for o in observations]
    check()
    return {'vertices':xyz,'triangles':faces,'faceSourceTriangles':face_sources,'sourceTriangles':src,'observations':ledger,
            'metrics':{'inputTriangles':len(src),'candidatePairs':pairs,'arrangementCells':len(cells),'outputVertices':len(xyz),'outputTriangles':len(faces),'contributorLinks':links,'inputObservations':len(observations),'retainedObservations':len(ledger),'geometricSupportSources':len(represented_sources),'maximumArithmeticLiftSpread':maximum_spread},
            '_chartPolygons':polys,'_chartCells':covered_cells,'_chartVertices':vertices}
