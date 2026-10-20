#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/mesh_cleanup.hpp"
#include <cassert>
#include <limits>
#include <map>
int main(){
 rf::SurfaceMesh m;
 for(auto p:{rf::Vec3{0,0,0},rf::Vec3{1,0,0},rf::Vec3{1,1,0},rf::Vec3{0,1,0},rf::Vec3{3,0,0},rf::Vec3{3.001,0,0},rf::Vec3{3,.001,0}}){rf::SurfaceVertex v;v.xyz=p;v.normal={0,0,1};m.vertices.push_back(v);}
 m.triangles={{0,1,2,0},{0,2,3,0},{4,5,6,0},{2,0,1,1},{0,0,1,0}};
 auto p=rf::planMeshCleanup(m);assert((p.removedFaces==std::vector<std::size_t>{3,4}));
 auto r=rf::applyMeshCleanup(m,p);assert(r.mesh.triangles.size()==3&&m.triangles.size()==5);
 assert(r.mesh.vertices.size()==m.vertices.size()&&r.maxDisplacement==0&&r.generatedFaces==0);
 assert(r.plan.sourceFaceForResult[2]==2); // small supported component survives
 p.removedFaces.push_back(1);bool rejected=false;try{rf::applyMeshCleanup(m,p);}catch(const rf::Error&){rejected=true;}assert(rejected);
 auto bad=m;bad.vertices[0].xyz.x=std::numeric_limits<double>::infinity();rejected=false;
 try{rf::planMeshCleanup(bad);}catch(const rf::Error&){rejected=true;}assert(rejected);
 // A genuine hole remains open; no face or vertex is ever synthesized.
 rf::SurfaceMesh thin=m;thin.triangles={{0,1,2,0}};assert(rf::applyMeshCleanup(thin,rf::planMeshCleanup(thin)).mesh.triangles.size()==1);
 std::atomic_bool stop{true};rejected=false;try{rf::cancellation::Scope scope(&stop);rf::planMeshCleanup(m);}catch(const rf::cancellation::Cancelled&){rejected=true;}assert(rejected);
 auto large=m;large.triangles.assign(50000,{0,1,2,0});auto lp=rf::planMeshCleanup(large);assert(lp.removedFaces.size()==49999&&lp.sourceFaceForResult.size()==1);
 auto copy=rf::applyMeshCleanup(large,lp);assert(copy.mesh.triangles.size()==1&&large.triangles.size()==50000);

 auto twoSided=m;twoSided.triangles={{0,1,2,0},{2,1,0,1}};assert(rf::planMeshCleanup(twoSided).removedFaces.empty());
 auto duplicateCoordinates=m;duplicateCoordinates.vertices.push_back(m.vertices[0]);duplicateCoordinates.vertices.push_back(m.vertices[1]);duplicateCoordinates.vertices.push_back(m.vertices[2]);duplicateCoordinates.triangles={{0,1,2,0},{7,8,9,1}};
 assert((rf::planMeshCleanup(duplicateCoordinates).removedFaces==std::vector<std::size_t>{1}));

 // Arbitrary scale and rounded product cancellation must never erase real area.
 for(const auto& xyz:std::vector<std::array<rf::Vec3,3>>{
     {{{0,0,0},{1e-200,0,0},{0,1e-200,0}}},
     {{{0,0,0},{1,1+std::ldexp(1.,-52),0},{1-std::ldexp(1.,-52),1,0}}}}){
     rf::SurfaceMesh tiny;for(auto position:xyz){rf::SurfaceVertex vertex;vertex.xyz=position;tiny.vertices.push_back(vertex);}
     tiny.triangles={{0,1,2,0}};assert(rf::planMeshCleanup(tiny).removedFaces.empty());
 }
 rf::SurfaceMesh collinear;for(auto x:{0.,1.,2.}){rf::SurfaceVertex vertex;vertex.xyz={x,x,0};collinear.vertices.push_back(vertex);}
 collinear.triangles={{0,1,2,0}};assert(rf::planMeshCleanup(collinear).removedFaces.size()==1);

 // A noisy open annulus has a real handle and two boundary loops. Only the
 // injected exact duplicate/degenerate may disappear; all shape samples survive.
 rf::SurfaceMesh ring;
 for(auto position:{rf::Vec3{-2,-2,0},rf::Vec3{2,-2,.01},rf::Vec3{2,2,-.01},rf::Vec3{-2,2,.02},
                    rf::Vec3{-1,-1,.01},rf::Vec3{1,-1,0},rf::Vec3{1,1,.02},rf::Vec3{-1,1,-.01}}){
     rf::SurfaceVertex vertex;vertex.xyz=position;vertex.normal={0,0,1};vertex.confidence=.8;ring.vertices.push_back(vertex);
 }
 for(std::uint32_t i=0;i<4;++i){const auto j=(i+1)%4;ring.triangles.push_back({i,j,4+j,0});ring.triangles.push_back({i,4+j,4+i,0});}
 ring.triangles.push_back(ring.triangles.front());ring.triangles.push_back({0,0,1,0});
 const auto cleanedRing=rf::applyMeshCleanup(ring,rf::planMeshCleanup(ring));
 assert(cleanedRing.mesh.triangles.size()==8&&ring.triangles.size()==10&&cleanedRing.generatedFaces==0);
 std::map<std::pair<std::uint32_t,std::uint32_t>,std::size_t> edges;
 for(const auto& face:cleanedRing.mesh.triangles)for(auto edge:std::array<std::pair<std::uint32_t,std::uint32_t>,3>{{{face.a,face.b},{face.b,face.c},{face.c,face.a}}}){
     if(edge.first>edge.second)std::swap(edge.first,edge.second);
     ++edges[edge];
 }
 std::size_t boundary=0;std::array<std::size_t,8> degrees{};
 for(const auto& [edge,count]:edges)if(count==1){++boundary;++degrees[edge.first];++degrees[edge.second];assert((edge.first<4)==(edge.second<4));}
 assert(boundary==8&&edges.size()==16);for(auto degree:degrees)assert(degree==2);
 for(std::size_t i=0;i<ring.vertices.size();++i){const auto& before=ring.vertices[i];const auto& after=cleanedRing.mesh.vertices[i];
     assert(before.xyz.x==after.xyz.x&&before.xyz.y==after.xyz.y&&before.xyz.z==after.xyz.z);
     assert(before.normal.x==after.normal.x&&before.normal.y==after.normal.y&&before.normal.z==after.normal.z&&before.confidence==after.confidence);
 }

}
