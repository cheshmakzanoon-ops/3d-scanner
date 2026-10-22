#pragma once
#include "realityforge/dense.hpp"
#include <algorithm>
#include <cmath>
#include <array>
namespace rf::consistency {
enum class Visibility { Unknown, Supported, Occluded, Inconsistent, Discontinuity, Outside };
struct Sample { Visibility classification{Visibility::Unknown}; double depth{},confidence{},x{},y{}; };
// Interpolate only within a continuous cell of independently observed samples.
// Missing support is unknown; it is never filled by a convenient nearby layer.
inline Sample sampleDepth(const DenseDepthMap& map,double x,double y,int step,int origin,double maximumRelativeSpread=.055){
    if(map.width<=0||map.height<=0||step<=0||origin<0||!std::isfinite(x)||!std::isfinite(y)||
       !std::isfinite(maximumRelativeSpread)||maximumRelativeSpread<0||maximumRelativeSpread>1||
       map.depth.size()!=static_cast<std::size_t>(map.width)*map.height||map.confidence.size()!=map.depth.size())return {};
    if(x<origin||y<origin||x>=map.width||y>=map.height)return {Visibility::Outside};
    const double gx=(x-origin)/step,gy=(y-origin)/step;
    if(gx>map.width||gy>map.height)return {Visibility::Outside};
    const int ix=origin+static_cast<int>(std::floor(gx))*step,iy=origin+static_cast<int>(std::floor(gy))*step;
    const double fx=(x-ix)/step,fy=(y-iy)/step;
    const std::array<double,4>weight{(1-fx)*(1-fy),fx*(1-fy),(1-fx)*fy,fx*fy};
    const std::array<int,4>xs{ix,ix+step,ix,ix+step},ys{iy,iy,iy+step,iy+step};
    double lo=1e300,hi=0,z=0,c=0;
    for(int k=0;k<4;++k){
        if(weight[k]<1e-12)continue;
        if(xs[k]<0||ys[k]<0||xs[k]>=map.width||ys[k]>=map.height)return {Visibility::Outside};
        const auto i=static_cast<std::size_t>(ys[k])*map.width+xs[k];const double d=map.depth[i],q=map.confidence[i];
        if(!std::isfinite(d)||!std::isfinite(q)||d<=0||q<0||q>1)return {};
        lo=std::min(lo,d);hi=std::max(hi,d);z+=weight[k]*d;c+=weight[k]*q;
    }
    if((hi-lo)/hi>maximumRelativeSpread)return {Visibility::Discontinuity};
    return {Visibility::Supported,z,c,x,y};
}
struct Verification { Visibility classification{Visibility::Unknown}; double relativeError{},reprojectionError{}; };
inline Verification verify(const Sample& sample,double predictedDepth,double reprojectionError,double relativeTolerance,double pixelTolerance){
    if(sample.classification!=Visibility::Supported)return {sample.classification};
    if(!std::isfinite(sample.depth)||sample.depth<=0||!std::isfinite(sample.confidence)||sample.confidence<0||sample.confidence>1)return {};
    if(!std::isfinite(predictedDepth)||predictedDepth<=0||!std::isfinite(reprojectionError)||reprojectionError<0||
       !std::isfinite(relativeTolerance)||relativeTolerance<=0||!std::isfinite(pixelTolerance)||pixelTolerance<=0)return {};
    const double error=std::abs(predictedDepth-sample.depth)/std::max(predictedDepth,sample.depth);
    if(error>relativeTolerance)return {predictedDepth>sample.depth?Visibility::Occluded:Visibility::Inconsistent,error,reprojectionError};
    return {reprojectionError<=pixelTolerance?Visibility::Supported:Visibility::Inconsistent,error,reprojectionError};
}
inline constexpr unsigned NORMAL_SETTINGS_VERSION=2;
struct SurfaceNormal { Vec3 cameraNormal{}; double confidence{}; bool valid{}; };
// A normal needs four independent neighboring observations, continuous depth,
// and agreement between two local triangles. Missing/unstable evidence remains
// unknown. Depth is calibrated camera-z, never Euclidean ray distance.
inline SurfaceNormal estimateNormal(const DenseView& camera,const DenseDepthMap& map,
    double x,double y,int step,int origin,double maximumRelativeSpread=.055){
    if(camera.width!=map.width||camera.height!=map.height||!std::isfinite(camera.fx)||!std::isfinite(camera.fy)||
       !std::isfinite(camera.cx)||!std::isfinite(camera.cy)||camera.fx<=0||camera.fy<=0||step<1||step>8)return {};
    const std::array<double,5> xs{x,x-step,x+step,x,x},ys{y,y,y,y-step,y+step};
    std::array<Vec3,5> p{};double lo=1e300,hi=0,confidence=1,centerDepth=0;
    for(std::size_t k=0;k<5;++k){const auto sampled=sampleDepth(map,xs[k],ys[k],step,origin,maximumRelativeSpread);
        if(sampled.classification!=Visibility::Supported||sampled.confidence<=0)return {};
        lo=std::min(lo,sampled.depth);hi=std::max(hi,sampled.depth);confidence=std::min(confidence,sampled.confidence);
        // A common scale leaves the normal unchanged, while making the area
        // degeneracy test independent of arbitrary SfM world units. Normalize
        // before coordinate products/cross products, not after they underflow.
        if(k==0)centerDepth=sampled.depth;
        const double relativeDepth=sampled.depth/centerDepth;
        p[k]={((xs[k]-camera.cx)/camera.fx)*relativeDepth,((ys[k]-camera.cy)/camera.fy)*relativeDepth,relativeDepth};}
    if((hi-lo)/hi>maximumRelativeSpread)return {};
    const auto sub=[](Vec3 a,Vec3 b){return Vec3{a.x-b.x,a.y-b.y,a.z-b.z};};
    const auto cross=[](Vec3 a,Vec3 b){return Vec3{a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};};
    const auto unit=[](Vec3 a){double n=std::hypot(a.x,a.y,a.z);return std::isfinite(n)&&n>1e-12?Vec3{a.x/n,a.y/n,a.z/n}:Vec3{};};
    const auto a=unit(cross(sub(p[2],p[0]),sub(p[4],p[0]))),b=unit(cross(sub(p[0],p[1]),sub(p[0],p[3])));
    const double dot=a.x*b.x+a.y*b.y+a.z*b.z;
    if(!std::isfinite(dot)||dot<.7071067811865476)return {};
    auto n=unit(cross(sub(p[2],p[1]),sub(p[4],p[3])));
    if(n.x==0&&n.y==0&&n.z==0)return {};
    if(n.z<0)n={-n.x,-n.y,-n.z};
    return {n,confidence*std::clamp(dot,0.,1.),true};
}
struct NormalVerification { Visibility classification{Visibility::Unknown}; double angleDegrees{}; };
// Compare separately reconstructed neighborhoods in one common world frame.
// Plane normals are unoriented, so opposite representations are equivalent.
// This gate never turns an unknown normal into independent surface support.
inline NormalVerification verifyNormals(const SurfaceNormal& reference,const ViewPose& referencePose,
    const SurfaceNormal& source,const ViewPose& sourcePose,double maximumAngleDegrees=35.){
    if(!reference.valid||!source.valid||!std::isfinite(maximumAngleDegrees)||maximumAngleDegrees<=0||maximumAngleDegrees>60)return {};
    const auto calibrated=[](const ViewPose& pose){const auto& r=pose.rotation;
        for(double v:r)if(!std::isfinite(v))return false;
        for(int row=0;row<3;++row)for(int col=0;col<3;++col){double dot=0;for(int k=0;k<3;++k)dot+=r[row*3+k]*r[col*3+k];
            if(!std::isfinite(dot)||std::abs(dot-(row==col?1.:0.))>.025)return false;}
        const double det=r[0]*(r[4]*r[8]-r[5]*r[7])-r[1]*(r[3]*r[8]-r[5]*r[6])+r[2]*(r[3]*r[7]-r[4]*r[6]);
        return std::isfinite(det)&&std::abs(det-1)<.025;};
    if(!calibrated(referencePose)||!calibrated(sourcePose))return {};
    const auto unit=[](const SurfaceNormal& a){const double n=std::hypot(a.cameraNormal.x,a.cameraNormal.y,a.cameraNormal.z);return std::isfinite(n)&&std::abs(n-1)<1e-6;};
    if(!unit(reference)||!unit(source))return {};
    const auto world=[](const SurfaceNormal& a,const ViewPose& pose){const auto& r=pose.rotation;const auto n=a.cameraNormal;
        return Vec3{r[0]*n.x+r[3]*n.y+r[6]*n.z,r[1]*n.x+r[4]*n.y+r[7]*n.z,r[2]*n.x+r[5]*n.y+r[8]*n.z};};
    const auto a=world(reference,referencePose),b=world(source,sourcePose);const double an=std::hypot(a.x,a.y,a.z),bn=std::hypot(b.x,b.y,b.z);
    if(!std::isfinite(an)||!std::isfinite(bn)||an<1e-12||bn<1e-12||!std::isfinite(reference.confidence)||!std::isfinite(source.confidence)||reference.confidence<=0||source.confidence<=0||reference.confidence>1||source.confidence>1)return {};
    const double cosine=std::clamp(std::abs((a.x/an)*(b.x/bn)+(a.y/an)*(b.y/bn)+(a.z/an)*(b.z/bn)),0.,1.);
    const double angle=std::acos(cosine)*57.29577951308232;
    return {angle<=maximumAngleDegrees?Visibility::Supported:Visibility::Inconsistent,angle};
}
}
