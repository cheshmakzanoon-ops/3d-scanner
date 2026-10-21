// Stage A only: synthetic serialization/IO guards. NEVER calls fuseDepthSurfaces.
#include "snapshot.hpp"
#include <fstream>
#include <functional>
#include <iostream>
namespace {
using namespace rf;
using namespace rf::trace_test;
struct Offsets {std::size_t fx{},rgba{},mapDepth{},sampleCount{},sample0{},sample1{},sourceReference{};};
Offsets locate(const std::vector<std::uint8_t>& b){
 Reader r{std::span(b).first(b.size()-32)};r.data(184);require(r.integer<std::uint32_t>()==2,"guard fixture view count");Offsets o;
 for(int i=0;i<2;++i){if(i==0)o.fx=r.offset+8;r.data(136);auto n=r.integer<std::uint32_t>();if(i==0)o.rgba=r.offset;r.data(n);}
 require(r.integer<std::uint32_t>()==2,"guard fixture map count");for(int i=0;i<2;++i){r.data(8);auto n=r.integer<std::uint32_t>();if(i==0)o.mapDepth=r.offset;r.data(std::size_t(n)*4);n=r.integer<std::uint32_t>();r.data(std::size_t(n)*4);}
 auto n=r.integer<std::uint32_t>();require(n==2,"guard fixture point count");r.data(std::size_t(n)*32);o.sampleCount=r.offset;n=r.integer<std::uint32_t>();require(n==2,"guard fixture sample count");o.sample0=r.offset;o.sample1=r.offset+66;r.data(std::size_t(n)*66);r.data(22*8+8);require(r.integer<std::uint32_t>()==2,"guard fixture source rows");require(r.integer<std::uint32_t>()==1,"guard fixture source count");o.sourceReference=r.offset;return o;
}
template<class T>void put(std::vector<std::uint8_t>& b,std::size_t off,T value){static_assert(std::is_unsigned_v<T>);require(off+sizeof(T)<=b.size(),"guard mutation bound");for(std::size_t j=0;j<sizeof(T);++j)b[off+j]=static_cast<std::uint8_t>(value>>(8*j));}
void reseal(std::vector<std::uint8_t>& b){const auto digest=hash(std::span(b).first(b.size()-32));std::copy(digest.begin(),digest.end(),b.end()-32);}
std::array<std::size_t,22> counters(const DenseResult& d){return {d.sampledPixels,d.photometricPixels,d.consistentPixels,d.peakLuminanceBytes,d.pointBudget,d.resumedDepthMaps,d.writtenDepthMaps,d.completedTiles,d.totalTiles,d.admittedWorkingSetBytes,d.resumedDepthTiles,d.recomputedDepthTiles,d.writtenDepthTiles,d.rejectedDepthTiles,d.coarseDepthMaps,d.propagatedHypotheses,d.sparsePriorTiles,d.normalHypothesesEvaluated,d.normalRefinedPixels,d.normalSupportPairs,d.normalRejectedPairs,d.normalUnknownPairs};}
Snapshot fixture(){
 Snapshot x;x.depthIdentity=std::string(64,'a');x.settings=SurfaceSettings{};
 for(int cam=0;cam<2;++cam){DenseView v;v.width=32;v.height=32;v.fx=32;v.fy=33;v.cx=16;v.cy=16;v.pose.rotation={1,0,0,0,1,0,0,0,1};v.pose.translation={cam==0?-0.0:0.125,0,0};v.rgba.resize(32*32*4);for(std::size_t j=0;j<v.rgba.size();j+=4){v.rgba[j]=17;v.rgba[j+1]=31;v.rgba[j+2]=47;v.rgba[j+3]=255;}x.views.push_back(std::move(v));DenseDepthMap m;m.width=32;m.height=32;m.depth.assign(32*32,2.f);m.confidence.assign(32*32,.75f);m.depth[0]=-0.f;m.confidence[0]=-0.f;x.dense.maps.push_back(std::move(m));
  DensePoint p;p.xyz={cam==0?-0.0:.25,.5,2};p.color={17,31,47};p.confidence=.75f;p.consistentViews=2;x.dense.points.push_back(p);
  VerifiedDepthSample sample;sample.xyz=p.xyz;sample.color=p.color;sample.confidence=p.confidence;sample.supportingViews=2;sample.camera=static_cast<std::uint8_t>(cam);sample.x=8;sample.y=8;sample.worldNormal={-0.,0,1};sample.normalConfidence=.5f;sample.normalSupportingViews=2;x.dense.verifiedSamples.push_back(sample);
 }
 auto& d=x.dense;d.sampledPixels=4;d.photometricPixels=3;d.consistentPixels=2;d.medianConfidence=.8125;d.sourceViews={{1},{0}};d.consistentPixelsPerView={1,1};
 d.peakLuminanceBytes=101;d.pointBudget=20;d.resumedDepthMaps=103;d.writtenDepthMaps=104;d.completedTiles=105;d.totalTiles=106;d.admittedWorkingSetBytes=107;d.resumedDepthTiles=108;d.recomputedDepthTiles=109;d.writtenDepthTiles=110;d.rejectedDepthTiles=111;d.coarseDepthMaps=112;d.propagatedHypotheses=113;d.sparsePriorTiles=114;d.normalHypothesesEvaluated=115;d.normalRefinedPixels=116;d.normalSupportPairs=117;d.normalRejectedPairs=118;d.normalUnknownPairs=119;
 return x;
}
}
int main(int argc,char** argv){try{
 using namespace rf::trace_test;require(argc==2,"usage: guard_test fresh-output-directory");require(!std::getenv("RF_TRACE_SNAPSHOT")&&!std::getenv("RF_TRACE_COUNTS")&&!std::getenv("RF_TRACE_CAPTURE_MESH_BITS"),"guard tests must not activate capture");
 const std::filesystem::path out=argv[1];freshDirectory(out);std::vector<std::string> tests;
 auto pass=[&](const std::string& name){tests.push_back(name);};
 auto rejects=[&](const std::string& name,const std::string& expected,const std::function<void()>& action){bool caught=false;try{action();}catch(const std::exception& e){caught=true;require(std::string(e.what())==expected,"guard rejected with unexpected reason");}require(caught,"guard unexpectedly accepted malformed input");pass(name);};
 auto original=fixture();const auto bytes=encodeSnapshot(original.views,original.dense,original.settings,original.depthIdentity);auto decoded=decodeSnapshot(bytes,original.depthIdentity);require(bytes==encodeSnapshot(decoded.views,decoded.dense,decoded.settings,decoded.depthIdentity),"fixture roundtrip differs");require(counters(decoded.dense)==counters(original.dense)&&std::bit_cast<std::uint64_t>(decoded.dense.medianConfidence)==std::bit_cast<std::uint64_t>(original.dense.medianConfidence)&&decoded.dense.sourceViews==original.dense.sourceViews&&decoded.dense.consistentPixelsPerView==original.dense.consistentPixelsPerView,"full DenseResult evidence changed");pass("full_dense_22_counter_roundtrip");
 require(std::bit_cast<std::uint64_t>(decoded.views[0].pose.translation.x)==0x8000000000000000ULL&&std::bit_cast<std::uint64_t>(decoded.dense.points[0].xyz.x)==0x8000000000000000ULL&&std::bit_cast<std::uint64_t>(decoded.dense.verifiedSamples[0].worldNormal.x)==0x8000000000000000ULL&&std::bit_cast<std::uint32_t>(decoded.dense.maps[0].depth[0])==0x80000000U&&std::bit_cast<std::uint32_t>(decoded.dense.maps[0].confidence[0])==0x80000000U,"negative-zero bits changed");pass("explicit_f32_f64_negative_zero_bit_identity");
 const auto positions=locate(bytes);auto mutation=[&](const std::string& name,const std::string& error,const std::function<void(std::vector<std::uint8_t>&)>& change,bool checksum=true){auto b=bytes;change(b);if(checksum)reseal(b);rejects(name,error,[&]{(void)decodeSnapshot(b,original.depthIdentity);});};
 mutation("bad_sha_trailer","trace checksum mismatch",[](auto& b){b.back()^=1;},false);
 mutation("wrong_magic","trace schema/length",[](auto& b){b[0]='X';});
 mutation("wrong_schema","trace schema/length",[](auto& b){put(b,8,std::uint32_t{2});});
 mutation("wrong_declared_length","trace schema/length",[](auto& b){put(b,12,std::uint32_t{1});});
 mutation("changed_depth_identity","trace depth identity mismatch",[](auto& b){b[16]='b';});
 rejects("independent_wrong_identity","trace depth identity mismatch",[&]{(void)decodeSnapshot(bytes,std::string(64,'b'));});
 rejects("short_input","trace snapshot bound/identity",[&]{(void)decodeSnapshot(std::span(bytes).first(31),original.depthIdentity);});
 mutation("truncated_payload_valid_checksum","trace truncated",[](auto& b){b.erase(b.end()-33);put(b,12,static_cast<std::uint32_t>(b.size()));});
 mutation("trailing_payload_valid_checksum","trace trailing data",[](auto& b){b.insert(b.end()-32,0);put(b,12,static_cast<std::uint32_t>(b.size()));});
 mutation("excessive_view_count","trace count bound",[](auto& b){put(b,184,std::uint32_t{9});});
 mutation("excessive_sample_count","trace count bound",[&](auto& b){put(b,positions.sampleCount,std::uint32_t{100001});});
 mutation("nonfinite_double","trace nonfinite double",[&](auto& b){put(b,positions.fx,std::uint64_t{0x7ff8000000000000ULL});});
 mutation("nonfinite_float","trace nonfinite float",[&](auto& b){put(b,positions.mapDepth,std::uint32_t{0x7fc00000U});});
 mutation("invalid_rgba_alpha","trace validity byte",[&](auto& b){b[positions.rgba+3]=7;});
 mutation("invalid_sample_camera","trace sample provenance",[&](auto& b){b[positions.sample0+32]=2;});
 mutation("duplicate_sample_pixel","trace sample provenance",[&](auto& b){b[positions.sample1+32]=0;});
 mutation("self_source_reference","trace source references",[&](auto& b){put(b,positions.sourceReference,std::uint32_t{0});});
 mutation("changed_native_geometry_digest","trace native input digest mismatch",[](auto& b){b[80]^=1;});
 mutation("changed_native_algorithm_digest","trace native input digest mismatch",[](auto& b){b[112]^=1;});
 mutation("changed_baseline_settings","trace unchanged baseline settings",[](auto& b){put(b,144,std::bit_cast<std::uint32_t>(.25f));});
 mutation("changed_rgba_digest_binding","trace native input digest mismatch",[&](auto& b){b[positions.rgba]^=1;});
 auto oversize=bytes;oversize.resize(SNAPSHOT_MAX+1);rejects("oversize_memory_input","trace snapshot bound/identity",[&]{(void)decodeSnapshot(oversize,original.depthIdentity);});
 Writer bounded;bounded.limit=4;const std::array<std::uint8_t,5> five{};rejects("writer_preallocation_byte_cap","trace encoded byte bound",[&]{bounded.data(five);});
 const auto file=out/"positive.snapshot";secureWriteFresh(file,bytes,SNAPSHOT_MAX);require(secureRead(file)==bytes,"fresh file roundtrip differs");pass("fresh_owned_file_roundtrip");
 rejects("existing_output_not_overwritten","trace output must be fresh",[&]{secureWriteFresh(file,bytes,SNAPSHOT_MAX);});require(secureRead(file)==bytes,"preexisting output changed");
 require(::symlink(file.c_str(),(out/"leaf-link").c_str())==0,"guard symlink setup");rejects("leaf_symlink_read","trace input open",[&]{(void)secureRead(out/"leaf-link");});rejects("leaf_symlink_write","trace output must be fresh",[&]{secureWriteFresh(out/"leaf-link",bytes,SNAPSHOT_MAX);});
 freshDirectory(out/"owned-child");require(::chmod((out/"owned-child").c_str(),0777)==0,"guard parent mode setup");rejects("writable_parent_rejected_before_creation","trace parent ownership/mode",[&]{secureWriteFresh(out/"owned-child/must-not-exist",bytes,SNAPSHOT_MAX);});require(!std::filesystem::exists(out/"owned-child/must-not-exist"),"writable parent write created file");require(::chmod((out/"owned-child").c_str(),0700)==0,"guard parent mode restore");require(::symlink((out/"owned-child").c_str(),(out/"ancestor-link").c_str())==0,"guard ancestor symlink setup");rejects("ancestor_symlink_write","trace ancestor NOFOLLOW",[&]{secureWriteFresh(out/"ancestor-link/new",bytes,SNAPSHOT_MAX);});rejects("ancestor_symlink_read","trace ancestor NOFOLLOW",[&]{(void)secureRead(out/"ancestor-link/new");});
 require(::link(file.c_str(),(out/"hardlink").c_str())==0,"guard hardlink setup");rejects("hardlinked_input","trace input type/size/owner",[&]{(void)secureRead(file);});
 Fd large(::open((out/"oversize.snapshot").c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));require(large.value>=0&&::ftruncate(large.value,static_cast<off_t>(SNAPSHOT_MAX+1))==0,"guard oversize file setup");rejects("oversize_file_input","trace input type/size/owner",[&]{(void)secureRead(out/"oversize.snapshot");});rejects("oversize_write_no_file","trace output bound",[&]{secureWriteFresh(out/"must-not-exist",oversize,SNAPSHOT_MAX);});require(!std::filesystem::exists(out/"must-not-exist"),"oversize write created file");
 rejects("directory_not_regular_file","trace input type/size/owner",[&]{(void)secureRead(out/"owned-child");});rejects("relative_path_rejected","trace path must be canonical absolute",[&]{(void)secureRead("relative.snapshot");});
 std::string receipt="{\"status\":\"PASS_SERIALIZATION_GUARDS_ONLY_NO_FUSER\",\"syntheticGuardFixture\":true,\"fuserInvoked\":false,\"snapshotBytes\":"+std::to_string(bytes.size())+",\"guards\":[";bool first=true;for(const auto& name:tests){if(!first)receipt+=',';first=false;receipt+='"'+name+'"';}receipt+="],\"notTested\":[\"Wrong-owner rejection: no chown or alternate-user process authorized.\",\"Concurrent rename/write races are not simulated; static path/link/size guards only.\",\"No geometry quality, native fuser replay, or telemetry equivalence claim.\"]}\n";secureWriteFresh(out/"guard-receipt.json",std::span(reinterpret_cast<const std::uint8_t*>(receipt.data()),receipt.size()),1024*1024);std::cout<<receipt;
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
