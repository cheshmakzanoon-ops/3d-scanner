#pragma once
#include "realityforge/bundle_adjustment.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <iomanip>
#include <limits>
#include <locale>
#include <map>
#include <numeric>
#include <set>
#include <sstream>
#include <span>
#include <string>
#include <vector>

namespace rf::jni {
namespace bundle_bridge {
inline std::size_t index(double value,std::size_t count){
    if(!std::isfinite(value)||value<0||std::floor(value)!=value||value>=static_cast<double>(count))throw Error("Invalid typed bundle index");
    return static_cast<std::size_t>(value);
}
inline std::uint8_t color(double value){
    if(!std::isfinite(value)||value<0||value>255)throw Error("Invalid typed bundle color");
    return static_cast<std::uint8_t>(value);
}
inline bool sha256(const std::string& value){return value.size()==64&&std::all_of(value.begin(),value.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});}
inline Vec3 cameraPoint(const ViewPose& pose,Vec3 world){auto q=multiply(pose.rotation,world);return {q.x+pose.translation.x,q.y+pose.translation.y,q.z+pose.translation.z};}
inline std::string quoted(const std::string& value){
    std::string out="\"";
    for(unsigned char c:value){if(c<32)throw Error("Control character in typed bundle identity");if(c=='"'||c=='\\')out+='\\';out+=static_cast<char>(c);}return out+'"';
}
struct Prepared {
    BundleProblem problem;
    std::vector<std::string> groupIds;
    std::size_t loopAttempts{},loopVerified{};
};
/** Parses value-owned packed inputs; no Java references, borrowed strings or operation-token state persist. */
inline Prepared prepare(std::span<const double> cameraRows,std::span<const double> seedRows,std::span<const double> pixelRows,
                        std::span<const double> intrinsicRows,std::span<const std::string> declaredGroups,
                        std::span<const std::string> photos,std::span<const std::string> sourceTracks,
                        bool measured,bool refineFocal,double threshold,int iterations){
    if(cameraRows.size()%12||cameraRows.size()/12<3||cameraRows.size()/12>32||seedRows.size()%7||seedRows.size()/7<16||seedRows.size()/7>20000||
       pixelRows.size()%7||pixelRows.size()/7<32||pixelRows.size()/7>100000||sourceTracks.size()<16||sourceTracks.size()>20000||
       intrinsicRows.size()!=cameraRows.size()/12*11||declaredGroups.size()!=cameraRows.size()/12||photos.size()!=declaredGroups.size()||
       !std::isfinite(threshold)||threshold<=0||threshold>.025||iterations<0||iterations>25)throw Error("Invalid typed bundle input shape or budget");
    const std::size_t nc=declaredGroups.size();
    for(const auto& values:{cameraRows,seedRows,pixelRows,intrinsicRows})for(double v:values)if(!std::isfinite(v))throw Error("Nonfinite typed bundle input");
    std::set<std::string> photoSet,sourceSet;
    for(const auto& photo:photos)if(photo.empty()||photo.size()>256||!photoSet.insert(photo).second)throw Error("Invalid typed bundle photo identity");
    for(const auto& track:sourceTracks)if(!sha256(track)||!sourceSet.insert(track).second)throw Error("Invalid typed bundle full source mapping");
    Prepared out;auto& p=out.problem;
    std::map<std::string,std::size_t> groups;
    for(std::size_t camera=0;camera<nc;++camera){
        cancellation::checkpoint();
        ViewPose pose;std::copy_n(cameraRows.begin()+static_cast<std::ptrdiff_t>(camera*12),9,pose.rotation.begin());
        pose.translation={cameraRows[camera*12+9],cameraRows[camera*12+10],cameraRows[camera*12+11]};
        if(std::abs(determinant(pose.rotation)-1.)>.02)throw Error("Invalid typed bundle rotation");
        const auto orth=multiply(pose.rotation,transpose(pose.rotation));for(int k=0;k<9;++k)if(std::abs(orth[k]-(k%4==0?1.:0.))>.025)throw Error("Invalid typed bundle rotation");
        p.cameras.push_back(pose);
        const auto& id=declaredGroups[camera];if(id.empty()||id.size()>1024)throw Error("Invalid typed intrinsic group identity");
        IntrinsicModel model;model.width=index(intrinsicRows[camera*11],100001);model.height=index(intrinsicRows[camera*11+1],100001);
        model.lensCropIdentity=id;model.measured=measured;std::copy_n(intrinsicRows.begin()+static_cast<std::ptrdiff_t>(camera*11+2),9,model.parameters.begin());
        model.refinable[0]=model.refinable[1]=!measured&&refineFocal;validateIntrinsicModel(model);
        const auto existing=groups.find(id);
        if(existing!=groups.end()){
            const auto& other=p.intrinsicGroups[existing->second].model;
            if(other.width!=model.width||other.height!=model.height||other.parameters!=model.parameters||other.measured!=model.measured)throw Error("Declared intrinsic group model disagrees");
            p.cameraIntrinsicGroups.push_back(existing->second);
        }else{
            const auto group=p.intrinsicGroups.size();groups.emplace(id,group);out.groupIds.push_back(id);
            BundleIntrinsicGroup value;value.model=model;value.prior.centre=model.parameters;
            for(std::size_t k=0;k<9;++k){value.prior.sigma[k]=1.;value.prior.lower[k]=model.parameters[k];value.prior.upper[k]=model.parameters[k];}
            // Explicit estimator policy only: 5% focal regularizer, at most15% movement. This is not measured calibration uncertainty.
            for(std::size_t k=0;k<2;++k){value.prior.sigma[k]=model.parameters[k]*.05;value.prior.lower[k]=model.parameters[k]*.85;value.prior.upper[k]=model.parameters[k]*1.15;}
            p.intrinsicGroups.push_back(value);p.cameraIntrinsicGroups.push_back(group);
        }
    }
    std::map<std::size_t,BundleSeed> seeds;
    for(std::size_t row=0;row<seedRows.size();row+=7){
        BundleSeed seed;seed.track=index(seedRows[row],sourceTracks.size());seed.xyz={seedRows[row+1],seedRows[row+2],seedRows[row+3]};
        for(std::size_t k=0;k<3;++k)seed.color[k]=color(seedRows[row+4+k]);
        if(!seeds.emplace(seed.track,seed).second)throw Error("Duplicate typed bundle seed");
        p.seeds.push_back(seed);p.stableTrackMapping.emplace_back(seed.track,sourceTracks[seed.track]);
    }
    struct Supported {std::size_t camera;Vec2 normalized;};
    std::map<std::size_t,std::vector<Supported>> supported;
    std::map<std::pair<std::size_t,std::size_t>,Vec2> observations;
    std::vector<std::set<std::size_t>> groupViews(p.intrinsicGroups.size());
    std::vector<double> minRadius(p.intrinsicGroups.size(),std::numeric_limits<double>::infinity()),maxRadius(p.intrinsicGroups.size()),
        minDepth(p.intrinsicGroups.size(),std::numeric_limits<double>::infinity()),maxDepth(p.intrinsicGroups.size());
    std::vector<std::size_t> components(nc);std::iota(components.begin(),components.end(),0);
    const auto component=[&](std::size_t value){while(components[value]!=value)value=components[value];return value;};
    for(std::size_t row=0;row<pixelRows.size();row+=7){
        cancellation::checkpoint();const auto track=index(pixelRows[row],sourceTracks.size()),camera=index(pixelRows[row+1],nc);
        const auto group=p.cameraIntrinsicGroups[camera];const auto& model=p.intrinsicGroups[group].model;
        const Vec2 pixel{pixelRows[row+2],pixelRows[row+3]};
        if(pixel.x<-.5||pixel.y<-.5||pixel.x>model.width-.5||pixel.y>model.height-.5)throw Error("Typed bundle pixel outside declared image");
        std::array<std::uint8_t,3> rgb{};for(std::size_t k=0;k<3;++k)rgb[k]=color(pixelRows[row+4+k]);
        if(!seeds.contains(track))continue; // Source snapshot may also contain tracks rejected by native reconstruction.
        const auto normalized=unprojectIntrinsic(model,pixel);
        if(!observations.emplace(std::make_pair(track,camera),normalized).second)throw Error("Duplicate typed bundle observation");
        p.observations.push_back({track,camera,pixel,rgb});
        const auto q=cameraPoint(p.cameras[camera],seeds.at(track).xyz);
        if(q.z<=1e-8||!std::isfinite(q.z)||std::hypot(q.x/q.z-normalized.x,q.y/q.z-normalized.y)>threshold)continue;
        supported[track].push_back({camera,normalized});
    }
    for(const auto& [track,rows]:supported){
        if(rows.size()<2)continue;
        for(const auto& row:rows){const auto group=p.cameraIntrinsicGroups[row.camera];auto& evidence=p.intrinsicGroups[group].evidence;
            ++evidence.observations;groupViews[group].insert(row.camera);
            const auto radius=std::hypot(row.normalized.x,row.normalized.y),depth=cameraPoint(p.cameras[row.camera],seeds.at(track).xyz).z;
            minRadius[group]=std::min(minRadius[group],radius);maxRadius[group]=std::max(maxRadius[group],radius);
            minDepth[group]=std::min(minDepth[group],depth);maxDepth[group]=std::max(maxDepth[group],depth);
        }
        for(std::size_t k=1;k<rows.size();++k){const auto a=component(rows[0].camera),b=component(rows[k].camera);components[std::max(a,b)]=std::min(a,b);}
    }
    for(std::size_t group=0;group<p.intrinsicGroups.size();++group){auto& e=p.intrinsicGroups[group].evidence;
        e.independentViews=groupViews[group].size();e.normalizedRadiusSpan=e.observations?maxRadius[group]-minRadius[group]:0.;
        e.depthRatio=e.observations?std::max(1.,maxDepth[group]/minDepth[group]):1.;}
    // Only nonadjacent same-component revisits. Each accepted landmark requires two OTHER current geometrically supported views.
    for(std::size_t revisit=3;revisit<nc&&out.loopAttempts<64;++revisit)for(std::size_t source=0;source+3<=revisit&&out.loopAttempts<64;++source){
        cancellation::checkpoint();if(component(source)!=component(revisit))continue;
        LoopProposal proposal;proposal.sourcePhotoId=photos[source];proposal.revisitPhotoId=photos[revisit];
        proposal.componentId=proposal.revisitComponentId="native-observed-component-"+std::to_string(component(source));
        proposal.revisitCamera=revisit;proposal.currentPose=p.cameras[revisit];
        for(const auto& [track,seed]:seeds){
            const auto observed=observations.find({track,revisit});const auto support=supported.find(track);
            if(observed==observations.end()||support==supported.end())continue;
            std::size_t independent=0;bool sourceSupports=false;
            for(const auto& row:support->second)if(row.camera!=revisit){++independent;if(row.camera==source)sourceSupports=true;}
            if(!sourceSupports||independent<2)continue;
            proposal.landmarks.push_back({track,sourceTracks[track],seed.xyz,observed->second,independent});
            if(proposal.landmarks.size()==2000)break;
        }
        if(proposal.landmarks.size()<16)continue;
        ++out.loopAttempts;const auto decision=verifyLoopClosure(proposal,threshold,2000);
        if(decision.accepted){++out.loopVerified;p.loops.push_back(decision.constraint);}
    }
    return out;
}
} // namespace bundle_bridge

inline std::string bundleProblemJson(std::span<const double> cameraRows,std::span<const double> seedRows,std::span<const double> pixelRows,
                                     std::span<const double> intrinsicRows,std::span<const std::string> groupIds,
                                     std::span<const std::string> photos,std::span<const std::string> sourceTracks,
                                     bool measured,bool refineFocal,double threshold,int iterations){
    auto prepared=bundle_bridge::prepare(cameraRows,seedRows,pixelRows,intrinsicRows,groupIds,photos,sourceTracks,measured,refineFocal,threshold,iterations);
    BundleSettings settings;settings.maxReprojectionError=threshold;settings.maxIterations=iterations;settings.gauge=BundleGauge::PoseAndBaseline;
    const auto result=bundleAdjustProblem(prepared.problem,settings);
    auto base=bundleJson(result.geometry);if(base.empty()||base.back()!='}')throw Error("Invalid typed bundle serialization");base.pop_back();
    std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(17)<<base
        <<",\"bundleProblemSchema\":1,\"loopProposalCount\":"<<prepared.loopAttempts<<",\"verifiedLoopCount\":"<<prepared.loopVerified
        <<",\"acceptedLoopCorrections\":"<<result.acceptedLoopCorrections<<",\"acceptedIntrinsicGroups\":"<<result.acceptedIntrinsicGroups
        <<",\"intrinsicPolicy\":\"fixed-measured; estimated-focal-5percent-regularizer-15percent-bound-v1; algorithmic policy, not measured sigma\",\"cameraIntrinsics\":[";
    for(std::size_t camera=0;camera<prepared.problem.cameras.size();++camera){if(camera)out<<',';
        const auto& model=result.intrinsics[prepared.problem.cameraIntrinsicGroups[camera]];out<<'['<<model.width<<','<<model.height;
        for(double value:model.parameters)out<<','<<value;
        out<<']';}
    out<<"],\"cameraIntrinsicGroups\":[";for(std::size_t i=0;i<prepared.problem.cameraIntrinsicGroups.size();++i){if(i)out<<',';out<<prepared.problem.cameraIntrinsicGroups[i];}
    out<<"],\"intrinsicGroups\":[";
    for(std::size_t group=0;group<result.intrinsics.size();++group){if(group)out<<',';const auto& model=result.intrinsics[group];
        out<<"{\"id\":"<<bundle_bridge::quoted(prepared.groupIds[group])<<",\"measured\":"<<(model.measured?"true":"false")
            <<",\"refinableFocal\":"<<(model.refinable[0]?"true":"false")<<",\"observability\":"<<static_cast<int>(intrinsicObservability(model,prepared.problem.intrinsicGroups[group].evidence))<<'}';}
    out<<"],\"stableTrackMapping\":[";for(std::size_t i=0;i<result.stableTrackMapping.size();++i){if(i)out<<',';const auto& entry=result.stableTrackMapping[i];out<<'['<<entry.first<<','<<bundle_bridge::quoted(entry.second)<<']';}
    return out.str()+"]}";
}
} // namespace rf::jni
