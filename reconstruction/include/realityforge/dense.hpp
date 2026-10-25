#pragma once
#include "realityforge/multiview.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
inline constexpr std::size_t MAX_DENSE_VIEWS = 8;
inline constexpr std::size_t MAX_DENSE_SAMPLES = 100000;
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
    unsigned executionVersion = 1;
    int tileEdge = 64;
    std::size_t workingSetBudgetBytes = 64u*1024u*1024u;
    bool referenceMode = false; // untiled cached-luminance numerical reference
    bool discontinuityAwareConsistency = false; // qualified separately before default enable
    unsigned localRefinementIterations = 0; // opt-in measured quality path
    unsigned normalRefinementIterations = 0; // <=3, <=6 observed plane hypotheses
    bool normalConsistency = false; // independent calibrated neighborhoods
    double normalMaxAngleDegrees = 35.;
    bool robustSourceAggregation = false;
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
struct DenseSparseDepthPrior {
    std::uint32_t camera{};double x{},y{},cameraZ{},relativeUncertainty{};bool visible{true};
};
struct DenseRefinementInputs {
    std::span<const DenseSparseDepthPrior> sparsePriors;
    std::span<const DenseView> coarseViews; // actual lower-level rectified photos, same camera order
    std::string identitySha256; // canonical identity of exact admitted observations/levels
};
// A synchronous borrowed immutable lease around existing surface/texture entry
// points. Explicit scope admits cpu-refined-v3:6 local iterations,3 normal-depth
// iterations, strict depth and independent normal checks. No scope means baseline behavior; nested
// null scope disables inheritance. Inputs stay immutable through synchronous call.
class DenseRefinementScope final {
public:
    explicit DenseRefinementScope(const DenseRefinementInputs* inputs) noexcept;
    ~DenseRefinementScope() noexcept;
    DenseRefinementScope(const DenseRefinementScope&)=delete;
    DenseRefinementScope& operator=(const DenseRefinementScope&)=delete;
private:
    const DenseRefinementInputs* previous_{};
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
    Vec3 worldNormal{}; // unoriented; zero when no independent neighborhood evidence
    float normalConfidence{};
    std::uint8_t normalSupportingViews{};
};
// Diagnostic coverage only; never serialized into depth/surface checkpoint wires.
// Defaults unknown for recovered results; acceptedSamples counts fresh evaluation only.
struct DensePhotometricDiagnostics {
    bool available{};
    std::size_t evaluatedSamples{}, acceptedSamples{};
    std::size_t lowVariance{}, invalidOrBoundaryBest{}, ncc{}, uniqueness{}, invalidInverseDepth{};
};
// Originating-call branch evidence, never depth/surface checkpoint data. Fixed
// arrays bound storage independently of image size; cached photometric work is
// unknown while reciprocal checks are measured again in the current call.
struct DenseViewLossDiagnostics {
    std::size_t sampled{}, evaluated{}, photometricAccepted{};
    std::size_t invalidReferencePatch{}, validLowVariance{}, invalidOrBoundaryBest{}, ncc{}, uniqueness{}, invalidInverseDepth{};
    // Nonspatial 16-level patch-mean bins; not a foreground/object classification.
    // Recorded only from the existing valid reference.mean, without pixel traversal.
    std::array<std::size_t,16> validReferenceMeanHistogram{}, validLowVarianceMeanHistogram{};
    std::size_t reciprocalEvaluated{}, invalidWorld{}, noConfirmation{}, reciprocalAccepted{};
    std::size_t sourcePairs{}, invalidSourceProjection{}, sourceDepthUnavailable{}, depthMismatch{};
    // Exhaustive subreasons for sourceDepthUnavailable. The first three apply
    // to nearest-grid compatibility sampling; strict interpolation has its own
    // unsupported class without pretending its missing/discontinuous fit is a point.
    std::size_t sourceOutsideImage{}, sourceGridRejected{}, sourceMissingDepth{}, sourceInterpolationUnsupported{};
    std::size_t invalidReverseProjection{}, reverseReprojection{}, normalMismatch{}, confirmedPairs{};
    std::array<std::size_t,MAX_DENSE_VIEWS> confirmationHistogram{};
};
struct DenseLossDiagnostics {
    bool available{};
    std::size_t viewCount{};
    std::array<DenseViewLossDiagnostics,MAX_DENSE_VIEWS> views{};
};
// Fits in the existing 64 KiB fixed scratch allowance; no admission budget change.
static_assert(sizeof(DenseLossDiagnostics)<=8192);
struct DenseResult {
    DensePhotometricDiagnostics photometricDiagnostics;
    DenseLossDiagnostics lossDiagnostics;
    std::vector<DenseDepthMap> maps;
    std::vector<DensePoint> points;
    std::vector<VerifiedDepthSample> verifiedSamples; // bounded, transient; omitted from M07 JSON
    std::size_t sampledPixels{}, photometricPixels{}, consistentPixels{};
    double medianConfidence{};
    std::vector<std::vector<std::size_t>> sourceViews; // <=3 source photos per reference
    std::vector<std::size_t> consistentPixelsPerView;
    std::size_t peakLuminanceBytes{}, pointBudget{};
    std::size_t resumedDepthMaps{}, writtenDepthMaps{};
    std::size_t completedTiles{}, totalTiles{}, admittedWorkingSetBytes{};
    std::size_t resumedDepthTiles{},recomputedDepthTiles{},writtenDepthTiles{},rejectedDepthTiles{};
    std::size_t coarseDepthMaps{},propagatedHypotheses{},sparsePriorTiles{};
    std::size_t normalHypothesesEvaluated{},normalRefinedPixels{};
    std::size_t normalSupportPairs{},normalRejectedPairs{},normalUnknownPairs{};
};
// Never invents unobserved geometry. Patches assume locally fronto-parallel
// surfaces and ZNCC; it rejects textureless/ambiguous/occluded observations.
DenseResult reconstructDense(std::span<const DenseView> views, const DenseSettings& settings = {}, const DenseRefinementInputs* inputs = nullptr);
std::string denseJson(const DenseResult& result);
}

// C ABI: one 16-double row per view: 9 R, 3 t, fx, fy, cx, cy.
// Interleaved RGBA (0/255 validity) across equally sized undistorted views.
// malloc-owned JSON, release with rf_free.
extern "C" char* rf_dense_json(const std::uint8_t* rgba, int width, int height,
    const double* camera_rows, std::size_t camera_count,
    double near_depth, double far_depth, int depth_planes, int pixel_stride) noexcept;

// Explicit opt-in refined POINTS path: same16-double camera rows and RGBA layout.
// prior_rows:6doubles(cameraIndex,x,y,cameraZ,relativeUncertainty,visible0|1), <=20000.
// Coarse buffers may all be absent (null,0,0,null); otherwise same camera count.
// identity is a valid NUL-terminated64-lowerhex SHA256 of every admitted input.
// Opt-in cpu-refined-v3:6 local/3 normal-depth iterations, strict depth/normal checks.
// Inputs are borrowed immutable for call duration; returned JSON is malloc/rf_free.
extern "C" char* rf_dense_refined_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,double near_depth,double far_depth,
    int depth_planes,int pixel_stride,const double* prior_rows,std::size_t prior_count,
    const std::uint8_t* coarse_rgba,int coarse_width,int coarse_height,
    const double* coarse_camera_rows,const char* identity) noexcept;
