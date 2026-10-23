#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/checkpoint_digest.hpp"
#include <filesystem>
#include <fstream>
#include <iostream>
#include <algorithm>
#include <stdexcept>
#include <iterator>
#include <array>
#include <set>
void check(bool c,const char*m){if(!c)throw std::runtime_error(m);}
std::vector<std::uint8_t> read(const std::filesystem::path&p){std::ifstream f(p,std::ios::binary);return {std::istreambuf_iterator<char>(f),{}};}
void write(const std::filesystem::path&p,const std::string&s){std::ofstream f(p,std::ios::binary);f<<s;f.close();check(bool(f),"output write failed");}
namespace fs=std::filesystem;
constexpr auto fixtureMarker="realityforge synthetic surface migration fixture v1\n";
constexpr auto markerName=".rf-surface-migration-fixture-v1";
constexpr auto fixtureHash="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
bool entryExists(const fs::path&p){
 std::error_code ec;const auto status=fs::symlink_status(p,ec);
 if(ec==std::errc::no_such_file_or_directory)return false;
 check(!ec,"cannot inspect path");return status.type()!=fs::file_type::not_found;
}
void requireDirectories(const fs::path&p){
 check(p.is_absolute()&&p.string().size()<=1000,"paths must be bounded and absolute");
 fs::path current;
 for(const auto&part:p){check(part!="."&&part!="..","dot path components are forbidden");current/=part;
  check(fs::is_directory(fs::symlink_status(current)),"directory must exist without symlink components");}
}
void requireEntries(const fs::path&p,std::set<std::string> required,const std::string&optional={}){
 for(const auto&entry:fs::directory_iterator(p)){
  const auto name=entry.path().filename().string();
  check(required.erase(name)==1||(!optional.empty()&&name==optional),"unexpected scratch fixture entry");
 }
 check(required.empty(),"incomplete scratch fixture");
}
std::string fixtureIdentity(const fs::path&directory){
 std::string identity=fixtureMarker;
 for(const auto*name:{"ref-0.bin","ref-1.bin","surface-v2-fusion-v7.rfmesh"}){
  const auto bytes=read(directory/name);rf::checkpoint::Sha256 digest;digest.update(bytes);
  identity+=std::string(name)+" "+rf::checkpoint::hex(digest.finish())+"\n";
 }
 return identity;
}
struct Paths {fs::path directory,staging;std::array<fs::path,4> outputs;};
Paths preflight(const fs::path&root,const fs::path&prefix){
 requireDirectories(root);check(root!=root.root_path(),"filesystem root is not a scratch directory");
 requireDirectories(prefix.parent_path());check(!prefix.filename().empty()&&prefix.filename()!="."&&prefix.filename()!="..","invalid output prefix");
 const auto relative=prefix.lexically_relative(root);
 check(!relative.empty()&&*relative.begin()=="..","outputs must be outside the cache scratch root");
 Paths result{root/".depth-checkpoints-m18"/fixtureHash,fs::path(prefix.string()+"-staging"),{}};
 const std::array<const char*,4> suffixes{"-mesh.json","-texture.json","-texture.png","-texture.glb"};
 for(std::size_t i=0;i<suffixes.size();++i){result.outputs[i]=prefix.string()+suffixes[i];check(!entryExists(result.outputs[i]),"output target already exists");}
 check(!entryExists(result.staging),"output staging directory already exists");
#ifdef RF_PROVENANCE_V3
 requireEntries(root,{markerName,".depth-checkpoints-m18"});
 const auto marker=root/markerName;check(fs::is_regular_file(fs::symlink_status(marker))&&fs::file_size(marker)<=1024,"missing fixture ownership marker");
 requireDirectories(result.directory);
 requireEntries(root/".depth-checkpoints-m18",{fixtureHash});
 requireEntries(result.directory,{"ref-0.bin","ref-1.bin","surface-v2-fusion-v7.rfmesh"},rf::surface_checkpoint::FILENAME);
 for(const auto&entry:fs::directory_iterator(result.directory))
  check(fs::is_regular_file(entry.symlink_status())&&fs::file_size(entry.path())<=12u*1024u*1024u,"fixture entries must be bounded regular files, not symlinks");
 const auto markerBytes=read(marker);check(std::string(markerBytes.begin(),markerBytes.end())==fixtureIdentity(result.directory),"fixture bytes do not match baseline marker");
#else
 check(fs::is_empty(root),"baseline requires an empty scratch directory");
#endif
 // Reserve a fresh staging directory atomically. Publication below never replaces
 // a destination, even if another process creates one after this preflight.
 check(fs::create_directory(result.staging),"cannot reserve fresh output staging directory");
 return result;
}
int run(int argc,char**argv){
 check(argc==3,"expected cache-root and output-prefix");
 const auto paths=preflight(argv[1],argv[2]);const auto&directory=paths.directory;
 std::filesystem::create_directories(directory);
 std::vector<rf::DenseView> views(2);rf::DenseResult dense;dense.maps.resize(2);dense.sourceViews={{1},{0}};dense.consistentPixelsPerView={289,289};dense.pointBudget=50000;dense.medianConfidence=.9;
 for(int c=0;c<2;++c){auto&v=views[c];v.width=v.height=40;v.fx=v.fy=100;v.cx=v.cy=19;v.pose={rf::identity(),{0,0,0}};
  if(c){v.pose.rotation={0,-1,0,1,0,0,0,0,1};}v.rgba.resize(6400);for(int p=0;p<1600;++p){v.rgba[4*p]=(p*17+c*31)%255;v.rgba[4*p+1]=(p*7)%255;v.rgba[4*p+2]=(p*3)%255;v.rgba[4*p+3]=255;}
  auto&m=dense.maps[c];m.width=m.height=40;m.depth.assign(1600,0);m.confidence.assign(1600,0);
  for(int y=3;y<37;y+=2)for(int x=3;x<37;x+=2){double rx=(x-19)/100.,ry=(y-19)/100.;if(c){double q=rx;rx=ry;ry=-q;}
   rf::Vec3 p{rx*5,ry*5,5};m.depth[y*40+x]=5;m.confidence[y*40+x]=.9f;
   dense.verifiedSamples.push_back({p,{100,110,120},.9f,2,static_cast<std::uint8_t>(c),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
   dense.points.push_back({p,{100,110,120},.9f,2});}}
 dense.sampledPixels=dense.photometricPixels=dense.consistentPixels=dense.verifiedSamples.size();
 rf::SurfaceSettings settings;settings.pixelStride=2;auto mesh=rf::fuseDepthSurfaces(views,dense,settings);
 rf::depth_checkpoint::Scope scope(directory.string(),fixtureHash);
 std::vector<std::vector<std::uint8_t>> depthBefore;
 for(std::size_t i=0;i<2;++i){auto path=directory/("ref-"+std::to_string(i)+".bin");if(!std::filesystem::exists(path)){check(rf::depth_checkpoint::store(i,dense.maps[i]),"depth store failed");++dense.writtenDepthMaps;}else{rf::DenseDepthMap actual;actual.width=actual.height=40;check(rf::depth_checkpoint::load(i,actual)&&actual.depth==dense.maps[i].depth&&actual.confidence==dense.maps[i].confidence,"existing depth differs");++dense.resumedDepthMaps;}depthBefore.push_back(read(path));}
 const auto oldFile=directory/"surface-v2-fusion-v7.rfmesh";const auto oldBytes=read(oldFile);bool created=false;
 check(rf::surface_checkpoint::store(views,dense,mesh,settings,&created),"mesh store failed");
 auto saved=directory/rf::surface_checkpoint::FILENAME;const auto acceptedBytes=read(saved);
 rf::surface_checkpoint::AcceptedSurface loaded;check(rf::surface_checkpoint::load(saved.string(),views,loaded),"mesh load failed");
 check(loaded.mesh.observations.size()==mesh.observations.size()&&rf::surfaceJson(loaded.mesh)==rf::surfaceJson(mesh),"roundtrip changed mesh");
#ifdef RF_PROVENANCE_V3
 check(oldFile!=saved&&!oldBytes.empty()&&read(oldFile)==oldBytes,"old immutable checkpoint modified");
 check(!rf::surface_checkpoint::load(oldFile.string(),views,loaded),"legacy filename accepted");
 check(mesh.observations.size()==578&&loaded.mesh.unattachedObservations.empty(),"provenance not preserved");
 std::size_t exact=0;for(std::size_t i=0;i<mesh.observations.size();++i){const auto&a=mesh.observations[i];const auto&b=loaded.mesh.observations[i];
 check(a.vertex==b.vertex&&a.camera==b.camera&&a.x==b.x&&a.y==b.y&&a.sourceSampleId==b.sourceSampleId&&a.relation==b.relation,"relation changed on load");if(b.relation==rf::SurfaceObservationRelation::ExactDuplicateFace)++exact;}
 check(exact==64,"expected64 exact duplicate support identities");
 check(dense.resumedDepthMaps==2&&dense.writtenDepthMaps==0,"depth rerun during surface migration");
#endif
 bool secondCreated=true;check(rf::surface_checkpoint::store(views,loaded.dense,loaded.mesh,loaded.settings,&secondCreated)&&!secondCreated,"replay not idempotent");
 check(read(saved)==acceptedBytes,"accepted mesh bytes changed on replay");
 for(std::size_t i=0;i<2;++i)check(read(directory/("ref-"+std::to_string(i)+".bin"))==depthBefore[i],"depth modified");
 std::vector<rf::TextureSource> photos;for(const auto&v:views)photos.push_back({v.width,v.height,v.fx,v.fy,v.cx,v.cy,v.pose,v.rgba});
 auto baked=rf::bakePhotographicAtlas(mesh,views,dense.maps,photos);
 std::array<fs::path,4> staged;for(std::size_t i=0;i<staged.size();++i)staged[i]=paths.staging/paths.outputs[i].filename();
 write(staged[0],rf::surfaceJson(mesh));write(staged[1],rf::texturedSurfaceJson(baked));rf::writeTexturePng(baked,staged[2].string());rf::writeTexturedGlb(mesh,baked,staged[3].string());
 for(std::size_t i=0;i<staged.size();++i)fs::copy_file(staged[i],paths.outputs[i],fs::copy_options::none);
#ifndef RF_PROVENANCE_V3
 // Only a completed baseline run opts this exact synthetic tree into migration.
 write(paths.staging/markerName,fixtureIdentity(directory));fs::copy_file(paths.staging/markerName,fs::path(argv[1])/markerName,fs::copy_options::none);
#endif
 std::cout<<"PASS "<<rf::surface_checkpoint::FILENAME<<" refs="<<mesh.observations.size()<<" created="<<created<<" depthWrites="<<dense.writtenDepthMaps<<" depthReused="<<dense.resumedDepthMaps<<" checkpointBytes="<<acceptedBytes.size()<<'\n';
 return 0;
}
int main(int argc,char**argv){try{return run(argc,argv);}catch(const std::exception&e){std::cerr<<"REJECT: "<<e.what()<<'\n';return 1;}}
