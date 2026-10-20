// First-party SYNTHETIC interoperability fixture, never a physical measurement.
// Compile against the immutable C10/066 snapshots recorded in090's handoff receipt.
#include "realityforge/asset_edit.hpp"
#include "realityforge/glb_export.hpp"
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <stdexcept>
#include <vector>

using namespace rf;
using namespace rf::asset;

static void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

int main(int argc, char** argv) {
    require(argc == 3, "arguments: original checker PNG, output GLB");
    std::ifstream input(argv[1], std::ios::binary);
    const std::vector<std::uint8_t> png((std::istreambuf_iterator<char>(input)), {});
    require(!png.empty(), "PNG missing");
    const std::vector<Vec3> positions{{0,0,0},{2,0,0},{0,2,0},{10,0,0},{12,0,0},{10,2,0}};
    const std::vector<Vec3> normals(6, Vec3{0,0,1});
    const std::vector<Vec2> uv{{0,0},{1,0},{0,1},{0,0},{1,0},{0,1}};
    const std::vector<std::uint32_t> indices{0,1,2,3,4,5};
    PointAsset points;
    points.representation = Representation::ObservedSurface;
    points.positions = positions;
    points.normals = normals;
    points.provenance = {"analytic-master", "", std::string(64, 'a'), "synthetic-090-066"};
    SurfaceAsset source{points, indices};
    EditRecipe recipe;
    recipe.parentAssetId = "analytic-master";
    recipe.sourceSha256 = std::string(64, 'a');
    recipe.operations = {
        OrientationEdit{{-1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1}},
        CropEdit{{-3,-1,-1},{1,3,1}},
        MetricReferenceEdit{{0,0,0},{-2,0,0},.4,.002,"SYNTHETIC known-distance fixture"}
    };
    auto edited = applyEdits(source, recipe, uv);
    require(edited.positions.size() == 3 && edited.indices.size() == 3, "crop did not retain one open triangle");
    require(edited.units == Units::Meters && std::abs(edited.measurement.metersPerOriginalUnit-.2) < 1e-12, "metric provenance missing");
    auto retained = edited.parentVertexIndices;
    std::sort(retained.begin(), retained.end());
    require(retained == std::vector<std::uint32_t>({0,1,2}) && edited.parentTriangleIndices == std::vector<std::uint32_t>({0}), "parent-index sidecars lost");
    for (std::size_t i = 0; i < edited.positions.size(); ++i) {
        const auto parent = edited.parentVertexIndices[i];
        require(std::abs(edited.positions[i].x+positions[parent].x*.2) < 1e-12 && std::abs(edited.positions[i].y-positions[parent].y*.2) < 1e-12, "metric conversion must be baked exactly once");
        require(edited.uv[i].x == uv[parent].x && edited.uv[i].y == uv[parent].y, "compacted UV must follow its parent vertex");
    }
    require(edited.provenance.parentId == "analytic-master" && edited.provenance.assetId.empty(), "derived identity must remain pending owner009");
    auto surface = edited.surface();
    surface.vertices.representation = Representation::PhotographicSurface;
    TexturedAsset textured{surface, edited.uv, png, 2, 2};
    writeGlbFile(textured, std::filesystem::path(argv[2]));
    require(positions[1].x == 2 && positions[3].x == 10 && indices[1] == 1 && uv[1].x == 1, "master was mutated");
    std::ofstream sidecar(std::string(argv[2]) + ".provenance.json");
    sidecar << "{\"parent_vertex_indices\":[";
    for (std::size_t i = 0; i < edited.parentVertexIndices.size(); ++i) sidecar << (i ? "," : "") << edited.parentVertexIndices[i];
    sidecar << "],\"parent_triangle_indices\":[0],\"meters_per_original_unit\":0.2,\"master_unchanged\":true}\n";
    sidecar.close();
    require(!sidecar.fail(), "provenance sidecar write failed");
}
