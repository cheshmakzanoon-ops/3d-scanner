// Diagnostic schema-v2 metadata reader. NOT surface_checkpoint::load: RGBA/depth maps unavailable.
#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/mesh_topology.hpp"
#include <bit>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <set>
#include <sstream>
#include <iomanip>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
namespace {
using namespace rf;
void need(bool value,const char* message){if(!value)throw std::runtime_error(message);}
struct Reader {
 std::span<const std::uint8_t> bytes;std::size_t offset=0;
 auto data(std::size_t n){need(n<=bytes.size()-offset,"truncated");auto part=bytes.subspan(offset,n);offset+=n;return part;}
 template<class T>T integer(){auto p=data(sizeof(T));T v=0;for(std::size_t i=0;i<sizeof(T);++i)v|=static_cast<T>(p[i])<<(8*i);return v;}
 double real(){auto v=std::bit_cast<double>(integer<std::uint64_t>());need(std::isfinite(v),"nonfinite");return v;}
 float single(){auto v=std::bit_cast<float>(integer<std::uint32_t>());need(std::isfinite(v),"nonfinite");return v;}
 Vec3 vector(){Vec3 p{real(),real(),real()};need(std::max({std::abs(p.x),std::abs(p.y),std::abs(p.z)})<=1e6,"coordinate bound");return p;}
 std::array<std::uint8_t,3> color(){auto p=data(3);return {p[0],p[1],p[2]};}
 checkpoint::Digest digest(){checkpoint::Digest d{};auto p=data(32);std::copy(p.begin(),p.end(),d.begin());return d;}
};
std::vector<std::uint8_t> read(const std::filesystem::path& path){
 need(path.is_absolute()&&path.lexically_normal()==path,"noncanonical input");
 int parent=::open("/",O_RDONLY|O_DIRECTORY|O_CLOEXEC);need(parent>=0,"root open failed");
 for(auto& part:path.parent_path().relative_path()){int next=::openat(parent,part.c_str(),O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC);::close(parent);need(next>=0,"ancestor open failed");parent=next;}
 int fd=::openat(parent,path.filename().c_str(),O_RDONLY|O_NOFOLLOW|O_CLOEXEC);::close(parent);need(fd>=0,"open failed");struct stat before{},after{};
 if(::fstat(fd,&before)||!S_ISREG(before.st_mode)||before.st_nlink!=1||before.st_uid!=::geteuid()||before.st_size<348||before.st_size>static_cast<off_t>(surface_checkpoint::MAX_FILE_BYTES)){::close(fd);throw std::runtime_error("file bound/type/owner");}
 std::vector<std::uint8_t>b(before.st_size);std::size_t off=0;while(off<b.size()){auto n=::read(fd,b.data()+off,b.size()-off);if(n<=0){::close(fd);throw std::runtime_error("read failed");}off+=n;}
 const bool stable=::fstat(fd,&after)==0&&before.st_dev==after.st_dev&&before.st_ino==after.st_ino&&before.st_size==after.st_size&&before.st_mtim.tv_sec==after.st_mtim.tv_sec&&before.st_mtim.tv_nsec==after.st_mtim.tv_nsec&&before.st_ctim.tv_sec==after.st_ctim.tv_sec&&before.st_ctim.tv_nsec==after.st_ctim.tv_nsec;
 ::close(fd);need(stable,"input changed while reading");return b;
}
void confidence(double v){need(v>=0&&v<=1,"confidence bound");}
struct Decoded {SurfaceMesh mesh;SurfaceSettings settings;std::vector<VerifiedDepthSample> samples;std::size_t cameras{},width{},height{},triangleOffset{};checkpoint::Digest geometry{},maps{};};
Decoded decode(std::span<const std::uint8_t> bytes,std::string identity,int expectedStride){
 need(identity.size()==64&&identity.find_first_not_of("0123456789abcdef")==std::string::npos,"identity syntax");checkpoint::Sha256 sha;sha.update(bytes.first(bytes.size()-32));auto digest=sha.finish();need(std::equal(digest.begin(),digest.end(),bytes.end()-32),"checksum mismatch");Reader in{bytes.first(bytes.size()-32)};auto magic=in.data(8);need(std::string(magic.begin(),magic.end())=="RFMSH002","magic");need(in.integer<std::uint32_t>()==2,"schema");need(in.integer<std::uint32_t>()==bytes.size(),"declared length");
 Decoded out;out.width=in.integer<std::uint32_t>();out.height=in.integer<std::uint32_t>();out.cameras=in.integer<std::uint32_t>();auto nv=in.integer<std::uint32_t>(),nf=in.integer<std::uint32_t>(),np=in.integer<std::uint32_t>(),ns=in.integer<std::uint32_t>();need(out.width>=32&&out.width<=640&&out.height>=32&&out.height<=640&&out.width*out.height<=320000&&out.cameras>=2&&out.cameras<=MAX_DENSE_VIEWS,"dimensions/cameras");need(nv>0&&nv<=40000&&nf>=8&&nf<=80000&&np>0&&np<=50000&&ns>0&&ns<=MAX_DENSE_SAMPLES,"counts");auto id=in.data(64);need(std::string(id.begin(),id.end())==identity,"depth identity mismatch");out.geometry=in.digest();auto algorithm=in.digest();out.maps=in.digest();auto& s=out.settings;auto& m=out.mesh;
 s.minimumConfidence=in.single();s.maximumRelativeDepthJump=in.real();s.maximumEdgePixelFactor=in.real();s.maxVertices=in.integer<std::uint32_t>();s.maxTriangles=in.integer<std::uint32_t>();s.pixelStride=in.integer<std::uint32_t>();
 auto sampled=in.integer<std::uint64_t>(),photometric=in.integer<std::uint64_t>(),consistent=in.integer<std::uint64_t>();confidence(in.real());auto budget=in.integer<std::uint32_t>();m.inputSamples=in.integer<std::uint64_t>();m.acceptedSamples=in.integer<std::uint64_t>();m.rejectedCells=in.integer<std::uint64_t>();m.rejectedTriangles=in.integer<std::uint64_t>();m.fusedObservations=in.integer<std::uint64_t>();m.medianConfidence=in.single();confidence(m.medianConfidence);
 need(sampled<=MAX_DENSE_SAMPLES&&photometric<=sampled&&consistent<=photometric&&np<=consistent&&ns<=consistent&&budget>=10&&budget<=50000&&np<=budget&&m.inputSamples==ns&&m.acceptedSamples<=ns&&m.fusedObservations<=m.acceptedSamples&&m.rejectedCells<=MAX_DENSE_SAMPLES&&m.rejectedTriangles<=2*MAX_DENSE_SAMPLES,"evidence counts");
 std::size_t total=0;for(std::size_t cam=0;cam<out.cameras;++cam){auto count=in.integer<std::uint32_t>();need(count>=1&&count<=3,"source count");std::set<std::uint32_t> source;for(std::size_t j=0;j<count;++j){auto c=in.integer<std::uint32_t>();need(c<out.cameras&&c!=cam&&source.insert(c).second,"source camera");}auto n=in.integer<std::uint64_t>();need(n<=consistent,"per-view count");total+=n;}need(total==consistent,"per-view sum");
 need(std::size_t(np)*32+std::size_t(ns)*66+std::size_t(nv)*56+std::size_t(nf)*13<=in.bytes.size()-in.offset,"array extent");
 auto memory=std::size_t(np)*sizeof(DensePoint)+std::size_t(ns)*sizeof(VerifiedDepthSample)+std::size_t(nv)*sizeof(SurfaceVertex)+std::size_t(nf)*sizeof(SurfaceTriangle);need(memory<10*1024*1024,"native memory bound");
 for(std::size_t i=0;i<np;++i){in.vector();in.color();confidence(in.single());auto n=in.integer<std::uint8_t>();need(n>=2&&n<=out.cameras,"point views");}
 for(std::size_t i=0;i<ns;++i){VerifiedDepthSample p;p.xyz=in.vector();p.color=in.color();p.confidence=in.single();confidence(p.confidence);p.supportingViews=in.integer<std::uint8_t>();p.camera=in.integer<std::uint8_t>();p.x=in.integer<std::uint16_t>();p.y=in.integer<std::uint16_t>();p.worldNormal=in.vector();p.normalConfidence=in.single();confidence(p.normalConfidence);p.normalSupportingViews=in.integer<std::uint8_t>();need(p.supportingViews>=2&&p.supportingViews<=out.cameras&&p.camera<out.cameras&&p.x<out.width&&p.y<out.height&&p.normalSupportingViews<=out.cameras,"sample provenance");out.samples.push_back(p);}
 for(std::size_t i=0;i<nv;++i){SurfaceVertex v;v.xyz=in.vector();v.normal=in.vector();v.color=in.color();v.confidence=in.single();confidence(v.confidence);v.supportingViews=in.integer<std::uint8_t>();need(v.supportingViews>=1&&v.supportingViews<=out.cameras&&v.normal.x*v.normal.x+v.normal.y*v.normal.y+v.normal.z*v.normal.z<=1.01,"vertex support/normal");m.vertices.push_back(v);}
 out.triangleOffset=in.offset;
 for(std::size_t i=0;i<nf;++i){SurfaceTriangle t;t.a=in.integer<std::uint32_t>();t.b=in.integer<std::uint32_t>();t.c=in.integer<std::uint32_t>();t.sourceCamera=in.integer<std::uint8_t>();need(t.a<nv&&t.b<nv&&t.c<nv&&t.a!=t.b&&t.a!=t.c&&t.b!=t.c&&t.sourceCamera<out.cameras,"face provenance/index");m.triangles.push_back(t);}
 auto no=in.integer<std::uint32_t>();need(no<=ns&&std::size_t(no)*17<=in.bytes.size()-in.offset&&memory+std::size_t(no)*sizeof(SurfaceObservationRef)<10*1024*1024,"observation bound");
 for(std::size_t i=0;i<no;++i){SurfaceObservationRef o;o.vertex=in.integer<std::uint32_t>();o.camera=in.integer<std::uint8_t>();o.x=in.integer<std::uint16_t>();o.y=in.integer<std::uint16_t>();o.sourceSampleId=in.integer<std::uint64_t>();need(o.vertex<nv&&o.camera<out.cameras&&o.sourceSampleId<ns,"observation index");auto& sample=out.samples[o.sourceSampleId];need(o.camera==sample.camera&&o.x==sample.x&&o.y==sample.y,"observation sample mismatch");m.observations.push_back(o);}
 m.topology.boundaryEdges=in.integer<std::uint64_t>();m.topology.components=in.integer<std::uint64_t>();m.topology.nonmanifoldEdges=in.integer<std::uint64_t>();m.topology.intersections=in.integer<std::uint64_t>();m.topology.ambiguousPairs=in.integer<std::uint64_t>();m.topology.removedExactDefects=in.integer<std::uint64_t>();auto incomplete=in.integer<std::uint8_t>();need(incomplete<=1,"incomplete flag");m.topology.intersectionBudgetExceeded=incomplete;s.maxVoxelOccupancy=in.integer<std::uint32_t>();s.maxFusionCandidates=in.integer<std::uint32_t>();for(int i=0;i<15;++i)in.integer<std::uint64_t>();need(in.offset==in.bytes.size(),"trailing fields");need(nv<=s.maxVertices&&nf<=s.maxTriangles,"settings limits");SurfaceSettings expected;expected.pixelStride=expectedStride;
 need(expectedStride>=2&&expectedStride<=8,"expected stride bound");
 need(s.minimumConfidence==expected.minimumConfidence&&s.maximumRelativeDepthJump==expected.maximumRelativeDepthJump&&s.maximumEdgePixelFactor==expected.maximumEdgePixelFactor&&s.maxVertices==expected.maxVertices&&s.maxTriangles==expected.maxTriangles&&s.pixelStride==expected.pixelStride&&s.maxVoxelOccupancy==expected.maxVoxelOccupancy&&s.maxFusionCandidates==expected.maxFusionCandidates,"baseline settings mismatch");
 need(algorithm==surface_checkpoint::algorithmIdentity(expected),"algorithm mismatch");
 std::set<std::size_t> sampleIds;std::set<std::tuple<unsigned,unsigned,unsigned>> samplePixels;for(auto& sample:out.samples)need(samplePixels.emplace(sample.camera,sample.x,sample.y).second,"duplicate sample pixel");
 std::vector<std::set<unsigned>> vertexCameras(nv);for(auto& o:m.observations){need(sampleIds.insert(o.sourceSampleId).second,"duplicate observation sample");need(vertexCameras[o.vertex].insert(o.camera).second,"duplicate vertex camera");}
 need(no<=m.acceptedSamples,"observation accounting");for(std::size_t i=0;i<nv;++i)need(vertexCameras[i].size()==m.vertices[i].supportingViews,"vertex observation support mismatch");
 for(auto& t:m.triangles)for(auto v:{t.a,t.b,t.c})need(vertexCameras[v].contains(t.sourceCamera),"face source not observed");
 return out;
}
}
int main(int argc,char**argv){try{
 need(argc==7,"usage: decode serialize|classify checkpoint identity expected-stride fresh-output-dir ply-or-dash");
 std::string mode=argv[1];need(mode=="serialize"||mode=="classify","mode");
 auto bytes=read(argv[2]);auto d=decode(bytes,argv[3],std::stoi(argv[4]));std::filesystem::path out=argv[5];need(!std::filesystem::exists(out),"output exists");need(std::filesystem::create_directory(out),"output mkdir");
 rf::writeSurfacePly(d.mesh,(out/"serialized.ply").string());std::ofstream(out/"serialized-mesh.json")<<rf::surfaceJson(d.mesh);
 if(mode=="serialize"){need(std::string(argv[6])=="-","unexpected PLY");std::cout<<"SERIALIZED_NOT_YET_BOUND\n";return 0;}
 // Equality is checked here as well as by the wrapper before any labels are interpreted.
 auto ply=read(argv[6]);auto regenerated=read(out/"serialized.ply");need(ply==regenerated,"original native PLY serialization differs");
 std::istringstream input(std::string(ply.begin(),ply.end()));std::string line;while(std::getline(input,line)&&line!="end_header"){}need(line=="end_header","PLY header");
 for(auto& v:d.mesh.vertices){need(bool(std::getline(input,line)),"PLY vertex");std::istringstream row(line);float x,y,z,nx,ny,nz;int r,g,b;need(bool(row>>x>>y>>z>>nx>>ny>>nz>>r>>g>>b),"PLY fields");v.xyz={x,y,z};}
 // Same declared-float parse as the original 6176-crossing exported-coordinate diagnostic.
 auto report=rf::analyzeMeshTopology(d.mesh);need(!report.intersectionBudgetExceeded,"intersection bound");need(report.intersections.size()==d.mesh.topology.intersections,"checkpoint intersection mismatch");
 std::map<std::string,std::size_t> classes;std::vector<std::set<unsigned>> observationCameras(d.mesh.vertices.size());for(auto&o:d.mesh.observations)observationCameras[o.vertex].insert(o.camera);
 for(auto [i,j]:report.intersections){auto a=d.mesh.triangles[i],b=d.mesh.triangles[j];int shared=0,fused=0;for(auto x:{a.a,a.b,a.c}){fused+=observationCameras[x].size()>1;for(auto y:{b.a,b.b,b.c})shared+=x==y;}for(auto y:{b.a,b.b,b.c})fused+=observationCameras[y].size()>1;std::string k=(a.sourceCamera==b.sourceCamera?"within":"between");k+="/camera"+std::to_string(std::min(a.sourceCamera,b.sourceCamera))+"-"+std::to_string(std::max(a.sourceCamera,b.sourceCamera))+"/shared"+std::to_string(shared)+"/fusedCorners"+std::to_string(fused);++classes[k];}
 std::map<unsigned,std::size_t> supportHistogram,normalSupportHistogram;for(auto& c:observationCameras)++supportHistogram[c.size()];for(auto& sample:d.samples)++normalSupportHistogram[sample.normalSupportingViews];
 std::ofstream trace(out/"trace.json");trace<<"{\"schema\":2,\"triangleOffset\":"<<d.triangleOffset<<",\"cameraCount\":"<<d.cameras<<",\"intersections\":"<<report.intersections.size()<<",\"ambiguousPairs\":"<<report.ambiguousPairs.size()<<",\"intersectionCandidates\":"<<report.intersectionCandidates<<",\"classes\":{";bool first=true;for(auto&[k,n]:classes){if(!first)trace<<',';first=false;trace<<'"'<<k<<"\":"<<n;}
 for(auto entry:{std::pair{"vertexObservationCameraHistogram",supportHistogram},std::pair{"sampleNormalSupportingViewsHistogram",normalSupportHistogram}}){trace<<"},\""<<entry.first<<"\":{";first=true;for(auto&[k,n]:entry.second){if(!first)trace<<',';first=false;trace<<'"'<<k<<"\":"<<n;}}
 trace<<"},\"sourceCamera\":[";first=true;for(auto&t:d.mesh.triangles){if(!first)trace<<',';first=false;trace<<unsigned(t.sourceCamera);}trace<<"],\"observations\":[";first=true;for(auto&o:d.mesh.observations){if(!first)trace<<',';first=false;trace<<'['<<o.vertex<<','<<unsigned(o.camera)<<','<<o.x<<','<<o.y<<','<<o.sourceSampleId<<']';}trace<<"],\"intersectionPairs\":[";first=true;for(auto[i,j]:report.intersections){if(!first)trace<<',';first=false;trace<<'['<<i<<','<<j<<']';}trace<<"]}\n";trace.flush();need(bool(trace),"trace write");std::cout<<"DIAGNOSTIC_DECODE_PASS_NOT_NATIVE_CACHE_LOAD\n";
 }catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
