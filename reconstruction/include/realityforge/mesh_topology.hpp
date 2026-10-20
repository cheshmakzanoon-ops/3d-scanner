#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <map>
#include <numeric>
#include <set>
#include <span>
#include <utility>
#include <vector>

namespace rf {
struct MeshTopologySettings {
    std::size_t maxVertices{40000},maxFaces{80000},maxIntersectionCandidates{2000000};
    double relativeTolerance{1e-10};
    std::span<const std::uint8_t> faceEvidence; // 0 unknown, 1 observed, 2 generated; empty means unknown
};
struct MeshTopologyReport {
    std::vector<std::size_t> degenerateFaces,duplicateFaces,windingConflictFaces,nonmanifoldFaces;
    std::vector<std::pair<std::size_t,std::size_t>> intersections,ambiguousPairs;
    std::vector<std::uint32_t> faceComponents;
    std::vector<std::vector<std::uint32_t>> boundaryLoops,boundaryChains;
    std::size_t components{},boundaryEdges{},nonmanifoldEdges{},nonmanifoldVertices{},intersectionCandidates{};
    std::size_t observedFaces{},generatedFaces{},unknownFaces{};
    double tolerance{};
    bool intersectionBudgetExceeded{};
    // A diagnostic does not certify watertightness or infer generated geometry.
};
namespace mesh_topology_detail {
struct P {long double x{},y{},z{};};
inline P point(Vec3 p){return {p.x,p.y,p.z};}
inline P sub(P a,P b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline P cross(P a,P b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline long double dot(P a,P b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline long double len(P p){return std::sqrt(dot(p,p));}
inline long double orient(P a,P b,P c,int axis){
    const auto u=sub(b,a),v=sub(c,a);
    return axis==0?u.y*v.z-u.z*v.y:axis==1?u.x*v.z-u.z*v.x:u.x*v.y-u.y*v.x;
}
inline bool inside(P p,const std::array<P,3>& t,int axis,long double eps,bool strict){
    const auto a=orient(t[0],t[1],p,axis),b=orient(t[1],t[2],p,axis),c=orient(t[2],t[0],p,axis);
    if(strict)return (a>eps&&b>eps&&c>eps)||(a<-eps&&b<-eps&&c<-eps);
    return (a>=-eps&&b>=-eps&&c>=-eps)||(a<=eps&&b<=eps&&c<=eps);
}
// Returns 2 for a definite crossing/positive-area overlap, 1 for tolerance-band
// contact and 0 for separated faces. Coplanar tests preserve open-sheet edges.
inline int intersect(const std::array<P,3>& a,const std::array<P,3>& b,long double tolerance){
    auto na=cross(sub(a[1],a[0]),sub(a[2],a[0])),nb=cross(sub(b[1],b[0]),sub(b[2],b[0]));
    const auto la=len(na),lb=len(nb);if(la==0||lb==0)return 0;
    int axis=0;if(std::abs(na.y)>std::abs(na.x))axis=1;if(std::abs(na.z)>(axis==0?std::abs(na.x):std::abs(na.y)))axis=2;
    const bool coplanar=len(cross(na,nb))<=la*lb*1e-10L&&std::abs(dot(na,sub(b[0],a[0])))<=la*tolerance;
    if(coplanar){
        long double edge=0;for(int i=0;i<3;++i)edge=std::max(edge,len(sub(a[(i+1)%3],a[i])));
        const long double eps=tolerance*edge;
        const P ca{(a[0].x+a[1].x+a[2].x)/3,(a[0].y+a[1].y+a[2].y)/3,(a[0].z+a[1].z+a[2].z)/3};
        if(inside(ca,b,axis,eps,true))return 2;
        for(auto p:a)if(inside(p,b,axis,eps,true))return 2;
        for(auto p:b)if(inside(p,a,axis,eps,true))return 2;
        bool near=false;
        for(int i=0;i<3;++i)for(int j=0;j<3;++j){
            auto aa=orient(a[i],a[(i+1)%3],b[j],axis),ab=orient(a[i],a[(i+1)%3],b[(j+1)%3],axis);
            auto ba=orient(b[j],b[(j+1)%3],a[i],axis),bb=orient(b[j],b[(j+1)%3],a[(i+1)%3],axis);
            if(((aa>eps&&ab<-eps)||(aa<-eps&&ab>eps))&&((ba>eps&&bb<-eps)||(ba<-eps&&bb>eps)))return 2;
            if(std::abs(aa)<=eps||std::abs(ab)<=eps||std::abs(ba)<=eps||std::abs(bb)<=eps)near=true;
        }
        return near?1:0;
    }
    bool ambiguous=false;
    const auto edges=[&](const std::array<P,3>& from,const std::array<P,3>& to,P n,long double l){
        int projection=0;if(std::abs(n.y)>std::abs(n.x))projection=1;if(std::abs(n.z)>(projection==0?std::abs(n.x):std::abs(n.y)))projection=2;
        for(int i=0;i<3;++i){
            auto p=from[i],q=from[(i+1)%3];auto d=dot(n,sub(p,to[0]))/l,e=dot(n,sub(q,to[0]))/l;
            if((d>tolerance&&e>tolerance)||(d<-tolerance&&e<-tolerance))continue;
            if(std::abs(d-e)<=tolerance){if(std::abs(d)<=tolerance)ambiguous=true;continue;}
            const long double t=d/(d-e);if(t<0||t>1)continue;
            auto v=sub(q,p);P hit{p.x+t*v.x,p.y+t*v.y,p.z+t*v.z};
            const auto areaEps=tolerance*std::sqrt(l);
            if(inside(hit,to,projection,areaEps,true)&&t>1e-12L&&t<1-1e-12L)return true;
            if(inside(hit,to,projection,areaEps,false))ambiguous=true;
        }return false;
    };
    if(edges(a,b,nb,lb)||edges(b,a,na,la))return 2;
    return ambiguous?1:0;
}
}
inline MeshTopologyReport analyzeMeshTopology(const SurfaceMesh& mesh,const MeshTopologySettings& s={}){
    using namespace mesh_topology_detail;
    cancellation::checkpoint();
    if(mesh.vertices.size()>s.maxVertices||mesh.triangles.size()>s.maxFaces||s.maxVertices>10000000||s.maxFaces>10000000||
       s.maxIntersectionCandidates>20000000||!std::isfinite(s.relativeTolerance)||s.relativeTolerance<=0||s.relativeTolerance>.001)
        throw Error("Mesh topology bounds/settings exceeded");
    MeshTopologyReport out;const auto n=mesh.triangles.size();
    if(!s.faceEvidence.empty()&&s.faceEvidence.size()!=n)throw Error("Topology face-evidence size mismatch");
    if(s.faceEvidence.empty())out.unknownFaces=n;
    else for(auto kind:s.faceEvidence){if(kind==0)++out.unknownFaces;else if(kind==1)++out.observedFaces;else if(kind==2)++out.generatedFaces;else throw Error("Invalid topology face evidence");}
    std::vector<std::uint32_t> parent(n);std::iota(parent.begin(),parent.end(),0);
    auto root=[&](std::uint32_t x){while(parent[x]!=x){parent[x]=parent[parent[x]];x=parent[x];}return x;};
    auto unite=[&](std::uint32_t a,std::uint32_t b){a=root(a);b=root(b);if(a!=b)parent[std::max(a,b)]=std::min(a,b);};
    P low{},high{};bool first=true;
    for(auto& v:mesh.vertices){auto p=point(v.xyz);if(!std::isfinite(v.xyz.x)||!std::isfinite(v.xyz.y)||!std::isfinite(v.xyz.z)||len(p)>1e100L)throw Error("Invalid topology vertex");if(first){low=high=p;first=false;}low={std::min(low.x,p.x),std::min(low.y,p.y),std::min(low.z,p.z)};high={std::max(high.x,p.x),std::max(high.y,p.y),std::max(high.z,p.z)};}
    const long double scale=len(sub(high,low)),epsilon=scale*s.relativeTolerance;out.tolerance=static_cast<double>(epsilon);
    using Edge=std::pair<std::uint32_t,std::uint32_t>;struct Use{std::size_t face;bool forward;};
    std::map<Edge,std::vector<Use>> edges;std::map<std::array<std::uint32_t,3>,std::size_t> faces;
    std::vector<std::vector<std::size_t>> vertexFaces(mesh.vertices.size());
    struct Box{long double lo[3],hi[3];std::size_t face;};std::vector<Box> boxes;
    for(std::size_t i=0;i<n;++i){
        if((i&127u)==0){cancellation::checkpoint();}
        const auto& f=mesh.triangles[i];std::array<std::uint32_t,3> ids{f.a,f.b,f.c};
        for(auto id:ids)if(id>=mesh.vertices.size())throw Error("Topology face index out of range");
        auto sorted=ids;std::sort(sorted.begin(),sorted.end());if(!faces.emplace(sorted,i).second)out.duplicateFaces.push_back(i);
        std::array<P,3> p{point(mesh.vertices[f.a].xyz),point(mesh.vertices[f.b].xyz),point(mesh.vertices[f.c].xyz)};
        const auto area=len(cross(sub(p[1],p[0]),sub(p[2],p[0])));
        if(f.a==f.b||f.b==f.c||f.a==f.c||area<=scale*epsilon){out.degenerateFaces.push_back(i);continue;}
        for(int k=0;k<3;++k){auto a=ids[k],b=ids[(k+1)%3];edges[{std::min(a,b),std::max(a,b)}].push_back({i,a<b});vertexFaces[a].push_back(i);}
        Box box{{p[0].x,p[0].y,p[0].z},{p[0].x,p[0].y,p[0].z},i};for(auto v:p){long double a[]{v.x,v.y,v.z};for(int k=0;k<3;++k){box.lo[k]=std::min(box.lo[k],a[k]);box.hi[k]=std::max(box.hi[k],a[k]);}}boxes.push_back(box);
    }
    std::map<std::uint32_t,std::vector<std::uint32_t>> boundary;
    for(const auto& [edge,uses]:edges){
        if(uses.size()==1){++out.boundaryEdges;boundary[edge.first].push_back(edge.second);boundary[edge.second].push_back(edge.first);}
        if(uses.size()>2){++out.nonmanifoldEdges;for(auto u:uses)out.nonmanifoldFaces.push_back(u.face);}
        if(uses.size()==2&&uses[0].forward==uses[1].forward){out.windingConflictFaces.push_back(uses[0].face);out.windingConflictFaces.push_back(uses[1].face);}
        for(std::size_t k=1;k<uses.size();++k)unite(static_cast<std::uint32_t>(uses[0].face),static_cast<std::uint32_t>(uses[k].face));
    }
    std::map<std::uint32_t,std::uint32_t> components;for(std::size_t i=0;i<n;++i){auto r=root(static_cast<std::uint32_t>(i));auto it=components.emplace(r,static_cast<std::uint32_t>(components.size())).first;out.faceComponents.push_back(it->second);}out.components=components.size();
    // Vertex-link graph: each incident face contributes the opposite edge. A
    // manifold vertex link is one path (boundary) or one cycle (interior).
    for(std::size_t vertex=0;vertex<vertexFaces.size();++vertex){
        const auto& incident=vertexFaces[vertex];if(incident.empty())continue;std::map<std::uint32_t,std::set<std::uint32_t>> link;
        for(auto i:incident){auto f=mesh.triangles[i];std::vector<std::uint32_t> others;for(auto id:{f.a,f.b,f.c})if(id!=vertex)others.push_back(id);if(others.size()==2){link[others[0]].insert(others[1]);link[others[1]].insert(others[0]);}}
        bool bad=false;std::set<std::uint32_t> visited;std::vector<std::uint32_t> stack{link.begin()->first};while(!stack.empty()){auto v=stack.back();stack.pop_back();if(!visited.insert(v).second)continue;for(auto a:link[v])stack.push_back(a);}
        for(auto& [v,neighbors]:link){(void)v;if(neighbors.size()>2)bad=true;}if(visited.size()!=link.size())bad=true;if(bad)++out.nonmanifoldVertices;
    }
    std::set<Edge> used;
    for(const auto& [start,neighbors]:boundary)for(auto next:neighbors){Edge key{std::min(start,next),std::max(start,next)};if(used.count(key))continue;
        std::vector<std::uint32_t> walk{start};auto previous=start,current=next;used.insert(key);
        while(true){walk.push_back(current);if(current==start)break;if(boundary[current].size()!=2)break;auto neighbor=boundary[current][0]==previous?boundary[current][1]:boundary[current][0];Edge k{std::min(current,neighbor),std::max(current,neighbor)};if(!used.insert(k).second)break;previous=current;current=neighbor;}
        if(walk.size()>2&&walk.front()==walk.back())out.boundaryLoops.push_back(std::move(walk));else out.boundaryChains.push_back(std::move(walk));
    }
    std::sort(boxes.begin(),boxes.end(),[](const Box& a,const Box& b){return a.lo[0]==b.lo[0]?a.face<b.face:a.lo[0]<b.lo[0];});
    for(std::size_t i=0;i<boxes.size()&&!out.intersectionBudgetExceeded;++i){
        if((i&63u)==0){cancellation::checkpoint();}
        const auto& a=boxes[i];
        for(std::size_t j=i+1;j<boxes.size()&&boxes[j].lo[0]<=a.hi[0]+epsilon;++j){const auto& b=boxes[j];
            if(out.intersectionCandidates++>=s.maxIntersectionCandidates){out.intersectionBudgetExceeded=true;break;}
            if((out.intersectionCandidates&63u)==0)cancellation::checkpoint();
            if(b.lo[1]>a.hi[1]+epsilon||a.lo[1]>b.hi[1]+epsilon||b.lo[2]>a.hi[2]+epsilon||a.lo[2]>b.hi[2]+epsilon)continue;
            const auto f=mesh.triangles[a.face],g=mesh.triangles[b.face];
            const std::array<P,3> p{point(mesh.vertices[f.a].xyz),point(mesh.vertices[f.b].xyz),point(mesh.vertices[f.c].xyz)},q{point(mesh.vertices[g.a].xyz),point(mesh.vertices[g.b].xyz),point(mesh.vertices[g.c].xyz)};
            auto pair=std::minmax(a.face,b.face);const int result=intersect(p,q,epsilon);if(result==2)out.intersections.push_back(pair);else if(result==1)out.ambiguousPairs.push_back(pair);
        }
    }
    for(auto* list:{&out.nonmanifoldFaces,&out.windingConflictFaces}){std::sort(list->begin(),list->end());list->erase(std::unique(list->begin(),list->end()),list->end());}
    std::sort(out.intersections.begin(),out.intersections.end());std::sort(out.ambiguousPairs.begin(),out.ambiguousPairs.end());return out;
}
}
