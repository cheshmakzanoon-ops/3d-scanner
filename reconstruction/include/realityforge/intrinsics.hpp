#pragma once
#include "realityforge/core.hpp"
#include <array>
#include <cstdint>
#include <span>
#include <string>

namespace rf {
// Pixel centres are integer coordinates. Positive camera axes: right/down/forward.
// Parameters in order: fx,fy,cx,cy,k1,k2,p1,p2,k3; distortion acts on normalized rays.
inline constexpr std::size_t intrinsicParameterCount = 9;
struct IntrinsicModel {
    std::uint32_t schema = 1;
    std::size_t width{}, height{};
    std::string lensCropIdentity;
    std::array<double,9> parameters{};
    std::array<bool,9> refinable{};
    bool measured = true;
};
struct IntrinsicProjection {
    Vec2 pixel;
    std::array<double,18> parameterJacobian{}; // row-major 2x9
    std::array<double,4> rayJacobian{};       // d(pixel)/d(normalized x,y)
};
struct IntrinsicPrior {
    std::array<double,9> centre{};
    std::array<double,9> sigma{};
    std::array<double,9> lower{}, upper{};
};
enum class IntrinsicObservability { Fixed, InsufficientViews, WeakSpatialExtent, WeakDepthExtent, Observable };
struct IntrinsicEvidence {
    std::size_t independentViews{}, observations{};
    double normalizedRadiusSpan{}, depthRatio{};
};
void validateIntrinsicModel(const IntrinsicModel& model);
IntrinsicProjection projectIntrinsic(const IntrinsicModel& model, Vec2 normalizedRay);
Vec2 unprojectIntrinsic(const IntrinsicModel& model, Vec2 pixel, double pixelTolerance=1e-7);
IntrinsicObservability intrinsicObservability(const IntrinsicModel& model,const IntrinsicEvidence& evidence);
// Solver-owned step admission. Fixed parameters are bit-for-bit preserved; weak groups cannot move.
IntrinsicModel applyIntrinsicStep(const IntrinsicModel& model, const IntrinsicPrior& prior,
    std::span<const double,9> delta, IntrinsicObservability observability);
double intrinsicPriorCost(const IntrinsicModel& model,const IntrinsicPrior& prior);
}
