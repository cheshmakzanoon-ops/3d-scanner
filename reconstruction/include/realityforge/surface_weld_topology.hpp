#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <vector>
#include <span>
#include <limits>

namespace rf::surface_weld_topology {
// Combinatorial admission only: no tolerance, positions, face removal or claims
// about intersections. Source grids have <=8 incident faces per source pixel,
// and a fusion group has at most one source from each of eight cameras.
struct Report { std::vector<std::uint8_t> invalidLinks; std::size_t invalidVertexCount{}; };
inline Report inspect(const SurfaceMesh& mesh,std::span<const std::size_t> retainedFaces,bool excluded=false) {
    cancellation::checkpoint();
    if(mesh.vertices.size()>40000||mesh.triangles.size()>80000||retainedFaces.size()>mesh.triangles.size())throw Error("Surface topology admission budget exceeded");
    constexpr std::uint32_t none=std::numeric_limits<std::uint32_t>::max();
    // One 32-bit next pointer per triangle corner, one head per output vertex.
    // No map nodes or unbounded per-vertex adjacency allocations.
    std::vector<std::uint32_t> heads(mesh.vertices.size(),none),next(3*mesh.triangles.size(),none);
    for(std::size_t i=0;i<retainedFaces.size();++i){
        if((i&127u)==0)cancellation::checkpoint();
        if(retainedFaces[i]>=mesh.triangles.size()||(i&&retainedFaces[i]<=retainedFaces[i-1]))
            throw Error("Invalid retained face in topology admission");
    }
    const auto addFace=[&](std::size_t face){
        const auto& f=mesh.triangles[face];
        if(f.a>=mesh.vertices.size()||f.b>=mesh.vertices.size()||f.c>=mesh.vertices.size()||f.a==f.b||f.a==f.c||f.b==f.c)
            throw Error("Invalid source face in surface topology admission");
        const std::array ids{f.a,f.b,f.c};
        for(std::size_t corner=0;corner<3;++corner){const auto node=static_cast<std::uint32_t>(3*face+corner);next[node]=heads[ids[corner]];heads[ids[corner]]=node;}
    };
    if(excluded){
        std::size_t removed=0;
        for(std::size_t face=0;face<mesh.triangles.size();++face){
            if((face&127u)==0)cancellation::checkpoint();
            if(removed<retainedFaces.size()&&retainedFaces[removed]==face){++removed;continue;}
            addFace(face);
        }
    }else for(std::size_t i=0;i<retainedFaces.size();++i){if((i&127u)==0)cancellation::checkpoint();addFace(retainedFaces[i]);}
    Report result;result.invalidLinks.resize(mesh.vertices.size());
    for(std::size_t vertex=0;vertex<heads.size();++vertex){
        if((vertex&127u)==0)cancellation::checkpoint();
        if(heads[vertex]==none)continue;
        std::array<std::uint32_t,16*MAX_DENSE_VIEWS> neighbors{};
        std::array<std::size_t,16*MAX_DENSE_VIEWS> parent{},degree{},incoming{},outgoing{};std::size_t count=0;
        const auto index=[&](std::uint32_t id){for(std::size_t j=0;j<count;++j)if(neighbors[j]==id)return j;if(count>=neighbors.size())throw Error("Surface source-star neighbor budget exceeded");neighbors[count]=id;parent[count]=count;return count++;};
        const auto root=[&](std::size_t j){while(parent[j]!=j)j=parent[j];return j;};
        bool invalid=false;
        std::size_t incident=0;
        for(auto node=heads[vertex];node!=none;node=next[node]){
            if(++incident>8*MAX_DENSE_VIEWS)throw Error("Surface source-star budget exceeded");
            const auto& f=mesh.triangles[node/3];const std::array ids{f.a,f.b,f.c};const auto corner=node%3;
            const auto a=index(ids[(corner+1)%3]),b=index(ids[(corner+2)%3]);
            ++degree[a];++degree[b];++outgoing[a];++incoming[b];parent[root(a)]=root(b);
        }
        std::size_t ends=0;const auto first=root(0);
        for(std::size_t j=0;j<count;++j){invalid=invalid||degree[j]>2||incoming[j]>1||outgoing[j]>1||root(j)!=first;ends+=degree[j]==1;}
        invalid=invalid||(ends!=0&&ends!=2);
        if(invalid){result.invalidLinks[vertex]=1;++result.invalidVertexCount;}
    }
    cancellation::checkpoint();return result;
}
}
