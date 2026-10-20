#pragma once
#include "realityforge/checkpoint_digest.hpp"
#include "realityforge/surface.hpp"

namespace rf::surface_checkpoint {
inline constexpr char FILENAME[]="surface-v1.rfmesh";
inline constexpr std::size_t MAX_FILE_BYTES=12*1024*1024;

struct AcceptedSurface {
    SurfaceMesh mesh;
    DenseResult dense;
    SurfaceSettings settings;
};

/** Exact rectified dimensions, camera geometry and source pixels; texture settings are excluded. */
checkpoint::Digest geometryIdentity(std::span<const DenseView> views);
checkpoint::Digest algorithmIdentity(const SurfaceSettings& settings);

/**
 * Store only real accepted geometry under the active depth scope. Returns false when caching is
 * unavailable, another different checkpoint already owns the path, or depth publication failed.
 * The final file is atomically created, checksummed, bounded and never overwrites accepted bytes.
 */
bool store(std::span<const DenseView> views,const DenseResult& dense,const SurfaceMesh& mesh,
    const SurfaceSettings& settings,bool* created = nullptr);

/**
 * Read a full accepted mesh and its matching original depth maps. A miss does not run any solver.
 * Requires exact canonical filename inside the active SHA-named depth directory. Wrong identity,
 * checksum, dimensions, camera geometry, algorithm, counts, indices or depth files fail closed.
 */
bool load(const std::string& checkpointPath,std::span<const DenseView> views,AcceptedSurface& output);
} // namespace rf::surface_checkpoint

// Texture-only C ABI. Caller supplies bounded packed buffers, as for rf_textured_surface_json.
// Preserves accepted mesh/depth bytes; only texture output paths are writable. No depth/fusion call.
extern "C" char* rf_texture_accepted_surface_json(
    const std::uint8_t* depth_rgba,int width,int height,
    const std::uint8_t* photograph_rgba,int photograph_width,int photograph_height,
    const double* camera_rows,std::size_t camera_count,
    const char* mesh_checkpoint_path,const char* textured_glb_path,const char* texture_png_path,
    const char* depth_directory,const char* depth_identity) noexcept;
