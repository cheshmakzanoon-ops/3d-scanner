#include "realityforge/c_abi_support.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/depth_cache_manifest.hpp"
#include "realityforge/stereo_warp.hpp"
#include "realityforge/dense_selection.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/depth_tiles.hpp"
#include "realityforge/progress.hpp"
#include "realityforge/matching_cost.hpp"
#include "realityforge/depth_refinement.hpp"
#include "realityforge/depth_consistency.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <limits>
#include <locale>
#include <numeric>
#include <sstream>
#include <unordered_set>

namespace rf {
namespace {
thread_local const DenseRefinementInputs* currentRefinementInputs=nullptr;
Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 scale(Vec3 a,double s){return {a.x*s,a.y*s,a.z*s};}
double norm(Vec3 a){return std::sqrt(a.x*a.x+a.y*a.y+a.z*a.z);}
bool finite(Vec3 a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
Vec3 fromDepth(const DenseView& view,double x,double y,double z){
    Vec3 camera{((x-view.cx)/view.fx)*z,((y-view.cy)/view.fy)*z,z};
    return multiply(transpose(view.pose.rotation),sub(camera,view.pose.translation));
}
Vec3 cameraPoint(const DenseView& view,Vec3 world){return add(multiply(view.pose.rotation,world),view.pose.translation);}
Vec3 center(const DenseView& v){return scale(multiply(transpose(v.pose.rotation),v.pose.translation),-1);}
// Luminance is an exactly representable /256 weighted integer. Cache it once
// per input view, rather than recomputing RGB at every patch/depth hypothesis.
struct GrayView {
    const DenseView& image;
    std::vector<float> pixels;
    explicit GrayView(const DenseView& v,bool cache):image(v),pixels(cache?v.rgba.size()/4:0) {
        for(std::size_t i=0;i<pixels.size();++i) {
            if((i & 4095u)==0) cancellation::checkpoint();
            pixels[i]=(77.f*v.rgba[i*4]+150.f*v.rgba[i*4+1]+29.f*v.rgba[i*4+2])/256.f;
        }
    }
};
struct Bilinear {double gray{};bool valid{};};
Bilinear sample(const GrayView& cached,double x,double y){
    const auto& image=cached.image;
    if(!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=image.width-1||y>=image.height-1)return {};
    int ix=static_cast<int>(x),iy=static_cast<int>(y);
    double fx=x-ix,fy=y-iy;
    const std::size_t p=static_cast<std::size_t>(iy)*image.width+ix;
    const std::array<std::size_t,4> pos{p,p+1,p+image.width,p+image.width+1};
    for(auto i:pos)if(image.rgba[i*4+3]!=255)return {};
    auto value=[&](std::size_t i){return cached.pixels.empty()?(77.f*image.rgba[i*4]+150.f*image.rgba[i*4+1]+29.f*image.rgba[i*4+2])/256.f:cached.pixels[i];};
    return {value(pos[0])*(1-fx)*(1-fy)+value(pos[1])*fx*(1-fy)+value(pos[2])*(1-fx)*fy+value(pos[3])*fx*fy,true};
}
struct Patch {double mean{},var{};std::array<double,49> pixels{};bool valid{};};
Patch referencePatch(const GrayView& v,int x,int y,int radius){
    Patch p;int n=0;double sum=0,sumSq=0;
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        auto s=sample(v,x+dx,y+dy);if(!s.valid)return {};
        p.pixels[n++]=s.gray;sum+=s.gray;sumSq+=s.gray*s.gray;
    }
    p.mean=sum/n;p.var=std::max(0.,sumSq/n-p.mean*p.mean);p.valid=true;return p;
}
// Algebraically identical calibrated fronto-parallel warp. Relative transforms
// are prepared once per pair; the hot loop never reconstructs world points.
double ncc(const GrayView& neighbor,const detail::StereoWarp& warp,
    int x,int y,double z,const Patch& patch,int radius){
    int n=0;std::array<double,49> sourcePatch{};
    const auto base=warp.homogeneous(x,y,1./z);
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        const Vec3 q{base.x+warp.h[0]*dx+warp.h[1]*dy,
                     base.y+warp.h[3]*dx+warp.h[4]*dy,
                     base.z+warp.h[6]*dx+warp.h[7]*dy};
        if(q.z*z<=1e-5||!finite(q))return -2;
        auto intensity=sample(neighbor,q.x/q.z,q.y/q.z);
        if(!intensity.valid)return -2;
        sourcePatch[n++]=intensity.gray;
    }
    const auto cost=matching::zncc(std::span<const double>(patch.pixels).first(n),std::span<const double>(sourcePatch).first(n));
    return cost.valid?1.-cost.value:-2.;
}
// A plane is a bounded proposal, never geometric support. Each patch ray is
// intersected with that plane and evaluated against actual source pixels.
double planeNcc(const GrayView& neighbor,const detail::StereoWarp& warp,
    const DenseView& camera,const refinement::PlaneHypothesis& plane,
    int x,int y,double z,const Patch& patch,int radius){
    int n=0;std::array<double,49> sourcePatch{};
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        const double depth=refinement::planeDepth(plane,camera,x,y,z,x+dx,y+dy);
        if(depth<=0)return -2;
        const auto q=warp.homogeneous(x+dx,y+dy,1./depth);
        if(q.z*depth<=1e-5||!finite(q))return -2;
        const auto intensity=sample(neighbor,q.x/q.z,q.y/q.z);if(!intensity.valid)return -2;
        sourcePatch[n++]=intensity.gray;
    }
    const auto cost=matching::zncc(std::span<const double>(patch.pixels).first(n),std::span<const double>(sourcePatch).first(n));
    return cost.valid?1.-cost.value:-2.;
}

float relativeDifference(double a,double b){return static_cast<float>(std::abs(a-b)/std::max(a,b));}
// Nearest *sampled* depth neighbor in the sparse grid, with a small reprojection budget.
enum class NeighborDepthFailure { None, OutsideImage, GridRejected, MissingDepth };
float neighborDepth(const DenseDepthMap& depth,double x,double y,int step,int radius,NeighborDepthFailure* failure=nullptr){
    if(failure)*failure=NeighborDepthFailure::None;
    if(!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=depth.width||y>=depth.height){
        if(failure)*failure=NeighborDepthFailure::OutsideImage;
        return 0;}
    int ix=static_cast<int>(std::round((x-radius)/step))*step+radius;
    int iy=static_cast<int>(std::round((y-radius)/step))*step+radius;
    if(ix<0||iy<0||ix>=depth.width||iy>=depth.height||
       std::abs(ix-x)>step*.65||std::abs(iy-y)>step*.65){
        if(failure)*failure=NeighborDepthFailure::GridRejected;
        return 0;}
    const auto value=depth.depth[static_cast<std::size_t>(iy)*depth.width+ix];
    if(failure&&value<=0)*failure=NeighborDepthFailure::MissingDepth;
    return value;
}
struct Voxel {
    long long x{},y{},z{};
    bool operator==(const Voxel&)const = default;
};
struct VoxelHash {
    std::size_t operator()(const Voxel& v) const noexcept {
        auto h=std::hash<long long>{}(v.x);
        h^=std::hash<long long>{}(v.y)+0x9e3779b9+(h<<6)+(h>>2);
        h^=std::hash<long long>{}(v.z)+0x9e3779b9+(h<<6)+(h>>2);
        return h;
    }
};
double median(std::vector<double>& v){if(v.empty())return 0.;std::nth_element(v.begin(),v.begin()+v.size()/2,v.end());return v[v.size()/2];}
void validate(std::span<const DenseView> views,const DenseSettings& s){
    if(views.size()<2||views.size()>MAX_DENSE_VIEWS)throw Error("Dense MVS requires 2 to 8 calibrated views");
    if(!std::isfinite(s.nearDepth)||!std::isfinite(s.farDepth)||s.nearDepth<=.01||s.farDepth<=s.nearDepth*1.05||s.farDepth>100000||
       s.depthPlanes<12||s.depthPlanes>128||s.pixelStride<2||s.pixelStride>8||
       s.patchRadius<1||s.patchRadius>3||!std::isfinite(s.minimumNcc)||s.minimumNcc<0||s.minimumNcc>1||
       !std::isfinite(s.minUniqueness)||!std::isfinite(s.depthConsistencyFraction)||
       s.minUniqueness<.005||s.minUniqueness>.5||s.depthConsistencyFraction<.005||s.depthConsistencyFraction>.25||
       s.maxPoints<10||s.maxPoints>50000||s.executionVersion!=1||s.tileEdge<16||s.tileEdge>640||s.localRefinementIterations>12||s.normalRefinementIterations>3||
       !std::isfinite(s.normalMaxAngleDegrees)||s.normalMaxAngleDegrees<15||s.normalMaxAngleDegrees>60)throw Error("Invalid bounded CPU MVS configuration");
    int w=views.front().width,h=views.front().height;
    if(w<32||h<32||w>640||h>640||static_cast<std::int64_t>(w)*h>320000)throw Error("Dense MVS image exceeds CPU reference limits");
    const int start=s.patchRadius+1;
    const auto columns=(w-s.patchRadius-3-start+s.pixelStride)/s.pixelStride;
    const auto rows=(h-s.patchRadius-3-start+s.pixelStride)/s.pixelStride;
    if (static_cast<std::size_t>(columns)*rows*views.size()>MAX_DENSE_SAMPLES)
        throw Error("Dense sampling budget exceeded; reduce resolution or increase stride");
    for(auto& v:views){
        if(v.width!=w||v.height!=h||v.rgba.size()!=static_cast<std::size_t>(w)*h*4)
            throw Error("Views must share bounded rectified dimensions and RGBA buffers");
        if(!std::isfinite(v.fx)||!std::isfinite(v.fy)||v.fx<=10||v.fy<=10||!std::isfinite(v.cx)||!std::isfinite(v.cy)||
           v.cx<0||v.cx>=w||v.cy<0||v.cy>=h)throw Error("Invalid rectified camera intrinsics");
        for(double x:v.pose.rotation)if(!std::isfinite(x))throw Error("Nonfinite dense rotation");
        for(double x:{v.pose.translation.x,v.pose.translation.y,v.pose.translation.z})if(!std::isfinite(x))throw Error("Nonfinite dense translation");
        auto orth=multiply(v.pose.rotation,transpose(v.pose.rotation));
        for(int k=0;k<9;++k)if(std::abs(orth[k]-(k%4==0?1.:0.))>.025)throw Error("Dense camera rotation not orthonormal");
        if(std::abs(determinant(v.pose.rotation)-1.)>.025)throw Error("Invalid dense camera orientation");
        for(std::size_t k=3;k<v.rgba.size();k+=4)if(v.rgba[k]!=0&&v.rgba[k]!=255)
            throw Error("RGBA validity masks must be zero or 255");
    }
    if(norm(sub(center(views[0]),center(views[1])))<.02)throw Error("Insufficient stereo baseline for a reliable depth map");
}
void writeLossDiagnostics(std::ostream& out,const DenseLossDiagnostics& d){
    out<<"\"lossDiagnostics\":{\"schema\":2,\"scope\":\"ORIGINATING_DENSE_BRANCHES\",\"available\":"<<(d.available?"true":"false");
    if(!d.available){out<<",\"viewCount\":null,\"views\":null}";return;}
    if(d.viewCount<2||d.viewCount>MAX_DENSE_VIEWS)throw Error("Invalid dense diagnostic view count");
    out<<",\"viewCount\":"<<d.viewCount<<",\"views\":[";
    std::size_t sampledTotal=0;
    for(std::size_t i=0;i<d.viewCount;++i){
        const auto& v=d.views[i];
        if(v.sampled>MAX_DENSE_SAMPLES||v.evaluated>v.sampled||v.reciprocalEvaluated>v.sampled||
           v.photometricAccepted>v.evaluated||v.invalidReferencePatch>v.evaluated||v.validLowVariance>v.evaluated||
           v.invalidOrBoundaryBest>v.evaluated||v.ncc>v.evaluated||v.uniqueness>v.evaluated||v.invalidInverseDepth>v.evaluated||
           v.photometricAccepted+v.invalidReferencePatch+v.validLowVariance+v.invalidOrBoundaryBest+v.ncc+v.uniqueness+v.invalidInverseDepth!=v.evaluated||
           v.ambiguousMinimum+v.nearBoundaryBest+v.farBoundaryBest!=v.invalidOrBoundaryBest||
           v.invalidWorld>v.reciprocalEvaluated||v.noConfirmation>v.reciprocalEvaluated||v.reciprocalAccepted>v.reciprocalEvaluated||
           v.invalidWorld+v.noConfirmation+v.reciprocalAccepted!=v.reciprocalEvaluated||v.sourcePairs>3*v.reciprocalEvaluated)
            throw Error("Invalid dense diagnostic sample accounting");
        const std::array<std::size_t,7> pairs{v.invalidSourceProjection,v.sourceDepthUnavailable,v.depthMismatch,
            v.invalidReverseProjection,v.reverseReprojection,v.normalMismatch,v.confirmedPairs};
        const std::array<std::size_t,4> unavailable{v.sourceOutsideImage,v.sourceGridRejected,v.sourceMissingDepth,v.sourceInterpolationUnsupported};
        std::size_t unavailableTotal=0;
        for(auto value:unavailable){if(value>v.sourceDepthUnavailable)throw Error("Invalid dense diagnostic lookup accounting");unavailableTotal+=value;}
        if(unavailableTotal!=v.sourceDepthUnavailable)throw Error("Inconsistent dense diagnostic lookup totals");
        std::size_t validReferenceTotal=0,validLowVarianceTotal=0;
        for(std::size_t k=0;k<v.validReferenceMeanHistogram.size();++k){
            const auto valid=v.validReferenceMeanHistogram[k],low=v.validLowVarianceMeanHistogram[k];
            if(valid>v.evaluated||low>valid||low>v.validLowVariance)throw Error("Invalid dense diagnostic patch-mean histogram");
            validReferenceTotal+=valid;validLowVarianceTotal+=low;
        }
        if(validReferenceTotal!=v.evaluated-v.invalidReferencePatch||validLowVarianceTotal!=v.validLowVariance)
            throw Error("Inconsistent dense diagnostic patch-mean totals");
        std::size_t pairTotal=0,histogramTotal=0,confirmedTotal=0;
        for(auto value:pairs){if(value>v.sourcePairs)throw Error("Invalid dense diagnostic pair accounting");pairTotal+=value;}
        for(std::size_t k=0;k<v.confirmationHistogram.size();++k){
            const auto value=v.confirmationHistogram[k];
            if(value>v.reciprocalEvaluated||(k>3&&value))throw Error("Invalid dense diagnostic support histogram");
            histogramTotal+=value;confirmedTotal+=k*value;
        }
        if(pairTotal!=v.sourcePairs||histogramTotal+v.invalidWorld!=v.reciprocalEvaluated||
           v.confirmationHistogram[0]!=v.noConfirmation||confirmedTotal!=v.confirmedPairs)
            throw Error("Inconsistent dense diagnostic branch totals");
        sampledTotal+=v.sampled;if(sampledTotal>MAX_DENSE_SAMPLES)throw Error("Dense diagnostic sample budget exceeded");
        if(i)out<<',';
        const bool observed=v.evaluated>0;
        out<<"{\"referenceView\":"<<i<<",\"photometric\":{\"coverage\":\""<<(observed?(v.evaluated==v.sampled?"COMPLETE":"PARTIAL"):"UNAVAILABLE")
           <<"\",\"sampledSamples\":"<<v.sampled<<",\"unknownSamples\":"<<v.sampled-v.evaluated;
        if(observed){
            out<<",\"evaluatedSamples\":"<<v.evaluated<<",\"acceptedSamples\":"<<v.photometricAccepted
               <<",\"rejectionCounts\":{\"invalidReferencePatch\":"<<v.invalidReferencePatch<<",\"validLowVariance\":"<<v.validLowVariance
               <<",\"invalidOrBoundaryBest\":"<<v.invalidOrBoundaryBest
               <<",\"invalidOrBoundaryBestReasons\":{\"ambiguousMinimum\":"<<v.ambiguousMinimum<<",\"nearBoundaryBest\":"<<v.nearBoundaryBest
               <<",\"farBoundaryBest\":"<<v.farBoundaryBest<<"},\"ncc\":"<<v.ncc<<",\"uniqueness\":"<<v.uniqueness
               <<",\"invalidInverseDepth\":"<<v.invalidInverseDepth<<"}";
            out<<",\"validReferenceMeanHistogram\":[";
            for(std::size_t k=0;k<v.validReferenceMeanHistogram.size();++k){if(k)out<<',';out<<v.validReferenceMeanHistogram[k];}
            out<<"],\"validLowVarianceMeanHistogram\":[";
            for(std::size_t k=0;k<v.validLowVarianceMeanHistogram.size();++k){if(k)out<<',';out<<v.validLowVarianceMeanHistogram[k];}
            out<<']';
        }else out<<",\"evaluatedSamples\":null,\"acceptedSamples\":null,\"rejectionCounts\":null,\"validReferenceMeanHistogram\":null,\"validLowVarianceMeanHistogram\":null";
        out<<"},\"reciprocal\":{\"coverage\":\"COMPLETE\",\"evaluatedSamples\":"<<v.reciprocalEvaluated
           <<",\"acceptedSamples\":"<<v.reciprocalAccepted<<",\"invalidWorldSamples\":"<<v.invalidWorld
           <<",\"noConfirmationSamples\":"<<v.noConfirmation<<",\"sourcePairs\":"<<v.sourcePairs
           <<",\"pairFirstRejectionCounts\":{\"invalidSourceProjection\":"<<v.invalidSourceProjection
           <<",\"sourceDepthUnavailable\":"<<v.sourceDepthUnavailable<<",\"depthMismatch\":"<<v.depthMismatch
           <<",\"invalidReverseProjection\":"<<v.invalidReverseProjection<<",\"reverseReprojection\":"<<v.reverseReprojection
           <<",\"normalMismatch\":"<<v.normalMismatch<<"},\"sourceDepthUnavailableReasons\":{\"outsideImage\":"<<v.sourceOutsideImage
           <<",\"sampledGridRejected\":"<<v.sourceGridRejected<<",\"missingPhotometricDepth\":"<<v.sourceMissingDepth
           <<",\"strictInterpolationUnsupported\":"<<v.sourceInterpolationUnsupported<<"},\"confirmedPairs\":"<<v.confirmedPairs<<",\"confirmationHistogram\":[";
        for(std::size_t k=0;k<v.confirmationHistogram.size();++k){if(k)out<<',';out<<v.confirmationHistogram[k];}
        out<<"]}}";
    }
    out<<"]}";
}
}

DenseRefinementScope::DenseRefinementScope(const DenseRefinementInputs* inputs) noexcept:previous_(currentRefinementInputs){currentRefinementInputs=inputs;}
DenseRefinementScope::~DenseRefinementScope() noexcept{currentRefinementInputs=previous_;}
DenseResult reconstructDense(std::span<const DenseView> views,const DenseSettings& requestedSettings,const DenseRefinementInputs* inputs){
    auto settings=requestedSettings;
    if(!inputs&&currentRefinementInputs){inputs=currentRefinementInputs;settings.localRefinementIterations=6;settings.discontinuityAwareConsistency=true;settings.normalRefinementIterations=3;settings.normalConsistency=true;settings.normalMaxAngleDegrees=35;settings.robustSourceAggregation=false;}
    cancellation::checkpoint();
    validate(views,settings);
    DenseResult result;result.pointBudget=settings.maxPoints;
    result.photometricDiagnostics.available=true;
    result.lossDiagnostics.available=true;result.lossDiagnostics.viewCount=views.size();
    auto admitted=tiles::admit(views.size(),static_cast<std::size_t>(views[0].width)*views[0].height,MAX_DENSE_SAMPLES,settings.maxPoints,settings.depthPlanes,settings.workingSetBudgetBytes,settings.referenceMode);
    result.admittedWorkingSetBytes=admitted.totalBytes;
    if(inputs){
        if((inputs->sparsePriors.empty()&&inputs->coarseViews.empty())||!depth_cache::validIdentity(inputs->identitySha256)||inputs->sparsePriors.size()>20000||(!inputs->coarseViews.empty()&&inputs->coarseViews.size()!=views.size()))throw Error("Invalid immutable dense refinement inputs");
        std::size_t extra=inputs->sparsePriors.size()*96+views.size()*8192u*sizeof(std::vector<refinement::SparsePrior>);
        for(const auto& prior:inputs->sparsePriors){
            if(prior.camera>=views.size()||!std::isfinite(prior.x)||!std::isfinite(prior.y)||prior.x<0||prior.y<0||prior.x>=views[prior.camera].width||prior.y>=views[prior.camera].height||!std::isfinite(prior.cameraZ)||prior.cameraZ<=0||prior.cameraZ>100000||!std::isfinite(prior.relativeUncertainty)||prior.relativeUncertainty<0||prior.relativeUncertainty>.25)throw Error("Invalid measured sparse depth prior");
        }
        if(!inputs->coarseViews.empty()){
            validate(inputs->coarseViews,settings);
            for(std::size_t i=0;i<views.size();++i){const auto& a=views[i];const auto& b=inputs->coarseViews[i];
                const double sx=double(b.width)/a.width,sy=double(b.height)/a.height;
                if(b.width>=a.width||b.height>=a.height||b.pose.rotation!=a.pose.rotation||b.pose.translation.x!=a.pose.translation.x||b.pose.translation.y!=a.pose.translation.y||b.pose.translation.z!=a.pose.translation.z||std::abs(b.fx-a.fx*sx)>1e-8||std::abs(b.fy-a.fy*sy)>1e-8||std::abs(b.cx-((a.cx+.5)*sx-.5))>1e-8||std::abs(b.cy-((a.cy+.5)*sy-.5))>1e-8)throw Error("Coarse photo geometry/half-pixel transform mismatch");
            }
            extra+=tiles::admit(views.size(),static_cast<std::size_t>(inputs->coarseViews[0].width)*inputs->coarseViews[0].height,MAX_DENSE_SAMPLES,settings.maxPoints,settings.depthPlanes,settings.workingSetBudgetBytes,true).totalBytes;
        }
        if(extra>settings.workingSetBudgetBytes-result.admittedWorkingSetBytes)throw Error("Optional refinement input working-set budget exceeded");
        result.admittedWorkingSetBytes+=extra;
    }
    const int w=views.front().width,h=views.front().height,r=settings.patchRadius,step=settings.pixelStride;
    // Inverse camera-z sampling allocates more candidates to nearby surfaces.
    std::vector<double> planes(settings.depthPlanes);
    for(int k=0;k<settings.depthPlanes;++k){
        double t=double(k)/(settings.depthPlanes-1);
        planes[k]=1./((1-t)/settings.nearDepth+t/settings.farDepth);
    }
    const std::size_t n=static_cast<std::size_t>(w)*h;
    const auto tilePlan=tiles::plan(w,h,r+1,step,r+1,settings.referenceMode?640:settings.tileEdge);
    if(tilePlan.size()>4096)throw Error("Depth tile count exceeds durable manifest admission");
    result.totalTiles=tilePlan.size()*views.size();
    std::vector<std::vector<refinement::SparsePrior>> priorBuckets;
    if(inputs&&!inputs->sparsePriors.empty()){
        priorBuckets.resize(tilePlan.size()*views.size());
        const auto& first=tilePlan.front();std::size_t columns=0;for(const auto& tile:tilePlan)if(tile.y0==first.y0)++columns;
        for(const auto& p:inputs->sparsePriors){
            if(!p.visible||p.x<first.x0||p.y<first.y0)continue;
            const auto tx=static_cast<std::size_t>((p.x-first.x0)/(first.x1-first.x0)),ty=static_cast<std::size_t>((p.y-first.y0)/(first.y1-first.y0)),index=ty*columns+tx;
            if(tx>=columns||index>=tilePlan.size())continue;
            const auto& tile=tilePlan[index];
            if(p.x>=tile.x0&&p.x<tile.x1&&p.y>=tile.y0&&p.y<tile.y1)priorBuckets[p.camera*tilePlan.size()+index].push_back({p.cameraZ,p.relativeUncertainty,p.visible});
        }
    }
    std::vector<DenseDepthMap> coarseMaps;
    if(inputs&&!inputs->coarseViews.empty()){
        auto coarseSettings=settings;coarseSettings.localRefinementIterations=0;coarseSettings.normalRefinementIterations=0;coarseSettings.normalConsistency=false;coarseSettings.referenceMode=true;coarseSettings.maxPoints=10;
        DenseRefinementScope noRecursiveInheritance(nullptr);
        auto coarseResult=reconstructDense(inputs->coarseViews,coarseSettings,nullptr);
        coarseMaps=std::move(coarseResult.maps);result.coarseDepthMaps=coarseMaps.size();
    }
    std::ostringstream settingsText;settingsText.imbue(std::locale::classic());settingsText<<std::hexfloat<<"tile-cpu-v2|"<<settings.nearDepth<<'|'<<settings.farDepth<<'|'<<settings.depthPlanes<<'|'<<step<<'|'<<r<<'|'<<settings.minimumNcc<<'|'<<settings.minUniqueness<<'|'<<settings.depthConsistencyFraction<<'|'<<settings.maxPoints<<'|'<<settings.executionVersion<<'|'<<settings.tileEdge<<'|'<<settings.workingSetBudgetBytes<<'|'<<settings.referenceMode<<'|'<<settings.discontinuityAwareConsistency<<'|'<<settings.localRefinementIterations<<'|'<<settings.robustSourceAggregation<<'|'<<settings.normalRefinementIterations<<'|'<<settings.normalConsistency<<'|'<<settings.normalMaxAngleDegrees<<'|'<<refinement::SETTINGS_VERSION<<'|'<<consistency::NORMAL_SETTINGS_VERSION<<'|'<<(inputs?inputs->identitySha256:std::string("none"));
    // Only this previously inconsistent optional combination changes its
    // objective. Preserve all established robust=false checkpoint bytes.
    if(settings.robustSourceAggregation&&settings.localRefinementIterations)
        settingsText<<"|robust-local-objective-v2";
    if(inputs&&!inputs->coarseViews.empty())settingsText<<"|coarse-incumbent-objective-v1";
    result.sourceViews=detail::denseSourcePlan(views,settings.nearDepth,settings.farDepth);
    settingsText<<"|sources:";for(std::size_t ref=0;ref<result.sourceViews.size();++ref){settingsText<<ref<<':';for(auto source:result.sourceViews[ref])settingsText<<source<<',';settingsText<<';';}
    settingsText<<"|uniqueness:"<<matching::UNIQUENESS_SETTINGS_VERSION;
    const auto solverIdentity=settingsText.str();
    result.consistentPixelsPerView.assign(views.size(),0);
    result.maps.reserve(views.size());
    result.verifiedSamples.reserve(std::min<std::size_t>(settings.maxPoints * 2,MAX_DENSE_SAMPLES));
    for(std::size_t ref=0;ref<views.size();++ref){
        cancellation::checkpoint();
        auto& loss=result.lossDiagnostics.views[ref];
        DenseDepthMap map;map.width=w;map.height=h;map.depth.assign(n,0);map.confidence.assign(n,0);
        // Reuse only fully validated, input-identity-bound depth maps. Camera-z
        // consistency, meshing and UV projection still run afresh on every scan.
        const auto& sources=result.sourceViews[ref];
        // Existing Kotlin identity predates optional refinement/aggregation.
        // Never reuse a legacy checkpoint with altered photometric semantics.
        const bool checkpointCompatible=!inputs&&!settings.referenceMode&&settings.localRefinementIterations==0&&!settings.robustSourceAggregation&&settings.normalRefinementIterations==0;
        if(checkpointCompatible&&depth_checkpoint::load(ref,map)){
            for(int y=r+1;y<h-r-2;y+=step)for(int x=r+1;x<w-r-2;x+=step){
                ++result.sampledPixels;
                ++loss.sampled;
                if(map.depth[static_cast<std::size_t>(y)*w+x]>0)++result.photometricPixels;
            }
            result.peakLuminanceBytes=std::max(result.peakLuminanceBytes,(settings.referenceMode?(1+sources.size())*n*sizeof(float):0));
            result.maps.push_back(std::move(map));
            progress::begin(progress::Stage::Depth,ref,0,tilePlan.size());
            progress::advance(tilePlan.size());
            ++result.resumedDepthMaps;
            result.completedTiles+=tilePlan.size();
            continue;
        }
        const GrayView image(views[ref],settings.referenceMode);
        std::vector<GrayView> grayscale;grayscale.reserve(sources.size());
        std::vector<detail::StereoWarp> warps;warps.reserve(sources.size());
        for (auto source:sources) {
            grayscale.emplace_back(views[source],settings.referenceMode);
            warps.push_back(detail::StereoWarp::between(views[ref],views[source]));
        }
        result.peakLuminanceBytes=std::max(result.peakLuminanceBytes,(settings.referenceMode?(1+sources.size())*n*sizeof(float):0));
        // One scratch cost vector per reference, not an allocation per pixel.
        std::vector<double> costs(settings.depthPlanes,2.);
        for(std::size_t tileIndex=0;tileIndex<tilePlan.size();++tileIndex){
         const auto& tile=tilePlan[tileIndex];
         depth_checkpoint::TileKey tileKey{static_cast<std::uint32_t>(ref),static_cast<std::uint32_t>(tileIndex),static_cast<std::uint32_t>(w),static_cast<std::uint32_t>(h),static_cast<std::uint32_t>(tile.x0),static_cast<std::uint32_t>(tile.y0),static_cast<std::uint32_t>(tile.x1-tile.x0),static_cast<std::uint32_t>(tile.y1-tile.y0),static_cast<std::uint32_t>(step),settings.executionVersion,solverIdentity};
         const std::size_t tileSamples=static_cast<std::size_t>((tile.x1-tile.x0+step-1)/step)*((tile.y1-tile.y0+step-1)/step);
         DenseDepthMap retainedTile;depth_checkpoint::TileLoadStatus cacheStatus=depth_checkpoint::TileLoadStatus::Unavailable;
         if(!settings.referenceMode&&depth_checkpoint::loadTile(tileKey,retainedTile,&cacheStatus)){
            for(int y=tile.y0;y<tile.y1;++y)for(int x=tile.x0;x<tile.x1;++x){const auto dst=static_cast<std::size_t>(y)*w+x,src=static_cast<std::size_t>(y-tile.y0)*retainedTile.width+x-tile.x0;map.depth[dst]=retainedTile.depth[src];map.confidence[dst]=retainedTile.confidence[src];}
            for(int y=tile.y0;y<tile.y1;y+=step)for(int x=tile.x0;x<tile.x1;x+=step){++result.sampledPixels;++loss.sampled;if(map.depth[static_cast<std::size_t>(y)*w+x]>0)++result.photometricPixels;}
            progress::begin(progress::Stage::Depth,ref,tileIndex,tileSamples);progress::advance(tileSamples);
            ++result.resumedDepthTiles;++result.completedTiles;continue;
         }
         if(cacheStatus==depth_checkpoint::TileLoadStatus::Rejected)++result.rejectedDepthTiles;
         ++result.recomputedDepthTiles;
         auto range=refinement::DepthPrior{settings.nearDepth,settings.farDepth,0,true};
         if(!priorBuckets.empty()){range=refinement::interval(priorBuckets[ref*tilePlan.size()+tileIndex],settings.nearDepth,settings.farDepth);if(!range.conservativeFallback)++result.sparsePriorTiles;}
         for(int k=0;k<settings.depthPlanes;++k){double t=double(k)/(settings.depthPlanes-1);planes[k]=1./((1-t)/range.nearDepth+t/range.farDepth);}
         progress::begin(progress::Stage::Depth,ref,tileIndex,tileSamples);
         std::size_t tileWork=0;
         cancellation::checkpoint();
         for(int y=tile.y0;y<tile.y1;y+=step){
          cancellation::checkpoint();
          for(int x=tile.x0;x<tile.x1;x+=step){
            ++result.sampledPixels;progress::advance(++tileWork);
            ++result.photometricDiagnostics.evaluatedSamples;
            ++loss.sampled;++loss.evaluated;
            auto reference=referencePatch(image,x,y,r);
            std::size_t meanBucket=0;
            if(reference.valid){
                // Existing valid 8-bit luminance patch mean is finite in [0,255].
                // These bins contain no coordinates and never classify foreground.
                meanBucket=static_cast<std::size_t>(std::clamp(static_cast<int>(reference.mean/16),0,15));
                ++loss.validReferenceMeanHistogram[meanBucket];
            }
            if(reference.var<18.){++result.photometricDiagnostics.lowVariance;
                if(reference.valid){++loss.validLowVariance;++loss.validLowVarianceMeanHistogram[meanBucket];}else ++loss.invalidReferencePatch;
                continue;} // legacy counter/decision unchanged, including invalid reference patches
            std::fill(costs.begin(),costs.end(),2.);
            for(int k=0;k<settings.depthPlanes;++k){
                if((k&7)==0)cancellation::checkpoint();
                // A candidate must be supported by another photo, not a single-image guess.
                std::array<matching::Cost,7> sourceCosts{};
                for(std::size_t src=0;src<sources.size();++src){
                    double corr=ncc(grayscale[src],warps[src],x,y,planes[k],reference,r);
                    if(corr>-.999)sourceCosts[src]={1.-corr,static_cast<std::size_t>((2*r+1)*(2*r+1)),true};
                }
                costs[k]=matching::aggregate(std::span<const matching::Cost>(sourceCosts).first(sources.size()),settings.robustSourceAggregation).cost;
            }
            const auto distinct=matching::distinctMinimum(costs);
            const int best=static_cast<int>(distinct.best);
            if(!distinct.valid||best<2||best>=settings.depthPlanes-2){
                ++result.photometricDiagnostics.invalidOrBoundaryBest;++loss.invalidOrBoundaryBest;
                // Additive subreason; the legacy counter and this branch's decision are unchanged.
                if(!distinct.valid)++loss.ambiguousMinimum;
                else if(best<2)++loss.nearBoundaryBest;
                else ++loss.farBoundaryBest;
                continue;
            }
            if(costs[best]>1-settings.minimumNcc){++result.photometricDiagnostics.ncc;++loss.ncc;continue;}
            const double margin=distinct.margin;
            if(margin<settings.minUniqueness){++result.photometricDiagnostics.uniqueness;++loss.uniqueness;continue;}
            double inverse=refinement::parabolicInverseDepth(costs,planes,static_cast<std::size_t>(best));
            double selectedCost=-1.;
            if(settings.localRefinementIterations){
                auto refined=refinement::local(planes[best-1],1./inverse,planes[best+1],settings.localRefinementIterations,[&](double depth){
                    cancellation::checkpoint();
                    if(!settings.robustSourceAggregation){
                        double bestCost=2.;
                        for(std::size_t src=0;src<sources.size();++src){double corr=ncc(grayscale[src],warps[src],x,y,depth,reference,r);if(corr>-.999)bestCost=std::min(bestCost,1.-corr);}
                        return bestCost;
                    }
                    std::array<matching::Cost,7> sourceCosts{};
                    for(std::size_t src=0;src<sources.size();++src){const double corr=ncc(grayscale[src],warps[src],x,y,depth,reference,r);if(corr>-.999)sourceCosts[src]={1.-corr,static_cast<std::size_t>((2*r+1)*(2*r+1)),true};}
                    return matching::aggregate(std::span<const matching::Cost>(sourceCosts).first(sources.size()),true).cost;
                });
                if(refined.valid&&refined.cost<=costs[best]){inverse=1./refined.depth;selectedCost=refined.cost;}
            }
            if(!coarseMaps.empty()){
                // Compare coarse proposals with the currently selected depth,
                // not the stale discrete-plane cost before local refinement.
                if(selectedCost<0){
                    std::array<matching::Cost,7> selectedCosts{};
                    for(std::size_t src=0;src<sources.size();++src){const double corr=ncc(grayscale[src],warps[src],x,y,1./inverse,reference,r);if(corr>-.999)selectedCosts[src]={1.-corr,static_cast<std::size_t>((2*r+1)*(2*r+1)),true};}
                    selectedCost=matching::aggregate(std::span<const matching::Cost>(selectedCosts).first(sources.size()),settings.robustSourceAggregation).cost;
                }
                const auto& coarse=coarseMaps[ref];const double sx=double(coarse.width)/w,sy=double(coarse.height)/h;
                const double proposal=refinement::propagate(coarse,(x+.5)*sx-.5,(y+.5)*sy-.5,settings.depthConsistencyFraction,step,r+1);
                if(proposal>=settings.nearDepth&&proposal<=settings.farDepth){
                    std::array<matching::Cost,7> proposedCosts{};
                    for(std::size_t src=0;src<sources.size();++src){const double corr=ncc(grayscale[src],warps[src],x,y,proposal,reference,r);if(corr>-.999)proposedCosts[src]={1.-corr,static_cast<std::size_t>((2*r+1)*(2*r+1)),true};}
                    const auto proposed=matching::aggregate(std::span<const matching::Cost>(proposedCosts).first(sources.size()),settings.robustSourceAggregation);
                    if(proposed.valid&&proposed.cost<=costs[best]&&proposed.cost<=selectedCost){inverse=1./proposal;selectedCost=proposed.cost;++result.propagatedHypotheses;}
                }
            }
            if(settings.normalRefinementIterations&&!coarseMaps.empty()&&inverse>0){
                const auto& coarse=coarseMaps[ref];const double sx=double(coarse.width)/w,sy=double(coarse.height)/h;
                const auto hypotheses=refinement::planeHypotheses(inputs->coarseViews[ref],coarse,(x+.5)*sx-.5,(y+.5)*sy-.5,step,r+1,settings.depthConsistencyFraction);
                const auto evaluate=[&](const refinement::PlaneHypothesis& plane,double depth){
                    cancellation::checkpoint();std::array<matching::Cost,7> candidateCosts{};
                    for(std::size_t src=0;src<sources.size();++src){
                        const double corr=planeNcc(grayscale[src],warps[src],views[ref],plane,x,y,depth,reference,r);
                        if(corr>-.999)candidateCosts[src]={1.-corr,static_cast<std::size_t>((2*r+1)*(2*r+1)),true};
                    }
                    return matching::aggregate(std::span<const matching::Cost>(candidateCosts).first(sources.size()),settings.robustSourceAggregation).cost;
                };
                const double selectedDepth=1./inverse;double winningCost=evaluate(hypotheses.values[0],selectedDepth);std::size_t winner=0;
                ++result.normalHypothesesEvaluated;
                for(std::size_t k=1;k<hypotheses.count;++k){const double cost=evaluate(hypotheses.values[k],selectedDepth);++result.normalHypothesesEvaluated;
                    if(cost+1e-8<winningCost){winningCost=cost;winner=k;}}
                if(winner&&hypotheses.values[winner].observed){
                    ++result.normalRefinedPixels;
                    const double left=std::max(range.nearDepth,selectedDepth/1.03),right=std::min(range.farDepth,selectedDepth*1.03);
                    if(left<selectedDepth&&selectedDepth<right){
                        const auto refined=refinement::local(left,selectedDepth,right,settings.normalRefinementIterations,
                            [&](double depth){return evaluate(hypotheses.values[winner],depth);});
                        if(refined.valid&&refined.cost<=winningCost)inverse=1./refined.depth;
                    }
                }
            }
            if(inverse<=0){++result.photometricDiagnostics.invalidInverseDepth;++loss.invalidInverseDepth;continue;}
            auto idx=static_cast<std::size_t>(y)*w+x;
            map.depth[idx]=static_cast<float>(1./inverse);
            map.confidence[idx]=static_cast<float>(std::clamp(margin/.20,0.,1.)*
                std::clamp((1.-costs[best])/.9,0.,1.));
            ++result.photometricPixels;
            ++result.photometricDiagnostics.acceptedSamples;
            ++loss.photometricAccepted;
          }
        }
         if(!settings.referenceMode&&depth_checkpoint::enabled()){
            DenseDepthMap completed;completed.width=tile.x1-tile.x0;completed.height=tile.y1-tile.y0;completed.depth.resize(static_cast<std::size_t>(completed.width)*completed.height);completed.confidence.resize(completed.depth.size());
            for(int y=tile.y0;y<tile.y1;++y)for(int x=tile.x0;x<tile.x1;++x){const auto src=static_cast<std::size_t>(y)*w+x,dst=static_cast<std::size_t>(y-tile.y0)*completed.width+x-tile.x0;completed.depth[dst]=map.depth[src];completed.confidence[dst]=map.confidence[src];}
            progress::begin(progress::Stage::Checkpoint,ref,tileIndex,1);if(depth_checkpoint::storeTile(tileKey,completed)){++result.writtenDepthTiles;progress::advance(1);}
         }
         ++result.completedTiles;
        }
        // Publish the completed reference atomically before processing the next.
        // A cancelled or killed process can reuse previous references, never a
        // half-solved reference or an unverified mesh.
        progress::begin(progress::Stage::Checkpoint,ref,0,1);
        if(checkpointCompatible&&depth_checkpoint::store(ref,map)){++result.writtenDepthMaps;progress::advance(1);}
        result.maps.push_back(std::move(map));
    }
    // Reciprocal camera-z depth checks: don't infer an object surface from NCC alone.
    const double voxel=std::max(.003,settings.nearDepth*.002);
    std::unordered_set<Voxel,VoxelHash> occupied;
    std::vector<double> scores;
    for(std::size_t ref=0;ref<views.size();++ref){
        cancellation::checkpoint();
        const auto& camera=views[ref];const auto& map=result.maps[ref];
        auto& loss=result.lossDiagnostics.views[ref];
        progress::begin(progress::Stage::Consistency,ref,0);
        std::size_t checkedPixels=0;
        for(int y=r+1;y<h-r-2;y+=step){
          cancellation::checkpoint();
          for(int x=r+1;x<w-r-2;x+=step){
            progress::advance(++checkedPixels);
            std::size_t idx=static_cast<std::size_t>(y)*w+x;
            float depth=map.depth[idx];if(depth<=0)continue;
            ++loss.reciprocalEvaluated;
            auto world=fromDepth(camera,x,y,depth);
            if(!finite(world)){++loss.invalidWorld;continue;}
            int confirmations=0,normalConfirmations=0;double normalConfidence=1;
            const auto referenceNormal=settings.normalConsistency?consistency::estimateNormal(camera,map,x,y,step,r+1,settings.depthConsistencyFraction):consistency::SurfaceNormal{};
            for(std::size_t src:result.sourceViews[ref]){
                ++loss.sourcePairs;
                auto q=cameraPoint(views[src],world);if(q.z<=.01||!finite(q)){++loss.invalidSourceProjection;continue;}
                double sx=views[src].fx*q.x/q.z+views[src].cx;
                double sy=views[src].fy*q.y/q.z+views[src].cy;
                const auto continuous=settings.discontinuityAwareConsistency?consistency::sampleDepth(result.maps[src],sx,sy,step,r+1,settings.depthConsistencyFraction):consistency::Sample{};
                NeighborDepthFailure lookupFailure{};
                float d=settings.discontinuityAwareConsistency?(continuous.classification==consistency::Visibility::Supported?static_cast<float>(continuous.depth):0):neighborDepth(result.maps[src],sx,sy,step,r+1,&lookupFailure);
                if(d<=0){++loss.sourceDepthUnavailable;
                    if(settings.discontinuityAwareConsistency)++loss.sourceInterpolationUnsupported;
                    else if(lookupFailure==NeighborDepthFailure::OutsideImage)++loss.sourceOutsideImage;
                    else if(lookupFailure==NeighborDepthFailure::GridRejected)++loss.sourceGridRejected;
                    else ++loss.sourceMissingDepth;
                    continue;}
                if(relativeDifference(q.z,d)>settings.depthConsistencyFraction){++loss.depthMismatch;continue;}
                // Reverse reprojection rejects nearest-grid mistakes at depth discontinuities.
                auto back=fromDepth(views[src],settings.discontinuityAwareConsistency?sx:std::round((sx-(r+1))/step)*step+(r+1),
                                    settings.discontinuityAwareConsistency?sy:std::round((sy-(r+1))/step)*step+(r+1),d);
                auto refCamera=cameraPoint(camera,back);
                if(refCamera.z<=0||!finite(refCamera)){++loss.invalidReverseProjection;continue;}
                double px=camera.fx*refCamera.x/refCamera.z+camera.cx;
                double py=camera.fy*refCamera.y/refCamera.z+camera.cy;
                if(std::hypot(px-x,py-y)>step*1.6){++loss.reverseReprojection;continue;}
                if(settings.normalConsistency){
                    const auto sourceNormal=consistency::estimateNormal(views[src],result.maps[src],sx,sy,step,r+1,settings.depthConsistencyFraction);
                    const auto agreement=consistency::verifyNormals(referenceNormal,camera.pose,sourceNormal,views[src].pose,settings.normalMaxAngleDegrees);
                    if(agreement.classification==consistency::Visibility::Inconsistent){++result.normalRejectedPairs;++loss.normalMismatch;continue;}
                    if(agreement.classification==consistency::Visibility::Supported){++result.normalSupportPairs;++normalConfirmations;normalConfidence=std::min({normalConfidence,referenceNormal.confidence,sourceNormal.confidence});}
                    else ++result.normalUnknownPairs;
                }
                ++confirmations;
                ++loss.confirmedPairs;
            }
            ++loss.confirmationHistogram[static_cast<std::size_t>(confirmations)];
            if(confirmations==0){++loss.noConfirmation;continue;}
            ++loss.reciprocalAccepted;
            ++result.consistentPixels;
            ++result.consistentPixelsPerView[ref];
            auto i=idx*4;
            const auto confidence=static_cast<float>(map.confidence[idx]*std::min(1.0,confirmations/2.0+0.5));
            auto observedNormal=normalConfirmations?multiply(transpose(camera.pose.rotation),referenceNormal.cameraNormal):Vec3{};
            if(normalConfirmations)observedNormal=scale(observedNormal,1./norm(observedNormal));
            // Verified samples retain their image neighborhood for confidence-weighted
            // M08 meshing. Strict cap prevents unbounded 2D-grid memory consumption.
            if(result.verifiedSamples.size()<MAX_DENSE_SAMPLES)
                result.verifiedSamples.push_back({world,{camera.rgba[i],camera.rgba[i+1],camera.rgba[i+2]},
                    confidence,static_cast<std::uint8_t>(confirmations+1),static_cast<std::uint8_t>(ref),
                    static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y),
                    observedNormal,
                    normalConfirmations?static_cast<float>(normalConfidence):0.f,static_cast<std::uint8_t>(normalConfirmations)});

          }
        }
    }
    // Select representatives AFTER all views were verified. Stronger evidence
    // wins a voxel; deterministic geometry/color ties remove camera-order bias.
    std::vector<const VerifiedDepthSample*> candidates;
    candidates.reserve(result.verifiedSamples.size());
    for(const auto& sample:result.verifiedSamples)candidates.push_back(&sample);
    std::sort(candidates.begin(),candidates.end(),[](auto a,auto b){
        if(a->confidence!=b->confidence)return a->confidence>b->confidence;
        if(a->supportingViews!=b->supportingViews)return a->supportingViews>b->supportingViews;
        if(a->xyz.x!=b->xyz.x)return a->xyz.x<b->xyz.x;
        if(a->xyz.y!=b->xyz.y)return a->xyz.y<b->xyz.y;
        if(a->xyz.z!=b->xyz.z)return a->xyz.z<b->xyz.z;
        return a->color<b->color;
    });
    for(const auto* sample:candidates){
        cancellation::checkpoint();if(result.points.size()>=settings.maxPoints)break;
        Voxel key;
        if(!tiles::voxelCoordinate(sample->xyz.x,voxel,key.x)||!tiles::voxelCoordinate(sample->xyz.y,voxel,key.y)||!tiles::voxelCoordinate(sample->xyz.z,voxel,key.z))continue;
        if(!occupied.insert(key).second)continue;
        result.points.push_back({sample->xyz,sample->color,sample->confidence,sample->supportingViews});
        scores.push_back(sample->confidence);
    }
    cancellation::checkpoint();
    result.medianConfidence=median(scores);
    return result;
}
std::string denseJson(const DenseResult& result){
    std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(11);
    out<<"{\"schema\":7,\"representation\":\"dense_points\",\"technique\":\"bounded_cpu_plane_sweep_zncc\",";
    const auto& diagnostic=result.photometricDiagnostics;
    const bool observed=diagnostic.available&&diagnostic.evaluatedSamples>0;
    const auto unknown=observed?result.sampledPixels-diagnostic.evaluatedSamples:result.sampledPixels;
    out<<"\"photometricDiagnostics\":{\"schema\":1,\"scope\":\"ORIGINATING_DENSE_EVALUATION\",\"available\":"<<(observed?"true":"false")
       <<",\"coverage\":\""<<(observed?(unknown?"PARTIAL":"COMPLETE"):"UNAVAILABLE")<<"\",\"unknownSamples\":"<<unknown;
    if(observed){
        out<<",\"evaluatedSamples\":"<<diagnostic.evaluatedSamples<<",\"acceptedSamples\":"<<diagnostic.acceptedSamples
           <<",\"rejectionCounts\":{\"lowVariance\":"<<diagnostic.lowVariance
           <<",\"invalidOrBoundaryBest\":"<<diagnostic.invalidOrBoundaryBest<<",\"ncc\":"<<diagnostic.ncc
           <<",\"uniqueness\":"<<diagnostic.uniqueness<<",\"invalidInverseDepth\":"<<diagnostic.invalidInverseDepth<<"}";
    }else out<<",\"evaluatedSamples\":null,\"acceptedSamples\":null,\"rejectionCounts\":null";
    out<<"},";
    writeLossDiagnostics(out,result.lossDiagnostics);out<<',';
    out<<"\"scale\":\"arbitrary\",\"triangleCount\":0,\"textured\":false,\"pointCount\":"<<result.points.size()
       <<",\"sampledPixels\":"<<result.sampledPixels<<",\"photometricPixels\":"<<result.photometricPixels
       <<",\"consistentPixels\":"<<result.consistentPixels<<",\"medianConfidence\":"<<result.medianConfidence<<",\"points\":[";
    for(std::size_t i=0;i<result.points.size();++i){
        auto& p=result.points[i];if(i)out<<',';
        out<<'['<<p.xyz.x<<','<<p.xyz.y<<','<<p.xyz.z<<','<<int(p.color[0])<<','<<int(p.color[1])<<','<<int(p.color[2])
           <<','<<p.confidence<<','<<int(p.consistentViews)<<']';
    }
    out<<"],\"sourceViewIndices\":[";
    for(std::size_t i=0;i<result.sourceViews.size();++i){
        if(i)out<<',';
        out<<'[';
        for(std::size_t j=0;j<result.sourceViews[i].size();++j){if(j)out<<',';out<<result.sourceViews[i][j];}
        out<<']';
    }
    out<<"],\"consistentPixelsPerView\":[";
    for(std::size_t i=0;i<result.consistentPixelsPerView.size();++i){if(i)out<<',';out<<result.consistentPixelsPerView[i];}
    out<<"],\"resumedDepthMaps\":"<<result.resumedDepthMaps
       <<",\"writtenDepthMaps\":"<<result.writtenDepthMaps
       <<",\"resumedDepthTiles\":"<<result.resumedDepthTiles<<",\"recomputedDepthTiles\":"<<result.recomputedDepthTiles<<",\"writtenDepthTiles\":"<<result.writtenDepthTiles
       <<",\"rejectedDepthTiles\":"<<result.rejectedDepthTiles
       <<",\"coarseDepthMaps\":"<<result.coarseDepthMaps<<",\"propagatedHypotheses\":"<<result.propagatedHypotheses<<",\"sparsePriorTiles\":"<<result.sparsePriorTiles
       <<",\"normalHypothesesEvaluated\":"<<result.normalHypothesesEvaluated<<",\"normalRefinedPixels\":"<<result.normalRefinedPixels
       <<",\"normalSupportPairs\":"<<result.normalSupportPairs<<",\"normalRejectedPairs\":"<<result.normalRejectedPairs<<",\"normalUnknownPairs\":"<<result.normalUnknownPairs
       <<",\"completedTiles\":"<<result.completedTiles<<",\"totalTiles\":"<<result.totalTiles
       <<",\"admittedWorkingSetBytes\":"<<result.admittedWorkingSetBytes
       <<",\"peakLuminanceBytes\":"<<result.peakLuminanceBytes
       <<",\"verifiedSampleCount\":"<<result.verifiedSamples.size()
       <<",\"pointExportLimitReached\":"<<(result.points.size()>=result.pointBudget?"true":"false")<<'}';
    return out.str();
}
} // namespace rf

extern "C" char* rf_dense_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,double near_depth,double far_depth,int planes,int stride)noexcept try {
    std::string response;
    try{
        if(!rgba||!camera_rows||camera_count<2||camera_count>rf::MAX_DENSE_VIEWS||width<32||height<32||width>640||height>640||
           static_cast<std::int64_t>(width)*height>320000)throw rf::Error("Invalid dense C ABI buffers");
        std::vector<rf::DenseView> views;views.reserve(camera_count);
        const auto n=static_cast<std::size_t>(width)*height*4;
        for(std::size_t i=0;i<camera_count;++i){
            rf::DenseView view;view.width=width;view.height=height;
            for(int k=0;k<9;++k)view.pose.rotation[k]=camera_rows[i*16+k];
            view.pose.translation={camera_rows[i*16+9],camera_rows[i*16+10],camera_rows[i*16+11]};
            view.fx=camera_rows[i*16+12];view.fy=camera_rows[i*16+13];
            view.cx=camera_rows[i*16+14];view.cy=camera_rows[i*16+15];
            view.rgba.assign(rgba+i*n,rgba+(i+1)*n);
            views.push_back(std::move(view));
        }
        rf::DenseSettings s;s.nearDepth=near_depth;s.farDepth=far_depth;s.depthPlanes=planes;s.pixelStride=stride;
        response=rf::denseJson(rf::reconstructDense(views,s));
    }catch(const std::bad_alloc&){throw;}
    catch(const std::exception& e){
        response="{\"error\":\"";
        for(char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(static_cast<unsigned char>(c)>=32)response+=c;}
        response+="\"}";
    }
    auto* out=static_cast<char*>(std::malloc(response.size()+1));if(!out)return nullptr;
    std::memcpy(out,response.c_str(),response.size()+1);return out;
} catch (const std::bad_alloc&) {
    return rf::abi::error("RESOURCE_EXHAUSTED", "Native allocation failed");
} catch (const std::exception&) {
    return rf::abi::error("NATIVE_FAILURE", "Native boundary failure");
} catch (...) {
    return rf::abi::error("UNKNOWN_NATIVE_FAILURE", "Unknown native boundary failure");
}

extern "C" char* rf_dense_refined_json(const std::uint8_t* rgba,int width,int height,
 const double* camera_rows,std::size_t camera_count,double near_depth,double far_depth,
 int planes,int stride,const double* prior_rows,std::size_t prior_count,
 const std::uint8_t* coarse_rgba,int coarse_width,int coarse_height,
 const double* coarse_camera_rows,const char* identity) noexcept {
 try {
  const auto readViews=[&](const std::uint8_t* pixels,int w,int h,const double* rows){
   if(!pixels||!rows||camera_count<2||camera_count>rf::MAX_DENSE_VIEWS||w<32||h<32||w>640||h>640||static_cast<std::int64_t>(w)*h>320000)throw rf::Error("Invalid refined dense image/camera buffers");
   std::vector<rf::DenseView> out;out.reserve(camera_count);const auto bytes=static_cast<std::size_t>(w)*h*4;
   for(std::size_t i=0;i<camera_count;++i){rf::DenseView v;v.width=w;v.height=h;for(int k=0;k<9;++k)v.pose.rotation[k]=rows[i*16+k];v.pose.translation={rows[i*16+9],rows[i*16+10],rows[i*16+11]};v.fx=rows[i*16+12];v.fy=rows[i*16+13];v.cx=rows[i*16+14];v.cy=rows[i*16+15];v.rgba.assign(pixels+i*bytes,pixels+(i+1)*bytes);out.push_back(std::move(v));}return out;
  };
  if(!identity||prior_count>20000||(prior_count&&!prior_rows))throw rf::Error("Invalid refined prior buffers/identity");
  std::size_t identitySize=0;while(identitySize<=64&&identity[identitySize])++identitySize;
  if(identitySize!=64)throw rf::Error("Refinement identity must be a 64-character SHA256");
  auto views=readViews(rgba,width,height,camera_rows);std::vector<rf::DenseView> coarse;
  if(coarse_rgba||coarse_camera_rows||coarse_width||coarse_height)coarse=readViews(coarse_rgba,coarse_width,coarse_height,coarse_camera_rows);
  if(!prior_count&&coarse.empty())throw rf::Error("Refined execution requires actual admitted priors or coarse photographs");
  std::vector<rf::DenseSparseDepthPrior> priors;priors.reserve(prior_count);
  for(std::size_t i=0;i<prior_count;++i){const auto* row=prior_rows+i*6;for(int k=0;k<6;++k)if(!std::isfinite(row[k]))throw rf::Error("Nonfinite sparse prior row");if(row[0]<0||row[0]>=camera_count||row[0]!=std::floor(row[0])||(row[5]!=0&&row[5]!=1))throw rf::Error("Invalid sparse prior camera/visibility");priors.push_back({static_cast<std::uint32_t>(row[0]),row[1],row[2],row[3],row[4],row[5]==1});}
  rf::DenseRefinementInputs inputs{priors,coarse,std::string(identity,identitySize)};
  rf::DenseSettings settings;settings.nearDepth=near_depth;settings.farDepth=far_depth;settings.depthPlanes=planes;settings.pixelStride=stride;settings.localRefinementIterations=6;settings.discontinuityAwareConsistency=true;settings.normalRefinementIterations=3;settings.normalConsistency=true;
  auto response=rf::denseJson(rf::reconstructDense(views,settings,&inputs));auto* out=static_cast<char*>(std::malloc(response.size()+1));if(out)std::memcpy(out,response.c_str(),response.size()+1);return out;
 }catch(const std::exception& e){
  try{std::string response="{\"error\":\"";for(const unsigned char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(c>=32)response+=static_cast<char>(c);}response+="\"}";auto* out=static_cast<char*>(std::malloc(response.size()+1));if(out)std::memcpy(out,response.c_str(),response.size()+1);return out;}catch(...){}
 }catch(...){}
 constexpr char failure[]=R"({"error":"refined dense native failure"})";auto* out=static_cast<char*>(std::malloc(sizeof(failure)));if(out)std::memcpy(out,failure,sizeof(failure));return out;
}
