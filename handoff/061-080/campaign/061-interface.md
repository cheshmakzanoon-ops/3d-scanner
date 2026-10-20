#pragma once
#include "realityforge/core.hpp"
#include <array>
#include <cstdint>
#include <functional>
#include <span>
#include <string>

namespace rf::asset {
// Internal coordinates are x-right/y-down/z-forward. Exporters use the proper
// rotation diag(1,-1,-1), determinant +1: winding is preserved, UVs are unchanged.
enum class Representation { SparsePoints, DensePoints, ObservedSurface, PhotographicSurface };
enum class Units { Arbitrary, Meters };
struct Provenance {
    std::string assetId;
    std::string parentId;
    std::string sourceSha256;
    std::string algorithmVersion;
};
struct PointAsset {
    Representation representation = Representation::SparsePoints;
    std::span<const Vec3> positions;
    std::span<const std::array<std::uint8_t,3>> colors;
    std::span<const Vec3> normals;
    std::span<const float> confidence;
    std::span<const std::uint32_t> supportingViews;
    Units units = Units::Arbitrary;
    Provenance provenance;
};
struct SurfaceAsset {
    PointAsset vertices;
    // Consecutive triples, zero-based. Holes and disconnected components remain.
    std::span<const std::uint32_t> indices;
};
struct TexturedAsset {
    SurfaceAsset surface;
    std::span<const Vec2> uv; // (0,0) is upper left, including GLB PNG origin.
    std::span<const std::uint8_t> png;
    std::uint32_t imageWidth{}, imageHeight{};
};
struct Limits {
    std::size_t maxVertices = 2'000'000;
    std::size_t maxTriangles = 4'000'000;
    std::size_t maxImageBytes = 64*1024*1024;
    std::size_t maxImagePixels = 16*1024*1024;
    std::size_t maxOutputBytes = 256*1024*1024;
};
using Cancel = std::function<bool()>;
struct Bounds { Vec3 minimum, maximum; };
std::string representationName(Representation representation);
void checkCancelled(const Cancel& cancel);
Bounds validate(const PointAsset& asset,const Limits& limits = {},const Cancel& cancel = {});
Bounds validate(const SurfaceAsset& asset,const Limits& limits = {},const Cancel& cancel = {});
Bounds validate(const TexturedAsset& asset,const Limits& limits = {},const Cancel& cancel = {});
Vec3 toGltf(Vec3 p);
}
