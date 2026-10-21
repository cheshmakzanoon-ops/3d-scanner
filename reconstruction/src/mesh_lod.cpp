#include "realityforge/mesh_lod.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>
#include <set>
#include <tuple>

namespace rf::asset {
namespace {
using Face=std::array<std::uint32_t,3>;
using Edge=std::pair<std::uint32_t,std::uint32_t>;
Edge edge(std::uint32_t a,std::uint32_t b){return std::minmax(a,b);}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
double length(Vec3 v){return std::sqrt(dot(v,v));}
Vec3 normal(const Face& f,const std::vector<Vec3>& p){return cross(sub(p[f[1]],p[f[0]]),sub(p[f[2]],p[f[0]]));}
Face key(Face f){std::sort(f.begin(),f.end());return f;}
template<class T> void copy(std::vector<T>& to,std::span<const T> from){to.assign(from.begin(),from.end());}
}
SurfaceAsset LodGeometry::surface() const {
    PointAsset p; p.representation=representation;p.positions=positions;p.colors=colors;p.normals=normals;
    p.confidence=confidence;p.supportingViews=supportingViews;p.units=units;p.provenance=provenance;
    return {p,indices};
}
LodResult simplifySurface(const SurfaceAsset& input,const LodRecipe& recipe,std::span<const Vec2> uv,const Cancel& cancel){
    Limits limits; limits.maxVertices=recipe.maximumVertices;limits.maxTriangles=recipe.maximumTriangles;
    validate(input,limits,cancel);
    const auto count=input.vertices.positions.size();
    if(input.indices.empty()||recipe.targetTriangles==0||recipe.targetTriangles>input.indices.size()/3)
        throw Error("LOD requires a positive target no larger than the source face count");
    if(!std::isfinite(recipe.maximumDisplacement)||recipe.maximumDisplacement<0||
       !std::isfinite(recipe.maximumUvDisplacement)||recipe.maximumUvDisplacement<0||
       !std::isfinite(recipe.minimumNormalCosine)||recipe.minimumNormalCosine<=0||recipe.minimumNormalCosine>1||
       recipe.maximumWork==0)throw Error("Invalid LOD error/work budget");
    if(input.vertices.provenance.assetId.empty()||input.vertices.provenance.sourceSha256.size()!=64)
        throw Error("LOD requires immutable parent identity and SHA-256 source provenance");
    if(!uv.empty()&&uv.size()!=count)throw Error("LOD UV count must equal vertex count");
    for(auto p:uv)if(!std::isfinite(p.x)||!std::isfinite(p.y))throw Error("LOD nonfinite UV");
    LodResult result;auto& g=result.geometry;auto& report=result.report;
    copy(g.positions,input.vertices.positions);copy(g.normals,input.vertices.normals);copy(g.colors,input.vertices.colors);
    copy(g.confidence,input.vertices.confidence);copy(g.supportingViews,input.vertices.supportingViews);copy(g.uv,uv);
    g.units=input.vertices.units;g.representation=input.vertices.representation;g.provenance=input.vertices.provenance;
    g.provenance.parentId=input.vertices.provenance.assetId;g.provenance.assetId.clear();
    g.provenance.algorithmVersion=LodReport::algorithmVersion;
    report.inputTriangles=input.indices.size()/3;report.parentAssetId=g.provenance.parentId;report.sourceSha256=g.provenance.sourceSha256;
    std::vector<Face> faces;
    std::vector<std::uint32_t> faceSources;
    for(std::size_t i=0;i<input.indices.size();i+=3){
        if(i%3072==0)checkCancelled(cancel);
        Face f{input.indices[i],input.indices[i+1],input.indices[i+2]};
        if(f[0]==f[1]||f[1]==f[2]||f[0]==f[2]||!(length(normal(f,g.positions))>0))throw Error("LOD degenerate source face");
        faces.push_back(f);
        faceSources.push_back(static_cast<std::uint32_t>(i/3));
    }
    std::vector<std::vector<std::uint32_t>> members(count);
    for(std::uint32_t i=0;i<count;++i)members[i].push_back(i);
    std::vector<bool> locked(count,false);
    // Multiple vertex identities at the same position encode chart/material or
    // sharp-normal discontinuities. Never weld or move those identities.
    std::map<std::tuple<double,double,double>,std::vector<std::uint32_t>> coincident;
    for(std::uint32_t i=0;i<count;++i){if(i%1024==0)checkCancelled(cancel);auto p=g.positions[i];coincident[{p.x,p.y,p.z}].push_back(i);}
    for(const auto& [p,ids]:coincident){(void)p;if(ids.size()>1)for(auto i:ids)locked[i]=true;}
    coincident.clear();
    auto consume=[&](std::size_t work){if(work>recipe.maximumWork-report.work)throw Error("LOD work budget exhausted; master preserved");report.work+=work;checkCancelled(cancel);};
    std::set<Face> unique;
    for(auto f:faces)if(!unique.insert(key(f)).second)throw Error("LOD duplicate source face");
    std::map<Edge,std::size_t> initialEdges;
    for(auto f:faces)for(unsigned i=0;i<3;++i)++initialEdges[edge(f[i],f[(i+1)%3])];
    for(auto [e,incidence]:initialEdges){
        if(incidence>2)throw Error("LOD refuses nonmanifold source edges");
        if(incidence==1)locked[e.first]=locked[e.second]=true;
    }
    // Reject pinched/bow-tie vertex links as well as nonmanifold edges.
    std::vector<std::vector<std::size_t>> initialIncident(count);
    for(std::size_t i=0;i<faces.size();++i)for(auto v:faces[i])initialIncident[v].push_back(i);
    consume(faces.size()*3);
    for(std::uint32_t vertex=0;vertex<count;++vertex){
        if(vertex%256==0)checkCancelled(cancel);
        if(initialIncident[vertex].empty())continue;
        std::map<std::uint32_t,std::vector<std::uint32_t>> ring;
        for(auto i:initialIncident[vertex]){
            std::array<std::uint32_t,2> other{};unsigned k=0;
            for(auto v:faces[i])if(v!=vertex)other[k++]=v;
            ring[other[0]].push_back(other[1]);ring[other[1]].push_back(other[0]);
        }
        unsigned ends=0;
        for(const auto& [v,adjacent]:ring){(void)v;if(adjacent.size()>2)throw Error("LOD refuses nonmanifold vertex links");if(adjacent.size()==1)++ends;}
        std::set<std::uint32_t> visited;std::vector<std::uint32_t> pending{ring.begin()->first};
        while(!pending.empty()){
            if(visited.size()%1024==0)checkCancelled(cancel);
            auto v=pending.back();pending.pop_back();if(!visited.insert(v).second)continue;
            for(auto next:ring.at(v))pending.push_back(next);
        }
        if(visited.size()!=ring.size()||(ends!=0&&ends!=2))throw Error("LOD refuses disconnected/nonmanifold vertex links");
    }
    initialEdges.clear();
    std::vector<std::vector<std::size_t>>().swap(initialIncident);
    while(faces.size()>recipe.targetTriangles){
        consume(faces.size()*3);
        std::map<Edge,std::vector<std::uint32_t>> edges;
        std::vector<std::set<std::uint32_t>> neighbors(count);
        std::vector<std::vector<std::size_t>> incident(count);
        for(std::size_t index=0;index<faces.size();++index){
            if(index%1024==0)checkCancelled(cancel);
            auto f=faces[index];
            for(unsigned i=0;i<3;++i){
                auto a=f[i],b=f[(i+1)%3],c=f[(i+2)%3];
                edges[edge(a,b)].push_back(c);neighbors[a].insert(b);neighbors[b].insert(a);incident[a].push_back(index);
            }
        }
        struct Candidate { double cost;std::uint32_t keep,remove; };
        std::vector<Candidate> candidates;
        for(const auto& [e,opposite]:edges){
            if(opposite.size()!=2||locked[e.first]||locked[e.second])continue;
            auto [a,b]=e;double cost=length(sub(g.positions[a],g.positions[b]));
            if(cost<=recipe.maximumDisplacement){candidates.push_back({cost,a,b});candidates.push_back({cost,b,a});}
        }
        std::size_t comparisons=0;
        std::sort(candidates.begin(),candidates.end(),[&](const auto& a,const auto& b){
            if((++comparisons&4095U)==0)checkCancelled(cancel);
            return std::tie(a.cost,a.keep,a.remove)<std::tie(b.cost,b.keep,b.remove);
        });
        std::vector<bool> touched(count,false),alive(faces.size(),true);
        std::size_t aliveCount=faces.size();bool changed=false;
        // Disjoint endpoint stars can be collapsed in one pass. Once any face in
        // a vertex's star changes, defer that vertex to the next rebuilt pass.
        for(auto candidate:candidates){
            auto a=candidate.keep,b=candidate.remove;
            if(touched[a]||touched[b])continue;
            std::vector<std::size_t> affected;
            std::set_union(incident[a].begin(),incident[a].end(),incident[b].begin(),incident[b].end(),std::back_inserter(affected));
            consume(affected.size()+members[b].size());
            std::vector<std::uint32_t> intersection;
            std::set_intersection(neighbors[a].begin(),neighbors[a].end(),neighbors[b].begin(),neighbors[b].end(),std::back_inserter(intersection));
            auto opposite=edges.at(edge(a,b));std::sort(opposite.begin(),opposite.end());
            if(intersection!=opposite)continue; // Manifold link condition.
            double deviation=report.maximumVertexDisplacement,uvDeviation=report.maximumUvDisplacement;
            for(auto original:members[b]){
                deviation=std::max(deviation,length(sub(input.vertices.positions[original],g.positions[a])));
                if(!uv.empty())uvDeviation=std::max(uvDeviation,std::hypot(uv[original].x-uv[a].x,uv[original].y-uv[a].y));
            }
            if(deviation>recipe.maximumDisplacement||uvDeviation>recipe.maximumUvDisplacement)continue;
            for(auto i:affected)unique.erase(key(faces[i]));
            std::map<std::size_t,Face> replacements;std::set<Face> nextKeys;
            std::vector<std::size_t> removed;bool valid=true;
            for(auto i:affected){
                auto f=faces[i];const auto before=normal(f,g.positions);
                for(auto& v:f)if(v==b)v=a;
                if(f[0]==f[1]||f[1]==f[2]||f[0]==f[2]){removed.push_back(i);continue;}
                auto after=normal(f,g.positions);double la=length(after),lb=length(before);
                if(!(la>0)||dot(before,after)<recipe.minimumNormalCosine*la*lb||unique.contains(key(f))||!nextKeys.insert(key(f)).second){valid=false;break;}
                replacements.emplace(i,f);
            }
            if(!valid||removed.empty()||aliveCount-removed.size()<recipe.targetTriangles){
                for(auto i:affected)unique.insert(key(faces[i]));
                continue;
            }
            for(auto i:removed)alive[i]=false;
            aliveCount-=removed.size();
            for(const auto& [i,f]:replacements)faces[i]=f;
            unique.insert(nextKeys.begin(),nextKeys.end());
            for(auto v:neighbors[a])touched[v]=true;
            for(auto v:neighbors[b])touched[v]=true;
            touched[a]=touched[b]=true;
            members[a].insert(members[a].end(),members[b].begin(),members[b].end());members[b].clear();
            report.maximumVertexDisplacement=deviation;report.maximumUvDisplacement=uvDeviation;
            ++report.collapsedEdges;changed=true;
            if(aliveCount==recipe.targetTriangles)break;
        }
        if(!changed)throw Error("LOD target cannot be reached within boundary, seam, topology and error constraints");
        std::vector<Face> compactFaces;compactFaces.reserve(aliveCount);
        std::vector<std::uint32_t> compactSources;compactSources.reserve(aliveCount);
        for(std::size_t i=0;i<faces.size();++i)if(alive[i]){compactFaces.push_back(faces[i]);compactSources.push_back(faceSources[i]);}
        faces.swap(compactFaces);
        faceSources.swap(compactSources);
    }
    report.lockedVertices=std::count(locked.begin(),locked.end(),true);report.outputTriangles=faces.size();
    // Compact only vertices still referenced, preserving every surviving attribute.
    std::vector<std::uint32_t> remap(count,std::numeric_limits<std::uint32_t>::max());
    LodGeometry compact;compact.units=g.units;compact.representation=g.representation;compact.provenance=g.provenance;
    for(std::size_t face=0;face<faces.size();++face){
      compact.parentTriangleIndices.push_back(faceSources[face]);
      for(auto v:faces[face]){
        if(remap[v]==std::numeric_limits<std::uint32_t>::max()){
            remap[v]=static_cast<std::uint32_t>(compact.positions.size());compact.positions.push_back(g.positions[v]);
            compact.parentVertexIndices.push_back(v);
            if(!g.normals.empty())compact.normals.push_back(g.normals[v]);
            if(!g.colors.empty())compact.colors.push_back(g.colors[v]);
            if(!g.confidence.empty())compact.confidence.push_back(g.confidence[v]);
            if(!g.supportingViews.empty())compact.supportingViews.push_back(g.supportingViews[v]);
            if(!g.uv.empty())compact.uv.push_back(g.uv[v]);
        }
        compact.indices.push_back(remap[v]);
      }
    }
    checkCancelled(cancel);g=std::move(compact);validate(g.surface(),limits,cancel);return result;
}
}
