#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <numeric>
#include <set>
#include <limits>

namespace rf {
// Schema 1: deletion-only proposal. No vertex welding, displacement, hole filling,
// normal change, component-size filter or generated geometry is permitted.
struct CleanupSettings {std::size_t maxVertices=40000,maxTriangles=80000;};
struct CleanupPlan {
    std::vector<std::size_t> removedFaces,sourceFaceForResult;
    std::size_t inputVertices{},inputFaces{};
};
struct CleanupResult {SurfaceMesh mesh; CleanupPlan plan; double maxDisplacement{}; std::size_t generatedFaces{};};
namespace detail {
// Deletion needs a proof of zero area, not a rounded double determinant. On
// supported GNU/NDK targets the wider exponent range prevents product underflow.
// If an exact difference cannot be represented, preserve the uncertain face.
inline bool cleanupProvesZeroArea(Vec3 a,Vec3 b,Vec3 c) {
    if constexpr(std::numeric_limits<long double>::max_exponent<=2*std::numeric_limits<double>::max_exponent ||
        std::numeric_limits<long double>::min_exponent>2*(std::numeric_limits<double>::min_exponent-std::numeric_limits<double>::digits))return false;
    std::array<long double,3> u{},v{};
    const auto difference=[](long double first,long double second,long double& result){
        result=first-second;
        const auto virtualSecond=first-result,virtualFirst=result+virtualSecond;
        return ((first-virtualFirst)+(virtualSecond-second))==0;
    };
    const std::array<double,3> aa{a.x,a.y,a.z},bb{b.x,b.y,b.z},cc{c.x,c.y,c.z};
    for(std::size_t i=0;i<3;++i)if(!difference(bb[i],aa[i],u[i])||!difference(cc[i],aa[i],v[i]))return false;
    for(std::size_t i=0;i<3;++i){
        const auto j=(i+1)%3,k=(i+2)%3;
        const auto first=u[j]*v[k],second=u[k]*v[j];
        // fma retains the product's low part; equal rounded products alone are
        // insufficient for nearly collinear, otherwise valid thin geometry.
        if(first!=second||std::fma(u[j],v[k],-first)!=std::fma(u[k],v[j],-second))return false;
    }
    return true;
}
}
inline CleanupPlan planMeshCleanup(const SurfaceMesh& mesh,const CleanupSettings& s={}) {
    if(s.maxVertices>1000000||s.maxTriangles>2000000||mesh.vertices.size()>s.maxVertices||mesh.triangles.size()>s.maxTriangles)
        throw Error("Mesh cleanup budget exceeded");
    for(const auto& v:mesh.vertices) for(double d:{v.xyz.x,v.xyz.y,v.xyz.z,v.normal.x,v.normal.y,v.normal.z})
        if(!std::isfinite(d)) throw Error("Nonfinite cleanup geometry");
    for(const auto& v:mesh.vertices)if(!std::isfinite(v.confidence)||v.confidence<0||v.confidence>1)throw Error("Invalid cleanup confidence");
    CleanupPlan p;p.inputVertices=mesh.vertices.size();p.inputFaces=mesh.triangles.size();
    using FaceCoordinates=std::array<std::array<double,3>,3>;
    std::set<FaceCoordinates> seen;
    for(std::size_t i=0;i<mesh.triangles.size();++i) {
        if((i&255u)==0)cancellation::checkpoint();
        const auto& t=mesh.triangles[i];
        if(t.a>=mesh.vertices.size()||t.b>=mesh.vertices.size()||t.c>=mesh.vertices.size())throw Error("Invalid cleanup triangle");
        const auto a=mesh.vertices[t.a].xyz,b=mesh.vertices[t.b].xyz,c=mesh.vertices[t.c].xyz;
        const Vec3 u{b.x-a.x,b.y-a.y,b.z-a.z},v{c.x-a.x,c.y-a.y,c.z-a.z};
        const Vec3 cross{u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x};
        for(double d:{u.x,u.y,u.z,v.x,v.y,v.z,cross.x,cross.y,cross.z})if(!std::isfinite(d))throw Error("Cleanup coordinate dynamic range exceeded");
        FaceCoordinates key{{{{a.x,a.y,a.z}},{{b.x,b.y,b.z}},{{c.x,c.y,c.z}}}};
        // Cyclic rotations preserve orientation. The opposite side of a thin
        // two-sided sheet is not a duplicate and must remain observed geometry.
        const auto first=std::min_element(key.begin(),key.end());std::rotate(key.begin(),first,key.end());
        const bool degenerate=t.a==t.b||t.a==t.c||t.b==t.c||
            ((cross.x==0&&cross.y==0&&cross.z==0)&&detail::cleanupProvesZeroArea(a,b,c));
        if(degenerate||!seen.insert(key).second)p.removedFaces.push_back(i);else p.sourceFaceForResult.push_back(i);
    }
    return p;
}
// 055 report adapter: only independently verified exact defects are adopted.
// Near-degenerates/intersections require a separate user edit and displacement budget.
template<class TopologyReport>
CleanupPlan planMeshCleanup(const SurfaceMesh& mesh,const TopologyReport& report,const CleanupSettings& s={}) {
    auto p=planMeshCleanup(mesh,s);
    for(auto face:report.duplicateFaces)if(face>=mesh.triangles.size())throw Error("Invalid topology face provenance");
    for(auto face:report.degenerateFaces)if(face>=mesh.triangles.size())throw Error("Invalid topology face provenance");
    return p;
}
inline CleanupResult applyMeshCleanup(const SurfaceMesh& source,const CleanupPlan& plan,const CleanupSettings& settings={}) {
    // Revalidate against current content; a stale/tampered plan cannot delete faces.
    const auto expected=planMeshCleanup(source,settings);
    if(plan.inputVertices!=expected.inputVertices||plan.inputFaces!=expected.inputFaces||
       plan.removedFaces!=expected.removedFaces||plan.sourceFaceForResult!=expected.sourceFaceForResult)
        throw Error("Stale or unsafe cleanup plan");
    CleanupResult r;r.plan=plan;r.mesh=source;r.mesh.triangles.clear();r.mesh.triangles.reserve(plan.sourceFaceForResult.size());
    for(auto i:plan.sourceFaceForResult){cancellation::checkpoint();r.mesh.triangles.push_back(source.triangles[i]);}
    return r;
}
}
