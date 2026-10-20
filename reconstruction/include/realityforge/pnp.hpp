#pragma once
#include "realityforge/core.hpp"
#include <cstddef>
#include <span>
#include <vector>

namespace rf {
// Calibrated, undistorted normalized pixel observations of ALREADY triangulated points.
// World XYZ are in the anchor pair's arbitrary, shared coordinate system.
struct Observation { Vec3 world; Vec2 image; };
struct CameraPose {
    Mat3 rotation{}; // world to new camera, row major
    Vec3 translation{}; // same arbitrary units as anchor cloud
    std::vector<std::size_t> inliers; // indexes into the input observations
    std::size_t inputCount{};
    double medianError{}; // Euclidean reprojection error, normalized camera units
};
CameraPose registerCamera(std::span<const Observation> observations,
    double reprojectionThreshold = 0.003, std::uint32_t seed = 20261009);
std::string cameraJson(const CameraPose& pose);
}
// rows N x 5 = X,Y,Z,normalized_u,normalized_v; malloc-owned UTF-8 JSON.
extern "C" char* rf_register_camera_json(const double* rows, std::size_t count, double threshold) noexcept;
