"""Copies actual original evaluated cell/triangle decisions; no new geometry tests."""
from patch import replace

def patch_cells(root,package):
 p=root/'reconstruction/src/surface.cpp';s=p.read_text()
 pairs=[
 ('#include "ledger_observer.hpp"','#include "ledger_observer.hpp"\n#include "cell_observer.hpp"'),
 ('    std::unordered_set<FaceKey,FaceHash> faces;','    cell_observer::begin();\n    std::unordered_set<FaceKey,FaceHash> faces;'),
 ('        if(ids[0]<0||ids[1]<0||ids[2]<0||ids[0]==ids[1]||ids[1]==ids[2]||ids[0]==ids[2])return false;','        cell_observer::Triangle trace(cam,ids,depths);\n        if(trace.test(0,ids[0]<0||ids[1]<0||ids[2]<0||ids[0]==ids[1]||ids[1]==ids[2]||ids[0]==ids[2]))return trace.reject(1);'),
 ('        if(minZ<=0||(maxZ-minZ)/maxZ>s.maximumRelativeDepthJump)return false;','        trace.depths(maxZ,minZ);\n        if(trace.test(1,minZ<=0||(maxZ-minZ)/maxZ>s.maximumRelativeDepthJump))return trace.reject(2);'),
 ('        if(norm(sub(A,B))>limit||norm(sub(A,C))>limit||norm(sub(B,C))>limit)return false;','        trace.scale(footprint,limit);\n        if(trace.test(2,norm(sub(A,B))>limit)||trace.test(3,norm(sub(A,C))>limit)||trace.test(4,norm(sub(B,C))>limit))return trace.reject(3);'),
 ('        if(norm(normal)<footprint*footprint*.015)return false;','        trace.normal(normal);\n        if(trace.test(5,norm(normal)<footprint*footprint*.015))return trace.reject(4);'),
 ('        if(!faces.insert({sorted[0],sorted[1],sorted[2]}).second)return false;','        if(trace.test(6,!faces.insert({sorted[0],sorted[1],sorted[2]}).second))return trace.reject(5);'),
 ('        if(dot(normal,sub(cameraCenter(view),centroid))<0)std::swap(tri.b,tri.c);','        trace.centroid(centroid);\n        if(trace.winding(dot(normal,sub(cameraCenter(view),centroid))<0))std::swap(tri.b,tri.c);'),
 ('        mesh.triangles.push_back(tri);\n        return true;','        mesh.triangles.push_back(tri);\n        return trace.accepted(mesh.triangles.size()-1);'),
 ('                if(std::any_of(ids.begin(),ids.end(),[](auto id){return id<0;})){','                cell_observer::start(cam,x,y,pix,ids);\n                if(std::any_of(ids.begin(),ids.end(),[](auto id){return id<0;})){'),
 ('                    ++mesh.rejectedCells;continue;','                    ++mesh.rejectedCells;cell_observer::finish();continue;'),
 ('                const auto face=[&](int a,int b,int c){','                cell_observer::diagonal(d,d03,d12,diag03);\n                const auto face=[&](int a,int b,int c){'),
 ('                    if(!validTriangle(cam,{ids[a],ids[b],ids[c]},{d[a],d[b],d[c]}))++mesh.rejectedTriangles;','                    cell_observer::proposal(a,b,c);\n                    if(!validTriangle(cam,{ids[a],ids[b],ids[c]},{d[a],d[b],d[c]}))++mesh.rejectedTriangles;'),
 ('                if(diag03){face(0,1,3);face(0,3,2);}else{face(0,1,2);face(1,3,2);}','                if(diag03){face(0,1,3);face(0,3,2);}else{face(0,1,2);face(1,3,2);}\n                cell_observer::finish();'),
 ('    progress::advance(2*prepared.size());','    cell_observer::end();\n    progress::advance(2*prepared.size());')]
 for a,b in pairs:s=replace(s,a,b)
 p.write_text(s);(p.parent/'cell_observer.hpp').write_bytes((package/'cell_observer.hpp').read_bytes())
