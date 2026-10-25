// Synthetic representation-only controls, never physical coverage evidence.
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
namespace rf::cancellation {
inline std::size_t aliasCalls{},aliasAbortAt{};
inline std::atomic_bool* aliasStop{};
inline void aliasCheckpoint(){if(aliasStop&&++aliasCalls==aliasAbortAt)aliasStop->store(true);checkpoint();}
}
#define checkpoint aliasCheckpoint
#define surface_alias_detail alias_probe_detail
#define validateSurfaceAliases validateAliasProbe
#define validateSurfaceAliasDiagnostics validateAliasDiagnosticsProbe
#define normalizeSurfaceAliases normalizeAliasProbe
#include "realityforge/surface_alias.hpp"
#undef checkpoint
#undef surface_alias_detail
#undef validateSurfaceAliases
#undef validateSurfaceAliasDiagnostics
#undef normalizeSurfaceAliases
#include "realityforge/mesh_topology.hpp"
#include <iostream>
#include <stdexcept>
#include <set>
using rf::SurfaceMesh;
void need(bool b,const char* s){if(!b)throw std::runtime_error(s);}
template<class F> void reject(F f){bool bad=false;try{f();}catch(const rf::Error&){bad=true;}need(bad,"unsafe alias accepted");}
SurfaceMesh bowtie(){
    SurfaceMesh m;
    for(auto p:{rf::Vec3{0,0,5},rf::Vec3{1,0,5},rf::Vec3{0,1,5},rf::Vec3{-1,0,5},rf::Vec3{0,-1,5}}){
        rf::SurfaceVertex v;v.xyz=p;v.normal={0,0,1};v.color={123,45,67};v.confidence=.7f;v.supportingViews=2;m.vertices.push_back(v);
    }
    m.triangles={{0,1,2,0},{0,3,4,1}};m.inputSamples=m.acceptedSamples=5;m.medianConfidence=.7f;
    for(std::uint32_t i=0;i<5;++i)m.observations.push_back({i,0,static_cast<std::uint16_t>(i),0,i});
    return m;
}
void preserved(const SurfaceMesh& a,const SurfaceMesh& b){
    need(a.triangles.size()==b.triangles.size(),"face count changed");
    for(std::size_t i=0;i<a.triangles.size();++i){const auto x=rf::alias_probe_detail::indices(a.triangles[i]),y=rf::alias_probe_detail::indices(b.triangles[i]);
        need(a.triangles[i].sourceCamera==b.triangles[i].sourceCamera,"source face camera changed");
        for(int j=0;j<3;++j)need(rf::alias_probe_detail::equalVertex(a.vertices[x[j]],b.vertices[y[j]]),"ordered face attributes changed");}
    need(a.observations.size()==b.observations.size()&&a.acceptedSamples==b.acceptedSamples&&a.inputSamples==b.inputSamples&&a.fusedObservations==b.fusedObservations&&a.medianConfidence==b.medianConfidence,"evidence counts/median changed");
    std::set<std::size_t> seen;
    for(std::size_t i=0;i<a.observations.size();++i){auto x=a.observations[i],y=b.observations[i];need(x.vertex==y.vertex&&x.camera==y.camera&&x.x==y.x&&x.y==y.y&&x.sourceSampleId==y.sourceSampleId&&x.relation==y.relation&&seen.insert(y.sourceSampleId).second,"observation changed/duplicated");}
}
int main(){try{
    auto a=bowtie(),b=a;rf::normalizeAliasProbe(b);preserved(a,b);
    need(b.vertices.size()==6&&b.topologyAliasSources==std::vector<std::uint32_t>{0},"expected one direct alias");
    need(b.preAliasInvalidVertexLinks==1&&b.postAliasInvalidVertexLinks==0&&b.unsupportedAliasStars==0,"incorrect diagnostics");
    const auto before=rf::analyzeMeshTopology(a),after=rf::analyzeMeshTopology(b);
    need(before.nonmanifoldVertices==1&&after.nonmanifoldVertices==0&&before.boundaryEdges==after.boundaryEdges&&before.intersections==after.intersections&&before.ambiguousPairs==after.ambiguousPairs,"topology/contact result changed unexpectedly");
    auto once=b;rf::normalizeAliasProbe(b);preserved(once,b);need(b.topologyAliasSources==once.topologyAliasSources,"not idempotent");
    auto fan=bowtie();fan.triangles={{0,1,2,0},{0,2,3,0}};auto same=fan;rf::normalizeAliasProbe(fan);preserved(same,fan);need(fan.topologyAliasSources.empty(),"valid fan split");
    auto cycle=bowtie();cycle.triangles={{0,1,2,0},{0,2,3,0},{0,3,4,0},{0,4,1,0}};same=cycle;rf::normalizeAliasProbe(cycle);preserved(same,cycle);need(cycle.topologyAliasSources.empty(),"valid cycle split");
    for(int kind=0;kind<3;++kind){auto bad=bowtie();bad.triangles={{0,1,2,0},{0,1,3,0}};if(kind==1)bad.triangles.push_back({0,1,4,0});if(kind==2)bad.triangles[1]=bad.triangles[0];same=bad;rf::normalizeAliasProbe(bad);preserved(same,bad);need(bad.topologyAliasSources.empty()&&bad.unsupportedAliasStars>0&&bad.postAliasInvalidVertexLinks>0,"unsupported star hidden");}
    auto mixed=bowtie();for(auto v:a.vertices)mixed.vertices.push_back(v);mixed.triangles.push_back({5,6,7,0});mixed.triangles.push_back({5,6,8,0});same=mixed;rf::normalizeAliasProbe(mixed);preserved(same,mixed);need(mixed.topologyAliasSources.size()==1&&mixed.unsupportedAliasStars==2&&mixed.postAliasInvalidVertexLinks==2,"mixed refusal broken");
    for(std::size_t i=2;i<same.triangles.size();++i)need(rf::alias_probe_detail::indices(same.triangles[i])==rf::alias_probe_detail::indices(mixed.triangles[i]),"unsupported face indices changed");
    once=mixed;rf::normalizeAliasProbe(mixed);preserved(once,mixed);
    // A bowtie adjacent to a winding-invalid star must not alter that star's faces.
    auto neighbor=bowtie();neighbor.triangles.push_back({1,2,3,0});same=neighbor;rf::normalizeAliasProbe(neighbor);preserved(same,neighbor);
    need(neighbor.topologyAliasSources.empty()&&neighbor.postAliasInvalidVertexLinks>0,"unsupported-neighbor protection failed");
    for(std::size_t i=0;i<same.triangles.size();++i)need(rf::alias_probe_detail::indices(same.triangles[i])==rf::alias_probe_detail::indices(neighbor.triangles[i]),"unsupported incident face rewritten");
    auto perm=a;std::reverse(perm.vertices.begin(),perm.vertices.end());std::reverse(perm.triangles.begin(),perm.triangles.end());for(auto& t:perm.triangles){t.a=4-t.a;t.b=4-t.b;t.c=4-t.c;}for(auto& o:perm.observations)o.vertex=4-o.vertex;same=perm;rf::normalizeAliasProbe(perm);preserved(same,perm);need(perm.topologyAliasSources.size()==1&&perm.postAliasInvalidVertexLinks==0,"permutation failed");
    auto invalid=b;invalid.topologyAliasSources[0]=5;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.vertices.back().normal.x=-0.;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.observations[0].vertex=5;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.preAliasInvalidVertexLinks=0;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.postAliasInvalidVertexLinks=1;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.unsupportedAliasStars=1;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.triangles[1].a=0;reject([&]{rf::validateAliasProbe(invalid);});
    invalid=b;invalid.triangles[1].b=2;reject([&]{rf::validateAliasProbe(invalid);}); // stale partition now connected
    invalid=a;reject([&]{rf::normalizeAliasProbe(invalid,5);});preserved(a,invalid);need(invalid.vertices.size()==a.vertices.size(),"cap refusal partially modified mesh");
    invalid=a;reject([&]{rf::normalizeAliasProbe(invalid,40000,1);});preserved(a,invalid);
    invalid=b;reject([&]{rf::normalizeAliasProbe(invalid,40000,1);});preserved(b,invalid);
    // Fano incidence: every source star has three disjoint triangles; aliases exceed canonical count.
    SurfaceMesh high;
    for(int i=0;i<7;++i){rf::SurfaceVertex v;v.xyz={double(i),double(i*i),double(i*i*i)};v.confidence=.5f;v.supportingViews=1;high.vertices.push_back(v);}
    high.triangles={{0,1,2,0},{0,3,4,0},{0,5,6,0},{1,3,5,0},{1,4,6,0},{2,3,6,0},{2,4,5,0}};
    same=high;
    const std::size_t peak=rf::alias_probe_detail::geometryBytes(high)+6*21+12*7+8192+21*sizeof(rf::SurfaceVertex)+7*sizeof(rf::SurfaceTriangle)+14*sizeof(std::uint32_t);
    reject([&]{rf::normalizeAliasProbe(high,40000,peak-1);});preserved(same,high);need(high.vertices.size()==7,"high-ratio cap partially committed");
    rf::normalizeAliasProbe(high,40000,peak);preserved(same,high);need(high.topologyAliasSources.size()==14&&high.postAliasInvalidVertexLinks==0,"high alias ratio failed");
    // Interrupt every checkpoint, including candidate planning/validation; no partial commit.
    rf::cancellation::aliasCalls=0;std::atomic_bool stop=false;rf::cancellation::aliasStop=&stop;rf::cancellation::aliasAbortAt=0;
    invalid=a;{rf::cancellation::Scope scope(&stop);rf::normalizeAliasProbe(invalid);}const auto calls=rf::cancellation::aliasCalls;
    for(std::size_t at=1;at<=calls;++at){stop=false;rf::cancellation::aliasCalls=0;rf::cancellation::aliasAbortAt=at;invalid=a;bool cancelled=false;try{rf::cancellation::Scope scope(&stop);rf::normalizeAliasProbe(invalid);}catch(...){cancelled=true;}need(cancelled,"cancellation checkpoint not reached");preserved(a,invalid);need(invalid.vertices.size()==a.vertices.size()&&invalid.topologyAliasSources.empty(),"cancel partially published");}
    rf::cancellation::aliasStop=nullptr;
    std::cout<<"surface alias controls passed; cancellation checkpoints="<<calls<<'\n';return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
