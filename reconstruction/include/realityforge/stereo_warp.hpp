#pragma once
#include "realityforge/dense.hpp"

namespace rf::detail {
// K_source * (R_source * R_reference^T) * inv(K_reference), plus
// K_source * (t_source - R_relative * t_reference). No scene approximation.
struct StereoWarp {
    Mat3 h{};
    Vec3 offset{};
    static StereoWarp between(const DenseView& reference, const DenseView& source) {
        const auto r=multiply(source.pose.rotation,transpose(reference.pose.rotation));
        const auto rt=multiply(r,reference.pose.translation);
        const Vec3 t{source.pose.translation.x-rt.x,source.pose.translation.y-rt.y,
                     source.pose.translation.z-rt.z};
        const Mat3 inverseK{1/reference.fx,0,-reference.cx/reference.fx,
                           0,1/reference.fy,-reference.cy/reference.fy,0,0,1};
        const Mat3 sourceK{source.fx,0,source.cx,0,source.fy,source.cy,0,0,1};
        return {multiply(multiply(sourceK,r),inverseK),multiply(sourceK,t)};
    }
    // Homogeneous source pixel coordinates divided by reference camera-z.
    // Source camera-z is returned as q.z * referenceDepth.
    Vec3 homogeneous(double x, double y, double inverseDepth) const {
        return {h[0]*x+h[1]*y+h[2]+offset.x*inverseDepth,
                h[3]*x+h[4]*y+h[5]+offset.y*inverseDepth,
                h[6]*x+h[7]*y+h[8]+offset.z*inverseDepth};
    }
};
}
