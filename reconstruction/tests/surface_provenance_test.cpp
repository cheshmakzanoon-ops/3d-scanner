#include "realityforge/surface_provenance.hpp"
#include <iostream>
#include <thread>
#include <chrono>
#include <stdexcept>
namespace {
void check(bool c,const char* m){if(!c)throw std::runtime_error(m);}
void rejects(auto&& fn){bool bad=false;try{fn();}catch(const rf::Error&){bad=true;}check(bad,"invalid provenance accepted");}
rf::SurfaceMesh fixture(double displacement=0,bool reverse=false){
    rf::SurfaceMesh m;
    for(std::size_t i=0;i<6;++i){
        rf::SurfaceVertex v;v.xyz={i%3==1?1.:0.,i%3==2?1.:0.,i<3?0:displacement};
        v.confidence=i<3?.2f:.9f;v.color={static_cast<std::uint8_t>(i<3?20:240),10,40};v.supportingViews=1;m.vertices.push_back(v);
        m.observations.push_back({static_cast<std::uint32_t>(i),static_cast<std::uint8_t>(i/3),static_cast<std::uint16_t>(i%3),0,i});
    }
    m.triangles={{0,1,2,0},reverse?rf::SurfaceTriangle{3,5,4,1}:rf::SurfaceTriangle{5,3,4,1}};
    m.inputSamples=m.acceptedSamples=6;return m;
}
struct Result{rf::CleanupResult cleanup;rf::CompactedSurfaceObservations ledger;};
Result compact(const rf::SurfaceMesh& m){
    const auto p=rf::planMeshCleanup(m);auto targets=rf::exactCleanupObservationTargets(m,p);
    auto r=rf::applyMeshCleanup(m,p);std::vector<std::int32_t> remap(m.vertices.size(),-1);std::size_t n=0;
    for(const auto&t:r.mesh.triangles)for(auto id:{t.a,t.b,t.c})if(remap[id]<0)remap[id]=n++;
    return {std::move(r),rf::compactSurfaceObservations(m.observations,remap,targets)};
}
}
int main(){
    int passed=0;const auto test=[&](const char* name,auto&& fn){fn();++passed;std::cout<<"PASS "<<name<<'\n';};
    test("exact cyclic duplicate retains distinct source identities without editing attributes",[]{
        const auto m=fixture();const auto r=compact(m);
        check(r.cleanup.mesh.triangles.size()==1&&r.ledger.attached.size()==6&&r.ledger.unattached.empty(),"exact support lost");
        for(std::size_t i=0;i<6;++i){const auto&o=r.ledger.attached[i];check(o.sourceSampleId==i&&o.camera==i/3&&o.x==i%3,"identity changed");
            check(o.vertex==i%3,"wrong cyclic corner");check(o.relation==(i<3?rf::SurfaceObservationRelation::Accumulator:rf::SurfaceObservationRelation::ExactDuplicateFace),"contributor relation fabricated");
            check(r.cleanup.mesh.vertices[i].confidence==m.vertices[i].confidence&&r.cleanup.mesh.vertices[i].color==m.vertices[i].color,"attributes changed");}
    });
    test("signed zero and source-face order preserve identity roles without displacement",[]{
        auto m=fixture();m.vertices[3].xyz.x=-0.;m.vertices[3].xyz.y=-0.;
        const auto first=compact(m);std::swap(m.triangles[0],m.triangles[1]);const auto second=compact(m);
        check(first.ledger.attached.size()==6&&second.ledger.attached.size()==6,"signed zero lost support");
        for(std::size_t i=0;i<6;++i){
            check(first.ledger.attached[i].sourceSampleId==second.ledger.attached[i].sourceSampleId,"permutation lost identities");
            check(first.ledger.attached[i].relation!=second.ledger.attached[i].relation,"first-face creator attributes silently reassigned");
        }
    });
    test("opposite thin sides and representably separated sheets never gain equivalence",[]{
        for(double distance:{std::numeric_limits<double>::denorm_min(),std::nextafter(1.,2.)-1.,1e-6}){
            const auto r=compact(fixture(distance));check(r.cleanup.mesh.triangles.size()==2&&r.ledger.attached.size()==6,"separated sheet deleted");
            for(const auto&o:r.ledger.attached)check(o.relation==rf::SurfaceObservationRelation::Accumulator,"nearby sheets gained unsupported association");
        }
        const auto r=compact(fixture(0,true));check(r.cleanup.mesh.triangles.size()==2&&r.ledger.attached.size()==6,"opposite winding deleted");
    });
    test("exact position alone or degenerate face never supplies association proof",[]{
        auto m=fixture();m.triangles.resize(1);m.triangles.push_back({3,3,4,1});
        const auto r=compact(m);check(r.ledger.attached.size()==3&&r.ledger.unattached.size()==3,"unrelated orphan attached");
        for(const auto&o:r.ledger.unattached)check(o.reason==rf::SurfaceUnattachedReason::NoRetainedFace,"wrong orphan classification");
    });
    test("one orphan with two distinct live correspondences remains explicit ambiguity",[]{
        rf::SurfaceMesh m;
        const std::vector<rf::Vec3> positions{{0,0,0},{1,0,0},{0,1,0},{0,0,0},{-1,0,0},{0,-1,0},
            {0,0,0},{1,0,0},{0,1,0},{-1,0,0},{0,-1,0}};
        for(std::size_t i=0;i<positions.size();++i){rf::SurfaceVertex v;v.xyz=positions[i];m.vertices.push_back(v);m.observations.push_back({static_cast<std::uint32_t>(i),0,static_cast<std::uint16_t>(i),0,i});}
        m.triangles={{0,1,2,0},{3,4,5,0},{6,7,8,1},{6,9,10,1}};
        const auto r=compact(m);check(r.cleanup.mesh.triangles.size()==2&&r.ledger.attached.size()==10&&r.ledger.unattached.size()==1,"ambiguous support silently discarded or attached");
        check(r.ledger.unattached[0].sourceSampleId==6&&r.ledger.unattached[0].reason==rf::SurfaceUnattachedReason::AmbiguousExactDuplicate,"ambiguous source identity lost");
    });
    test("duplicate sample IDs camera pixels and stale plans reject",[]{
        auto m=fixture();m.observations.back().sourceSampleId=m.observations.front().sourceSampleId;rejects([&]{compact(m);});
        m=fixture();m.observations.back().camera=0;m.observations.back().x=0;rejects([&]{compact(m);});
        m=fixture();auto p=rf::planMeshCleanup(m);p.removedFaces.clear();rejects([&]{rf::exactCleanupObservationTargets(m,p);});
    });
    test("hard input and work bounds preserve caller evidence",[]{
        auto m=fixture();auto p=rf::planMeshCleanup(m);rf::CleanupSettings tiny;tiny.maxTriangles=1;
        rejects([&]{rf::exactCleanupObservationTargets(m,p,tiny);});
        std::vector<rf::SurfaceObservationRef> excess(rf::MAX_DENSE_SAMPLES+1);
        rejects([&]{rf::compactSurfaceObservations(excess,{},{});});check(m.observations.size()==6,"failure mutated source");
        const std::vector<std::int32_t> good{0,1,2,0,1,2};auto invalid=good;invalid[0]=std::numeric_limits<std::int32_t>::max();
        rejects([&]{rf::compactSurfaceObservations(m.observations,invalid,good);});invalid=good;invalid[0]=-3;
        rejects([&]{rf::compactSurfaceObservations(m.observations,good,invalid);});
    });
    test("pre-cancel and in-flight large duplicate classification do not publish partial ledgers",[]{
        auto m=fixture();auto p=rf::planMeshCleanup(m);std::atomic_bool stop{true};bool caught=false;
        try{rf::cancellation::Scope scope(&stop);rf::exactCleanupObservationTargets(m,p);}catch(const rf::cancellation::Cancelled&){caught=true;}
        check(caught,"pre-cancellation ignored");
        m.triangles.assign(80000,m.triangles.front());p=rf::planMeshCleanup(m);stop=false;caught=false;
        std::thread cancel([&]{std::this_thread::sleep_for(std::chrono::milliseconds(1));stop=true;});
        try{rf::cancellation::Scope scope(&stop);rf::exactCleanupObservationTargets(m,p);}catch(const rf::cancellation::Cancelled&){caught=true;}
        cancel.join();check(caught&&m.triangles.size()==80000,"active cancellation lost source or returned partial proof");
    });
    std::cout<<passed<<" provenance cases passed\n";
}
