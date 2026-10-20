#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/photometric.hpp"
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf {
inline constexpr const char* PHOTOGRAPHIC_ATLAS_ENGINE_VERSION="rf-atlas-v2-groups-zlib-radiometry2";
// M09: camera-projection UV charts over real rectified photographs. An atlas
// contains source camera images (not painted procedural colors). All geometry
// remains open, incomplete, unscaled and backed by M08 depth evidence.
struct TextureSettings {
    int gutter = 3;                    // Transparent padded tile boundaries
    double minimumFacing = 0.12;      // Reject back-facing/edge-on photos
    double depthTolerance = 0.075;    // Reject visibility contradictions
    std::size_t maxUvVertices = 240000;
    bool balanceColors = true;
    bool compactCharts = false; // opt-in until device/profile qualification; baseline remains available
    int depthSampleStride = 1, depthSampleOrigin = 0; // declared sparse depth grid
    int atlasEdge = 2048; // 2K CPU admission; 4K requires an explicitly qualified budget
    double maximumUvStretch = .02; // explicit compact profile distortion admission
    double chartTexelsPerUnit = 128.; // arbitrary world units; measured density is returned
    double seamPenalty = .18; // local label smoothing over manifold, similarly oriented faces
};
struct TextureSource {
    int width{},height{};
    double fx{},fy{},cx{},cy{};
    ViewPose pose{};
    std::vector<std::uint8_t> rgba; // actual undistorted photo RGBA
};
struct TextureVertex {
    std::uint32_t surfaceVertex{};
    float u{},v{}; // glTF UV convention: (0,0) is the upper-left of PNG
};
struct TexturedSurface {
    int atlasWidth{},atlasHeight{};
    std::vector<std::uint8_t> atlasRgba;
    std::vector<TextureVertex> vertices; // de-indexed across UV chart seams
    std::vector<std::uint32_t> indices;
    std::vector<std::size_t> sourceTriangleCounts;
    std::size_t inputTriangles{},skippedTriangles{},viewSwitchEdges{};
    double faceCoverage{},medianFacing{};
    std::size_t viewSwitchEdgesBefore{}, reassignedFaces{};
    ExposureBalance exposure;
    std::size_t supportedTexels{},unsupportedTexels{},blendedTexels{},chartCount{};
    double texelsPerUnit{},atlasUtilization{},meanBlendResidual{},maximumUvStretch{};
    bool compactCharts{};
};
TexturedSurface bakePhotographicAtlas(const SurfaceMesh& mesh,
    std::span<const DenseView> depthViews,std::span<const DenseDepthMap> depthMaps,
    std::span<const TextureSource> sources,const TextureSettings& settings = {});
std::string texturedSurfaceJson(const TexturedSurface& baked);
void writeTexturePng(const TexturedSurface& baked,const std::string& path);
void writeTexturedGlb(const SurfaceMesh& mesh,const TexturedSurface& baked,const std::string& path);
}
// M09 C ABI: ONE dense/mesh reconstruction; high-resolution rectified photo
// buffer separately supplies real source texture pixels. N x (width*height*4)
// and N x (texture_width*texture_height*4), N camera rows x 16. The engine
// validates all counts before use. Output paths are nullable. JSON malloc-owned.
extern "C" char* rf_textured_surface_json(
    const std::uint8_t* depth_rgba,int width,int height,
    const std::uint8_t* texture_rgba,int texture_width,int texture_height,
    const double* camera_rows,std::size_t camera_count,
    double near_depth,double far_depth,int depth_planes,int pixel_stride,
    const char* mesh_glb_path,const char* mesh_ply_path,
    const char* textured_glb_path,const char* texture_png_path) noexcept;

// Explicit 2K CPU compact profile, with the same checked buffers and malloc/rf_free ownership.
extern "C" char* rf_textured_surface_compact_json(const std::uint8_t* depth_rgba,int width,int height,
    const std::uint8_t* texture_rgba,int texture_width,int texture_height,
    const double* camera_rows,std::size_t camera_count,
    double near_depth,double far_depth,int depth_planes,int pixel_stride,
    const char* mesh_glb_path,const char* mesh_ply_path,
    const char* textured_glb_path,const char* texture_png_path) noexcept;
