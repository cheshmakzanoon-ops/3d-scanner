#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <bit>
#include <limits>
#include <vector>

namespace rf {
namespace surface_alias_detail {
constexpr auto none=std::numeric_limits<std::uint32_t>::max();
constexpr std::size_t maxIncident=8*MAX_DENSE_VIEWS,maxNeighbors=2*maxIncident;
inline bool equalVertex(const SurfaceVertex& a,const SurfaceVertex& b){
    const auto same=[](double x,double y){return std::bit_cast<std::uint64_t>(x)==std::bit_cast<std::uint64_t>(y);};
    return same(a.xyz.x,b.xyz.x)&&same(a.xyz.y,b.xyz.y)&&same(a.xyz.z,b.xyz.z)&&
        same(a.normal.x,b.normal.x)&&same(a.normal.y,b.normal.y)&&same(a.normal.z,b.normal.z)&&
        a.color==b.color&&std::bit_cast<std::uint32_t>(a.confidence)==std::bit_cast<std::uint32_t>(b.confidence)&&a.supportingViews==b.supportingViews;
}
inline std::array<std::uint32_t,3> indices(const SurfaceTriangle& f){return {f.a,f.b,f.c};}
struct Links {
    std::vector<std::uint32_t> heads,next;
    Links(const SurfaceMesh& m,bool canonical):heads(m.vertices.size()- (canonical?m.topologyAliasSources.size():0),none),next(3*m.triangles.size(),none){
        const auto prefix=m.vertices.size()-m.topologyAliasSources.size();
        for(std::size_t i=0;i<m.triangles.size();++i){
            if((i&127u)==0)cancellation::checkpoint();
            auto ids=indices(m.triangles[i]);
            for(auto& id:ids){if(id>=m.vertices.size())throw Error("Alias face index out of bounds");if(canonical&&id>=prefix)id=m.topologyAliasSources[id-prefix];}
            if(ids[0]==ids[1]||ids[0]==ids[2]||ids[1]==ids[2])throw Error("Repeated canonical alias face corner");
            for(std::size_t k=0;k<3;++k){const auto node=static_cast<std::uint32_t>(3*i+k);next[node]=heads[ids[k]];heads[ids[k]]=node;}
        }
    }
};
struct Star {
    std::array<std::uint32_t,maxIncident> nodes{},first{},second{},component{},representative{};
    std::size_t size{},components{};bool unsupported{};
};
inline Star star(const SurfaceMesh& m,const Links& links,std::size_t center,bool canonical){
    Star result;std::array<std::uint32_t,maxNeighbors> neighbor{},parent{},degree{},incoming{},outgoing{};std::size_t count=0;
    const auto prefix=m.vertices.size()-m.topologyAliasSources.size();
    const auto original=[&](std::uint32_t id){return canonical&&id>=prefix?m.topologyAliasSources[id-prefix]:id;};
    const auto root=[&](std::uint32_t i){while(parent[i]!=i)i=parent[i];return i;};
    const auto index=[&](std::uint32_t id){for(std::size_t j=0;j<count;++j)if(neighbor[j]==id)return static_cast<std::uint32_t>(j);if(count>=maxNeighbors)throw Error("Alias star neighbor budget exceeded");neighbor[count]=id;parent[count]=static_cast<std::uint32_t>(count);return static_cast<std::uint32_t>(count++);};
    for(auto node=links.heads[center];node!=none;node=links.next[node]){
        if(result.size>=maxIncident)throw Error("Alias source-star incidence budget exceeded");
        const auto ids=indices(m.triangles[node/3]);const auto k=node%3;
        const auto a=index(original(ids[(k+1)%3])),b=index(original(ids[(k+2)%3]));
        // Preserve multiplicity: duplicate/reversed edges are not silently collapsed.
        for(std::size_t j=0;j<result.size;++j)if((result.first[j]==a&&result.second[j]==b)||(result.first[j]==b&&result.second[j]==a))result.unsupported=true;
        const auto j=result.size++;result.nodes[j]=node;result.first[j]=a;result.second[j]=b;
        ++degree[a];++degree[b];++outgoing[a];++incoming[b];parent[root(a)]=root(b);
    }
    for(std::size_t j=0;j<count;++j)if(degree[j]>2||incoming[j]>1||outgoing[j]>1)result.unsupported=true;
    std::array<std::uint32_t,maxIncident> roots{};std::array<std::size_t,maxIncident> ends{};
    // Components are ordered by smallest original face/corner ID, independent of traversal order.
    for(std::size_t j=0;j<result.size;++j){
        const auto r=root(result.first[j]);std::size_t c=0;for(;c<result.components&&roots[c]!=r;++c){}
        if(c==result.components){roots[c]=r;result.representative[c]=result.nodes[j];++result.components;}
        result.component[j]=static_cast<std::uint32_t>(c);result.representative[c]=std::min(result.representative[c],result.nodes[j]);
    }
    for(std::size_t j=0;j<count;++j)if(degree[j]==1){for(std::size_t c=0;c<result.components;++c)if(roots[c]==root(static_cast<std::uint32_t>(j)))++ends[c];}
    for(std::size_t c=0;c<result.components;++c)if(ends[c]!=0&&ends[c]!=2)result.unsupported=true;
    return result;
}
inline std::size_t geometryBytes(const SurfaceMesh& m){
    return m.vertices.capacity()*sizeof(SurfaceVertex)+m.triangles.capacity()*sizeof(SurfaceTriangle)+
        m.observations.capacity()*sizeof(SurfaceObservationRef)+m.unattachedObservations.capacity()*sizeof(SurfaceUnattachedObservation)+m.topologyAliasSources.capacity()*sizeof(std::uint32_t);
}
}
// Wire/consumer validation: aliases must be complete disconnected original-link
// components, not arbitrary tears of a connected fan. Observations remain unique
// in their existing ledger; every attached reference targets the canonical prefix.
inline void validateSurfaceAliases(const SurfaceMesh& m){
    using namespace surface_alias_detail;
    cancellation::checkpoint();
    if(m.vertices.size()>40000||m.triangles.size()>80000||m.topologyAliasSources.size()>m.vertices.size())throw Error("Alias geometry bounds exceeded");
    const auto prefix=m.vertices.size()-m.topologyAliasSources.size();
    if(m.preAliasInvalidVertexLinks>prefix||m.postAliasInvalidVertexLinks>prefix||m.unsupportedAliasStars>prefix)throw Error("Alias diagnostics out of bounds");
    for(std::size_t i=0;i<m.observations.size();++i){if((i&255u)==0)cancellation::checkpoint();if(m.observations[i].vertex>=prefix)throw Error("Observation targets representation alias");}
    if(m.topologyAliasSources.empty())return;
    const auto validationScratch=6*m.vertices.size()+12*m.triangles.size()+8192;
    if(geometryBytes(m)+validationScratch>10*1024*1024)throw Error("Alias validation memory budget exceeded");
    std::vector<std::uint8_t> aliased(prefix,0);
    for(std::size_t i=0;i<m.topologyAliasSources.size();++i){
        if((i&127u)==0)cancellation::checkpoint();
        const auto target=m.topologyAliasSources[i];
        if(target>=prefix||!equalVertex(m.vertices[prefix+i],m.vertices[target]))throw Error("Invalid direct alias or changed attributes");
        aliased[target]=1;
    }
    std::size_t pre=0,unsupported=0;
    {
    const Links links(m,true);
    std::vector<std::uint8_t> used(m.topologyAliasSources.size(),0),protectedVertex(prefix,0);
    for(std::size_t v=0;v<prefix;++v){
        if((v&127u)==0)cancellation::checkpoint();
        const auto s=star(m,links,v,true);
        pre+=s.unsupported||s.components>1;unsupported+=s.unsupported;protectedVertex[v]=s.unsupported;
    }
    for(std::size_t v=0;v<prefix;++v){
        if((v&127u)==0)cancellation::checkpoint();
        if(!aliased[v])continue;
        const auto s=star(m,links,v,true);
        if(s.unsupported||s.components<2)throw Error("Unsupported or connected source alias star");
        for(std::size_t j=0;j<s.size;++j)for(auto id:indices(m.triangles[s.nodes[j]/3])){
            const auto canonical=id>=prefix?m.topologyAliasSources[id-prefix]:id;
            if(protectedVertex[canonical])throw Error("Alias rewrites an unsupported star face");
        }
        std::array<std::uint32_t,maxIncident> target;target.fill(none);
        for(std::size_t j=0;j<s.size;++j){const auto node=s.nodes[j],id=indices(m.triangles[node/3])[node%3],c=s.component[j];
            if(target[c]!=none&&target[c]!=id)throw Error("Alias tears connected source component");
            target[c]=id;
            if(id>=prefix)used[id-prefix]=1;
        }
        bool canonicalUsed=false;
        for(std::size_t c=0;c<s.components;++c){canonicalUsed|=target[c]==v;for(std::size_t d=0;d<c;++d)if(target[c]==target[d])throw Error("Alias combines disconnected components");}
        if(!canonicalUsed)throw Error("Unused canonical alias vertex");
    }
    if(std::find(used.begin(),used.end(),0)!=used.end())throw Error("Unused representation alias");
    }
    std::size_t post=0;
    {
        const Links rendered(m,false);
        for(std::size_t v=0;v<m.vertices.size();++v){
            if((v&127u)==0)cancellation::checkpoint();
            const auto s=star(m,rendered,v,false);post+=s.unsupported||s.components>1;
        }
    }
    if(pre!=m.preAliasInvalidVertexLinks||post!=m.postAliasInvalidVertexLinks||unsupported!=m.unsupportedAliasStars)throw Error("Alias topology diagnostics mismatch");
    cancellation::checkpoint();
}
// Strict persisted diagnostics validation, including unsupported-only meshes.
inline void validateSurfaceAliasDiagnostics(const SurfaceMesh& m){
    validateSurfaceAliases(m);
    if(!m.topologyAliasSources.empty())return;
    using namespace surface_alias_detail;
    if(geometryBytes(m)+6*m.vertices.size()+12*m.triangles.size()+8192>10*1024*1024)throw Error("Alias diagnostics memory budget exceeded");
    const Links links(m,false);std::size_t invalid=0,unsupported=0;
    for(std::size_t v=0;v<m.vertices.size();++v){
        if((v&127u)==0)cancellation::checkpoint();
        const auto s=star(m,links,v,false);invalid+=s.unsupported||s.components>1;unsupported+=s.unsupported;
    }
    if(m.preAliasInvalidVertexLinks!=invalid||m.postAliasInvalidVertexLinks!=invalid||m.unsupportedAliasStars!=unsupported)throw Error("Unaliased topology diagnostics mismatch");
}
// Atomic local normalization. The source is untouched on cancellation/refusal.
// No faces/positions/attributes/observations/statistics are created or discarded;
// only representation vertices are appended. The memory ceiling covers retained
// mesh capacities plus simultaneous candidate buffers and bounded link scratch.
inline void normalizeSurfaceAliases(SurfaceMesh& m,std::size_t maxVertices=40000,std::size_t maxMemoryBytes=10*1024*1024){
    using namespace surface_alias_detail;
    if(maxVertices>40000||m.vertices.size()>maxVertices||maxMemoryBytes>10*1024*1024)throw Error("Alias normalization limits exceeded");
    if(m.triangles.size()>80000||m.topologyAliasSources.size()>m.vertices.size())throw Error("Alias geometry bounds exceeded");
    const auto n=m.vertices.size(),f=m.triangles.size();
    const auto scratch=6*n+12*f+8192;
    const auto retained=geometryBytes(m);
    if(retained+scratch>maxMemoryBytes)throw Error("Alias planning memory budget exceeded");
    validateSurfaceAliases(m);if(!m.topologyAliasSources.empty())return;
    std::vector<SurfaceVertex> vertices;std::vector<SurfaceTriangle> triangles;std::vector<std::uint32_t> aliases;
    std::size_t invalid=0,unsupported=0,added=0,repaired=0;
    {
        const Links links(m,false);
        std::vector<std::uint8_t> protectedVertex(n,0),eligible(n,0);
        for(std::size_t v=0;v<n;++v){
            if((v&127u)==0)cancellation::checkpoint();
            const auto s=star(m,links,v,false);
            if(s.unsupported||s.components>1)++invalid;
            if(s.unsupported){++unsupported;protectedVertex[v]=1;}
        }
        for(std::size_t v=0;v<n;++v){
            if((v&127u)==0)cancellation::checkpoint();
            const auto s=star(m,links,v,false);
            if(s.unsupported||s.components<2)continue;
            bool protectedFace=false;
            for(std::size_t j=0;j<s.size;++j)for(auto id:indices(m.triangles[s.nodes[j]/3]))protectedFace|=protectedVertex[id]!=0;
            // Never change ANY corner of a face incident to an unsupported star.
            if(protectedFace)continue;
            eligible[v]=1;++repaired;added+=s.components-1;
            if(added>maxVertices-n)throw Error("Alias output vertex budget exceeded");
        }
        if(!added){cancellation::checkpoint();m.preAliasInvalidVertexLinks=invalid;m.postAliasInvalidVertexLinks=invalid;m.unsupportedAliasStars=unsupported;return;}
        const auto peak=retained+scratch+6*added+(n+added)*sizeof(SurfaceVertex)+f*sizeof(SurfaceTriangle)+added*sizeof(std::uint32_t);
        if(peak>maxMemoryBytes)throw Error("Alias candidate memory budget exceeded");
        vertices.reserve(n+added);vertices.insert(vertices.end(),m.vertices.begin(),m.vertices.end());triangles=m.triangles;aliases.reserve(added);
        for(std::size_t v=0;v<n;++v){
            if((v&127u)==0)cancellation::checkpoint();
            if(!eligible[v])continue;
            const auto s=star(m,links,v,false);
            std::array<std::uint32_t,maxIncident> order{},targets{};for(std::size_t c=0;c<s.components;++c)order[c]=static_cast<std::uint32_t>(c);
            std::sort(order.begin(),order.begin()+static_cast<std::ptrdiff_t>(s.components),[&](auto a,auto b){return s.representative[a]<s.representative[b];});
            targets[order[0]]=static_cast<std::uint32_t>(v);
            for(std::size_t c=1;c<s.components;++c){targets[order[c]]=static_cast<std::uint32_t>(vertices.size());vertices.push_back(m.vertices[v]);aliases.push_back(static_cast<std::uint32_t>(v));}
            for(std::size_t j=0;j<s.size;++j){const auto node=s.nodes[j];auto& t=triangles[node/3];std::array<std::uint32_t*,3> corners{&t.a,&t.b,&t.c};*corners[node%3]=targets[s.component[j]];}
        }
    }
    SurfaceMesh candidate;candidate.vertices=std::move(vertices);candidate.triangles=std::move(triangles);candidate.topologyAliasSources=std::move(aliases);
    candidate.preAliasInvalidVertexLinks=invalid;candidate.postAliasInvalidVertexLinks=invalid-repaired;candidate.unsupportedAliasStars=unsupported;
    validateSurfaceAliases(candidate); // links scratch above has been released
    cancellation::checkpoint();
    m.vertices.swap(candidate.vertices);m.triangles.swap(candidate.triangles);m.topologyAliasSources.swap(candidate.topologyAliasSources);
    m.preAliasInvalidVertexLinks=invalid;m.postAliasInvalidVertexLinks=invalid-repaired;m.unsupportedAliasStars=unsupported;
}
}
