#pragma once
#include "realityforge/mesh_cleanup.hpp"
#include <map>

namespace rf {
// Internal proof over ORIGINAL vertex indices. -1 = no proof, -2 = ambiguous.
// Only removed, nondegenerate, identically oriented faces provide equivalences.
// This never welds vertices, edits attributes, or uses a positional tolerance.
inline std::vector<std::int32_t> exactCleanupObservationTargets(
    const SurfaceMesh& source,const CleanupPlan& plan,const CleanupSettings& settings={}) {
    if(source.vertices.size()>40000||source.triangles.size()>80000)throw Error("Surface provenance input budget exceeded");
    const auto expected=planMeshCleanup(source,settings);
    if(plan.inputVertices!=expected.inputVertices||plan.inputFaces!=expected.inputFaces||
       plan.removedFaces!=expected.removedFaces||plan.sourceFaceForResult!=expected.sourceFaceForResult)
        throw Error("Stale provenance cleanup plan");
    using Point=std::array<double,3>;
    using Key=std::array<Point,3>;
    using Corners=std::array<std::uint32_t,3>;
    const auto canonical=[&](const SurfaceTriangle& t) {
        Corners corners{t.a,t.b,t.c};Key key{};
        for(std::size_t i=0;i<3;++i){const auto p=source.vertices[corners[i]].xyz;key[i]={p.x,p.y,p.z};}
        const auto offset=std::min_element(key.begin(),key.end())-key.begin();
        std::rotate(key.begin(),key.begin()+offset,key.end());
        std::rotate(corners.begin(),corners.begin()+offset,corners.end());
        return std::pair{key,corners};
    };
    std::vector<std::int32_t> targets(source.vertices.size(),-1);
    std::map<Key,Corners> retained;
    std::size_t visited=0;
    for(auto index:plan.sourceFaceForResult){
        if((visited++&255u)==0)cancellation::checkpoint();
        auto [key,corners]=canonical(source.triangles[index]);retained.emplace(key,corners);
    }
    for(auto index:plan.removedFaces){
        if((visited++&255u)==0)cancellation::checkpoint();
        auto [key,corners]=canonical(source.triangles[index]);
        const auto found=retained.find(key);if(found==retained.end())continue;
        for(std::size_t i=0;i<3;++i){
            const auto from=corners[i],to=found->second[i];auto& target=targets[from];
            if(target==-1)target=static_cast<std::int32_t>(to);
            else if(target!=static_cast<std::int32_t>(to))target=-2;
        }
    }
    cancellation::checkpoint();return targets;
}

struct CompactedSurfaceObservations {
    std::vector<SurfaceObservationRef> attached;
    std::vector<SurfaceUnattachedObservation> unattached;
};
inline CompactedSurfaceObservations compactSurfaceObservations(
    std::span<const SurfaceObservationRef> observations,
    std::span<const std::int32_t> remap,std::span<const std::int32_t> exactTargets) {
    if(observations.size()>MAX_DENSE_SAMPLES||remap.size()>40000||exactTargets.size()!=remap.size())
        throw Error("Surface provenance budget exceeded");
    CompactedSurfaceObservations result;result.attached.reserve(observations.size());
    std::vector<std::uint8_t> sampleSeen(MAX_DENSE_SAMPLES,0);
    std::vector<std::uint64_t> sourcePixels;sourcePixels.reserve(observations.size());
    for(std::size_t i=0;i<remap.size();++i){
        if((i&255u)==0)cancellation::checkpoint();
        if(remap[i]<-1||(remap[i]>=0&&static_cast<std::size_t>(remap[i])>=remap.size())||
           exactTargets[i]<-2||(exactTargets[i]>=0&&static_cast<std::size_t>(exactTargets[i])>=remap.size()))
            throw Error("Invalid provenance compaction map");
    }
    std::size_t visited=0;
    for(auto ref:observations){
        if((visited++&255u)==0)cancellation::checkpoint();
        if(ref.vertex>=remap.size()||ref.camera>=MAX_DENSE_VIEWS||ref.sourceSampleId>=MAX_DENSE_SAMPLES||
           ref.relation!=SurfaceObservationRelation::Accumulator||
           sampleSeen[ref.sourceSampleId]++)
            throw Error("Invalid or repeated surface observation identity");
        sourcePixels.push_back((static_cast<std::uint64_t>(ref.camera)<<32)|(static_cast<std::uint64_t>(ref.x)<<16)|ref.y);
        auto mapped=remap[ref.vertex];
        if(mapped<0){
            const auto target=exactTargets[ref.vertex];
            if(target>=0){
                if(static_cast<std::size_t>(target)>=remap.size()||remap[target]<0)
                    throw Error("Invalid exact duplicate target");
                mapped=remap[target];ref.relation=SurfaceObservationRelation::ExactDuplicateFace;
            }else{
                result.unattached.push_back({ref.camera,ref.x,ref.y,ref.sourceSampleId,
                    target==-2?SurfaceUnattachedReason::AmbiguousExactDuplicate:SurfaceUnattachedReason::NoRetainedFace});
                continue;
            }
        }
        ref.vertex=static_cast<std::uint32_t>(mapped);result.attached.push_back(ref);
    }
    std::size_t comparisons=0;
    std::sort(sourcePixels.begin(),sourcePixels.end(),[&](auto a,auto b){
        if((comparisons++&4095u)==0)cancellation::checkpoint();
        return a<b;
    });
    if(std::adjacent_find(sourcePixels.begin(),sourcePixels.end())!=sourcePixels.end())
        throw Error("Repeated surface observation pixel identity");
    cancellation::checkpoint();return result;
}
}
