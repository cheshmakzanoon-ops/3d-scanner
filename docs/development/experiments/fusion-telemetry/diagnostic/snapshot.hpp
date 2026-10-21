#pragma once
// EXTERNAL TEST ONLY. Explicit schema; never serializes object padding or guesses inputs.
#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include <algorithm>
#include <bit>
#include <cerrno>
#include <cmath>
#include <cstdlib>
#include <filesystem>
#include <limits>
#include <set>
#include <string>
#include <tuple>
#include <type_traits>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
namespace rf::trace_test {
inline constexpr std::size_t SNAPSHOT_MAX=12u*1024u*1024u;
inline void require(bool ok,const char* why){if(!ok)throw Error(why);}
struct Fd {
 int value{-1};explicit Fd(int n=-1):value(n){}~Fd(){if(value>=0)::close(value);}
 Fd(const Fd&)=delete;Fd& operator=(const Fd&)=delete;
 Fd(Fd&& x) noexcept:value(x.value){x.value=-1;}
 Fd& operator=(Fd&& x) noexcept{if(value>=0)::close(value);value=x.value;x.value=-1;return *this;}
};
inline Fd parentDirectory(const std::filesystem::path& path,bool owned=true){
 require(path.is_absolute()&&path.lexically_normal()==path&&!path.filename().empty(),"trace path must be canonical absolute");
 Fd fd(::open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC));require(fd.value>=0,"trace root open");
 for(const auto& part:path.parent_path().relative_path()){
  require(part!="."&&part!=".."&&!part.empty(),"trace invalid ancestor");
  Fd next(::openat(fd.value,part.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));require(next.value>=0,"trace ancestor NOFOLLOW");fd=std::move(next);
 }
 struct stat st{};require(::fstat(fd.value,&st)==0&&S_ISDIR(st.st_mode),"trace parent type");
 if(owned)require(st.st_uid==::geteuid()&&(st.st_mode&0022)==0,"trace parent ownership/mode");
 return fd;
}
inline bool sameFile(const struct stat& a,const struct stat& b){return a.st_dev==b.st_dev&&a.st_ino==b.st_ino&&a.st_size==b.st_size&&a.st_mtim.tv_sec==b.st_mtim.tv_sec&&a.st_mtim.tv_nsec==b.st_mtim.tv_nsec&&a.st_ctim.tv_sec==b.st_ctim.tv_sec&&a.st_ctim.tv_nsec==b.st_ctim.tv_nsec;}
inline std::vector<std::uint8_t> secureRead(const std::filesystem::path& path,std::size_t maxBytes=SNAPSHOT_MAX){
 auto parent=parentDirectory(path);Fd fd(::openat(parent.value,path.filename().c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC));require(fd.value>=0,"trace input open");
 struct stat before{},after{},named{};require(::fstat(fd.value,&before)==0&&S_ISREG(before.st_mode)&&before.st_uid==::geteuid()&&before.st_nlink==1&&before.st_size>0&&static_cast<std::uint64_t>(before.st_size)<=maxBytes,"trace input type/size/owner");
 std::vector<std::uint8_t> bytes(static_cast<std::size_t>(before.st_size));std::size_t off=0;
 while(off<bytes.size()){const auto n=::read(fd.value,bytes.data()+off,bytes.size()-off);if(n<0&&errno==EINTR)continue;require(n>0,"trace input read");off+=static_cast<std::size_t>(n);}
 require(::fstat(fd.value,&after)==0&&sameFile(before,after)&&::fstatat(parent.value,path.filename().c_str(),&named,AT_SYMLINK_NOFOLLOW)==0&&sameFile(after,named),"trace input changed");return bytes;
}
inline void secureWriteFresh(const std::filesystem::path& path,std::span<const std::uint8_t> bytes,std::size_t maxBytes){
 require(!bytes.empty()&&bytes.size()<=maxBytes,"trace output bound");auto parent=parentDirectory(path);
 Fd fd(::openat(parent.value,path.filename().c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));require(fd.value>=0,"trace output must be fresh");
 std::size_t off=0;while(off<bytes.size()){const auto n=::write(fd.value,bytes.data()+off,bytes.size()-off);if(n<0&&errno==EINTR)continue;require(n>0,"trace output write");off+=static_cast<std::size_t>(n);}
 require(::fsync(fd.value)==0,"trace output fsync");struct stat st{};require(::fstat(fd.value,&st)==0&&S_ISREG(st.st_mode)&&st.st_uid==::geteuid()&&st.st_nlink==1&&static_cast<std::size_t>(st.st_size)==bytes.size(),"trace output metadata");
 const auto reread=secureRead(path,maxBytes);require(std::equal(bytes.begin(),bytes.end(),reread.begin(),reread.end()),"trace output bytes changed");
}
inline void freshDirectory(const std::filesystem::path& path){
 auto parent=parentDirectory(path,false);struct stat st{};require(::fstat(parent.value,&st)==0&&(st.st_uid==0||st.st_uid==::geteuid()),"trace output parent owner");
 require(::mkdirat(parent.value,path.filename().c_str(),0700)==0,"trace output directory must be fresh");
 Fd child(::openat(parent.value,path.filename().c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));require(child.value>=0&&::fstat(child.value,&st)==0&&st.st_uid==::geteuid()&&(st.st_mode&0077)==0,"trace output directory ownership");
}
inline checkpoint::Digest hash(std::span<const std::uint8_t> bytes){checkpoint::Sha256 h;h.update(bytes);return h.finish();}
inline bool identityValid(const std::string& id){return id.size()==64&&id.find_first_not_of("0123456789abcdef")==std::string::npos;}
struct Writer {
 std::vector<std::uint8_t> bytes;std::size_t limit=SNAPSHOT_MAX;
 void data(std::span<const std::uint8_t> x){require(x.size()<=limit-bytes.size(),"trace encoded byte bound");bytes.insert(bytes.end(),x.begin(),x.end());}
 void text(const std::string& x){data(std::span(reinterpret_cast<const std::uint8_t*>(x.data()),x.size()));}
 template<class T>void integer(T x){static_assert(std::is_unsigned_v<T>);std::array<std::uint8_t,sizeof(T)> b{};for(std::size_t i=0;i<sizeof(T);++i)b[i]=static_cast<std::uint8_t>(x>>(8*i));data(b);}
 void real(double x){integer(std::bit_cast<std::uint64_t>(x));}void single(float x){integer(std::bit_cast<std::uint32_t>(x));}
 void vector(Vec3 x){real(x.x);real(x.y);real(x.z);}void count(std::size_t n){require(n<=UINT32_MAX,"trace count overflow");integer(static_cast<std::uint32_t>(n));}
};
struct Reader {
 std::span<const std::uint8_t> bytes;std::size_t offset{};
 auto data(std::size_t n){require(n<=bytes.size()-offset,"trace truncated");auto x=bytes.subspan(offset,n);offset+=n;return x;}
 template<class T>T integer(){static_assert(std::is_unsigned_v<T>);auto x=data(sizeof(T));T v=0;for(std::size_t i=0;i<sizeof(T);++i)v|=static_cast<T>(x[i])<<(8*i);return v;}
 double real(){auto x=std::bit_cast<double>(integer<std::uint64_t>());require(std::isfinite(x),"trace nonfinite double");return x;}
 float single(){auto x=std::bit_cast<float>(integer<std::uint32_t>());require(std::isfinite(x),"trace nonfinite float");return x;}
 Vec3 vector(){return {real(),real(),real()};}
 std::array<std::uint8_t,3> color(){auto x=data(3);return {x[0],x[1],x[2]};}
 checkpoint::Digest digest(){checkpoint::Digest d{};auto x=data(32);std::copy(x.begin(),x.end(),d.begin());return d;}
 std::size_t count(std::size_t max){auto n=integer<std::uint32_t>();require(n<=max,"trace count bound");return n;}
};
// All DenseResult size_t scalars, in declaration order, excluding vector lengths.
#define RF_TRACE_DENSE_COUNTERS(F) \
 F(sampledPixels) F(photometricPixels) F(consistentPixels) \
 F(peakLuminanceBytes) F(pointBudget) F(resumedDepthMaps) F(writtenDepthMaps) \
 F(completedTiles) F(totalTiles) F(admittedWorkingSetBytes) \
 F(resumedDepthTiles) F(recomputedDepthTiles) F(writtenDepthTiles) F(rejectedDepthTiles) \
 F(coarseDepthMaps) F(propagatedHypotheses) F(sparsePriorTiles) \
 F(normalHypothesesEvaluated) F(normalRefinedPixels) F(normalSupportPairs) F(normalRejectedPairs) F(normalUnknownPairs)
struct Snapshot {std::string depthIdentity;std::vector<DenseView> views;DenseResult dense;SurfaceSettings settings;};
inline void validate(std::span<const DenseView> views,const DenseResult& d,const SurfaceSettings& s){
 require(views.size()>=2&&views.size()<=MAX_DENSE_VIEWS,"trace view count");
 const auto w=views.front().width,h=views.front().height;require(w>=32&&w<=640&&h>=32&&h<=640&&std::size_t(w)*h<=320000,"trace dimensions");const auto pixels=std::size_t(w)*h;
 require(d.maps.size()==views.size()&&d.sourceViews.size()==views.size()&&d.consistentPixelsPerView.size()==views.size(),"trace dense per-view vectors");
 require(d.points.size()<=50000&&d.verifiedSamples.size()<=MAX_DENSE_SAMPLES&&d.sampledPixels<=MAX_DENSE_SAMPLES&&d.photometricPixels<=d.sampledPixels&&d.consistentPixels<=d.photometricPixels&&d.points.size()<=d.consistentPixels&&d.verifiedSamples.size()<=d.consistentPixels&&d.pointBudget>=10&&d.pointBudget<=50000&&d.points.size()<=d.pointBudget,"trace dense evidence bounds");
 require(std::isfinite(d.medianConfidence)&&d.medianConfidence>=0&&d.medianConfidence<=1,"trace dense median confidence");
 SurfaceSettings expected;expected.pixelStride=s.pixelStride;require(s.pixelStride>=2&&s.pixelStride<=8&&s.minimumConfidence==expected.minimumConfidence&&s.maximumRelativeDepthJump==expected.maximumRelativeDepthJump&&s.maximumEdgePixelFactor==expected.maximumEdgePixelFactor&&s.maxVertices==expected.maxVertices&&s.maxTriangles==expected.maxTriangles&&s.maxVoxelOccupancy==expected.maxVoxelOccupancy&&s.maxFusionCandidates==expected.maxFusionCandidates,"trace unchanged baseline settings");
 const auto finitePoint=[](Vec3 p){return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z)&&std::max({std::abs(p.x),std::abs(p.y),std::abs(p.z)})<=1e6;};
 const auto confidence=[](double x){return std::isfinite(x)&&x>=0&&x<=1;};
 std::size_t total=0;
 for(std::size_t i=0;i<views.size();++i){const auto& v=views[i];const auto& m=d.maps[i];require(v.width==w&&v.height==h&&v.rgba.size()==pixels*4&&m.width==w&&m.height==h&&m.depth.size()==pixels&&m.confidence.size()==pixels,"trace view/map extent");
  require(finitePoint(v.pose.translation)&&std::isfinite(v.fx)&&std::isfinite(v.fy)&&std::isfinite(v.cx)&&std::isfinite(v.cy),"trace calibration finite");for(auto x:v.pose.rotation)require(std::isfinite(x),"trace rotation finite");
  for(std::size_t j=0;j<pixels;++j){require(v.rgba[j*4+3]==0||v.rgba[j*4+3]==255,"trace validity byte");require(std::isfinite(m.depth[j])&&m.depth[j]>=0&&m.depth[j]<=1e6&&confidence(m.confidence[j]),"trace map values");}
  require(!d.sourceViews[i].empty()&&d.sourceViews[i].size()<=3&&d.consistentPixelsPerView[i]<=d.consistentPixels,"trace source evidence");std::set<std::size_t> unique;for(auto c:d.sourceViews[i])require(c<views.size()&&c!=i&&unique.insert(c).second,"trace source references");total+=d.consistentPixelsPerView[i];
 }
 require(total==d.consistentPixels,"trace dense count reconciliation");
 for(const auto& p:d.points)require(finitePoint(p.xyz)&&confidence(p.confidence)&&p.consistentViews>=2&&p.consistentViews<=views.size(),"trace dense point");
 std::set<std::tuple<unsigned,unsigned,unsigned>> source;
 for(const auto& p:d.verifiedSamples)require(finitePoint(p.xyz)&&finitePoint(p.worldNormal)&&confidence(p.confidence)&&confidence(p.normalConfidence)&&p.supportingViews>=2&&p.supportingViews<=views.size()&&p.camera<views.size()&&p.x<w&&p.y<h&&p.normalSupportingViews<=views.size()&&source.emplace(p.camera,p.x,p.y).second,"trace sample provenance");
 // Original digest routine supplies rigid-pose/intrinsic/alpha validation, without input mutation.
 (void)surface_checkpoint::geometryIdentity(views);(void)surface_checkpoint::algorithmIdentity(s);
}
inline std::vector<std::uint8_t> encodeSnapshot(std::span<const DenseView> views,const DenseResult& d,const SurfaceSettings& s,const std::string& id){
 require(identityValid(id),"trace depth identity");validate(views,d,s);Writer o;o.text("RFFUSE01");o.integer(std::uint32_t{1});o.integer(std::uint32_t{0});o.text(id);o.data(surface_checkpoint::geometryIdentity(views));o.data(surface_checkpoint::algorithmIdentity(s));
 o.single(s.minimumConfidence);o.real(s.maximumRelativeDepthJump);o.real(s.maximumEdgePixelFactor);o.count(s.maxVertices);o.count(s.maxTriangles);o.count(s.pixelStride);o.count(s.maxVoxelOccupancy);o.count(s.maxFusionCandidates);
 o.count(views.size());for(const auto& v:views){o.count(v.width);o.count(v.height);o.real(v.fx);o.real(v.fy);o.real(v.cx);o.real(v.cy);for(auto x:v.pose.rotation)o.real(x);o.vector(v.pose.translation);o.count(v.rgba.size());o.data(v.rgba);}
 o.count(d.maps.size());for(const auto& m:d.maps){o.count(m.width);o.count(m.height);o.count(m.depth.size());for(auto x:m.depth)o.single(x);o.count(m.confidence.size());for(auto x:m.confidence)o.single(x);}
 o.count(d.points.size());for(const auto& p:d.points){o.vector(p.xyz);o.data(p.color);o.single(p.confidence);o.integer(p.consistentViews);}
 o.count(d.verifiedSamples.size());for(const auto& p:d.verifiedSamples){o.vector(p.xyz);o.data(p.color);o.single(p.confidence);o.integer(p.supportingViews);o.integer(p.camera);o.integer(p.x);o.integer(p.y);o.vector(p.worldNormal);o.single(p.normalConfidence);o.integer(p.normalSupportingViews);}
 #define WRITE_COUNTER(name) o.integer(static_cast<std::uint64_t>(d.name));
 RF_TRACE_DENSE_COUNTERS(WRITE_COUNTER)
 #undef WRITE_COUNTER
 o.real(d.medianConfidence);o.count(d.sourceViews.size());for(const auto& row:d.sourceViews){o.count(row.size());for(auto x:row)o.count(x);}o.count(d.consistentPixelsPerView.size());for(auto x:d.consistentPixelsPerView)o.integer(static_cast<std::uint64_t>(x));
 require(o.bytes.size()<=SNAPSHOT_MAX-32,"trace checksum bound");const auto size=static_cast<std::uint32_t>(o.bytes.size()+32);for(unsigned i=0;i<4;++i)o.bytes[12+i]=static_cast<std::uint8_t>(size>>(8*i));o.data(hash(o.bytes));return o.bytes;
}
inline Snapshot decodeSnapshot(std::span<const std::uint8_t> bytes,const std::string& expectedId){
 require(identityValid(expectedId)&&bytes.size()>=256&&bytes.size()<=SNAPSHOT_MAX,"trace snapshot bound/identity");const auto body=bytes.first(bytes.size()-32);const auto checksum=hash(body);require(std::equal(checksum.begin(),checksum.end(),bytes.end()-32),"trace checksum mismatch");Reader in{body};auto magic=in.data(8);require(std::string(magic.begin(),magic.end())=="RFFUSE01"&&in.integer<std::uint32_t>()==1&&in.integer<std::uint32_t>()==bytes.size(),"trace schema/length");Snapshot out;auto id=in.data(64);out.depthIdentity=std::string(id.begin(),id.end());require(out.depthIdentity==expectedId,"trace depth identity mismatch");auto geometry=in.digest(),algorithm=in.digest();auto& s=out.settings;auto& d=out.dense;
 s.minimumConfidence=in.single();s.maximumRelativeDepthJump=in.real();s.maximumEdgePixelFactor=in.real();s.maxVertices=in.count(40000);s.maxTriangles=in.count(80000);s.pixelStride=static_cast<int>(in.count(8));s.maxVoxelOccupancy=in.count(512);s.maxFusionCandidates=in.count(4096);
 const auto nv=in.count(MAX_DENSE_VIEWS);require(nv>=2,"trace view count");
 for(std::size_t i=0;i<nv;++i){DenseView v;v.width=static_cast<int>(in.count(640));v.height=static_cast<int>(in.count(640));require(v.width>=32&&v.height>=32&&std::size_t(v.width)*v.height<=320000,"trace view dimensions");v.fx=in.real();v.fy=in.real();v.cx=in.real();v.cy=in.real();for(auto& x:v.pose.rotation)x=in.real();v.pose.translation=in.vector();auto n=in.count(1280000);require(n==std::size_t(v.width)*v.height*4,"trace RGBA extent");auto rgba=in.data(n);v.rgba.assign(rgba.begin(),rgba.end());out.views.push_back(std::move(v));}
 const auto nm=in.count(MAX_DENSE_VIEWS);require(nm==nv,"trace map count");for(std::size_t i=0;i<nm;++i){DenseDepthMap m;m.width=static_cast<int>(in.count(640));m.height=static_cast<int>(in.count(640));const auto pixels=std::size_t(out.views[i].width)*out.views[i].height;require(m.width==out.views[i].width&&m.height==out.views[i].height,"trace map dimensions");auto n=in.count(320000);require(n==pixels&&n<=(in.bytes.size()-in.offset)/4,"trace depth extent");m.depth.reserve(n);for(std::size_t j=0;j<n;++j)m.depth.push_back(in.single());n=in.count(320000);require(n==pixels&&n<=(in.bytes.size()-in.offset)/4,"trace confidence extent");m.confidence.reserve(n);for(std::size_t j=0;j<n;++j)m.confidence.push_back(in.single());d.maps.push_back(std::move(m));}
 const auto np=in.count(50000);require(np<=(in.bytes.size()-in.offset)/32,"trace point extent");d.points.reserve(np);for(std::size_t i=0;i<np;++i){DensePoint p;p.xyz=in.vector();p.color=in.color();p.confidence=in.single();p.consistentViews=in.integer<std::uint8_t>();d.points.push_back(p);}
 const auto ns=in.count(MAX_DENSE_SAMPLES);require(ns<=(in.bytes.size()-in.offset)/66,"trace sample extent");d.verifiedSamples.reserve(ns);for(std::size_t i=0;i<ns;++i){VerifiedDepthSample p;p.xyz=in.vector();p.color=in.color();p.confidence=in.single();p.supportingViews=in.integer<std::uint8_t>();p.camera=in.integer<std::uint8_t>();p.x=in.integer<std::uint16_t>();p.y=in.integer<std::uint16_t>();p.worldNormal=in.vector();p.normalConfidence=in.single();p.normalSupportingViews=in.integer<std::uint8_t>();d.verifiedSamples.push_back(p);}
 #define READ_COUNTER(name) {auto n=in.integer<std::uint64_t>();require(n<=std::numeric_limits<std::size_t>::max(),"trace scalar overflow");d.name=static_cast<std::size_t>(n);}
 RF_TRACE_DENSE_COUNTERS(READ_COUNTER)
 #undef READ_COUNTER
 d.medianConfidence=in.real();auto nc=in.count(MAX_DENSE_VIEWS);require(nc==nv,"trace source-vector count");for(std::size_t i=0;i<nc;++i){auto n=in.count(3);std::vector<std::size_t> row;for(std::size_t j=0;j<n;++j)row.push_back(in.count(MAX_DENSE_VIEWS-1));d.sourceViews.push_back(std::move(row));}nc=in.count(MAX_DENSE_VIEWS);require(nc==nv,"trace per-view count");for(std::size_t i=0;i<nc;++i){auto n=in.integer<std::uint64_t>();require(n<=MAX_DENSE_SAMPLES,"trace per-view evidence bound");d.consistentPixelsPerView.push_back(static_cast<std::size_t>(n));}
 require(in.offset==in.bytes.size(),"trace trailing data");validate(out.views,d,s);require(geometry==surface_checkpoint::geometryIdentity(out.views)&&algorithm==surface_checkpoint::algorithmIdentity(s),"trace native input digest mismatch");
 require(encodeSnapshot(out.views,d,s,out.depthIdentity)==std::vector<std::uint8_t>(bytes.begin(),bytes.end()),"trace bit-exact roundtrip mismatch");return out;
}
inline Snapshot readSnapshot(const std::filesystem::path& path,const std::string& expectedId){return decodeSnapshot(secureRead(path),expectedId);}
inline void capture(std::span<const DenseView> views,const DenseResult& d,const SurfaceSettings& s){
 const char* target=std::getenv("RF_TRACE_SNAPSHOT");if(!target)return;require(*target,"trace empty capture path");const auto* scope=depth_checkpoint::current();require(scope&&identityValid(scope->identity()),"trace active depth scope required");
 const auto bytes=encodeSnapshot(views,d,s,scope->identity());secureWriteFresh(target,bytes,SNAPSHOT_MAX);require(bytes==encodeSnapshot(views,d,s,scope->identity()),"trace source changed during capture");
}
// Exact logical fields, including provenance and diagnostics; no implementation padding.
inline std::vector<std::uint8_t> meshBits(const SurfaceMesh& m){
 require(m.vertices.size()<=40000&&m.triangles.size()<=80000&&m.observations.size()<=MAX_DENSE_SAMPLES,"trace mesh bound");Writer o;o.text("RFMESH01");o.count(m.vertices.size());o.count(m.triangles.size());o.count(m.observations.size());
 for(auto n:{m.inputSamples,m.acceptedSamples,m.rejectedCells,m.rejectedTriangles,m.fusedObservations}){
  o.integer(static_cast<std::uint64_t>(n));
 }
 o.single(m.medianConfidence);
 for(const auto& v:m.vertices){o.vector(v.xyz);o.vector(v.normal);o.data(v.color);o.single(v.confidence);o.integer(v.supportingViews);}for(const auto& t:m.triangles){o.integer(t.a);o.integer(t.b);o.integer(t.c);o.integer(t.sourceCamera);}for(const auto& r:m.observations){o.integer(r.vertex);o.integer(r.camera);o.integer(r.x);o.integer(r.y);o.integer(static_cast<std::uint64_t>(r.sourceSampleId));}
 for(auto n:{m.topology.boundaryEdges,m.topology.components,m.topology.nonmanifoldEdges,m.topology.intersections,m.topology.ambiguousPairs,m.topology.removedExactDefects}){
  o.integer(static_cast<std::uint64_t>(n));
 }
 o.integer(static_cast<std::uint8_t>(m.topology.intersectionBudgetExceeded));return o.bytes;
}
#undef RF_TRACE_DENSE_COUNTERS
} // namespace rf::trace_test
