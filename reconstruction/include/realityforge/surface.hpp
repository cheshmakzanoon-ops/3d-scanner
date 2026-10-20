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
struct SurfaceMesh {
    std::vector<SurfaceVertex> vertices;
    std::vector<SurfaceTriangle> triangles;
    std::size_t inputSamples{},acceptedSamples{},rejectedCells{},rejectedTriangles{},fusedObservations{};
    float medianConfidence{};
};
// Only verified samples (reciprocal depth, >=2 supporting cameras) can
// contribute vertices. Faces require 4 neighboring verified source samples.
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
