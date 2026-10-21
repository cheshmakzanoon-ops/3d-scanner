#pragma once
#include "realityforge/asset_model.hpp"
#include <filesystem>
#include <iosfwd>
namespace rf::asset {
// Stream failures and cancellation throw rf::Error. Caller owns publication.
// File helpers write an exclusive temporary sibling and rename only on success.
void writeGlb(const PointAsset&,std::ostream&,const Limits& = {},const Cancel& = {});
void writeGlb(const SurfaceAsset&,std::ostream&,const Limits& = {},const Cancel& = {});
void writeGlb(const TexturedAsset&,std::ostream&,const Limits& = {},const Cancel& = {});
void writeGlbFile(const PointAsset&,const std::filesystem::path&,const Limits& = {},const Cancel& = {});
void writeGlbFile(const SurfaceAsset&,const std::filesystem::path&,const Limits& = {},const Cancel& = {});
void writeGlbFile(const TexturedAsset&,const std::filesystem::path&,const Limits& = {},const Cancel& = {});
}
