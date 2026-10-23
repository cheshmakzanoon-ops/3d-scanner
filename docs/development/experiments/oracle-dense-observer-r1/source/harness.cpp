#include "realityforge/surface_fusion.hpp"
#include "observer.hpp"
#include <bit>
#include <cstdint>
#include <iostream>
#include <filesystem>
#include <algorithm>
#include <sys/resource.h>

namespace fs=std::filesystem;
void need(bool b,const char* m){if(!b)throw std::runtime_error(m);}
struct Bits {
 std::ofstream f;std::size_t size=0;explicit Bits(const fs::path& p):f(p,std::ios::binary){need(bool(f),"output open");}
 void integer(std::uint64_t n,int bytes=8){need(size+bytes<=16777216,"binary budget");for(int i=0;i<bytes;++i)f.put(static_cast<char>(n>>(8*i)));size+=bytes;need(bool(f),"binary write");}
 void value(double x){integer(std::bit_cast<std::uint64_t>(x));}void value(float x){integer(std::bit_cast<std::uint32_t>(x),4);}
 void vec(rf::Vec3 p){value(p.x);value(p.y);value(p.z);}
};
std::vector<rf::DenseView> load(const fs::path& path){
 std::ifstream f(path,std::ios::binary);need(bool(f),"input open");
 auto integer=[&](int n=8){std::uint64_t value=0;for(int i=0;i<n;++i){int c=f.get();need(c!=EOF,"input truncated");value|=std::uint64_t(static_cast<unsigned char>(c))<<(8*i);}return value;};
 auto number=[&](){return std::bit_cast<double>(integer());};need(integer()==8,"exact eight views required");std::vector<rf::DenseView> views(8);
 for(auto& v:views){v.width=static_cast<int>(integer());v.height=static_cast<int>(integer());need(v.width==160&&v.height==120,"input dimensions");for(auto& r:v.pose.rotation)r=number();v.pose.translation={number(),number(),number()};v.fx=number();v.fy=number();v.cx=number();v.cy=number();need(integer()==160*120*4,"RGBA count");v.rgba.resize(160*120*4);f.read(reinterpret_cast<char*>(v.rgba.data()),v.rgba.size());need(bool(f),"RGBA truncated");}
 need(f.peek()==EOF,"trailing input");return views;
}
void inputs(const fs::path& dir,const std::vector<rf::DenseView>& views){
 need(views.size()==8,"view count");Bits b(dir/"inputs.bits");b.integer(views.size());
 for(const auto& v:views){b.integer(v.width);b.integer(v.height);for(auto r:v.pose.rotation)b.value(r);b.vec(v.pose.translation);b.value(v.fx);b.value(v.fy);b.value(v.cx);b.value(v.cy);b.integer(v.rgba.size());for(auto c:v.rgba)b.integer(c,1);}
}
void denseFields(const fs::path& path,const rf::DenseResult& d){
 need(d.maps.size()==8&&d.verifiedSamples.size()<=35112&&d.points.size()<=20000,"dense field cap");Bits b(path);b.integer(d.maps.size());
 for(const auto& m:d.maps){need(m.width==160&&m.height==120&&m.depth.size()==19200&&m.confidence.size()==19200,"depth map bounds");b.integer(m.width);b.integer(m.height);b.integer(m.depth.size());for(auto z:m.depth)b.value(z);b.integer(m.confidence.size());for(auto c:m.confidence)b.value(c);}
 b.integer(d.points.size());for(const auto& p:d.points){b.vec(p.xyz);for(auto c:p.color)b.integer(c,1);b.value(p.confidence);b.integer(p.consistentViews,1);}
 b.integer(d.verifiedSamples.size());for(const auto& s:d.verifiedSamples){b.vec(s.xyz);for(auto c:s.color)b.integer(c,1);b.value(s.confidence);b.integer(s.supportingViews,1);b.integer(s.camera,1);b.integer(s.x,2);b.integer(s.y,2);b.vec(s.worldNormal);b.value(s.normalConfidence);b.integer(s.normalSupportingViews,1);}
 for(auto n:{d.sampledPixels,d.photometricPixels,d.consistentPixels})b.integer(n);b.value(d.medianConfidence);
 b.integer(d.sourceViews.size());for(const auto& ids:d.sourceViews){b.integer(ids.size());for(auto id:ids)b.integer(id);}
 b.integer(d.consistentPixelsPerView.size());for(auto n:d.consistentPixelsPerView)b.integer(n);
 for(auto n:{d.peakLuminanceBytes,d.pointBudget,d.resumedDepthMaps,d.writtenDepthMaps,d.completedTiles,d.totalTiles,d.admittedWorkingSetBytes,d.resumedDepthTiles,d.recomputedDepthTiles,d.writtenDepthTiles,d.rejectedDepthTiles,d.coarseDepthMaps,d.propagatedHypotheses,d.sparsePriorTiles,d.normalHypothesesEvaluated,d.normalRefinedPixels,d.normalSupportPairs,d.normalRejectedPairs,d.normalUnknownPairs})b.integer(n);
}
void output(const fs::path& dir,const rf::SurfaceMesh& mesh){
 need(mesh.vertices.size()<=40000&&mesh.triangles.size()<=80000&&mesh.observations.size()+mesh.unattachedObservations.size()<=35112,"mesh cardinality cap");Bits b(dir/"mesh.bits");b.integer(mesh.vertices.size());
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
 supportIds+=e.supportingObservationIds.size();need(supportIds<=316008,"support IDs budget");supported+=a.supported;expected<<ledger_observer::preparedRow(id,p,e,a);
 }
 need(bool(expected),"audit write");std::ofstream o(dir/"normal-audit.json");o<<std::setprecision(17)<<"{\"samples\":"<<dense.verifiedSamples.size()<<",\"preparedSupported\":"<<supported<<",\"preparedUnknown\":"<<dense.verifiedSamples.size()-supported<<",\"supportIds\":"<<supportIds<<",\"maxPixelError\":"<<maxPixel<<",\"maxRelativeMapDepthError\":"<<maxDepth<<",\"witnessRegeneration\":\"NOT_VERIFIED\"}\n";
}
int main(int argc,char** argv){try{
 need(argc==3,"usage: harness INPUT_BINARY FRESH_OUTPUT");struct rlimit limit{};need(getrlimit(RLIMIT_AS,&limit)==0&&limit.rlim_cur<=1073741824,"1GiB native AS required");need(getrlimit(RLIMIT_CPU,&limit)==0&&limit.rlim_cur<=600,"600s native CPU required");
 fs::path dir=argv[2];need(!fs::exists(dir),"existing output refused");fs::create_directories(dir);auto views=load(argv[1]);inputs(dir,views);
 rf::DenseSettings settings;settings.nearDepth=3;settings.farDepth=7;settings.depthPlanes=96;settings.pixelStride=2;
 const auto dense=rf::reconstructDense(views,settings);denseFields(dir/"dense.bits",dense);{std::ofstream o(dir/"dense.json");o<<rf::denseJson(dense)<<'\n';need(bool(o),"dense JSON");}
 rf::SurfaceSettings surface;surface.pixelStride=2;audit(dir,views,dense,surface);const auto mesh=rf::fuseDepthSurfaces(views,dense,surface);
 {std::ofstream summary(dir/"stage-summary.json");summary<<"{\"verifiedSamples\":"<<dense.verifiedSamples.size()<<",\"vertices\":"<<mesh.vertices.size()<<",\"faces\":"<<mesh.triangles.size()<<",\"acceptedSamples\":"<<mesh.acceptedSamples<<",\"baselineStage\":\""<<(dense.verifiedSamples.empty()?"EMPTY_VERIFIED_SAMPLES":mesh.triangles.empty()?"NO_VALID_FACES":"MESH_AVAILABLE")<<"\"}\n";need(bool(summary),"stage summary write");}
 output(dir,mesh);
 fs::create_directory(dir/"after");inputs(dir/"after",views);denseFields(dir/"after/dense.bits",dense);
 std::size_t bytes=0;for(const auto& entry:fs::recursive_directory_iterator(dir))if(entry.is_regular_file())bytes+=entry.file_size();need(bytes<=268435456,"case output budget");
 std::cout<<"samples "<<dense.verifiedSamples.size()<<" vertices "<<mesh.vertices.size()<<" faces "<<mesh.triangles.size()<<" accepted "<<mesh.acceptedSamples<<" attached "<<mesh.observations.size()<<" unattached "<<mesh.unattachedObservations.size()<<'\n';
}catch(const std::exception& e){std::cerr<<"REFUSED: "<<e.what()<<'\n';return 2;}}
