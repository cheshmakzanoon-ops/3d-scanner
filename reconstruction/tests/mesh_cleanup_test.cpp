#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/mesh_cleanup.hpp"
#include <cassert>
#include <limits>
int main(){
 rf::SurfaceMesh m;
 for(auto p:{rf::Vec3{0,0,0},rf::Vec3{1,0,0},rf::Vec3{1,1,0},rf::Vec3{0,1,0},rf::Vec3{3,0,0},rf::Vec3{3.001,0,0},rf::Vec3{3,.001,0}}){rf::SurfaceVertex v;v.xyz=p;v.normal={0,0,1};m.vertices.push_back(v);}
 m.triangles={{0,1,2,0},{0,2,3,0},{4,5,6,0},{2,1,0,1},{0,0,1,0}};
 auto p=rf::planMeshCleanup(m);assert((p.removedFaces==std::vector<std::size_t>{3,4}));
 auto r=rf::applyMeshCleanup(m,p);assert(r.mesh.triangles.size()==3&&m.triangles.size()==5);
 assert(r.mesh.vertices.size()==m.vertices.size()&&r.maxDisplacement==0&&r.generatedFaces==0);
 assert(r.plan.sourceFaceForResult[2]==2); // small supported component survives
 p.removedFaces.push_back(1);bool rejected=false;try{rf::applyMeshCleanup(m,p);}catch(const rf::Error&){rejected=true;}assert(rejected);
 auto bad=m;bad.vertices[0].xyz.x=std::numeric_limits<double>::infinity();rejected=false;
 try{rf::planMeshCleanup(bad);}catch(const rf::Error&){rejected=true;}assert(rejected);
 // A genuine hole remains open; no face or vertex is ever synthesized.
 rf::SurfaceMesh thin=m;thin.triangles={{0,1,2,0}};assert(rf::applyMeshCleanup(thin,rf::planMeshCleanup(thin)).mesh.triangles.size()==1);
}
