#include "realityforge/dense.hpp"
#include "realityforge/depth_checkpoint.hpp"
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
struct Patch {double mean{},var{};std::array<double,49> pixels{};};
Patch referencePatch(const GrayView& v,int x,int y,int radius){
    Patch p;int n=0;double sum=0,sumSq=0;
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        auto s=sample(v,x+dx,y+dy);if(!s.valid)return {};
        p.pixels[n++]=s.gray;sum+=s.gray;sumSq+=s.gray*s.gray;
    }
    p.mean=sum/n;p.var=std::max(0.,sumSq/n-p.mean*p.mean);return p;
}
// Algebraically identical calibrated fronto-parallel warp. Relative transforms
// are prepared once per pair; the hot loop never reconstructs world points.
double ncc(const GrayView& neighbor,const detail::StereoWarp& warp,
    int x,int y,double z,const Patch& patch,int radius){
    int n=0;double srcSum=0,srcSumSq=0,cross=0;
    const auto base=warp.homogeneous(x,y,1./z);
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        const Vec3 q{base.x+warp.h[0]*dx+warp.h[1]*dy,
                     base.y+warp.h[3]*dx+warp.h[4]*dy,
                     base.z+warp.h[6]*dx+warp.h[7]*dy};
        if(q.z*z<=1e-5||!finite(q))return -2;
        auto intensity=sample(neighbor,q.x/q.z,q.y/q.z);
        if(!intensity.valid)return -2;
        srcSum+=intensity.gray;srcSumSq+=intensity.gray*intensity.gray;
        cross+=(patch.pixels[n]-patch.mean)*intensity.gray;
        ++n;
    }
    double mean=srcSum/n,var=srcSumSq/n-mean*mean;
    if(var<18.||patch.var<18.)return -2;
    double correlation=(cross/n)/std::sqrt(patch.var*var);
    return std::clamp(correlation,-1.,1.);
}
float relativeDifference(double a,double b){return static_cast<float>(std::abs(a-b)/std::max(a,b));}
// Nearest *sampled* depth neighbor in the sparse grid, with a small reprojection budget.
float neighborDepth(const DenseDepthMap& depth,double x,double y,int step,int radius){
    if(!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=depth.width||y>=depth.height)return 0;
    int ix=static_cast<int>(std::round((x-radius)/step))*step+radius;
    int iy=static_cast<int>(std::round((y-radius)/step))*step+radius;
    if(ix<0||iy<0||ix>=depth.width||iy>=depth.height||
       std::abs(ix-x)>step*.65||std::abs(iy-y)>step*.65)return 0;
    return depth.depth[static_cast<std::size_t>(iy)*depth.width+ix];
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
       s.maxPoints<10||s.maxPoints>50000||s.executionVersion!=1||s.tileEdge<8||s.tileEdge>640||s.localRefinementIterations>12)throw Error("Invalid bounded CPU MVS configuration");
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
}

DenseResult reconstructDense(std::span<const DenseView> views,const DenseSettings& settings){
    cancellation::checkpoint();
    validate(views,settings);
    DenseResult result;result.pointBudget=settings.maxPoints;
    auto admitted=tiles::admit(views.size(),static_cast<std::size_t>(views[0].width)*views[0].height,MAX_DENSE_SAMPLES,settings.maxPoints,settings.depthPlanes,settings.workingSetBudgetBytes,settings.referenceMode);
    result.admittedWorkingSetBytes=admitted.totalBytes;
    const int w=views.front().width,h=views.front().height,r=settings.patchRadius,step=settings.pixelStride;
    // Inverse camera-z sampling allocates more candidates to nearby surfaces.
    std::vector<double> planes(settings.depthPlanes);
    for(int k=0;k<settings.depthPlanes;++k){
        double t=double(k)/(settings.depthPlanes-1);
        planes[k]=1./((1-t)/settings.nearDepth+t/settings.farDepth);
    }
    const std::size_t n=static_cast<std::size_t>(w)*h;
    const auto tilePlan=tiles::plan(w,h,r+1,step,r+1,settings.referenceMode?640:settings.tileEdge);
    result.totalTiles=tilePlan.size()*views.size();
    result.sourceViews=detail::denseSourcePlan(views,settings.nearDepth,settings.farDepth);
    result.consistentPixelsPerView.assign(views.size(),0);
    result.maps.reserve(views.size());
    result.verifiedSamples.reserve(std::min<std::size_t>(settings.maxPoints * 2,MAX_DENSE_SAMPLES));
    for(std::size_t ref=0;ref<views.size();++ref){
        cancellation::checkpoint();
        DenseDepthMap map;map.width=w;map.height=h;map.depth.assign(n,0);map.confidence.assign(n,0);
        // Reuse only fully validated, input-identity-bound depth maps. Camera-z
        // consistency, meshing and UV projection still run afresh on every scan.
        const auto& sources=result.sourceViews[ref];
        // Existing Kotlin identity predates optional refinement/aggregation.
        // Never reuse a legacy checkpoint with altered photometric semantics.
        const bool checkpointCompatible=settings.localRefinementIterations==0&&!settings.robustSourceAggregation;
        if(checkpointCompatible&&depth_checkpoint::load(ref,map)){
            for(int y=r+1;y<h-r-2;y+=step)for(int x=r+1;x<w-r-2;x+=step){
                ++result.sampledPixels;
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
        for(const auto& tile:tilePlan){
         const std::size_t tileSamples=static_cast<std::size_t>((tile.x1-tile.x0+step-1)/step)*((tile.y1-tile.y0+step-1)/step);
         progress::begin(progress::Stage::Depth,ref,result.completedTiles,tileSamples);
         std::size_t tileWork=0;
         cancellation::checkpoint();
         for(int y=tile.y0;y<tile.y1;y+=step){
          cancellation::checkpoint();
          for(int x=tile.x0;x<tile.x1;x+=step){
            ++result.sampledPixels;progress::advance(++tileWork);
            auto reference=referencePatch(image,x,y,r);
            if(reference.var<18.)continue; // no reliable photometric texture
            std::fill(costs.begin(),costs.end(),2.);
            for(int k=0;k<settings.depthPlanes;++k){
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
            if(best<2||best>=settings.depthPlanes-2||costs[best]>1-settings.minimumNcc)continue;
            double runnerUp=2.;
            for(int k=0;k<settings.depthPlanes;++k)if(std::abs(k-best)>3)runnerUp=std::min(runnerUp,costs[k]);
            double margin=runnerUp-costs[best];
            if(margin<settings.minUniqueness)continue;
            double inverse=refinement::parabolicInverseDepth(costs,planes,static_cast<std::size_t>(best));
            if(settings.localRefinementIterations){
                auto refined=refinement::local(planes[best-1],1./inverse,planes[best+1],settings.localRefinementIterations,[&](double depth){
                    cancellation::checkpoint();double bestCost=2.;
                    for(std::size_t src=0;src<sources.size();++src){double corr=ncc(grayscale[src],warps[src],x,y,depth,reference,r);if(corr>-.999)bestCost=std::min(bestCost,1.-corr);}
                    return bestCost;
                });
                if(refined.valid&&refined.cost<=costs[best])inverse=1./refined.depth;
            }
            if(inverse<=0)continue;
            auto idx=static_cast<std::size_t>(y)*w+x;
            map.depth[idx]=static_cast<float>(1./inverse);
            map.confidence[idx]=static_cast<float>(std::clamp(margin/.20,0.,1.)*
                std::clamp((1.-costs[best])/.9,0.,1.));
            ++result.photometricPixels;
          }
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
        progress::begin(progress::Stage::Consistency,ref,0);
        std::size_t checkedPixels=0;
        for(int y=r+1;y<h-r-2;y+=step){
          cancellation::checkpoint();
          for(int x=r+1;x<w-r-2;x+=step){
            progress::advance(++checkedPixels);
            std::size_t idx=static_cast<std::size_t>(y)*w+x;
            float depth=map.depth[idx];if(depth<=0)continue;
            auto world=fromDepth(camera,x,y,depth);
            if(!finite(world))continue;
            int confirmations=0;
            for(std::size_t src=0;src<views.size();++src){
                if(src==ref)continue;
                auto q=cameraPoint(views[src],world);if(q.z<=.01||!finite(q))continue;
                double sx=views[src].fx*q.x/q.z+views[src].cx;
                double sy=views[src].fy*q.y/q.z+views[src].cy;
                const auto continuous=settings.discontinuityAwareConsistency?consistency::sampleDepth(result.maps[src],sx,sy,step,r+1,settings.depthConsistencyFraction):consistency::Sample{};
                float d=settings.discontinuityAwareConsistency?(continuous.classification==consistency::Visibility::Supported?static_cast<float>(continuous.depth):0):neighborDepth(result.maps[src],sx,sy,step,r+1);
                if(d<=0||relativeDifference(q.z,d)>settings.depthConsistencyFraction)continue;
                // Reverse reprojection rejects nearest-grid mistakes at depth discontinuities.
                auto back=fromDepth(views[src],settings.discontinuityAwareConsistency?sx:std::round((sx-(r+1))/step)*step+(r+1),
                                    settings.discontinuityAwareConsistency?sy:std::round((sy-(r+1))/step)*step+(r+1),d);
                auto refCamera=cameraPoint(camera,back);
                if(refCamera.z<=0||!finite(refCamera))continue;
                double px=camera.fx*refCamera.x/refCamera.z+camera.cx;
                double py=camera.fy*refCamera.y/refCamera.z+camera.cy;
                if(std::hypot(px-x,py-y)>step*1.6)continue;
                ++confirmations;
            }
            if(confirmations==0)continue;
            ++result.consistentPixels;
            ++result.consistentPixelsPerView[ref];
            auto i=idx*4;
            const auto confidence=static_cast<float>(map.confidence[idx]*std::min(1.0,confirmations/2.0+0.5));
            // Verified samples retain their image neighborhood for confidence-weighted
            // M08 meshing. Strict cap prevents unbounded 2D-grid memory consumption.
            if(result.verifiedSamples.size()<MAX_DENSE_SAMPLES)
                result.verifiedSamples.push_back({world,{camera.rgba[i],camera.rgba[i+1],camera.rgba[i+2]},
                    confidence,static_cast<std::uint8_t>(confirmations+1),static_cast<std::uint8_t>(ref),
                    static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});

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
       <<",\"completedTiles\":"<<result.completedTiles<<",\"totalTiles\":"<<result.totalTiles
       <<",\"admittedWorkingSetBytes\":"<<result.admittedWorkingSetBytes
       <<",\"peakLuminanceBytes\":"<<result.peakLuminanceBytes
       <<",\"verifiedSampleCount\":"<<result.verifiedSamples.size()
       <<",\"pointExportLimitReached\":"<<(result.points.size()>=result.pointBudget?"true":"false")<<'}';
    return out.str();
}
} // namespace rf

extern "C" char* rf_dense_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,double near_depth,double far_depth,int planes,int stride) noexcept {
 try {
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
    }catch(const std::exception& e){
        response="{\"error\":\"";
        for(char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(static_cast<unsigned char>(c)>=32)response+=c;}
        response+="\"}";
    }
    auto* out=static_cast<char*>(std::malloc(response.size()+1));if(!out)return nullptr;
    std::memcpy(out,response.c_str(),response.size()+1);return out;
 } catch(...) {
    constexpr char failure[]=R"({"error":"dense native failure"})";
    auto* out=static_cast<char*>(std::malloc(sizeof(failure)));
    if(out)std::memcpy(out,failure,sizeof(failure));
    return out;
 }
}
