#pragma once
#include "realityforge/dense.hpp"
#include "realityforge/stereo_warp.hpp"
#include <algorithm>
#include <cmath>
#include <vector>

namespace rf::detail {
// Bounded CPU view plan: keep old <=4-view behavior exactly. For larger sets,
// select at most three useful source frusta. This is a scheduling heuristic,
// never evidence of actual overlap: ZNCC and reciprocal depth gates still decide.
inline std::vector<std::vector<std::size_t>> denseSourcePlan(
    std::span<const DenseView> views, double nearDepth, double farDepth) {
    if (views.size()<2 || views.size()>MAX_DENSE_VIEWS || !std::isfinite(nearDepth) ||
        !std::isfinite(farDepth) || nearDepth<=0 || farDepth<=nearDepth)
        throw Error("Invalid dense source plan");
    std::vector<std::vector<std::size_t>> plan(views.size());
    const double mid=2./(1./nearDepth+1./farDepth);
    for (std::size_t ref=0;ref<views.size();++ref) {
        if (views.size()<=4) {
            for (std::size_t src=0;src<views.size();++src) if(src!=ref) plan[ref].push_back(src);
            continue;
        }
        std::vector<std::pair<double,std::size_t>> scores;
        const auto& a=views[ref];
        for (std::size_t src=0;src<views.size();++src) {
            if (src==ref) continue;
            const auto& b=views[src];
            const auto ca=multiply(transpose(a.pose.rotation),a.pose.translation);
            const auto cb=multiply(transpose(b.pose.rotation),b.pose.translation);
            const double baseline=std::hypot(ca.x-cb.x,ca.y-cb.y,ca.z-cb.z);
            const double heading=a.pose.rotation[6]*b.pose.rotation[6]+
                a.pose.rotation[7]*b.pose.rotation[7]+a.pose.rotation[8]*b.pose.rotation[8];
            if (!std::isfinite(baseline) || baseline<.005*mid || heading<.15) continue;
            const auto warp=StereoWarp::between(a,b);
            double overlap=0;
            for (double depth:{nearDepth,mid,farDepth}) {
                int hits=0;
                for (int iy=1;iy<=5;++iy) for (int ix=1;ix<=5;++ix) {
                    const double x=(a.width-1)*ix/6.,y=(a.height-1)*iy/6.;
                    const auto q=warp.homogeneous(x,y,1./depth);
                    if (!std::isfinite(q.z) || q.z<=1e-6) continue;
                    const double u=q.x/q.z,v=q.y/q.z;
                    if (std::isfinite(u) && std::isfinite(v) &&
                        u>=2 && v>=2 && u<b.width-3 && v<b.height-3) ++hits;
                }
                overlap+=hits*(depth==mid?2.:1.);
            }
            if (overlap<12) continue;
            // Prefer substantial shared frusta and a usable but not extreme baseline.
            const double ratio=baseline/mid;
            const double score=overlap*heading*std::min(ratio/.10,1.)/(1.+ratio*ratio);
            scores.emplace_back(score,src);
        }
        std::sort(scores.begin(),scores.end(),[](const auto& a,const auto& b) {
            return a.first!=b.first ? a.first>b.first : a.second<b.second;
        });
        for (std::size_t i=0;i<std::min<std::size_t>(3,scores.size());++i)
            plan[ref].push_back(scores[i].second);
    }
    return plan;
}
} // namespace rf::detail
