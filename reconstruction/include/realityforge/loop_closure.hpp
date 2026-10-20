#pragma once
#include "realityforge/multiview.hpp"
#include <span>
#include <string>
#include <vector>
namespace rf {
struct LoopLandmarkEvidence {
    std::size_t compactTrack{}; // local native index; never a persistent identity
    std::string stableTrackId; // full immutable source SHA256, never truncated
    Vec3 world;
    Vec2 normalizedRevisit;
    std::size_t independentSupportingViews{};
};
struct LoopProposal {
    std::string sourcePhotoId, revisitPhotoId, componentId, revisitComponentId;
    std::size_t revisitCamera{};
    ViewPose currentPose;
    std::vector<LoopLandmarkEvidence> landmarks;
};
struct VerifiedLoopConstraint {
    std::uint32_t schema=1;
    std::string sourcePhotoId,revisitPhotoId,componentId;
    std::size_t camera{};
    ViewPose correctedPose;
    std::vector<std::size_t> supportingTrackIds; // compact IDs in same order as source identities
    std::vector<std::string> supportingStableTrackIds;
    double medianNormalizedError{}, inlierFraction{}, centreCorrection{}, rotationCorrectionRadians{};
};
struct LoopDecision {
    bool accepted=false;
    std::string reason;
    VerifiedLoopConstraint constraint;
};
// Actual calibrated robust PnP on established same-component landmarks; candidate similarity is never sufficient.
LoopDecision verifyLoopClosure(const LoopProposal&,double threshold=.003,std::size_t maximumLandmarks=2000);
}

#include "realityforge/pnp.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <set>
#include <map>
namespace rf {
inline LoopDecision verifyLoopClosure(const LoopProposal& p,double threshold,std::size_t cap) {
    cancellation::checkpoint();LoopDecision out;
    const auto reject=[&](const char* reason){out.reason=reason;return out;};
    if(!std::isfinite(threshold)||threshold<=0||threshold>.025||cap<16||cap>20000)throw Error("Invalid loop verification budget");
    if(p.sourcePhotoId.empty()||p.revisitPhotoId.empty()||p.sourcePhotoId.size()>256||p.revisitPhotoId.size()>256||p.componentId.size()>256||p.sourcePhotoId==p.revisitPhotoId)return reject("invalid_photo_identity");
    if(p.componentId.empty()||p.componentId!=p.revisitComponentId)return reject("disconnected_components");
    if(p.landmarks.size()<16||p.landmarks.size()>cap)return reject("landmark_budget");
    for(double v:p.currentPose.rotation)if(!std::isfinite(v))return reject("invalid_current_pose");
    if(std::abs(determinant(p.currentPose.rotation)-1.)>.02)return reject("invalid_current_pose");
    const auto orth=multiply(p.currentPose.rotation,transpose(p.currentPose.rotation));
    for(int k=0;k<9;++k)if(std::abs(orth[k]-(k%4==0?1.:0.))>.025)return reject("invalid_current_pose");
    if(!std::isfinite(p.currentPose.translation.x)||!std::isfinite(p.currentPose.translation.y)||!std::isfinite(p.currentPose.translation.z))return reject("invalid_current_pose");
    std::set<std::size_t> ids;std::set<std::string> sourceIds;std::vector<Observation> rows;rows.reserve(p.landmarks.size());
    std::vector<std::size_t> mapping;mapping.reserve(p.landmarks.size());
    for(const auto& e:p.landmarks){
        cancellation::checkpoint();
        if(e.stableTrackId.size()!=64||!std::all_of(e.stableTrackId.begin(),e.stableTrackId.end(),[](char ch){return (ch>='0'&&ch<='9')||(ch>='a'&&ch<='f');}))return reject("invalid_stable_track_identity");
        if(!ids.insert(e.compactTrack).second||!sourceIds.insert(e.stableTrackId).second)return reject("duplicate_track_identity");
        if(e.independentSupportingViews<2)continue;
        if(!std::isfinite(e.world.x)||!std::isfinite(e.world.y)||!std::isfinite(e.world.z)||
            !std::isfinite(e.normalizedRevisit.x)||!std::isfinite(e.normalizedRevisit.y))return reject("invalid_landmark");
        rows.push_back({e.world,e.normalizedRevisit});mapping.push_back(e.compactTrack);
    }
    if(rows.size()<16)return reject("insufficient_independent_support");
    CameraPose pose;
    try{pose=registerCamera(rows,threshold);}catch(const cancellation::Cancelled&){throw;}catch(const Error&){return reject("geometric_verification_failed");}
    cancellation::checkpoint();
    if(pose.inliers.size()<16||double(pose.inliers.size())/rows.size()<.65||pose.medianError>threshold)
        return reject("insufficient_geometric_consensus");
    // Require a spatially distributed image consensus, avoiding tiny repeated patches.
    double minX=1e9,maxX=-1e9,minY=1e9,maxY=-1e9;
    for(auto k:pose.inliers){const auto q=rows.at(k).image;minX=std::min(minX,q.x);maxX=std::max(maxX,q.x);minY=std::min(minY,q.y);maxY=std::max(maxY,q.y);}
    if((maxX-minX)*(maxY-minY)<.002)return reject("weak_image_extent");
    auto& c=out.constraint;c.sourcePhotoId=p.sourcePhotoId;c.revisitPhotoId=p.revisitPhotoId;c.componentId=p.componentId;c.camera=p.revisitCamera;
    c.correctedPose={pose.rotation,pose.translation};c.medianNormalizedError=pose.medianError;c.inlierFraction=double(pose.inliers.size())/rows.size();
    std::map<std::size_t,std::string> stableMapping;for(const auto& e:p.landmarks)stableMapping.emplace(e.compactTrack,e.stableTrackId);
    for(auto k:pose.inliers)c.supportingTrackIds.push_back(mapping.at(k));
    std::sort(c.supportingTrackIds.begin(),c.supportingTrackIds.end());
    for(auto id:c.supportingTrackIds)c.supportingStableTrackIds.push_back(stableMapping.at(id));
    const auto a=multiply(transpose(p.currentPose.rotation),p.currentPose.translation),b=multiply(transpose(pose.rotation),pose.translation);
    c.centreCorrection=std::hypot(a.x-b.x,a.y-b.y,a.z-b.z);
    const auto r=multiply(pose.rotation,transpose(p.currentPose.rotation));
    c.rotationCorrectionRadians=std::acos(std::clamp((r[0]+r[4]+r[8]-1)/2.,-1.,1.));
    out.accepted=true;out.reason="verified_resection";return out;
}
}
