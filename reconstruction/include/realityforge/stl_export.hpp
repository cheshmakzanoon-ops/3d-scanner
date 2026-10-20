#pragma once
#include "realityforge/asset_model.hpp"
#include <iosfwd>
#include <optional>

namespace rf::asset {
enum class StlLengthUnit { Meters, Millimeters, Inches };
struct StlScale {
    enum class Basis { MetricAsset, KnownReference, ArbitraryAcknowledged };
    Basis basis = Basis::ArbitraryAcknowledged;
    StlLengthUnit outputUnit = StlLengthUnit::Millimeters;
    // Required for reference/arbitrary conversion; MetricAsset always uses 1.
    double metersPerAssetUnit{};
    std::string referenceId;
    double relativeUncertainty{};
};
struct StlOptions {
    std::optional<StlScale> scale; // Missing acknowledgement/reference is an error.
    std::size_t maxTopologyEdges = 1'000'000; // Bounded scratch edge incidence.
};
struct StlReport {
    std::size_t outputBytes{}, triangles{}, boundaryEdges{}, nonManifoldEdges{}, orientationConflicts{};
    double outputUnitsPerAssetUnit{};
    bool closedOrientedByEdgeIncidence{};
    bool selfIntersectionsChecked = false;
    bool printabilityEstablished = false;
    bool hasMetricReference{};
    StlLengthUnit outputUnit{};
    std::string scaleReferenceId;
    double relativeScaleUncertainty{};
    Provenance sourceProvenance;
};
// Binary little-endian STL. Proper rotation (x,-y,-z) preserves winding.
// Material, color, UV and provenance have no portable STL fields. Carry the
// returned report alongside the file. Edge closure never establishes printability.
StlReport describeStl(const SurfaceAsset&, const StlOptions&, const Limits& = {}, const Cancel& = {});
// Writes only to a caller-owned private staging stream; caller must discard it
// on exception and publish/transport only after successful return and flush.
StlReport writeStl(const SurfaceAsset&, std::ostream&, const StlOptions&, const Limits& = {}, const Cancel& = {});
}
