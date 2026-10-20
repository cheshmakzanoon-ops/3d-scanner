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
    const double la=std::hypot(a.x,a.y,a.z),lb=std::hypot(b.x,b.y,b.z);
    return std::isfinite(la)&&std::isfinite(lb)&&la>.5&&lb>.5&&
        (a.x*b.x+a.y*b.y+a.z*b.z)/(la*lb)>=.8660254037844386;
}
}
