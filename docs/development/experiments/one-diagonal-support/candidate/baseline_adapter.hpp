#pragma once
// Test-only original adapter reference; body copied without numeric edits.
#include "realityforge/surface_evidence.hpp"
namespace rf {
inline ObservedNormal baselineDepthNeighborhoodNormal(const DenseView& view,const DenseDepthMap& map,
                                                       int x,int y,int step,double maxRelativeJump=.08) {
    cancellation::checkpoint();
    if(step<1||step>64||view.width!=map.width||view.height!=map.height||map.width<1||map.height<1||map.width>100000||map.height>100000||
       map.depth.size()!=static_cast<std::size_t>(map.width)*map.height||map.confidence.size()!=map.depth.size()||!std::isfinite(view.fx)||!std::isfinite(view.fy)||!std::isfinite(view.cx)||!std::isfinite(view.cy)||view.fx<=0||view.fy<=0||
       !std::isfinite(maxRelativeJump)||maxRelativeJump<=0||maxRelativeJump>.5)
        throw Error("Invalid depth normal geometry");
    ObservedNormal unknown;
    for(auto r:view.pose.rotation)if(!std::isfinite(r))throw Error("Non-finite normal pose");
    if(!surface_evidence_detail::finite(view.pose.translation))throw Error("Non-finite normal translation");
    const auto orth=multiply(view.pose.rotation,transpose(view.pose.rotation));
    for(int i=0;i<9;++i)if(std::abs(orth[i]-(i%4==0?1.:0.))>1e-5)throw Error("Normal pose is not orthonormal");
    if(std::abs(determinant(view.pose.rotation)-1)>1e-5)throw Error("Normal pose has invalid orientation");
    if(x<step||y<step||x>=map.width-step||y>=map.height-step)return unknown;
    const double z=map.depth[static_cast<std::size_t>(y)*map.width+x];
    if(!std::isfinite(z)||z<=0)return unknown;
    const auto inverse=transpose(view.pose.rotation);
    const auto camera=multiply(inverse,Vec3{-view.pose.translation.x,-view.pose.translation.y,-view.pose.translation.z});
    std::vector<NormalObservation> points;
    points.reserve(9);
    Vec3 query{};
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
        cancellation::checkpoint();
        const int u=x+dx*step,v=y+dy*step;const auto id=static_cast<std::size_t>(v)*map.width+u;
        const double depth=map.depth[id];
        if(!std::isfinite(depth)||depth<=0||std::abs(depth-z)/z>maxRelativeJump){unknown.validity=NormalValidity::Discontinuity;return unknown;}
        auto p=multiply(inverse,Vec3{(u-view.cx)*depth/view.fx-view.pose.translation.x,(v-view.cy)*depth/view.fy-view.pose.translation.y,depth-view.pose.translation.z});
        points.push_back({p,camera,id});if(dx==0&&dy==0)query=p;
    }
    // Preserve all nine depth/jump checks before interpreting reliability.
    // Zero denotes unavailable evidence for the whole window; malformed
    // confidence is an input error. Neither case silently removes a neighbor.
    bool unavailableConfidence=false;
    for(auto& point:points){
        const double confidence=map.confidence[point.observationId];
        if(!std::isfinite(confidence)||confidence<0||confidence>1)throw Error("Invalid depth normal confidence");
        unavailableConfidence=unavailableConfidence||confidence==0;
        point.weight=confidence;
    }
    if(unavailableConfidence)return unknown;
    // All nine camera-grid neighbors have already passed the finite depth and
    // discontinuity gates. A frontoparallel footprint cannot bound their world
    // distances on a slanted surface; retain the actual validated neighborhood.
    SurfaceEvidenceSettings settings;settings.radius=1e-12;
    // Use the same distance arithmetic as estimateObservedNormal: hypot and
    // sqrt(dot) can differ by more than one ulp at a boundary, losing a sample.
    for(const auto& point:points){const auto delta=surface_evidence_detail::sub(point.position,query);settings.radius=std::max(settings.radius,surface_evidence_detail::length(delta));}
    settings.radius=std::nextafter(settings.radius,std::numeric_limits<double>::infinity());
    settings.maxObservations=9;settings.maxNeighbors=9;
    settings.calibratedNoise=false; // photometric reliability is not physical sigma
    return estimateObservedNormal(points,query,settings);
}
}
