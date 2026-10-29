#pragma once
#include "realityforge/dense.hpp"
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
// M08 conservative open-surface meshing. This is NOT Poisson/TSDF, no watertight
// guarantee, no inferred backside, and no photographic UV texture atlas.
struct SurfaceSettings {
    float minimumConfidence = 0.12f;
    double maximumRelativeDepthJump = 0.08;
    double maximumEdgePixelFactor = 3.2;
    std::size_t maxVertices = 40000;
    std::size_t maxTriangles = 80000;
    int pixelStride = 3;
    std::size_t maxVoxelOccupancy = 64, maxFusionCandidates = 512;
};
struct SurfaceVertex {
    Vec3 xyz{};
    Vec3 normal{};
    std::array<std::uint8_t,3> color{};
    float confidence{};
    std::uint8_t supportingViews{};
};
struct SurfaceTriangle {
    std::uint32_t a{},b{},c{};
    std::uint8_t sourceCamera{}; // original depth map that supported the face
};
// Accumulator refs produced existing attributes. ExactDuplicateFace refs preserve
// additional observed support only; they do not retroactively change fusion weights.
enum class SurfaceObservationRelation : std::uint8_t { Accumulator=0, ExactDuplicateFace=1 };
enum class SurfaceUnattachedReason : std::uint8_t { NoRetainedFace=0, AmbiguousExactDuplicate=1 };
struct SurfaceObservationRef {
    std::uint32_t vertex{};std::uint8_t camera{};std::uint16_t x{},y{};std::size_t sourceSampleId{};
    SurfaceObservationRelation relation=SurfaceObservationRelation::Accumulator;
};
struct SurfaceUnattachedObservation {
    std::uint8_t camera{};std::uint16_t x{},y{};std::size_t sourceSampleId{};
    SurfaceUnattachedReason reason=SurfaceUnattachedReason::NoRetainedFace;
};
struct SurfaceQualityDiagnostics {
    std::size_t boundaryEdges{},components{},nonmanifoldEdges{},intersections{},ambiguousPairs{},removedExactDefects{};
    bool intersectionBudgetExceeded{};
};
// Observational, originating-call evidence only. Not part of surface checkpoint wires.
// Fixed per-view storage: no pixel coordinates, images, identities or spatial maps.
enum class SurfaceFusionGate : std::size_t {
    SameCamera, MissingNormal, NormalMismatch, NotCloser, OutsideRadius,
    CurrentVisibility, ProposedAnchorMetric, ProposedVisibility, PairMetric,
    AnchorSeesSample, SampleSeesAnchor, MemberAnchorMetric, MemberSeesProposed,
    Accepted, Count
};
enum class SurfaceTriangleRejection : std::size_t {
    MissingOrRepeatedVertex, DepthJump, EdgeLength, Area, DuplicateFace, Count
};
struct SurfaceLossView {
    std::size_t inputSamples{}, confidenceRejected{}, vertexLimitRejected{}, acceptedSamples{};
    // NormalValidity order: Supported, SparseSupport, Discontinuity, IllConditioned, Invalid.
    std::array<std::size_t,5> normalValidity{};
    // Mutually exclusive witness classes, measured only after an unsuccessful normal fit.
    std::size_t missingDepthNeighborhoods{}, depthJumpNeighborhoods{}, zeroConfidenceNeighborhoods{};
    std::size_t outsideDepthNeighborhoods{}, unsupportedNormalBounds{}, fusionCandidates{};
    // Missing-normal attribution. The gate previously could not distinguish an
    // unsupported source normal from an unsupported accumulator normal; these
    // counters are additive and sum to the MissingNormal first-gate count.
    std::uint32_t missingSourceNormal{}, missingAccumulatorNormal{}, missingBothNormals{};
    std::array<std::size_t,static_cast<std::size_t>(SurfaceFusionGate::Count)> fusionCandidateGates{};
    std::array<std::size_t,5> cellCornerCounts{};
    std::array<std::size_t,static_cast<std::size_t>(SurfaceTriangleRejection::Count)> triangleRejections{};
    std::size_t acceptedTriangleAttempts{}, attachedObservations{}, unattachedObservations{};
};
struct SurfaceLossDiagnostics {
    bool available{}; // false for default/decoded old checkpoints; absence is NOT measured zero
    std::size_t viewCount{}, removedExactFaces{};
    std::size_t topologyAdmissionPasses{},proposedWeldGroups{},rolledBackWeldGroups{},restoredSourceVertices{},unresolvedSourceVertexLinks{};
    bool triangleLimitReached{}; // visited-cell counters may be partial when true
    std::array<SurfaceLossView,MAX_DENSE_VIEWS> views{};
};
static_assert(sizeof(SurfaceLossDiagnostics)<=4096);
// Fixed diagnostics and conservative simultaneous value copies stay below half
// of the existing 64KiB dense scratch reserve; no allocation budget is raised.
static_assert(3*(sizeof(DenseLossDiagnostics)+sizeof(SurfaceLossDiagnostics))<=32*1024);
struct SurfaceMesh {
    std::vector<SurfaceVertex> vertices;
    std::vector<SurfaceTriangle> triangles;
    // Appended topology-only vertices directly alias the canonical prefix.
    // Aliases are not observations and never increase confidence or view support.
    std::vector<std::uint32_t> topologyAliasSources;
    std::size_t preAliasInvalidVertexLinks{},postAliasInvalidVertexLinks{},unsupportedAliasStars{};
    std::size_t inputSamples{},acceptedSamples{},rejectedCells{},rejectedTriangles{},fusedObservations{};
    float medianConfidence{};
    std::vector<SurfaceObservationRef> observations;
    std::vector<SurfaceUnattachedObservation> unattachedObservations;
    SurfaceQualityDiagnostics topology;
    SurfaceLossDiagnostics lossDiagnostics;
};
// Only verified samples (reciprocal depth, >=2 supporting cameras) can
// contribute vertices. Faces require 3 neighboring verified source samples within one grid cell.
// A fourth verified corner chooses the split with more geometrically admissible
// faces, retaining the shortest/canonical diagonal policy for equal support.
SurfaceMesh fuseDepthSurfaces(std::span<const DenseView> views,const DenseResult& dense,
                              const SurfaceSettings& settings = {});
std::string surfaceJson(const SurfaceMesh& mesh);
void writeSurfaceGlb(const SurfaceMesh& mesh,const std::string& filename);
void writeSurfacePly(const SurfaceMesh& mesh,const std::string& filename);
}
// Same calibrated, rectified bounded buffers as rf_dense_json. Produces ONE
// CPU depth reconstruction, then tries conservative meshing and optional
// exports. On mesh failure, retains dense JSON and returns a meshError.
// malloc-owned JSON, release using rf_free. Output paths may be nullptr.
extern "C" char* rf_surface_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,
    double near_depth,double far_depth,int depth_planes,int pixel_stride,
    const char* glb_path,const char* ply_path) noexcept;
