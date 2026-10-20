#pragma once
#include "realityforge/dense.hpp"
#include "realityforge/stereo_warp.hpp"
#include <algorithm>
#include <cmath>
#include <vector>
#include <set>
#include <string>
#include <tuple>

namespace rf::detail {
struct DenseApprovedSourcePlan {
    std::vector<std::vector<std::size_t>> adjacency;
    std::vector<ViewPose> poses;
};
inline thread_local const DenseApprovedSourcePlan* activeDenseSourcePlan = nullptr;
/** One operation's copied immutable adjacency; coarse/fine may change pixels/intrinsics but not camera order or world poses. */
class DenseSourcePlanScope final {
public:
    DenseSourcePlanScope(std::span<const std::vector<std::size_t>> plan,std::span<const double> cameras16)
        : snapshot_(checked(plan,cameras16)), previous_(activeDenseSourcePlan) { activeDenseSourcePlan=&snapshot_; }
    ~DenseSourcePlanScope() noexcept { activeDenseSourcePlan=previous_; }
    DenseSourcePlanScope(const DenseSourcePlanScope&)=delete;
    DenseSourcePlanScope& operator=(const DenseSourcePlanScope&)=delete;
private:
    DenseApprovedSourcePlan snapshot_;
    const DenseApprovedSourcePlan* previous_;
    static DenseApprovedSourcePlan checked(std::span<const std::vector<std::size_t>> plan,std::span<const double> cameras) {
        if(plan.size()<2||plan.size()>8||cameras.size()!=plan.size()*16)throw Error("Invalid approved dense source plan shape");
        for(const auto& row:plan){if(row.empty()||row.size()>3)throw Error("Invalid approved source degree");}
        DenseApprovedSourcePlan result;result.adjacency.assign(plan.begin(),plan.end());
        for(std::size_t ref=0;ref<plan.size();++ref){
            std::set<std::size_t> seen;
            for(auto src:plan[ref])if(src>=plan.size()||src==ref||!seen.insert(src).second)throw Error("Invalid approved source identity");
            ViewPose pose;for(std::size_t k=0;k<12;++k)if(!std::isfinite(cameras[16*ref+k]))throw Error("Nonfinite approved source pose");
            std::copy_n(cameras.begin()+static_cast<std::ptrdiff_t>(16*ref),9,pose.rotation.begin());
            pose.translation={cameras[16*ref+9],cameras[16*ref+10],cameras[16*ref+11]};
            if(std::abs(determinant(pose.rotation)-1.)>.02)throw Error("Invalid approved source rotation");
            const auto orth=multiply(pose.rotation,transpose(pose.rotation));
            for(int k=0;k<9;++k)if(std::abs(orth[k]-(k%4==0?1.:0.))>.025)throw Error("Invalid approved source rotation");
            result.poses.push_back(pose);
        }
        return result;
    }
};
// Bounded CPU view plan: keep old <=4-view behavior exactly. For larger sets,
// select at most three useful source frusta. This is a scheduling heuristic,
// never evidence of actual overlap: ZNCC and reciprocal depth gates still decide.
inline std::vector<std::vector<std::size_t>> denseSourcePlan(
    std::span<const DenseView> views, double nearDepth, double farDepth) {
    if (views.size()<2 || views.size()>MAX_DENSE_VIEWS || !std::isfinite(nearDepth) ||
        !std::isfinite(farDepth) || nearDepth<=0 || farDepth<=nearDepth)
        throw Error("Invalid dense source plan");
    if(activeDenseSourcePlan){
        const auto& approved=*activeDenseSourcePlan;
        if(approved.poses.size()!=views.size())throw Error("Approved source plan camera count changed");
        for(std::size_t i=0;i<views.size();++i){const auto& a=approved.poses[i];const auto& b=views[i].pose;
            for(std::size_t k=0;k<9;++k)if(a.rotation[k]!=b.rotation[k])throw Error("Approved source plan camera order or rotation changed");
            if(a.translation.x!=b.translation.x||a.translation.y!=b.translation.y||a.translation.z!=b.translation.z)throw Error("Approved source plan camera order or translation changed");
            for(auto src:approved.adjacency[i]){const auto ac=multiply(transpose(b.rotation),b.translation),bc=multiply(transpose(views[src].pose.rotation),views[src].pose.translation);
                const double baseline=std::hypot(ac.x-bc.x,ac.y-bc.y,ac.z-bc.z);
                if(!std::isfinite(baseline)||baseline<=1e-12)throw Error("Approved source cameras are coincident");}
        }
        return approved.adjacency;
    }
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
        std::sort(scores.begin(),scores.end(),[&](const auto& a,const auto& b) {
            if(a.first!=b.first)return a.first>b.first;
            // Equal evidence must not make registration order choose appearance.
            const auto& x=views[a.second].pose;const auto& y=views[b.second].pose;
            return std::tie(x.rotation,x.translation.x,x.translation.y,x.translation.z)<
                std::tie(y.rotation,y.translation.x,y.translation.y,y.translation.z);
        });
        for (std::size_t i=0;i<std::min<std::size_t>(3,scores.size());++i)
            plan[ref].push_back(scores[i].second);
    }
    return plan;
}

struct DenseCameraEvidence {
    std::string photoId;
    Vec3 centre;
    std::vector<std::size_t> verifiedTracks;
    double sharpness{}; // measured normalized image evidence, not dense coverage
    bool textureAvailable=true;
};
struct DenseEvidenceSelection {
    std::vector<std::string> references;
    std::vector<std::vector<std::string>> sources;
    std::vector<std::pair<std::string,std::string>> excluded;
    std::size_t uniqueSparseTracks{};
};
// Greedy observed-track set cover, followed by baseline-aware verified adjacency.
// This pure admission plan never calls its candidate overlap measured dense support.
inline DenseEvidenceSelection selectDenseEvidence(std::span<const DenseCameraEvidence> input,
    std::size_t referenceCap=8,std::size_t sourceCap=3,std::size_t minCommonTracks=8) {
    if(input.size()>250||referenceCap<2||referenceCap>8||sourceCap<1||sourceCap>3||minCommonTracks<2)
        throw Error("Invalid dense evidence selection budget");
    // Reject input budgets before copying caller-owned feature arrays.
    std::size_t inputTracks=0;
    for(const auto& c:input){
        if(c.photoId.empty()||c.photoId.size()>256||c.verifiedTracks.size()>20000||inputTracks>500000-c.verifiedTracks.size())throw Error("Dense evidence input budget exceeded");
        inputTracks+=c.verifiedTracks.size();
    }
    std::vector<DenseCameraEvidence> cameras(input.begin(),input.end());
    std::sort(cameras.begin(),cameras.end(),[](const auto& a,const auto& b){return a.photoId<b.photoId;});
    std::size_t trackBudget=0;
    for(std::size_t i=0;i<cameras.size();++i){auto& c=cameras[i];
        if(c.photoId.empty()||(i&&c.photoId==cameras[i-1].photoId)||!std::isfinite(c.centre.x)||
           !std::isfinite(c.centre.y)||!std::isfinite(c.centre.z)||!std::isfinite(c.sharpness)||c.sharpness<0||c.sharpness>1)
            throw Error("Invalid camera selection evidence");
        if(c.verifiedTracks.size()>20000||trackBudget>500000-c.verifiedTracks.size())throw Error("Track selection budget exceeded");
        trackBudget+=c.verifiedTracks.size();
        std::sort(c.verifiedTracks.begin(),c.verifiedTracks.end());
        c.verifiedTracks.erase(std::unique(c.verifiedTracks.begin(),c.verifiedTracks.end()),c.verifiedTracks.end());
    }
    const auto common=[](const auto& a,const auto& b){std::size_t n=0,i=0,j=0;
        while(i<a.size()&&j<b.size()){if(a[i]==b[j]){++n;++i;++j;}else if(a[i]<b[j])++i;else ++j;}return n;};
    DenseEvidenceSelection out;std::vector<std::vector<std::pair<double,std::size_t>>> adjacency(cameras.size());
    for(std::size_t i=0;i<cameras.size();++i)for(std::size_t j=i+1;j<cameras.size();++j){
        const auto& a=cameras[i];const auto& b=cameras[j];
        const auto n=common(a.verifiedTracks,b.verifiedTracks);
        const double baseline=std::hypot(a.centre.x-b.centre.x,a.centre.y-b.centre.y,a.centre.z-b.centre.z);
        if(!a.textureAvailable||!b.textureAvailable||n<minCommonTracks||baseline<1e-8)continue;
        // Baseline is used relatively, so arbitrary world scaling does not alter selection.
        const double score=double(n)*(1.+.25*std::min(a.sharpness,b.sharpness));
        adjacency[i].emplace_back(score,j);adjacency[j].emplace_back(score,i);
    }
    std::set<std::size_t> covered;std::vector<bool> selected(cameras.size());
    while(out.references.size()<referenceCap){
        std::size_t best=cameras.size();double bestScore=-1;
        for(std::size_t i=0;i<cameras.size();++i){if(selected[i]||adjacency[i].empty())continue;
            if(!out.references.empty()&&std::none_of(adjacency[i].begin(),adjacency[i].end(),[&](auto edge){return selected[edge.second];}))continue;
            std::size_t novel=0;for(auto t:cameras[i].verifiedTracks)if(!covered.contains(t))++novel;
            const double score=double(novel)+.1*cameras[i].verifiedTracks.size()+cameras[i].sharpness;
            if(score>bestScore){bestScore=score;best=i;}}
        if(best==cameras.size())break;
        selected[best]=true;out.references.push_back(cameras[best].photoId);
        covered.insert(cameras[best].verifiedTracks.begin(),cameras[best].verifiedTracks.end());
        auto edges=adjacency[best];
        std::sort(edges.begin(),edges.end(),[&](auto a,auto b){return a.first!=b.first?a.first>b.first:cameras[a.second].photoId<cameras[b.second].photoId;});
        std::vector<std::string> sources;for(std::size_t k=0;k<std::min(sourceCap,edges.size());++k)sources.push_back(cameras[edges[k].second].photoId);
        out.sources.push_back(std::move(sources));
    }
    // Sources share the admitted active reference set; no hidden extra decoded cameras.
    for(std::size_t i=0;i<out.references.size();++i){
        const auto it=std::find_if(cameras.begin(),cameras.end(),[&](const auto& c){return c.photoId==out.references[i];});
        const auto index=static_cast<std::size_t>(it-cameras.begin());auto edges=adjacency[index];
        std::sort(edges.begin(),edges.end(),[&](auto a,auto b){return a.first!=b.first?a.first>b.first:cameras[a.second].photoId<cameras[b.second].photoId;});
        out.sources[i].clear();
        for(auto edge:edges)if(selected[edge.second]&&out.sources[i].size()<sourceCap)out.sources[i].push_back(cameras[edge.second].photoId);
    }
    out.uniqueSparseTracks=covered.size();
    for(std::size_t i=0;i<cameras.size();++i)if(!selected[i])out.excluded.emplace_back(cameras[i].photoId,
        !cameras[i].textureAvailable?"texture_unavailable":adjacency[i].empty()?"insufficient_verified_overlap_or_baseline":std::none_of(adjacency[i].begin(),adjacency[i].end(),[&](auto edge){return selected[edge.second];})?"disconnected_from_selected_component":"reference_budget");
    return out;
}
} // namespace rf::detail
