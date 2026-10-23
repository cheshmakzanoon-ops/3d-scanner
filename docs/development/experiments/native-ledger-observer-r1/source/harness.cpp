#include "realityforge/surface_fusion.hpp"
#include "observer.hpp"
#include <bit>
#include <cstdint>
#include <iostream>
#include <filesystem>
#include <algorithm>
#include <sys/resource.h>
#include "plane-generator.inc"
namespace fs=std::filesystem;
void need(bool b,const char* m){if(!b)throw std::runtime_error(m);}
struct Bits {
 std::ofstream f;std::size_t size=0;explicit Bits(const fs::path& p):f(p,std::ios::binary){need(bool(f),"output open");}
 void integer(std::uint64_t n,int bytes=8){need(size+bytes<=8388608,"binary budget");for(int i=0;i<bytes;++i)f.put(static_cast<char>(n>>(8*i)));size+=bytes;need(bool(f),"binary write");}
 void value(double x){integer(std::bit_cast<std::uint64_t>(x));}void value(float x){integer(std::bit_cast<std::uint32_t>(x),4);}
 void vec(rf::Vec3 p){value(p.x);value(p.y);value(p.z);}
};
void inputs(const fs::path& dir,const std::vector<rf::DenseView>& views,const rf::DenseResult& dense){
 need(views.size()==2&&dense.maps.size()==2&&dense.verifiedSamples.size()<=162,"fixture budget");Bits b(dir/"inputs.bits");b.integer(views.size());
 for(std::size_t i=0;i<views.size();++i){const auto& v=views[i];const auto& m=dense.maps[i];need(v.width==32&&v.height==32&&m.width==32&&m.height==32&&m.depth.size()==1024&&m.confidence.size()==1024,"map dimensions");b.integer(v.width);b.integer(v.height);for(auto r:v.pose.rotation)b.value(r);b.vec(v.pose.translation);b.value(v.fx);b.value(v.fy);b.value(v.cx);b.value(v.cy);b.integer(v.rgba.size());for(auto c:v.rgba)b.integer(c,1);b.integer(m.width);b.integer(m.height);for(auto z:m.depth)b.value(z);for(auto c:m.confidence)b.value(c);}
 b.integer(dense.verifiedSamples.size());for(const auto& s:dense.verifiedSamples){b.vec(s.xyz);for(auto c:s.color)b.integer(c,1);b.value(s.confidence);b.integer(s.supportingViews,1);b.integer(s.camera,1);b.integer(s.x,2);b.integer(s.y,2);b.vec(s.worldNormal);b.value(s.normalConfidence);b.integer(s.normalSupportingViews,1);}
}
void output(const fs::path& dir,const rf::SurfaceMesh& mesh){
 need(mesh.vertices.size()<=162&&mesh.triangles.size()<=324&&mesh.observations.size()+mesh.unattachedObservations.size()<=162,"mesh cardinality cap");Bits b(dir/"mesh.bits");b.integer(mesh.vertices.size());
 for(const auto& v:mesh.vertices){b.vec(v.xyz);b.vec(v.normal);for(auto c:v.color)b.integer(c,1);b.value(v.confidence);b.integer(v.supportingViews,1);}
 b.integer(mesh.triangles.size());for(const auto& t:mesh.triangles){b.integer(t.a,4);b.integer(t.b,4);b.integer(t.c,4);b.integer(t.sourceCamera,1);}
 for(auto n:{mesh.inputSamples,mesh.acceptedSamples,mesh.rejectedCells,mesh.rejectedTriangles,mesh.fusedObservations})b.integer(n);b.value(mesh.medianConfidence);
 b.integer(mesh.observations.size());for(const auto& o:mesh.observations){b.integer(o.vertex,4);b.integer(o.camera,1);b.integer(o.x,2);b.integer(o.y,2);b.integer(o.sourceSampleId);b.integer(static_cast<int>(o.relation),1);}
 b.integer(mesh.unattachedObservations.size());for(const auto& o:mesh.unattachedObservations){b.integer(o.camera,1);b.integer(o.x,2);b.integer(o.y,2);b.integer(o.sourceSampleId);b.integer(static_cast<int>(o.reason),1);}
 const auto& t=mesh.topology;for(auto n:{t.boundaryEdges,t.components,t.nonmanifoldEdges,t.intersections,t.ambiguousPairs,t.removedExactDefects})b.integer(n);b.integer(t.intersectionBudgetExceeded,1);
 std::ofstream json(dir/"mesh.json");json<<rf::surfaceJson(mesh)<<'\n';need(bool(json),"JSON write");rf::writeSurfacePly(mesh,(dir/"mesh.ply").string());rf::writeSurfaceGlb(mesh,(dir/"mesh.glb").string());
}
struct Prepared {rf::Vec3 normal{};double frontRadius{},bound{};bool supported{};};
void audit(const fs::path& dir,const std::vector<rf::DenseView>& views,const rf::DenseResult& dense,const rf::SurfaceSettings& s){
 std::ofstream expected(dir/"expected-prepared.tsv");std::size_t supported=0,supportIds=0;double maxPixel=0,maxDepth=0;
 for(std::size_t id=0;id<dense.verifiedSamples.size();++id){const auto& p=dense.verifiedSamples[id];const auto& v=views[p.camera];const auto& map=dense.maps[p.camera];auto q=rf::multiply(v.pose.rotation,p.xyz);q.x+=v.pose.translation.x;q.y+=v.pose.translation.y;q.z+=v.pose.translation.z;
 const double z=map.depth[std::size_t(p.y)*map.width+p.x];const auto pixel=std::max(std::abs(v.fx*q.x/q.z+v.cx-p.x),std::abs(v.fy*q.y/q.z+v.cy-p.y));const auto dz=std::abs(q.z-z)/z;need(q.z>.01&&z>0&&pixel<=.25&&dz<=.001,"source projection/depth binding");maxPixel=std::max(maxPixel,pixel);maxDepth=std::max(maxDepth,dz);
 const auto e=rf::estimateDepthNeighborhoodNormal(v,map,p.x,p.y,s.pixelStride,s.maximumRelativeDepthJump);Prepared a;a.frontRadius=rf::surface_fusion::observedFusionRadius(q.z,std::min(v.fx,v.fy),s.pixelStride);a.bound=a.frontRadius;a.supported=e.validity==rf::NormalValidity::Supported&&rf::surface_fusion::unitNormal(e.direction,a.normal);
 if(a.supported){const auto bound=rf::surface_fusion::planePixelEnclosingRadius(v,p,a.normal,a.frontRadius,s.pixelStride);a.supported=bound.has_value();if(bound)a.bound=*bound;}
 supportIds+=e.supportingObservationIds.size();need(supportIds<=1458,"support IDs budget");supported+=a.supported;expected<<ledger_observer::preparedRow(id,p,e,a);
 }
 need(bool(expected),"audit write");std::ofstream o(dir/"normal-audit.json");o<<std::setprecision(17)<<"{\"samples\":"<<dense.verifiedSamples.size()<<",\"preparedSupported\":"<<supported<<",\"preparedUnknown\":"<<dense.verifiedSamples.size()-supported<<",\"supportIds\":"<<supportIds<<",\"maxPixelError\":"<<maxPixel<<",\"maxRelativeMapDepthError\":"<<maxDepth<<",\"witnessRegeneration\":\"NOT_VERIFIED\"}\n";
}
int main(int argc,char** argv){try{
 need(argc==4,"usage: harness prepare|run plane|central-hole fresh-output");struct rlimit limit{};need(getrlimit(RLIMIT_AS,&limit)==0&&limit.rlim_cur<=1073741824,"1GiB native AS required");need(getrlimit(RLIMIT_CPU,&limit)==0&&limit.rlim_cur<=60,"60s native CPU required");
 const std::string mode=argv[1],which=argv[2];need(mode=="prepare"||mode=="run","mode");need(which=="plane"||which=="central-hole","case");fs::path dir=argv[3];need(!fs::exists(dir),"existing output refused");fs::create_directories(dir);
 auto [views,dense]=plane(false);if(which=="central-hole"){
 for(auto& map:dense.maps)for(int y=12;y<=18;++y)for(int x=12;x<=18;++x){map.depth[y*32+x]=0;map.confidence[y*32+x]=0;}
 std::erase_if(dense.verifiedSamples,[](const auto& p){return p.x>=12&&p.x<=18&&p.y>=12&&p.y<=18;});}
 inputs(dir,views,dense);if(mode=="prepare")return 0;
 rf::SurfaceSettings settings;audit(dir,views,dense,settings);const auto mesh=rf::fuseDepthSurfaces(views,dense,settings);output(dir,mesh);
 fs::create_directory(dir/"after");inputs(dir/"after",views,dense);std::size_t bytes=0;for(const auto& entry:fs::recursive_directory_iterator(dir))if(entry.is_regular_file())bytes+=entry.file_size();need(bytes<=16777216,"case output budget");std::cout<<"samples "<<dense.verifiedSamples.size()<<" vertices "<<mesh.vertices.size()<<" faces "<<mesh.triangles.size()<<" accepted "<<mesh.acceptedSamples<<" attached "<<mesh.observations.size()<<" unattached "<<mesh.unattachedObservations.size()<<'\n';
}catch(const std::exception& e){std::cerr<<"REFUSED: "<<e.what()<<'\n';return 2;}}
