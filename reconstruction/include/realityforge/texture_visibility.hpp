#pragma once
#include "realityforge/texture.hpp"
#include <cmath>
namespace rf {
struct TextureProjection {double u{},v{},z{};};
// A texel is supported only by its own depth sample and four valid photograph
// interpolation pixels. Missing depth and foreground occluders are never filled
// by searching farther neighboring pixels for a convenient matching depth.
inline bool projectVisibleTexel(const TextureSource& photo,const DenseView& view,
    const DenseDepthMap& map,Vec3 world,double tolerance,TextureProjection& out){
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
    const int dx=static_cast<int>(std::lround((out.u+.5)*view.width/photo.width-.5)),dy=static_cast<int>(std::lround((out.v+.5)*view.height/photo.height-.5));
    if(dx<0||dy<0||dx>=view.width||dy>=view.height)return false;
    const auto d=map.depth[static_cast<std::size_t>(dy)*view.width+dx];
    return std::isfinite(d)&&d>0&&std::abs(d-out.z)/std::max(static_cast<double>(d),out.z)<=tolerance;
}
}
