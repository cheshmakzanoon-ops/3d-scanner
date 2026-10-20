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
inline bool compatibleNormal(Vec3 a,Vec3 b){
    // Accumulators carry confidence/support-weighted normals. Their magnitude
    // is not angular evidence: any finite positive scale preserves direction.
    // Scale before hypot so both subnormal and DBL_MAX inputs remain usable.
    const auto unit=[](Vec3 n,Vec3& result){
        if(!std::isfinite(n.x)||!std::isfinite(n.y)||!std::isfinite(n.z))return false;
        const double scale=std::max({std::abs(n.x),std::abs(n.y),std::abs(n.z)});
        if(scale==0)return false;
        n={n.x/scale,n.y/scale,n.z/scale};
        const double length=std::hypot(n.x,n.y,n.z);
        result={n.x/length,n.y/length,n.z/length};return true;
    };
    Vec3 first{},second{};
    return unit(a,first)&&unit(b,second)&&
        first.x*second.x+first.y*second.y+first.z*second.z>=.8660254037844386;
}
}
