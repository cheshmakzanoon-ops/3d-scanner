// Read-only exact native face eligibility; intersection budget ZERO, no crossings classified.
#include "snapshot.hpp"
#include "realityforge/mesh_topology.hpp"
#include <iostream>
#include <sstream>
namespace {std::string hexDigest(const rf::checkpoint::Digest& digest){static constexpr char digits[]="0123456789abcdef";std::string out;for(auto b:digest){out.push_back(digits[b>>4]);out.push_back(digits[b&15]);}return out;}}
int main(int argc,char** argv){try{
 using namespace rf;using namespace rf::trace_test;require(argc==3,"usage topology_filter mesh.bits fresh-output.json");
 const auto before=secureRead(argv[1]);Reader in{before};const auto magic=in.data(8);require(std::string(magic.begin(),magic.end())=="RFMESH01","raw mesh schema");
 const auto nv=in.count(40000),nf=in.count(80000),no=in.count(MAX_DENSE_SAMPLES);SurfaceMesh m;
 m.inputSamples=in.integer<std::uint64_t>();m.acceptedSamples=in.integer<std::uint64_t>();m.rejectedCells=in.integer<std::uint64_t>();m.rejectedTriangles=in.integer<std::uint64_t>();m.fusedObservations=in.integer<std::uint64_t>();m.medianConfidence=in.single();
 require(m.inputSamples<=MAX_DENSE_SAMPLES&&m.acceptedSamples<=m.inputSamples&&m.fusedObservations<=m.acceptedSamples&&m.medianConfidence>=0&&m.medianConfidence<=1,"raw mesh summaries");
 for(std::size_t i=0;i<nv;++i){SurfaceVertex v;v.xyz=in.vector();v.normal=in.vector();v.color=in.color();v.confidence=in.single();v.supportingViews=in.integer<std::uint8_t>();require(v.confidence>=0&&v.confidence<=1&&v.supportingViews>0&&v.supportingViews<=MAX_DENSE_VIEWS,"raw vertex");m.vertices.push_back(v);}
 for(std::size_t i=0;i<nf;++i){SurfaceTriangle t;t.a=in.integer<std::uint32_t>();t.b=in.integer<std::uint32_t>();t.c=in.integer<std::uint32_t>();t.sourceCamera=in.integer<std::uint8_t>();require(t.a<nv&&t.b<nv&&t.c<nv&&t.sourceCamera<MAX_DENSE_VIEWS,"raw triangle");m.triangles.push_back(t);}
 for(std::size_t i=0;i<no;++i){SurfaceObservationRef r;r.vertex=in.integer<std::uint32_t>();r.camera=in.integer<std::uint8_t>();r.x=in.integer<std::uint16_t>();r.y=in.integer<std::uint16_t>();r.sourceSampleId=in.integer<std::uint64_t>();require(r.vertex<nv&&r.camera<MAX_DENSE_VIEWS&&r.sourceSampleId<m.inputSamples,"raw observation");m.observations.push_back(r);}
 m.topology.boundaryEdges=in.integer<std::uint64_t>();m.topology.components=in.integer<std::uint64_t>();m.topology.nonmanifoldEdges=in.integer<std::uint64_t>();m.topology.intersections=in.integer<std::uint64_t>();m.topology.ambiguousPairs=in.integer<std::uint64_t>();m.topology.removedExactDefects=in.integer<std::uint64_t>();const auto incomplete=in.integer<std::uint8_t>();require(incomplete<=1,"raw budget flag");m.topology.intersectionBudgetExceeded=incomplete;
 require(in.offset==before.size()&&meshBits(m)==before,"raw mesh exact logical roundtrip");
 MeshTopologySettings settings;settings.maxIntersectionCandidates=0;const auto report=analyzeMeshTopology(m,settings);
 require(report.intersections.empty()&&report.ambiguousPairs.empty(),"zero-budget classified intersections unexpectedly");
 require(report.boundaryEdges==m.topology.boundaryEdges&&report.nonmanifoldEdges==m.topology.nonmanifoldEdges&&report.components==m.topology.components,"original native edge/component parity");
 std::ostringstream out;out<<"{\"schema\":1,\"meshBitsSha256\":\""<<hexDigest(hash(before))<<"\",\"scope\":\"EXACT_NATIVE_FACE_ELIGIBILITY_AND_EDGE_TOTALS_ONLY\",\"crossingsNotComputed\":true,\"maxIntersectionCandidates\":0,\"vertexCount\":"<<nv<<",\"triangleCount\":"<<nf<<",\"boundaryEdges\":"<<report.boundaryEdges<<",\"nonmanifoldEdges\":"<<report.nonmanifoldEdges<<",\"components\":"<<report.components<<",\"excludedNativeDegenerateFaces\":[";
 for(std::size_t i=0;i<report.degenerateFaces.size();++i){if(i)out<<',';out<<report.degenerateFaces[i];}out<<"]}\n";const auto text=out.str();
 require(before==secureRead(argv[1])&&meshBits(m)==before,"raw mesh input changed");secureWriteFresh(argv[2],std::span(reinterpret_cast<const std::uint8_t*>(text.data()),text.size()),SNAPSHOT_MAX);
 std::cout<<"READ_ONLY_NATIVE_EDGE_ELIGIBILITY_READY_NO_CROSSING_ANALYSIS\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
