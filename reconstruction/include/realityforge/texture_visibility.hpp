#pragma once
#include "realityforge/texture.hpp"
#include "realityforge/depth_consistency.hpp"
#include <cmath>
namespace rf {
struct TextureProjection {double u{},v{},z{};};
// A texel is supported only by its own depth sample and four valid photograph
// interpolation pixels. Missing depth and foreground occluders are never filled
// by searching farther neighboring pixels for a convenient matching depth.
inline bool projectVisibleTexel(const TextureSource& photo,const DenseView& view,
    const DenseDepthMap& map,Vec3 world,double tolerance,TextureProjection& out,int depthStep=1,int depthOrigin=0){
    if(photo.width<2||photo.height<2||view.width<1||view.height<1||map.width!=view.width||map.height!=view.height||
       map.depth.size()!=static_cast<std::size_t>(view.width)*view.height||
       photo.rgba.size()!=static_cast<std::size_t>(photo.width)*photo.height*4||!std::isfinite(tolerance)||tolerance<0||tolerance>.2)
        throw Error("Invalid photographic visibility input");
    const auto c=multiply(photo.pose.rotation,world);const Vec3 p{c.x+photo.pose.translation.x,c.y+photo.pose.translation.y,c.z+photo.pose.translation.z};
    if(!std::isfinite(p.x)||!std::isfinite(p.y)||!std::isfinite(p.z)||p.z<=.01)return false;
    out={p.x/p.z*photo.fx+photo.cx,p.y/p.z*photo.fy+photo.cy,p.z};
    if(!std::isfinite(out.u)||!std::isfinite(out.v)||out.u<0||out.v<0||out.u>=photo.width-1||out.v>=photo.height-1)return false;
    const int x=static_cast<int>(std::floor(out.u)),y=static_cast<int>(std::floor(out.v));
    for(int j=0;j<2;++j)for(int i=0;i<2;++i)if(photo.rgba[(static_cast<std::size_t>(y+j)*photo.width+x+i)*4+3]<250)return false;
    const double dx=(out.u+.5)*view.width/photo.width-.5,dy=(out.v+.5)*view.height/photo.height-.5;
    const auto sample=consistency::sampleDepth(map,dx,dy,depthStep,depthOrigin,.055);
    return sample.confidence>=.12&&consistency::verify(sample,out.z,0.,tolerance,.75).classification==consistency::Visibility::Supported;
}
}
