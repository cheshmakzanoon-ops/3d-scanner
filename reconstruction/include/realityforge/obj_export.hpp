#pragma once
#include "realityforge/asset_model.hpp"
#include <iosfwd>
#include <string>
#include <vector>

namespace rf::asset {
struct ObjOptions { std::string basename = "model"; };
struct ObjFile { std::string name; std::string mediaType; std::size_t bytes{}; };
struct ObjDescriptor {
    std::vector<ObjFile> files;
    std::size_t totalBytes{};
    Representation representation{};
    Units units{};
    bool vertexColorsOmitted{}, confidenceOmitted{}, supportingViewsOmitted{};
};
// Each stream must refer to a distinct private staging file. The caller owns
// publication/transport: an exception means discard ALL staging files. Success
// is returned only after every stream has flushed successfully. No originals,
// project paths, or existing documents are opened by these serializers.
struct ObjStreams {
    std::ostream& geometry;
    std::ostream* material = nullptr;
    std::ostream* texture = nullptr;
};
// Names are portable relative sibling names, never paths. Descriptors contain
// exact byte counts, computed with bounded memory before a byte is emitted.
ObjDescriptor describeObj(const PointAsset&, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
ObjDescriptor describeObj(const SurfaceAsset&, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
ObjDescriptor describeObj(const TexturedAsset&, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
ObjDescriptor writeObj(const PointAsset&, ObjStreams, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
ObjDescriptor writeObj(const SurfaceAsset&, ObjStreams, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
ObjDescriptor writeObj(const TexturedAsset&, ObjStreams, const ObjOptions& = {}, const Limits& = {}, const Cancel& = {});
}
