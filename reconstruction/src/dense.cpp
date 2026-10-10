#include "realityforge/dense.hpp"
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
struct Bilinear {double gray{};bool valid{};};
Bilinear sample(const DenseView& image,double x,double y){
    if(!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=image.width-1||y>=image.height-1)return {};
    int ix=static_cast<int>(x),iy=static_cast<int>(y);
    double fx=x-ix,fy=y-iy;
    const std::array<std::size_t,4> pos{
        (static_cast<std::size_t>(iy)*image.width+ix)*4,
        (static_cast<std::size_t>(iy)*image.width+ix+1)*4,
        (static_cast<std::size_t>(iy+1)*image.width+ix)*4,
        (static_cast<std::size_t>(iy+1)*image.width+ix+1)*4};
    for(auto i:pos)if(image.rgba[i+3]!=255)return {};
    auto gray=[&](std::size_t p){return (77.0*image.rgba[p]+150.0*image.rgba[p+1]+29.0*image.rgba[p+2])/256.;};
    return {gray(pos[0])*(1-fx)*(1-fy)+gray(pos[1])*fx*(1-fy)+
            gray(pos[2])*(1-fx)*fy+gray(pos[3])*fx*fy,true};
}
struct Patch {double mean{},var{};std::array<double,49> pixels{};};
Patch referencePatch(const DenseView& v,int x,int y,int radius){
    Patch p;int n=0;double sum=0,sumSq=0;
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        auto s=sample(v,x+dx,y+dy);if(!s.valid)return {};
        p.pixels[n++]=s.gray;sum+=s.gray;sumSq+=s.gray*s.gray;
    }
    p.mean=sum/n;p.var=std::max(0.,sumSq/n-p.mean*p.mean);return p;
}
// Warps a local fronto-parallel reference patch using actual calibrated cameras.
// Zero-mean NCC suppresses gain/offset changes without claiming photometric calibration.
double ncc(const DenseView& reference,const DenseView& neighbor,
    int x,int y,double z,const Patch& patch,int radius){
    int n=0;double srcSum=0,srcSumSq=0,cross=0;
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        Vec3 world=fromDepth(reference,x+dx,y+dy,z);
        Vec3 projected=cameraPoint(neighbor,world);
        if(projected.z<=1e-5||!finite(projected))return -2;
        auto intensity=sample(neighbor,neighbor.fx*projected.x/projected.z+neighbor.cx,
                             neighbor.fy*projected.y/projected.z+neighbor.cy);
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
    if(views.size()<2||views.size()>4)throw Error("Dense MVS requires 2 to 4 calibrated views");
    if(!std::isfinite(s.nearDepth)||!std::isfinite(s.farDepth)||s.nearDepth<=.01||s.farDepth<=s.nearDepth*1.05||s.farDepth>100000||
       s.depthPlanes<12||s.depthPlanes>128||s.pixelStride<2||s.pixelStride>8||
       s.patchRadius<1||s.patchRadius>3||s.minimumNcc<0||s.minimumNcc>1||
       s.minUniqueness<.005||s.minUniqueness>.5||s.depthConsistencyFraction<.005||s.depthConsistencyFraction>.25||
       s.maxPoints<10||s.maxPoints>50000)throw Error("Invalid bounded CPU MVS configuration");
    int w=views.front().width,h=views.front().height;
    if(w<32||h<32||w>640||h>640||static_cast<std::int64_t>(w)*h>320000)throw Error("Dense MVS image exceeds CPU reference limits");
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
    validate(views,settings);
    DenseResult result;
    const int w=views.front().width,h=views.front().height,r=settings.patchRadius,step=settings.pixelStride;
    // Inverse camera-z sampling allocates more candidates to nearby surfaces.
    std::vector<double> planes(settings.depthPlanes);
    for(int k=0;k<settings.depthPlanes;++k){
        double t=double(k)/(settings.depthPlanes-1);
        planes[k]=1./((1-t)/settings.nearDepth+t/settings.farDepth);
    }
    const std::size_t n=static_cast<std::size_t>(w)*h;
    result.maps.reserve(views.size());
    result.verifiedSamples.reserve(std::min<std::size_t>(settings.maxPoints * 2,50000));
    for(std::size_t ref=0;ref<views.size();++ref){
        DenseDepthMap map;map.width=w;map.height=h;map.depth.assign(n,0);map.confidence.assign(n,0);
        const auto& image=views[ref];
        for(int y=r+1;y<h-r-2;y+=step)for(int x=r+1;x<w-r-2;x+=step){
            ++result.sampledPixels;
            auto reference=referencePatch(image,x,y,r);
            if(reference.var<18.)continue; // no reliable photometric texture
            std::vector<double> costs(settings.depthPlanes,2.);
            for(int k=0;k<settings.depthPlanes;++k){
                // A candidate must be supported by another photo, not a single-image guess.
                double best=2.;
                for(std::size_t src=0;src<views.size();++src){
                    if(src==ref)continue;
                    double corr=ncc(image,views[src],x,y,planes[k],reference,r);
                    if(corr>-.999)best=std::min(best,1.-corr);
                }
                costs[k]=best;
            }
            int best=0;for(int k=1;k<settings.depthPlanes;++k)if(costs[k]<costs[best])best=k;
            if(best<2||best>=settings.depthPlanes-2||costs[best]>1-settings.minimumNcc)continue;
            double runnerUp=2.;
            for(int k=0;k<settings.depthPlanes;++k)if(std::abs(k-best)>3)runnerUp=std::min(runnerUp,costs[k]);
            double margin=runnerUp-costs[best];
            if(margin<settings.minUniqueness)continue;
            double inverse=1./planes[best];
            // Interpolate the peak in inverse depth using neighboring plane-sweep costs.
            const double a=costs[best-1],b=costs[best],c=costs[best+1];
            double denom=a-2*b+c;
            if(denom>1e-8){
                double offset=std::clamp(.5*(a-c)/denom,-.75,.75);
                double invStep=1./planes[1]-1./planes[0];
                inverse+=invStep*offset;
            }
            if(inverse<=0)continue;
            auto idx=static_cast<std::size_t>(y)*w+x;
            map.depth[idx]=static_cast<float>(1./inverse);
            map.confidence[idx]=static_cast<float>(std::clamp(margin/.20,0.,1.)*
                std::clamp((1.-costs[best])/.9,0.,1.));
            ++result.photometricPixels;
        }
        result.maps.push_back(std::move(map));
    }
    // Reciprocal camera-z depth checks: don't infer an object surface from NCC alone.
    const double voxel=std::max(.003,settings.nearDepth*.002);
    std::unordered_set<Voxel,VoxelHash> occupied;
    std::vector<double> scores;
    for(std::size_t ref=0;ref<views.size();++ref){
        const auto& camera=views[ref];const auto& map=result.maps[ref];
        for(int y=r+1;y<h-r-2;y+=step)for(int x=r+1;x<w-r-2;x+=step){
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
                float d=neighborDepth(result.maps[src],sx,sy,step,r+1);
                if(d<=0||relativeDifference(q.z,d)>settings.depthConsistencyFraction)continue;
                // Reverse reprojection rejects nearest-grid mistakes at depth discontinuities.
                auto back=fromDepth(views[src],std::round((sx-(r+1))/step)*step+(r+1),
                                    std::round((sy-(r+1))/step)*step+(r+1),d);
                auto refCamera=cameraPoint(camera,back);
                if(refCamera.z<=0||!finite(refCamera))continue;
                double px=camera.fx*refCamera.x/refCamera.z+camera.cx;
                double py=camera.fy*refCamera.y/refCamera.z+camera.cy;
                if(std::hypot(px-x,py-y)>step*1.6)continue;
                ++confirmations;
            }
            if(confirmations==0)continue;
            ++result.consistentPixels;
            auto i=idx*4;
            const auto confidence=static_cast<float>(map.confidence[idx]*std::min(1.0,confirmations/2.0+0.5));
            // Verified samples retain their image neighborhood for confidence-weighted
            // M08 meshing. Strict cap prevents unbounded 2D-grid memory consumption.
            if(result.verifiedSamples.size()<std::min<std::size_t>(settings.maxPoints*2,50000))
                result.verifiedSamples.push_back({world,{camera.rgba[i],camera.rgba[i+1],camera.rgba[i+2]},
                    confidence,static_cast<std::uint8_t>(confirmations+1),static_cast<std::uint8_t>(ref),
                    static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
            if(result.points.size()>=settings.maxPoints)continue;
            Voxel key{static_cast<long long>(std::floor(world.x/voxel)),
                      static_cast<long long>(std::floor(world.y/voxel)),
                      static_cast<long long>(std::floor(world.z/voxel))};
            if(!occupied.insert(key).second)continue;
            result.points.push_back({world,{camera.rgba[i],camera.rgba[i+1],camera.rgba[i+2]},static_cast<float>(confidence),
                                     static_cast<std::uint8_t>(confirmations+1)});
            scores.push_back(confidence);
        }
    }
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
    out<<"]}";return out.str();
}
} // namespace rf

extern "C" char* rf_dense_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,double near_depth,double far_depth,int planes,int stride){
    std::string response;
    try{
        if(!rgba||!camera_rows||camera_count<2||camera_count>4||width<32||height<32||width>640||height>640||
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
}
