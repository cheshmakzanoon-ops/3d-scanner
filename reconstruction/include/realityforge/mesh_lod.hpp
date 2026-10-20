#pragma once
#include "realityforge/asset_model.hpp"
#include <vector>

namespace rf::asset {
// Owned derived geometry. Its spans are views only and expire with this owner.
struct LodGeometry {
    std::vector<Vec3> positions, normals;
    std::vector<std::array<std::uint8_t,3>> colors;
    std::vector<float> confidence;
    std::vector<std::uint32_t> supportingViews, indices;
    std::vector<Vec2> uv;
    Units units = Units::Arbitrary;
    Representation representation = Representation::ObservedSurface;
    Provenance provenance;
    SurfaceAsset surface() const;
};
struct LodRecipe {
    std::size_t targetTriangles{};
    double maximumDisplacement{}; // Same units as input; zero permits unchanged geometry only.
    double maximumUvDisplacement{}; // Normalized atlas units; zero prevents UV movement.
    double minimumNormalCosine = 0.98;
    std::size_t maximumVertices = 100'000;
    std::size_t maximumTriangles = 200'000;
    std::size_t maximumWork = 20'000'000;
};
struct LodReport {
    std::size_t inputTriangles{}, outputTriangles{}, collapsedEdges{}, lockedVertices{}, work{};
    double maximumVertexDisplacement{}, maximumUvDisplacement{};
    std::string parentAssetId, sourceSha256;
    static constexpr const char* algorithmVersion = "rf-constrained-endpoint-lod-v2";
};
struct LodResult { LodGeometry geometry; LodReport report; };
// Never modifies input. Refuses unmet targets, nonmanifold topology, degenerate
// input and exhausted work budgets; no partially reduced geometry is published.
// UV duplicate-position vertices are locked, preserving photographic chart seams.
LodResult simplifySurface(const SurfaceAsset&,const LodRecipe&,
                          std::span<const Vec2> uv = {},const Cancel& cancel = {});
}
