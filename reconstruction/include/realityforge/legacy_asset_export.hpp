#pragma once
#include "realityforge/asset_model.hpp"
#include <iosfwd>
#include <vector>
namespace rf::asset {
// Compatibility reader only for the exact M19 observed, arbitrary-scale ASCII
// surface writer. Never accepts arbitrary PLY schemas or photographic claims.
struct LegacySurface {
    std::vector<Vec3> positions, normals;
    std::vector<std::array<std::uint8_t,3>> colors;
    std::vector<std::uint32_t> indices;
    SurfaceAsset view(const std::string& sourceSha256) const;
};
LegacySurface readLegacySurface(std::istream&, const Cancel& = {});
// Format 0: untextured OBJ; 1: binary STL, numeric size preserved, no metric claim.
void exportLegacySurface(std::istream&, std::ostream&, int format,
                         const std::string& sourceSha256, const Cancel& = {});
}
