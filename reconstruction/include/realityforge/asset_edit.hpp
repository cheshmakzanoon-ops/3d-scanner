#pragma once
#include "realityforge/asset_model.hpp"
#include <optional>
#include <variant>
#include <vector>

namespace rf::asset {
struct OrientationEdit { std::array<double,16> matrix; }; // Row-major rigid affine; reflection allowed.
struct CropEdit { Vec3 minimum, maximum; }; // Keep only wholly contained triangles; never fill/cap.
struct ComponentEdit { std::vector<std::uint32_t> componentIds; }; // Ascending-first-vertex components.
struct MetricReferenceEdit { Vec3 start,end;double knownMeters{},uncertaintyMeters{};std::string label; };
using AssetEdit=std::variant<OrientationEdit,CropEdit,ComponentEdit,MetricReferenceEdit>;
struct EditRecipe {
    std::uint32_t schemaVersion=1;
    std::string parentAssetId,sourceSha256;
    std::vector<AssetEdit> operations;
};
struct MeasurementEvidence {
    std::string referenceLabel;
    double metersPerOriginalUnit=1;
    std::optional<double> relativeUncertainty;
};
struct EditedGeometry {
    std::vector<Vec3> positions,normals;
    std::vector<std::array<std::uint8_t,3>> colors;
    std::vector<float> confidence;
    std::vector<std::uint32_t> supportingViews,indices;
    std::vector<std::uint32_t> parentVertexIndices,parentTriangleIndices;
    std::vector<Vec2> uv;
    Units units=Units::Arbitrary;
    Representation representation=Representation::ObservedSurface;
    Provenance provenance;
    MeasurementEvidence measurement;
    SurfaceAsset surface()const;
};
// Materializes a derived copy only; leaves source and photographs unchanged.
// Recipe identity must exactly match input and unknown versions are rejected.
EditedGeometry applyEdits(const SurfaceAsset&,const EditRecipe&,std::span<const Vec2> uv={},
                          const Limits& limits={},const Cancel& cancel={});
struct DistanceMeasurement { double value{};Units units=Units::Arbitrary;std::optional<double> uncertainty; };
DistanceMeasurement measureDistance(const EditedGeometry&,Vec3 a,Vec3 b);
}
