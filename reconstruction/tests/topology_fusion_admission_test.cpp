// Synthetic source-grid topology, not measured-object quality or overlap repair.
#include "realityforge/surface.hpp"
#include "realityforge/mesh_topology.hpp"
#include "realityforge/cancellation.hpp"
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
// Test-only inline inspector copy with deterministic checkpoint routing. Rename
// its namespace to avoid changing/interposing the real library inspector's ODR.
namespace rf::cancellation {
inline std::size_t topologyTestCalls{},topologyTestAbortAt{};
inline std::atomic_bool* topologyTestStop{};
inline void topologyTestCheckpoint(){
    ++topologyTestCalls;
    if(topologyTestStop&&topologyTestCalls==topologyTestAbortAt)topologyTestStop->store(true);
    checkpoint();
}
}
#define checkpoint topologyTestCheckpoint
#define surface_weld_topology topology_probe
#include "realityforge/surface_weld_topology.hpp"
#undef surface_weld_topology
#undef checkpoint
#endif
#include <algorithm>
#include <atomic>
#include <cmath>
#include <iostream>
#include <set>
#include <stdexcept>
#include <tuple>

namespace {
void check(bool value,const char* message){if(!value)throw std::runtime_error(message);}
enum class Case { SharedEdge, Bowtie, ManifoldSeam, DuplicatePatch };
struct Fixture {std::vector<rf::DenseView> views;rf::DenseResult dense;};
Fixture fixture(Case kind){
    Fixture f;f.views.resize(2);f.dense.maps.resize(2);
    for(int camera=0;camera<2;++camera){
        auto& v=f.views[camera];v.width=v.height=32;v.fx=kind==Case::DuplicatePatch?100:(camera?200:100);
        v.fy=100;v.cx=v.cy=15;v.pose.rotation=rf::identity();
        // Unsafe welds move the centroid. Repair must restore these measured
        // positions, not merely clone the already-averaged coordinate.
        v.pose.translation.x=camera&&(kind==Case::SharedEdge||kind==Case::Bowtie)?-.01:0;
        auto& map=f.dense.maps[camera];map.width=map.height=32;map.depth.assign(1024,0);map.confidence.assign(1024,0);
        // Complete measured normal neighborhoods; no partial-normal policy used.
        for(int y=3;y<29;y+=3)for(int x=3;x<29;x+=3){map.depth[y*32+x]=5;map.confidence[y*32+x]=1;}
        for(int y=12;y<=18;y+=3)for(int x=12;x<=18;x+=3){
            if(kind==Case::ManifoldSeam&&((camera==0&&x>15)||(camera==1&&x<15)))continue;
            if(kind==Case::Bowtie&&((camera==0&&(x<15||y<15))||(camera==1&&(x>15||y>15))))continue;
            f.dense.verifiedSamples.push_back({{(x-15)*5/v.fx-v.pose.translation.x,(y-15)*5/v.fy,5},
                {150,140,130},1,2,static_cast<std::uint8_t>(camera),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
        }
    }
    return f;
}
void manifold(const rf::SurfaceMesh& mesh){
    auto report=rf::analyzeMeshTopology(mesh);
    check(report.nonmanifoldEdges==0,"Unsafe shared edge still has more than two incident faces");
    check(report.nonmanifoldVertices==0,"Unsafe vertex link still contains disconnected source fans");
    check(report.degenerateFaces.empty()&&report.duplicateFaces.empty()&&report.windingConflictFaces.empty(),"Repair introduced a face defect");
}
void provenance(const Fixture& f,const rf::SurfaceMesh& mesh,bool restored){
    check(mesh.inputSamples==f.dense.verifiedSamples.size()&&mesh.acceptedSamples==mesh.inputSamples,"Repair dropped admitted source samples");
    check(mesh.observations.size()==mesh.inputSamples&&mesh.unattachedObservations.empty(),"Repair lost source observation attachment");
    std::set<std::size_t> seen;
    for(const auto& o:mesh.observations){
        check(o.sourceSampleId<f.dense.verifiedSamples.size()&&o.vertex<mesh.vertices.size(),"Repair corrupted source/vertex indices");
        const auto& source=f.dense.verifiedSamples[o.sourceSampleId];
        check(source.camera==o.camera&&source.x==o.x&&source.y==o.y&&seen.insert(o.sourceSampleId).second,"Repair duplicated or remapped original observation identity");
        if(restored){
            const auto p=mesh.vertices[o.vertex].xyz;
            check(p.x==source.xyz.x&&p.y==source.xyz.y&&p.z==source.xyz.z,"Rollback copied averaged coordinates instead of original measurement");
            check(o.relation==rf::SurfaceObservationRelation::Accumulator,"Rollback fabricated exact-duplicate provenance");
        }
    }
}
using Point=std::array<double,3>;
using Face=std::array<Point,3>;
std::multiset<Face> geometry(const rf::SurfaceMesh& mesh){
    std::multiset<Face> result;
    for(auto t:mesh.triangles){Face f;int k=0;for(auto id:{t.a,t.b,t.c}){auto p=mesh.vertices[id].xyz;f[k++]={p.x,p.y,p.z};}std::sort(f.begin(),f.end());result.insert(f);}return result;
}
}
int main(){
    for(auto kind:{Case::SharedEdge,Case::Bowtie}){
        auto f=fixture(kind);const auto mesh=rf::fuseDepthSurfaces(f.views,f.dense);
        const auto expectedFaces=kind==Case::SharedEdge?16u:4u;
        check(mesh.triangles.size()==expectedFaces,"Topology admission deleted supported source faces to hide defects");
        check(mesh.fusedObservations==0&&mesh.vertices.size()==f.dense.verifiedSamples.size(),"Conflicting source stars were not unwelded");
        manifold(mesh);provenance(f,mesh,true);
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
        const auto& admission=mesh.lossDiagnostics;
        const auto groups=kind==Case::SharedEdge?3u:1u;
        check(admission.topologyAdmissionPasses==2&&admission.proposedWeldGroups==groups&&
              admission.rolledBackWeldGroups==groups&&admission.restoredSourceVertices==2*groups&&
              admission.unresolvedSourceVertexLinks==0,"Repair pass/rollback diagnostics do not match measured source groups");
#endif
        std::array<std::size_t,2> faces{};for(auto face:mesh.triangles)++faces[face.sourceCamera];
        check(faces[0]==expectedFaces/2&&faces[1]==expectedFaces/2,"Repair selected one camera sheet and deleted another");
        // Input traversal may not select a different repair or mutate source IDs.
        auto reversed=f;std::reverse(reversed.dense.verifiedSamples.begin(),reversed.dense.verifiedSamples.end());
        auto reordered=rf::fuseDepthSurfaces(reversed.views,reversed.dense);
        manifold(reordered);provenance(reversed,reordered,true);
        check(rf::surfaceJson(mesh)==rf::surfaceJson(reordered),"Input order changed repaired output/counters");
        check(rf::surfaceJson(mesh)==rf::surfaceJson(rf::fuseDepthSurfaces(f.views,f.dense)),"Repeated repair is nondeterministic");
        // Camera renumbering changes source ordinals, not retained measured faces.
        auto swapped=f;std::swap(swapped.views[0],swapped.views[1]);std::swap(swapped.dense.maps[0],swapped.dense.maps[1]);
        for(auto& o:swapped.dense.verifiedSamples)o.camera=1-o.camera;
        auto renumbered=rf::fuseDepthSurfaces(swapped.views,swapped.dense);
        manifold(renumbered);provenance(swapped,renumbered,true);
        check(geometry(mesh)==geometry(renumbered),"Camera order changed measured repaired geometry");
    }
    for(auto kind:{Case::ManifoldSeam,Case::DuplicatePatch}){
        auto f=fixture(kind);auto mesh=rf::fuseDepthSurfaces(f.views,f.dense);
        check(mesh.fusedObservations==(kind==Case::ManifoldSeam?3u:9u),"Safe manifold weld was rejected");
        check(mesh.vertices.size()==9&&mesh.triangles.size()==8,"Safe seam/duplicate patch geometry changed");
        manifold(mesh);provenance(f,mesh,false);
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
        check(mesh.lossDiagnostics.topologyAdmissionPasses==1&&mesh.lossDiagnostics.rolledBackWeldGroups==0&&
              mesh.lossDiagnostics.restoredSourceVertices==0&&mesh.lossDiagnostics.unresolvedSourceVertexLinks==0,
              "Safe patch reported repair/unknown source defects");
#endif
    }
    // Large fully measured adjacent patches: the retained-face checker must
    // fit the unchanged scratch budget without rejecting a legitimate seam.
    // Intersection-budget exhaustion remains explicit, not a clean certificate.
    Fixture large;large.views.resize(2);large.dense.maps.resize(2);
    constexpr int width=384,height=256,endX=189;
    for(int camera=0;camera<2;++camera){
        auto& v=large.views[camera];v.width=width;v.height=height;v.fx=v.fy=100;
        v.pose.rotation=rf::identity();v.pose.translation.x=camera?-(endX-3)*.05:0;
        auto& map=large.dense.maps[camera];map.width=width;map.height=height;
        map.depth.assign(width*height,5);map.confidence.assign(width*height,1);
        for(int y=3;y<=249;y+=3)for(int x=3;x<=endX;x+=3)
            large.dense.verifiedSamples.push_back({{x*.05-v.pose.translation.x,y*.05,5},
                {150,140,130},1,2,static_cast<std::uint8_t>(camera),
                static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
    }
    const auto largeMesh=rf::fuseDepthSurfaces(large.views,large.dense);
    check(largeMesh.inputSamples==10458&&largeMesh.acceptedSamples==10458&&
          largeMesh.fusedObservations==83&&largeMesh.vertices.size()==10375&&
          largeMesh.triangles.size()==20336,"Large safe seam changed measured geometry or exceeded unchanged scratch budget");
    provenance(large,largeMesh,false);
    for(const auto& o:largeMesh.observations){
        const auto& original=large.dense.verifiedSamples[o.sourceSampleId].xyz;
        const auto actual=largeMesh.vertices[o.vertex].xyz;
        check(std::abs(actual.x-original.x)<1e-13&&std::abs(actual.y-original.y)<1e-13&&std::abs(actual.z-original.z)<1e-13,
              "Large safe seam introduced nonmeasured geometry");
    }
    check(largeMesh.topology.nonmanifoldEdges==0&&largeMesh.topology.intersectionBudgetExceeded,
          "Large seam topology certificate concealed existing intersection budget limit");
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
    check(largeMesh.lossDiagnostics.topologyAdmissionPasses==1&&
          largeMesh.lossDiagnostics.proposedWeldGroups==83&&
          largeMesh.lossDiagnostics.rolledBackWeldGroups==0&&
          largeMesh.lossDiagnostics.restoredSourceVertices==0&&
          largeMesh.lossDiagnostics.unresolvedSourceVertexLinks==0,
          "Large safe seam triggered unnecessary topology rollback");
#endif
    // A pre-existing single-camera source bowtie is not repaired by deleting
    // faces or blamed on fusion. If returned, it must be explicitly reported.
    auto sourceBowtie=fixture(Case::DuplicatePatch);
    auto& samples=sourceBowtie.dense.verifiedSamples;
    samples.erase(std::remove_if(samples.begin(),samples.end(),[](const auto& o){
        return o.camera!=0||!((o.x==15&&o.y==15)||(o.x==18&&(o.y==15||o.y==18))||(o.x==12&&(o.y==12||o.y==15)));
    }),samples.end());
    try {
        const auto sourceMesh=rf::fuseDepthSurfaces(sourceBowtie.views,sourceBowtie.dense);
        check(sourceMesh.triangles.size()==2&&sourceMesh.fusedObservations==0,"Source defect was hidden by face deletion or invented fusion");
        provenance(sourceBowtie,sourceMesh,true);
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
        check(sourceMesh.lossDiagnostics.unresolvedSourceVertexLinks==1,"Unwelded source bowtie was silently certified");
#endif
    } catch(const rf::Error&) { /* Explicit refusal is also an honest result. */ }
    // A legitimate isolated weld on a different vertex of a source bowtie's
    // face must not be rejected merely for adjacency to that source defect.
    auto adjacent=sourceBowtie;
    auto extra=*std::find_if(samples.begin(),samples.end(),[](const auto& o){return o.x==18&&o.y==15;});
    extra.camera=1;adjacent.dense.verifiedSamples.push_back(extra);
    auto adjacentMesh=rf::fuseDepthSurfaces(adjacent.views,adjacent.dense);
    check(adjacentMesh.fusedObservations==1&&adjacentMesh.triangles.size()==2,"Unrelated source defect discarded adjacent safe weld");
    provenance(adjacent,adjacentMesh,false);
#ifndef RF_TOPOLOGY_ADMISSION_BASELINE
    check(adjacentMesh.lossDiagnostics.rolledBackWeldGroups==0&&adjacentMesh.lossDiagnostics.unresolvedSourceVertexLinks==1,
          "Adjacent safe weld was blamed for original source bowtie");
    // Direct combinatorial admission rejects hostile retained-face lists and
    // impossible source-star degree without a dynamic adjacency allocation.
    rf::SurfaceMesh small;small.vertices.resize(4);small.triangles={{0,1,2,0},{0,2,3,0}};
    const auto fails=[&](const rf::SurfaceMesh& m,const std::vector<std::size_t>& retained){
        bool failed=false;try{(void)rf::topology_probe::inspect(m,retained);}catch(const rf::Error&){failed=true;}
        check(failed,"Malformed or unbounded topology helper input was accepted");
    };
    fails(small,{0,0});fails(small,{1,0});fails(small,{2});fails(small,{0,1,1});
    auto badIndex=small;badIndex.triangles[0].a=4;fails(badIndex,{0});
    auto repeated=small;repeated.triangles[0].b=0;fails(repeated,{0});
    rf::SurfaceMesh crowded;crowded.vertices.resize(131);std::vector<std::size_t> retained;
    for(std::size_t i=0;i<65;++i){crowded.triangles.push_back({0,static_cast<std::uint32_t>(2*i+1),static_cast<std::uint32_t>(2*i+2),0});retained.push_back(i);}
    fails(crowded,retained); //65incident faces,130distinctlinkneighbors exceed fixed64/128caps.
    crowded.triangles.pop_back();retained.pop_back();
    const auto maximumStar=rf::topology_probe::inspect(crowded,retained);
    check(maximumStar.invalidVertexCount==1&&maximumStar.invalidLinks[0],"Bounded64face/128neighbor star was not diagnosed correctly");
    auto tooManyVertices=small;tooManyVertices.vertices.resize(40001);fails(tooManyVertices,{});
    auto tooManyFaces=small;tooManyFaces.triangles.resize(80001);fails(tooManyFaces,{});
    const std::vector<std::size_t> both{0,1};const auto valid=rf::topology_probe::inspect(small,both);
    check(valid.invalidVertexCount==0,"Valid two-face source star was rejected");
    const std::vector<std::size_t> second{1},first{0},none;
    const auto complement=rf::topology_probe::inspect(small,second,true);
    check(complement.invalidLinks==rf::topology_probe::inspect(small,first).invalidLinks,
          "Excluded-face production path differs from exact retained subset");
    check(rf::topology_probe::inspect(small,none,true).invalidLinks==valid.invalidLinks&&
          rf::topology_probe::inspect(small,both,true).invalidVertexCount==0,
          "Excluded-face all/empty coverage differs");
    for(const auto& excluded:std::vector<std::vector<std::size_t>>{{0,0},{1,0},{2}}){
        bool failed=false;try{(void)rf::topology_probe::inspect(small,excluded,true);}catch(const rf::Error&){failed=true;}
        check(failed,"Malformed excluded-face production index was accepted");
    }
    rf::SurfaceMesh many;many.vertices.resize(390);std::vector<std::size_t> all;
    for(std::uint32_t i=0;i<130;++i){many.triangles.push_back({3*i,3*i+1,3*i+2,0});all.push_back(i);}
    std::atomic_bool during{false};
    {rf::cancellation::Scope scope(&during);
        rf::cancellation::topologyTestCalls=0;rf::cancellation::topologyTestAbortAt=3;rf::cancellation::topologyTestStop=&during;
        bool cancelled=false;try{(void)rf::topology_probe::inspect(many,all);}catch(const rf::cancellation::Cancelled&){cancelled=true;}
        rf::cancellation::topologyTestStop=nullptr;
        check(cancelled&&rf::cancellation::topologyTestCalls==3,"Inline inspector failed deterministic in-flight incidence cancellation");
    }
    check(many.triangles.size()==130&&many.vertices.size()==390,"Cancelled inspection mutated source topology");
#endif
    // Initial unsafe topology fits15vertices; complete rollback needs18. Do not
    // silently overflow the old cap or discard observations to make rollback fit.
    auto limited=fixture(Case::SharedEdge);rf::SurfaceSettings settings;settings.maxVertices=16;
    bool refused=false;try{(void)rf::fuseDepthSurfaces(limited.views,limited.dense,settings);}catch(const rf::Error&){refused=true;}
    check(refused,"Topology rollback exceeded existing maxVertices instead of failing closed");
    std::atomic_bool stop{true};{rf::cancellation::Scope scope(&stop);bool cancelled=false;
        try{(void)rf::fuseDepthSurfaces(limited.views,limited.dense);}catch(const rf::cancellation::Cancelled&){cancelled=true;}
        check(cancelled,"Topology repair ignored operation cancellation");}
    std::cout<<"Topology-safe measured fusion: shared-edge/bowtie rollback, original provenance, safe welds, determinism and bounds passed\n";
}
