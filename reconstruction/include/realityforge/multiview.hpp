#pragma once
#include "realityforge/core.hpp"
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
// Extrinsics map common anchor-world coordinates to normalized-camera coordinates.
// Cameras zero and one MUST be the noncoincident anchor pair; they are fixed for gauge.
struct ViewPose { Mat3 rotation{}; Vec3 translation{}; };
struct TrackObservation {
    std::size_t track{}, camera{};
    Vec2 image{}; // calibrated, undistorted, normalized image coordinates
    std::array<std::uint8_t,3> color{};
};
struct MultiViewSettings {
    double maxReprojectionError = 0.003;
    double minParallaxDegrees = 0.8;
    int iterations = 8; // bounded robust alternating sparse point/pose optimization
};
struct SparseTrackPoint {
    Point point;
    std::size_t track{}, supportingViews{};
};
struct MultiViewResult {
    std::vector<ViewPose> cameras;
    std::vector<SparseTrackPoint> tracks;
    std::size_t inputTracks{}, inputObservations{}, rejectedTracks{};
    double medianErrorBefore{}, medianErrorAfter{};
    std::size_t iterations{};
};
// Does NOT reconstruct dense depth/triangles or refine focal length/distortion.
MultiViewResult reconstructMultiView(std::span<const ViewPose> cameras,
    std::span<const TrackObservation> observations, const MultiViewSettings& settings = {});
std::string multiViewJson(const MultiViewResult& result);
}
// Camera array: 12 doubles per camera [9 rotation,row-major;3 translation].
// Observation array: 7 doubles [trackID,cameraIndex,u,v,r,g,b].
// Returns malloc-owned JSON and must be freed with rf_free. IDs must be exact integers.
extern "C" char* rf_multiview_json(const double* cameras, std::size_t camera_count,
    const double* observations, std::size_t observation_count,
    double max_error, int iterations);
