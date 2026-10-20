#pragma once
#include "realityforge/asset_model.hpp"
#include <iosfwd>

namespace rf::asset {
enum class PlyEncoding { BinaryLittleEndian, Ascii };
struct PlyOptions { PlyEncoding encoding = PlyEncoding::BinaryLittleEndian; };
struct PlyDescriptor {
    std::size_t bytes{}, vertices{}, faces{}, vertexStride{};
    Representation representation{};
    Units units{};
    bool normals{}, colors{}, confidence{}, supportingViews{};
};
// Stable properties: double xyz; optional double normals; uchar RGB;
// float confidence; uint supporting_views; face list uchar/uint vertex_indices.
// Frame is the same proper (x,-y,-z) rotation as GLB. Custom comments retain
// evidence but generic readers need not interpret them. PLY never embeds a
// photographic atlas; a TexturedAsset's surface is exported as observed_surface.
PlyDescriptor describePly(const PointAsset&, const PlyOptions& = {}, const Limits& = {}, const Cancel& = {});
PlyDescriptor describePly(const SurfaceAsset&, const PlyOptions& = {}, const Limits& = {}, const Cancel& = {});
// Streams are private staging output, owned by the caller. An exception means
// discard staging; publish only after successful return and flush.
PlyDescriptor writePly(const PointAsset&, std::ostream&, const PlyOptions& = {}, const Limits& = {}, const Cancel& = {});
PlyDescriptor writePly(const SurfaceAsset&, std::ostream&, const PlyOptions& = {}, const Limits& = {}, const Cancel& = {});
}
