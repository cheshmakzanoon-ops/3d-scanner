#pragma once
#include "realityforge/multiview.hpp"
#include "realityforge/intrinsics.hpp"
#include "realityforge/loop_closure.hpp"
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
// Global, coupled reprojection minimization for ALREADY REGISTERED calibrated cameras.
// Legacy mode fixes two poses. Minimal mode fixes pose 0 and anchor baseline length only.
// This small-memory Schur solver only refines extrinsics and existing sparse points.
// It does not estimate camera intrinsics, dense depths, triangles or absolute scale.
struct BundleSeed {
    std::size_t track{};
    Vec3 xyz{};
    std::array<std::uint8_t,3> color{};
};
enum class BundleGauge { TwoAnchors, PoseAndBaseline };
struct BundleSettings {
    double maxReprojectionError = .003; // normalized camera coordinates; robust Huber threshold
    int maxIterations = 12;             // 0..25, configurable for host regression
    BundleGauge gauge = BundleGauge::TwoAnchors;
    std::size_t maxWorkingBytes = 64 * 1024 * 1024;
};
struct BundlePoint {
    BundleSeed seed;
    std::size_t supportingViews{};
    double medianErrorNormalized{}, maxParallaxDegrees{};
};
struct BundleResult {
    std::vector<ViewPose> cameras;
    std::vector<BundlePoint> points;
    std::size_t inputPoints{}, inputObservations{}, usableObservations{}, rejectedObservations{};
    std::size_t acceptedSteps{}, attemptedIterations{};
    double robustCostBefore{}, robustCostAfter{};
    double medianErrorBefore{}, medianErrorAfter{};
    BundleGauge gauge = BundleGauge::TwoAnchors;
    std::size_t estimatedWorkingBytes{};
    std::string termination;
    std::vector<double> medianCameraResiduals;
    std::vector<std::size_t> cameraObservationCounts;
};
BundleResult bundleAdjust(std::span<const ViewPose> cameras,
    std::span<const BundleSeed> seeds,
    std::span<const TrackObservation> observations,
    const BundleSettings& settings = {});
std::array<double,12> bundleCameraJacobian(Vec3 cameraPoint); // row-major 2x6 left-SE3 update
struct IntrinsicMeasurement { Vec3 cameraPoint; Vec2 pixel; };
struct IntrinsicRefinement {
    IntrinsicModel model;
    double normalizedCostBefore{}, normalizedCostAfter{};
    std::size_t acceptedSteps{};
    IntrinsicObservability observability=IntrinsicObservability::Fixed;
};
IntrinsicRefinement refineIntrinsicGroup(const IntrinsicModel&,const IntrinsicPrior&,
    const IntrinsicEvidence&,std::span<const IntrinsicMeasurement>,int iterations=12);
struct BundlePixelObservation { std::size_t track{},camera{}; Vec2 pixel; std::array<std::uint8_t,3> color{}; };
struct BundleIntrinsicGroup { IntrinsicModel model; IntrinsicPrior prior; IntrinsicEvidence evidence; };
struct BundleProblem {
    std::uint32_t schema=1;
    std::vector<ViewPose> cameras;
    std::vector<BundleSeed> seeds;
    std::vector<BundlePixelObservation> observations;
    std::vector<BundleIntrinsicGroup> intrinsicGroups;
    std::vector<std::size_t> cameraIntrinsicGroups;
    std::vector<VerifiedLoopConstraint> loops;
};
struct BundleProblemResult {
    BundleResult geometry;
    std::vector<IntrinsicModel> intrinsics;
    std::size_t acceptedLoopCorrections{}, acceptedIntrinsicGroups{};
};
BundleProblemResult bundleAdjustProblem(const BundleProblem&,const BundleSettings& settings={});
std::string bundleJson(const BundleResult& result);
}

// C interface shared by Python regression tests and Android JNI.
// cameras: 12 doubles [rotation row-major (9), translation (3)]
// points: 7 doubles [trackId, X,Y,Z, R,G,B]
// observations: 7 doubles [trackId, cameraIndex, u,v, R,G,B]
// Returned malloc-owned UTF-8 JSON must be released using rf_free.
extern "C" char* rf_bundle_adjust_json(const double* cameras, std::size_t camera_count,
    const double* points, std::size_t point_count,
    const double* observations, std::size_t observation_count,
    double threshold, int iterations) noexcept;
