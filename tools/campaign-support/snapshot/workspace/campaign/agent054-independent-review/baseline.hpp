#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <span>
#include <set>
#include <optional>
#include <vector>

namespace rf {
enum class BaselineValidity { Supported, SparseSupport, Discontinuity, IllConditioned, Invalid };
struct BaselineObservation { Vec3 position{}, cameraCenter{}; std::uint64_t observationId{}; };
struct BaselineUncertainty {
    double planeResidual{}, conditioning{}, estimatedPositionSigma{};
    double maximumParallaxRadians{};
    std::optional<double> triangulationSigma;
    bool calibrated{}; // false means geometric/noise heuristic, never measured physical error
};
struct BaselineNormal {
    Vec3 direction{};
    BaselineUncertainty uncertainty{};
    BaselineValidity validity{BaselineValidity::SparseSupport};
    std::vector<std::uint64_t> supportingObservationIds;
};
struct BaselineSettings {
    std::size_t maxObservations{256}, maxNeighbors{64}, minimumNeighbors{5};
    double radius{1}, maximumResidualRatio{0.08}, minimumConditioning{0.02};
    double observationSigma{0.001};
    bool calibratedNoise{};
};
namespace baseline_detail {
inline Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline double length(Vec3 a){return std::sqrt(dot(a,a));}
inline bool finite(Vec3 p){return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z);}
}
// Bounded covariance fit; smallest eigenvector is the supported local plane.
// At depth discontinuities callers must use the depth-neighborhood adapter.
inline BaselineNormal baselineNormal(std::span<const BaselineObservation> observations,
                                             Vec3 query,const BaselineSettings& s={}) {
    using namespace baseline_detail;
    cancellation::checkpoint();
    if(observations.size()>s.maxObservations || s.maxObservations>100000 || s.maxNeighbors>256 ||
       s.minimumNeighbors<3 || s.minimumNeighbors>s.maxNeighbors || !finite(query) || length(query)>1e50 ||
       !std::isfinite(s.radius)||s.radius<=0||s.radius>1e50||s.radius<1e-50 || !std::isfinite(s.observationSigma)||s.observationSigma<0 ||
       !std::isfinite(s.maximumResidualRatio)||s.maximumResidualRatio<=0 ||s.maximumResidualRatio>=1 ||
       !std::isfinite(s.minimumConditioning)||s.minimumConditioning<=0||s.minimumConditioning>=1)
        throw Error("Invalid surface evidence settings/input bounds");
    std::vector<std::pair<double,std::size_t>> nearby;
    std::set<std::uint64_t> identities;
    for(std::size_t i=0;i<observations.size();++i) {
        if((i&31u)==0)cancellation::checkpoint();
        const auto& o=observations[i];
        if(!finite(o.position)||!finite(o.cameraCenter)||length(o.position)>1e50||length(o.cameraCenter)>1e50||!identities.insert(o.observationId).second)throw Error("Invalid normal observation or duplicate identity");
        const double d=length(sub(o.position,query));
        if(d<=s.radius)nearby.emplace_back(d,i);
    }
    std::sort(nearby.begin(),nearby.end(),[&](auto a,auto b){
        return a.first==b.first?observations[a.second].observationId<observations[b.second].observationId:a.first<b.first;
    });
    if(nearby.size()>s.maxNeighbors)nearby.resize(s.maxNeighbors);
    BaselineNormal out;
    if(nearby.size()<s.minimumNeighbors)return out;
    Vec3 center{};
    for(auto item:nearby){const auto& p=observations[item.second].position;center.x+=p.x/nearby.size();center.y+=p.y/nearby.size();center.z+=p.z/nearby.size();}
    double a[3][3]{}; double eigen[3][3]{{1,0,0},{0,1,0},{0,0,1}};
    for(auto item:nearby){auto p=sub(observations[item.second].position,center);double x[]{p.x,p.y,p.z};for(int i=0;i<3;++i)for(int j=0;j<3;++j)a[i][j]+=x[i]*x[j]/nearby.size();}
    for(int iteration=0;iteration<32;++iteration){
        cancellation::checkpoint();
        int p=0,q=1;
        for(int i=0;i<3;++i)for(int j=i+1;j<3;++j)if(std::abs(a[i][j])>std::abs(a[p][q])){p=i;q=j;}
        if(std::abs(a[p][q])<=std::numeric_limits<double>::epsilon()*std::max({a[0][0],a[1][1],a[2][2],1e-300}))break;
        const double phi=.5*std::atan2(2*a[p][q],a[q][q]-a[p][p]),c=std::cos(phi),d=std::sin(phi);
        const double pp=c*c*a[p][p]-2*c*d*a[p][q]+d*d*a[q][q],qq=d*d*a[p][p]+2*c*d*a[p][q]+c*c*a[q][q];
        for(int k=0;k<3;++k)if(k!=p&&k!=q){const double kp=c*a[k][p]-d*a[k][q],kq=d*a[k][p]+c*a[k][q];a[k][p]=a[p][k]=kp;a[k][q]=a[q][k]=kq;}
        a[p][p]=pp;a[q][q]=qq;a[p][q]=a[q][p]=0;
        for(int k=0;k<3;++k){const double kp=c*eigen[k][p]-d*eigen[k][q],kq=d*eigen[k][p]+c*eigen[k][q];eigen[k][p]=kp;eigen[k][q]=kq;}
    }
    std::array<int,3> ids{0,1,2};std::sort(ids.begin(),ids.end(),[&](int i,int j){return a[i][i]<a[j][j];});
    const double small=std::max(0.,a[ids[0]][ids[0]]),mid=std::max(0.,a[ids[1]][ids[1]]),large=std::max(0.,a[ids[2]][ids[2]]);
    out.uncertainty.conditioning=large>0?mid/large:0;
    out.uncertainty.planeResidual=std::sqrt(small);
    out.uncertainty.estimatedPositionSigma=std::sqrt(small+s.observationSigma*s.observationSigma)/std::sqrt(static_cast<double>(nearby.size()));
    out.uncertainty.calibrated=s.calibratedNoise;
    double maximumSine=0;
    for(std::size_t i=0;i<nearby.size();++i){
        cancellation::checkpoint();
        const auto a=sub(observations[nearby[i].second].cameraCenter,query);const double la=length(a);
        if(la<=0)continue;
        for(std::size_t j=i+1;j<nearby.size();++j){const auto b=sub(observations[nearby[j].second].cameraCenter,query);const double lb=length(b);if(lb<=0)continue;
            const double cosine=std::clamp(dot(a,b)/(la*lb),-1.,1.);const double angle=std::acos(cosine);out.uncertainty.maximumParallaxRadians=std::max(out.uncertainty.maximumParallaxRadians,angle);
            maximumSine=std::max(maximumSine,std::sqrt(std::max(0.,1-cosine*cosine)));
        }
    }
    if(maximumSine>1e-6)out.uncertainty.triangulationSigma=s.observationSigma/maximumSine;
    if(!std::isfinite(small)||!std::isfinite(mid)||!std::isfinite(large)||!std::isfinite(out.uncertainty.estimatedPositionSigma))throw Error("Unrepresentable surface evidence covariance");
    if(large<=0||mid<=0||out.uncertainty.conditioning<s.minimumConditioning||small/mid>s.maximumResidualRatio){out.validity=BaselineValidity::IllConditioned;return out;}
    out.direction={eigen[0][ids[0]],eigen[1][ids[0]],eigen[2][ids[0]]};
    const auto camera=sub(observations[nearby.front().second].cameraCenter,query);
    if(dot(out.direction,camera)<0){out.direction.x=-out.direction.x;out.direction.y=-out.direction.y;out.direction.z=-out.direction.z;}
    std::vector<double> residuals;residuals.reserve(nearby.size());
    for(auto item:nearby)residuals.push_back(dot(sub(observations[item.second].position,center),out.direction));
    std::sort(residuals.begin(),residuals.end());
    // Two tight separated layers are not one uncertain average plane.
    for(std::size_t split=3;split+3<=residuals.size();++split){
        if(residuals[split]-residuals[split-1]>6*s.observationSigma&&
           residuals[split-1]-residuals.front()<=2*s.observationSigma&&
           residuals.back()-residuals[split]<=2*s.observationSigma){out.validity=BaselineValidity::Discontinuity;return out;}
    }
    out.validity=BaselineValidity::Supported;
    for(auto item:nearby)out.supportingObservationIds.push_back(observations[item.second].observationId);
    return out;
}
// Depth camera-z samples are distinct from mesh vertex normals. A 3x3 support
// window rejects any missing or discontinuous neighbor rather than bridge a gap.
inline BaselineNormal baselineDepthNormal(const DenseView& view,const DenseDepthMap& map,
                                                       int x,int y,int step,double maxRelativeJump=.08) {
    if(step<1||step>64||view.width!=map.width||view.height!=map.height||map.width<1||map.height<1||map.width>100000||map.height>100000||
       map.depth.size()!=static_cast<std::size_t>(map.width)*map.height||!std::isfinite(view.fx)||!std::isfinite(view.fy)||!std::isfinite(view.cx)||!std::isfinite(view.cy)||view.fx<=0||view.fy<=0||
       !std::isfinite(maxRelativeJump)||maxRelativeJump<=0||maxRelativeJump>.5)
        throw Error("Invalid depth normal geometry");
    BaselineNormal unknown;
    for(auto r:view.pose.rotation)if(!std::isfinite(r))throw Error("Non-finite normal pose");
    if(!baseline_detail::finite(view.pose.translation))throw Error("Non-finite normal translation");
    const auto orth=multiply(view.pose.rotation,transpose(view.pose.rotation));
    for(int i=0;i<9;++i)if(std::abs(orth[i]-(i%4==0?1.:0.))>1e-5)throw Error("Normal pose is not orthonormal");
    if(std::abs(determinant(view.pose.rotation)-1)>1e-5)throw Error("Normal pose has invalid orientation");
    if(x<step||y<step||x>=map.width-step||y>=map.height-step)return unknown;
    const double z=map.depth[static_cast<std::size_t>(y)*map.width+x];
    if(!std::isfinite(z)||z<=0)return unknown;
    const auto inverse=transpose(view.pose.rotation);
    const auto camera=multiply(inverse,Vec3{-view.pose.translation.x,-view.pose.translation.y,-view.pose.translation.z});
    std::vector<BaselineObservation> points;
    Vec3 query{};
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
        const int u=x+dx*step,v=y+dy*step;const auto id=static_cast<std::size_t>(v)*map.width+u;
        const double depth=map.depth[id];
        if(!std::isfinite(depth)||depth<=0||std::abs(depth-z)/z>maxRelativeJump){unknown.validity=BaselineValidity::Discontinuity;return unknown;}
        auto p=multiply(inverse,Vec3{(u-view.cx)*depth/view.fx-view.pose.translation.x,(v-view.cy)*depth/view.fy-view.pose.translation.y,depth-view.pose.translation.z});
        points.push_back({p,camera,id});if(dx==0&&dy==0)query=p;
    }
    // All nine camera-grid neighbors have already passed the finite depth and
    // discontinuity gates. A frontoparallel footprint cannot bound their world
    // distances on a slanted surface; retain the actual validated neighborhood.
    BaselineSettings settings;settings.radius=1e-12;
    for(const auto& point:points){const auto delta=baseline_detail::sub(point.position,query);settings.radius=std::max(settings.radius,std::hypot(delta.x,delta.y,delta.z));}
    settings.radius=std::nextafter(settings.radius,std::numeric_limits<double>::infinity());
    settings.maxObservations=9;settings.maxNeighbors=9;
    return baselineNormal(points,query,settings);
}
}
