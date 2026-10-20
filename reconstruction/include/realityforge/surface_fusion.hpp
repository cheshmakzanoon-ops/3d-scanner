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
