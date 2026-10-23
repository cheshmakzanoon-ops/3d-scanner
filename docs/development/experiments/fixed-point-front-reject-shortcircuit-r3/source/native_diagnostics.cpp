#include "realityforge/mesh_topology.hpp"
#include <bit>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <sys/resource.h>
namespace fs=std::filesystem;
void need(bool b,const char* m){if(!b)throw std::runtime_error(m);}
struct Bits {
 std::ofstream f;std::size_t size=0;explicit Bits(const fs::path& p):f(p,std::ios::binary){need(bool(f),"output open");}
 void integer(std::uint64_t n,int bytes=8){need(size+bytes<=16777216,"binary budget");for(int i=0;i<bytes;++i)f.put(static_cast<char>(n>>(8*i)));size+=bytes;need(bool(f),"binary write");}
 void value(double x){integer(std::bit_cast<std::uint64_t>(x));}void value(float x){integer(std::bit_cast<std::uint32_t>(x),4);}
 void vec(rf::Vec3 p){value(p.x);value(p.y);value(p.z);}
};
void output(const fs::path& dir,const rf::SurfaceMesh& mesh){
 need(mesh.vertices.size()<=40000&&mesh.triangles.size()<=80000&&mesh.observations.size()+mesh.unattachedObservations.size()<=35112,"mesh cardinality cap");Bits b(dir/"mesh.bits");b.integer(mesh.vertices.size());
 for(const auto& v:mesh.vertices){b.vec(v.xyz);b.vec(v.normal);for(auto c:v.color)b.integer(c,1);b.value(v.confidence);b.integer(v.supportingViews,1);}
 b.integer(mesh.triangles.size());for(const auto& t:mesh.triangles){b.integer(t.a,4);b.integer(t.b,4);b.integer(t.c,4);b.integer(t.sourceCamera,1);}
 for(auto n:{mesh.inputSamples,mesh.acceptedSamples,mesh.rejectedCells,mesh.rejectedTriangles,mesh.fusedObservations})b.integer(n);b.value(mesh.medianConfidence);
 b.integer(mesh.observations.size());for(const auto& o:mesh.observations){b.integer(o.vertex,4);b.integer(o.camera,1);b.integer(o.x,2);b.integer(o.y,2);b.integer(o.sourceSampleId);b.integer(static_cast<int>(o.relation),1);}
 b.integer(mesh.unattachedObservations.size());for(const auto& o:mesh.unattachedObservations){b.integer(o.camera,1);b.integer(o.x,2);b.integer(o.y,2);b.integer(o.sourceSampleId);b.integer(static_cast<int>(o.reason),1);}
 const auto& t=mesh.topology;for(auto n:{t.boundaryEdges,t.components,t.nonmanifoldEdges,t.intersections,t.ambiguousPairs,t.removedExactDefects})b.integer(n);b.integer(t.intersectionBudgetExceeded,1);
 std::ofstream json(dir/"mesh.json");json<<rf::surfaceJson(mesh)<<'\n';need(bool(json),"JSON write");if(!mesh.triangles.empty()){rf::writeSurfacePly(mesh,(dir/"mesh.ply").string());rf::writeSurfaceGlb(mesh,(dir/"mesh.glb").string());}
}

int main(int argc,char** argv){try{
 need(argc==3,"usage native_diagnostics INPUT_MESH_BITS FRESH_OUTPUT");rlimit l{};need(getrlimit(RLIMIT_AS,&l)==0&&l.rlim_cur<=1073741824,"1GiB AS required");need(getrlimit(RLIMIT_CPU,&l)==0&&l.rlim_cur<=600,"600s CPU required");fs::path dir=argv[2];need(!fs::exists(dir),"fresh output required");fs::create_directory(dir);
 std::ifstream f(argv[1],std::ios::binary);need(bool(f),"mesh input");auto integer=[&](int n=8){std::uint64_t v=0;for(int i=0;i<n;++i){int c=f.get();need(c!=EOF,"truncated input");v|=std::uint64_t(static_cast<unsigned char>(c))<<(8*i);}return v;};auto real=[&](){return std::bit_cast<double>(integer());};auto scalar=[&](){return std::bit_cast<float>(static_cast<std::uint32_t>(integer(4)));};auto vec=[&](){return rf::Vec3{real(),real(),real()};};rf::SurfaceMesh m;
 auto nv=integer();need(nv<=40000,"vertex cap");for(std::size_t i=0;i<nv;++i){rf::SurfaceVertex v;v.xyz=vec();v.normal=vec();for(auto& c:v.color)c=integer(1);v.confidence=scalar();v.supportingViews=integer(1);m.vertices.push_back(v);}
 auto nf=integer();need(nf<=80000,"face cap");for(std::size_t i=0;i<nf;++i){rf::SurfaceTriangle t;t.a=integer(4);t.b=integer(4);t.c=integer(4);t.sourceCamera=integer(1);need(t.sourceCamera==255,"generated-face sentinel");m.triangles.push_back(t);}
 m.inputSamples=integer();m.acceptedSamples=integer();m.rejectedCells=integer();m.rejectedTriangles=integer();m.fusedObservations=integer();m.medianConfidence=scalar();auto na=integer();need(na<=20000,"ledger cap");for(std::size_t i=0;i<na;++i){rf::SurfaceObservationRef o;o.vertex=integer(4);o.camera=integer(1);o.x=integer(2);o.y=integer(2);o.sourceSampleId=integer();o.relation=static_cast<rf::SurfaceObservationRelation>(integer(1));m.observations.push_back(o);}
 auto nu=integer();need(na+nu<=20000,"ledger cap");for(std::size_t i=0;i<nu;++i){rf::SurfaceUnattachedObservation o;o.camera=integer(1);o.x=integer(2);o.y=integer(2);o.sourceSampleId=integer();o.reason=static_cast<rf::SurfaceUnattachedReason>(integer(1));m.unattachedObservations.push_back(o);}for(int i=0;i<6;++i)need(integer()==0,"uncomputed topology input");need(integer(1)==0&&f.peek()==EOF,"input EOF");
 std::vector<std::uint8_t> evidence(nf,2);rf::MeshTopologySettings settings;settings.faceEvidence=evidence;const auto r=rf::analyzeMeshTopology(m,settings);
 m.topology.boundaryEdges=r.boundaryEdges;m.topology.components=r.components;m.topology.nonmanifoldEdges=r.nonmanifoldEdges;m.topology.intersections=r.intersections.size();m.topology.ambiguousPairs=r.ambiguousPairs.size();m.topology.intersectionBudgetExceeded=r.intersectionBudgetExceeded;output(dir,m);
 std::ofstream o(dir/"topology.json");o<<"{\"vertices\":"<<nv<<",\"faces\":"<<nf<<",\"degenerateFaces\":"<<r.degenerateFaces.size()<<",\"duplicateFaces\":"<<r.duplicateFaces.size()<<",\"windingConflictFaces\":"<<r.windingConflictFaces.size()<<",\"nonmanifoldEdges\":"<<r.nonmanifoldEdges<<",\"nonmanifoldVertices\":"<<r.nonmanifoldVertices<<",\"boundaryEdges\":"<<r.boundaryEdges<<",\"intersections\":"<<r.intersections.size()<<",\"ambiguousPairs\":"<<r.ambiguousPairs.size()<<",\"intersectionCandidates\":"<<r.intersectionCandidates<<",\"intersectionBudgetExceeded\":"<<(r.intersectionBudgetExceeded?"true":"false")<<",\"generatedFaces\":"<<r.generatedFaces<<"}\n";need(bool(o),"report write");
}catch(const std::exception& e){std::cerr<<"REFUSED: "<<e.what()<<'\n';return 2;}}
