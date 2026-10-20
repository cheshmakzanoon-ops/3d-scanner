#include "realityforge/asset_edit.hpp"
#include "realityforge/glb_export.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
using namespace rf;using namespace rf::asset;
template<class F>void fails(F f){bool failed=false;try{f();}catch(const Error&){failed=true;}assert(failed);}
int main(int argc,char** argv){
    const std::vector<Vec3> positions={{0,0,0},{2,0,0},{0,2,0},{10,0,0},{12,0,0},{10,2,0}};
    const std::vector<Vec3> normals(6,Vec3{0,0,1});const std::vector<Vec2> uv={{0,0},{1,0},{0,1},{0,0},{1,0},{0,1}};
    const std::vector<std::uint32_t> indices={0,1,2,3,4,5};PointAsset points;points.representation=Representation::ObservedSurface;
    points.positions=positions;points.normals=normals;points.provenance={"original","",std::string(64,'a'),"synthetic-fixture"};SurfaceAsset source{points,indices};
    EditRecipe recipe;recipe.parentAssetId="original";recipe.sourceSha256=std::string(64,'a');
    auto original=applyEdits(source,recipe,uv);assert(original.positions.size()==6);assert(original.units==Units::Arbitrary);
    auto unknown=measureDistance(original,{0,0,0},{2,0,0});assert(unknown.units==Units::Arbitrary);assert(!unknown.uncertainty);
    recipe.operations={MetricReferenceEdit{{0,0,0},{2,0,0},.4,.002,"synthetic known-distance test"}};
    auto metric=applyEdits(source,recipe,uv);assert(metric.units==Units::Meters);assert(std::abs(metric.positions[1].x-.4)<1e-12);
    if(argc==2)writeGlbFile(metric.surface(),argv[1]);
    auto measured=measureDistance(metric,{0,0,0},{.4,0,0});assert(measured.units==Units::Meters);assert(std::abs(*measured.uncertainty-.002)<1e-12);
    assert(metric.measurement.metersPerOriginalUnit==.2);assert(metric.provenance.parentId=="original");assert(metric.provenance.assetId.empty());
    OrientationEdit reflection{{-1,0,0,0,0,1,0,0,0,0,1,0,0,0,0,1}};
    recipe.operations={reflection};auto reflected=applyEdits(source,recipe,uv);assert(reflected.positions[1].x==-2);assert(reflected.indices[1]==2&&reflected.indices[2]==1);
    if(argc==2)writeGlbFile(reflected.surface(),std::filesystem::path(argv[1]).parent_path()/"reflected-edit.glb");
    assert(reflected.normals[0].z==1);assert(reflected.uv[1].x==1);
    recipe.operations={CropEdit{{-1,-1,-1},{3,3,1}}};auto crop=applyEdits(source,recipe,uv);assert(crop.positions.size()==3&&crop.indices.size()==3);
    recipe.operations={ComponentEdit{{1}}};auto selected=applyEdits(source,recipe,uv);assert(selected.positions[0].x==10&&selected.indices.size()==3);
    recipe.operations={ComponentEdit{{2}}};fails([&]{applyEdits(source,recipe,uv);});
    recipe.operations={CropEdit{{100,100,100},{101,101,101}}};fails([&]{applyEdits(source,recipe,uv);});
    recipe.operations={MetricReferenceEdit{{0,0,0},{0,0,0},1,0,"bad"}};fails([&]{applyEdits(source,recipe);});
    auto scaled=reflection;scaled.matrix[0]=-2;recipe.operations={scaled};fails([&]{applyEdits(source,recipe);});
    recipe.operations.clear();recipe.sourceSha256=std::string(64,'b');fails([&]{applyEdits(source,recipe);});
    recipe.sourceSha256=std::string(64,'a');fails([&]{applyEdits(source,recipe,{}, {},[]{return true;});});
    assert(positions[1].x==2&&indices[1]==1&&uv[1].x==1);
    std::cout<<"agent066: metric uncertainty, reflection, crop, components, identity, cancellation and master preservation passed\n";
}
