#include "realityforge/mesh_topology.hpp"
#include <iostream>
#include <stdexcept>
namespace {void check(bool c){if(!c)throw std::runtime_error("mesh topology regression");}rf::SurfaceVertex v(double x,double y,double z){rf::SurfaceVertex o;o.xyz={x,y,z};return o;}}
int main(){
    rf::SurfaceMesh m;m.vertices={v(0,0,0),v(1,0,0),v(1,1,0),v(0,1,0)};m.triangles={{0,1,2,0},{0,2,3,0}};
    auto r=rf::analyzeMeshTopology(m);check(r.components==1&&r.boundaryEdges==4&&r.boundaryLoops.size()==1&&r.nonmanifoldEdges==0&&r.intersections.empty());
    m.triangles.push_back({2,1,0,1});m.triangles.push_back({0,0,1,0});r=rf::analyzeMeshTopology(m);check(r.duplicateFaces==std::vector<std::size_t>{2}&&r.degenerateFaces==std::vector<std::size_t>{3}&&r.nonmanifoldEdges==1);
    m.triangles={{0,1,2,0},{0,3,2,0}};r=rf::analyzeMeshTopology(m);check(r.windingConflictFaces.size()==2);
    rf::SurfaceMesh cross;cross.vertices={v(-1,-1,0),v(1,-1,0),v(0,1,0),v(0,-.5,-1),v(0,-.5,1),v(0,.5,.5)};cross.triangles={{0,1,2,0},{3,4,5,1}};
    r=rf::analyzeMeshTopology(cross);check(r.intersections.size()==1&&r.components==2);
    for(double scale:{1e-9,1e9}){auto scaled=cross;for(auto& p:scaled.vertices){p.xyz.x*=scale;p.xyz.y*=scale;p.xyz.z*=scale;}check(rf::analyzeMeshTopology(scaled).intersections.size()==1);}
    rf::SurfaceMesh overlap;overlap.vertices={v(0,0,0),v(2,0,0),v(0,2,0),v(.2,.2,0),v(.8,.2,0),v(.2,.8,0)};overlap.triangles={{0,1,2,0},{3,4,5,0}};check(rf::analyzeMeshTopology(overlap).intersections.size()==1);
    rf::MeshTopologySettings budget;budget.maxIntersectionCandidates=0;check(rf::analyzeMeshTopology(overlap,budget).intersectionBudgetExceeded);
    rf::SurfaceMesh adjacent;adjacent.vertices={v(-1,-1,0),v(1,-1,0),v(0,1,0),v(0,0,-1),v(0,0,1)};adjacent.triangles={{0,1,2,0},{0,3,4,1}};check(rf::analyzeMeshTopology(adjacent).intersections.size()==1);
    rf::SurfaceMesh broad;for(int i=0;i<50;++i){auto base=static_cast<std::uint32_t>(broad.vertices.size());broad.vertices.push_back(v(0,i*3,0));broad.vertices.push_back(v(1,i*3,0));broad.vertices.push_back(v(0,i*3+1,0));broad.triangles.push_back({base,base+1,base+2,0});}budget.maxIntersectionCandidates=20;check(rf::analyzeMeshTopology(broad,budget).intersectionBudgetExceeded);
    cross.triangles[1].a=50;bool invalid=false;try{(void)rf::analyzeMeshTopology(cross);}catch(const rf::Error&){invalid=true;}check(invalid);
    std::cout<<"open surface, exact defects, intersections, scale and budget cases passed\n";
}
