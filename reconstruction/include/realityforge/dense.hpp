#pragma once
#include "realityforge/multiview.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
/**
 * Rectified, calibrated RGB + validity pixels, row major. Pose maps SfM world
 * to camera. This is a CPU REFERENCE for photometric multi-view stereo, NOT a mesh.
 * All depth values are in the SfM anchor's arbitrary baseline units.
 */
struct DenseView {
    int width{}, height{};
    double fx{}, fy{}, cx{}, cy{};
    ViewPose pose{};
    std::vector<std::uint8_t> rgba; // R,G,B,valid(0|255); 4 bytes per pixel
};
struct DenseSettings {
    double nearDepth = 2.0, farDepth = 9.0;
    int depthPlanes = 56;
    int pixelStride = 3;
    int patchRadius = 2;
    double minimumNcc = 0.72;
    double minUniqueness = 0.025;
    double depthConsistencyFraction = 0.055;
    std::size_t maxPoints = 20000;
};
struct DensePoint {
    Vec3 xyz{};
    std::array<std::uint8_t,3> color{};
    float confidence{};
    std::uint8_t consistentViews{};
};
struct DenseDepthMap {
    int width{}, height{};
    std::vector<float> depth;      // 0 for invalid; camera-z (not Euclidean range)
    std::vector<float> confidence; // [0,1]
};
// A pixel supported by reciprocal camera-z depth checks (not merely ZNCC).
// Preserves its source pixel so M08 can triangulate only observed neighbor cells.
struct VerifiedDepthSample {
    Vec3 xyz{};
    std::array<std::uint8_t,3> color{};
    float confidence{};
    std::uint8_t supportingViews{};
    std::uint8_t camera{};
    std::uint16_t x{}, y{};
};
struct DenseResult {
    std::vector<DenseDepthMap> maps;
    std::vector<DensePoint> points;
    std::vector<VerifiedDepthSample> verifiedSamples; // bounded, transient; omitted from M07 JSON
    std::size_t sampledPixels{}, photometricPixels{}, consistentPixels{};
    double medianConfidence{};
};
// Never invents unobserved geometry. Patches assume locally fronto-parallel
// surfaces and ZNCC; it rejects textureless/ambiguous/occluded observations.
DenseResult reconstructDense(std::span<const DenseView> views, const DenseSettings& settings = {});
std::string denseJson(const DenseResult& result);
}

// C ABI: one 16-double row per view: 9 R, 3 t, fx, fy, cx, cy.
// Interleaved RGBA (0/255 validity) across equally sized undistorted views.
// malloc-owned JSON, release with rf_free.
extern "C" char* rf_dense_json(const std::uint8_t* rgba, int width, int height,
    const double* camera_rows, std::size_t camera_count,
    double near_depth, double far_depth, int depth_planes, int pixel_stride);
