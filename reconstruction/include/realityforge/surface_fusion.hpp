#pragma once
#include "realityforge/surface_evidence.hpp"
#include <array>
#include <cstdint>
#include <limits>

namespace rf::surface_fusion {
inline std::array<std::int64_t,3> checkedVoxel(Vec3 p,double size){
    if(!std::isfinite(size)||size<=0)throw Error("Invalid surface voxel size");
    std::array<std::int64_t,3> result{};std::array<double,3> xyz{p.x,p.y,p.z};
    // Keep one neighbor-cell margin so +/-1 cannot overflow the key.
    const long double lower=static_cast<long double>(std::numeric_limits<std::int64_t>::min())+2;
    const long double upper=static_cast<long double>(std::numeric_limits<std::int64_t>::max())-2;
    for(int k=0;k<3;++k){
        if(!std::isfinite(xyz[k]))throw Error("Non-finite surface voxel coordinate");
        const long double value=std::floor(static_cast<long double>(xyz[k])/size);
        if(!std::isfinite(value)||value<lower||value>upper)throw Error("Surface voxel coordinate outside integer range");
        result[k]=static_cast<std::int64_t>(value);
    }return result;
}
// Each vertex occupies one power-of-two level. A cell is at least as wide
// as its source radius, so any admissible pair lies in 27 adjacent cells at
// that vertex's level; all 24 levels require at most 648 bounded lookups.
inline constexpr std::size_t radiusLevelCount=24;
inline double radiusCellSize(std::size_t level){
    if(level>=radiusLevelCount)throw Error("Invalid surface radius level");
    return std::ldexp(.0001,static_cast<int>(level));
}
inline double observedFusionRadius(double cameraZ,double minimumFocal,int stride){
    if(!std::isfinite(cameraZ)||cameraZ<=0||!std::isfinite(minimumFocal)||minimumFocal<=0||stride<2||stride>8)throw Error("Invalid observed surface footprint");
    const long double spacing=static_cast<long double>(cameraZ)*stride/minimumFocal;
    return std::max(.0001,static_cast<double>(std::clamp(spacing,.00001L,1000.L))*.60);
}
inline std::size_t radiusLevel(double radius){
    if(!std::isfinite(radius)||radius<.0001||radius>600)throw Error("Invalid surface fusion radius");
    std::size_t level=0;
    while(radiusCellSize(level)<radius)++level;
    return level;
}
inline bool unitNormal(Vec3 n,Vec3& result){
    if(!std::isfinite(n.x)||!std::isfinite(n.y)||!std::isfinite(n.z))return false;
    const double scale=std::max({std::abs(n.x),std::abs(n.y),std::abs(n.z)});
    if(scale==0)return false;
    n={n.x/scale,n.y/scale,n.z/scale};
    const double length=std::hypot(n.x,n.y,n.z);
    result={n.x/length,n.y/length,n.z/length};return true;
}
inline bool compatibleNormal(Vec3 a,Vec3 b){
    Vec3 first{},second{};
    return unitNormal(a,first)&&unitNormal(b,second)&&
        first.x*second.x+first.y*second.y+first.z*second.z>=.8660254037844386;
}
namespace metric_detail {
inline Vec3 subtract(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline double length(Vec3 p){return std::hypot(p.x,p.y,p.z);}
inline bool finite(Vec3 p){return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z);}
inline Vec3 cameraPoint(const DenseView& view,Vec3 p){const auto q=multiply(view.pose.rotation,p);return {q.x+view.pose.translation.x,q.y+view.pose.translation.y,q.z+view.pose.translation.z};}
inline std::optional<Mat3> inverseRotation(const Mat3& m){
    for(auto x:m)if(!std::isfinite(x))return {};
    const double d=determinant(m);if(!std::isfinite(d)||std::abs(d-1)>.025)return {};
    const auto orth=multiply(m,transpose(m));
    for(int i=0;i<9;++i)if(std::abs(orth[i]-(i%4==0?1.:0.))>.025)return {};
    // A true inverse, including admitted small rotation roundoff; a transpose
    // alone would not give an exact enclosure for every accepted matrix.
    Mat3 inverse{m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
                 m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
                 m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3]};
    for(auto& x:inverse){x/=d;if(!std::isfinite(x))return {};}
    return inverse;
}
inline double pixelError(const DenseView& view,const VerifiedDepthSample& anchor,Vec3 point,int stride){
    if(stride<2||stride>8||!finite(point)||!std::isfinite(view.fx)||!std::isfinite(view.fy)||view.fx<=0||view.fy<=0||!std::isfinite(view.cx)||!std::isfinite(view.cy))return INFINITY;
    const auto p=cameraPoint(view,point);if(!finite(p)||p.z<=0)return INFINITY;
    const double x=view.fx*p.x/p.z+view.cx,y=view.fy*p.y/p.z+view.cy;
    if(!std::isfinite(x)||!std::isfinite(y))return INFINITY;
    return std::hypot(x-anchor.x,y-anchor.y)/(.60*stride);
}
}
// Enclose the ORIGINAL observed pixel's square frustum intersected with the
// supported world-normal slab. All eight finite positive intersections must
// exist with the same sign; a horizon-crossing window is unsupported.
inline std::optional<double> planePixelEnclosingRadius(const DenseView& view,const VerifiedDepthSample& anchor,Vec3 normal,double frontRadius,int stride){
    using namespace metric_detail;
    Vec3 n{};
    if(stride<2||stride>8||!unitNormal(normal,n)||!finite(anchor.xyz)||!std::isfinite(frontRadius)||frontRadius<.0001||frontRadius>600||
       anchor.x>=view.width||anchor.y>=view.height||!std::isfinite(view.fx)||!std::isfinite(view.fy)||view.fx<=0||view.fy<=0||!std::isfinite(view.cx)||!std::isfinite(view.cy))return {};
    if(!inverseRotation(view.pose.rotation))return {};
    using Wide=long double;using WVec=std::array<Wide,3>;
    const auto wdot=[](const WVec& a,const WVec& b){return a[0]*b[0]+a[1]*b[1]+a[2]*b[2];};
    const auto wlength=[](const WVec& p){return std::hypot(p[0],p[1],p[2]);};
    const auto wfinite=[](const WVec& p){return std::isfinite(p[0])&&std::isfinite(p[1])&&std::isfinite(p[2]);};
    std::array<Wide,9> m{};for(int i=0;i<9;++i)m[i]=view.pose.rotation[i];
    const Wide determinant=m[0]*(m[4]*m[8]-m[5]*m[7])-m[1]*(m[3]*m[8]-m[5]*m[6])+m[2]*(m[3]*m[7]-m[4]*m[6]);
    std::array<Wide,9> inverse{m[4]*m[8]-m[5]*m[7],m[2]*m[7]-m[1]*m[8],m[1]*m[5]-m[2]*m[4],
        m[5]*m[6]-m[3]*m[8],m[0]*m[8]-m[2]*m[6],m[2]*m[3]-m[0]*m[5],
        m[3]*m[7]-m[4]*m[6],m[1]*m[6]-m[0]*m[7],m[0]*m[4]-m[1]*m[3]};
    for(auto& x:inverse)x/=determinant;
    const WVec world{anchor.xyz.x,anchor.xyz.y,anchor.xyz.z},translation{view.pose.translation.x,view.pose.translation.y,view.pose.translation.z},normalWorld{n.x,n.y,n.z};
    WVec p{},cameraNormal{},coordinateMagnitude{};
    for(int i=0;i<3;++i){
        p[i]=translation[i];coordinateMagnitude[i]=std::abs(translation[i]);
        for(int j=0;j<3;++j){p[i]+=m[i*3+j]*world[j];coordinateMagnitude[i]+=std::abs(m[i*3+j]*world[j]);cameraNormal[i]+=inverse[j*3+i]*normalWorld[j];}
    }
    if(!wfinite(p)||p[2]<=0||!wfinite(cameraNormal))return {};
    const Wide epsilon=std::numeric_limits<double>::epsilon(),precision=std::sqrt(epsilon),pixelRadius=.60L*stride;
    // Bound cancellation in the double projection used by the acceptance
    // metric. Numerically unresolved calibration remains unsupported.
    const Wide zError=64*epsilon*coordinateMagnitude[2];
    if(p[2]<=zError)return {};
    for(int axis=0;axis<2;++axis){
        const Wide focal=axis?view.fy:view.fx,principal=axis?view.cy:view.cx;
        const Wide coordinateError=64*epsilon*coordinateMagnitude[axis];
        const Wide error=focal*(coordinateError+std::abs(p[axis]/p[2])*zError)/(p[2]-zError)+64*epsilon*(1+std::abs(focal*p[axis]/p[2])+std::abs(principal));
        if(!std::isfinite(error)||error>precision*pixelRadius)return {};
    }
    Wide maximumCondition=1;
    const auto stableDot=[&](const WVec& a,const WVec& b)->std::optional<Wide>{
        const Wide value=wdot(a,b),magnitude=std::abs(a[0]*b[0])+std::abs(a[1]*b[1])+std::abs(a[2]*b[2]);
        if(!std::isfinite(value)||!std::isfinite(magnitude)||value==0||64*epsilon*magnitude>precision*std::abs(value))return {};
        maximumCondition=std::max(maximumCondition,magnitude/std::abs(value));return value;
    };
    const auto planeValue=stableDot(cameraNormal,p);if(!planeValue)return {};
    const Wide plane=*planeValue;if(std::abs(plane)<=frontRadius)return {};
    Wide bound=0,largestCorner=0;
    for(int dx:{-1,1})for(int dy:{-1,1}){
        const WVec ray{(anchor.x+dx*pixelRadius-view.cx)/view.fx,(anchor.y+dy*pixelRadius-view.cy)/view.fy,1};
        if(!wfinite(ray))return {};
        const auto denominator=stableDot(cameraNormal,ray);
        if(!denominator||(*denominator>0)!=(plane>0))return {};
        for(int side:{-1,1}){
            const Wide numerator=plane+side*static_cast<Wide>(frontRadius),magnitude=std::abs(plane)+frontRadius;
            if(!std::isfinite(numerator)||numerator==0||(numerator>0)!=(plane>0)||64*epsilon*magnitude>precision*std::abs(numerator))return {};
            maximumCondition=std::max(maximumCondition,magnitude/std::abs(numerator));
            const Wide t=numerator/ *denominator;if(!std::isfinite(t)||t<=0)return {};
            const WVec corner{t*ray[0],t*ray[1],t};if(!wfinite(corner))return {};
            WVec delta{};for(int i=0;i<3;++i)for(int j=0;j<3;++j)delta[i]+=inverse[i*3+j]*(corner[j]-p[j]);
            const Wide distance=wlength(delta);if(!wfinite(delta)||!std::isfinite(distance))return {};
            bound=std::max(bound,distance);largestCorner=std::max(largestCorner,wlength(corner));
        }
    }
    Wide inverseNorm=0;for(auto x:inverse)inverseNorm+=x*x;
    // Operation-count and conditioning padding only broadens lookup. It does
    // not alter any original-anchor pixel, normal, visibility or distance gate.
    const Wide rounding=512*epsilon*(1+wlength(p)+largestCorner)*std::max(1.L,std::sqrt(inverseNorm))*(1+maximumCondition);
    if(!std::isfinite(rounding)||!std::isfinite(bound+rounding))return {};
    return std::clamp(std::nextafter(static_cast<double>(std::min(600.L,bound+rounding)),INFINITY),.0001,600.);
}
inline bool originalPairMetric(const DenseView& firstView,const VerifiedDepthSample& first,Vec3 firstNormal,double firstRadius,double firstBound,
                               const DenseView& secondView,const VerifiedDepthSample& second,Vec3 secondNormal,double secondRadius,double secondBound,int stride){
    using namespace metric_detail;
    Vec3 a{},b{};
    if(!unitNormal(firstNormal,a)||!unitNormal(secondNormal,b)||!compatibleNormal(a,b)||
       !std::isfinite(firstRadius)||!std::isfinite(secondRadius)||firstRadius<.0001||secondRadius<.0001||firstRadius>600||secondRadius>600||
       !std::isfinite(firstBound)||!std::isfinite(secondBound)||firstBound<.0001||secondBound<.0001||firstBound>600||secondBound>600)return false;
    const auto delta=subtract(second.xyz,first.xyz);
    if(!finite(delta)||length(delta)>std::min({firstBound,secondBound,600.}))return false;
    const double normalGap=std::max(std::abs(dot(a,delta)),std::abs(dot(b,delta)))/std::min(firstRadius,secondRadius);
    const double firstPixel=pixelError(firstView,first,second.xyz,stride),secondPixel=pixelError(secondView,second,first.xyz,stride);
    if(!std::isfinite(normalGap)||!std::isfinite(firstPixel)||!std::isfinite(secondPixel)||normalGap>1||firstPixel>1||secondPixel>1)return false;
    return normalGap*normalGap+firstPixel*firstPixel<=1&&normalGap*normalGap+secondPixel*secondPixel<=1;
}
inline bool originalAnchorMetric(const DenseView& view,const VerifiedDepthSample& anchor,Vec3 normal,double frontRadius,double bound,Vec3 point,int stride){
    using namespace metric_detail;
    Vec3 n{};if(!unitNormal(normal,n)||!std::isfinite(frontRadius)||frontRadius<.0001||frontRadius>600||!std::isfinite(bound)||bound<.0001||bound>600)return false;
    const auto delta=subtract(point,anchor.xyz);
    if(!finite(delta)||length(delta)>std::min(bound,600.))return false;
    const double gap=std::abs(dot(n,delta))/frontRadius,pixel=pixelError(view,anchor,point,stride);
    return std::isfinite(gap)&&std::isfinite(pixel)&&gap<=1&&pixel<=1&&gap*gap+pixel*pixel<=1;
}
}
