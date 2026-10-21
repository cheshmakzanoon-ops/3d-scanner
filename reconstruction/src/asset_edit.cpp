#include "realityforge/asset_edit.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>
#include <set>
#include <type_traits>

namespace rf::asset {
namespace {
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
double norm(Vec3 a){return std::hypot(a.x,a.y,a.z);}
double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
bool finite(Vec3 a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
template<class T>void copy(std::vector<T>& to,std::span<const T> from){to.assign(from.begin(),from.end());}
void compact(EditedGeometry& g){
    std::vector<std::uint32_t> map(g.positions.size(),std::numeric_limits<std::uint32_t>::max());
    EditedGeometry out;out.units=g.units;out.representation=g.representation;out.provenance=g.provenance;out.measurement=g.measurement;
    out.parentTriangleIndices=g.parentTriangleIndices;
    for(auto i:g.indices){
        if(map[i]==std::numeric_limits<std::uint32_t>::max()){
            map[i]=static_cast<std::uint32_t>(out.positions.size());out.positions.push_back(g.positions[i]);
            out.parentVertexIndices.push_back(g.parentVertexIndices[i]);
            if(!g.normals.empty())out.normals.push_back(g.normals[i]);
            if(!g.colors.empty())out.colors.push_back(g.colors[i]);
            if(!g.confidence.empty())out.confidence.push_back(g.confidence[i]);
            if(!g.supportingViews.empty())out.supportingViews.push_back(g.supportingViews[i]);
            if(!g.uv.empty())out.uv.push_back(g.uv[i]);
        }
        out.indices.push_back(map[i]);
    }
    if(out.indices.empty())throw Error("Edit would remove every observed triangle");
    g=std::move(out);
}
}
SurfaceAsset EditedGeometry::surface()const{
    PointAsset p;p.representation=representation;p.positions=positions;p.colors=colors;p.normals=normals;
    p.confidence=confidence;p.supportingViews=supportingViews;p.units=units;p.provenance=provenance;return {p,indices};
}
EditedGeometry applyEdits(const SurfaceAsset& input,const EditRecipe& recipe,std::span<const Vec2> uv,const Limits& limits,const Cancel& cancel){
    validate(input,limits,cancel);
    if(recipe.schemaVersion!=1||recipe.operations.size()>256)throw Error("Unsupported edit recipe version or operation budget");
    if(recipe.parentAssetId.empty()||recipe.parentAssetId!=input.vertices.provenance.assetId||
       recipe.sourceSha256.size()!=64||recipe.sourceSha256!=input.vertices.provenance.sourceSha256)
        throw Error("Edit recipe does not match immutable source asset");
    if(!uv.empty()&&uv.size()!=input.vertices.positions.size())throw Error("Edit UV count mismatch");
    for(auto p:uv)if(!std::isfinite(p.x)||!std::isfinite(p.y))throw Error("Edit nonfinite UV");
    EditedGeometry out;copy(out.positions,input.vertices.positions);copy(out.normals,input.vertices.normals);
    copy(out.colors,input.vertices.colors);copy(out.confidence,input.vertices.confidence);copy(out.supportingViews,input.vertices.supportingViews);
    copy(out.indices,input.indices);copy(out.uv,uv);out.units=input.vertices.units;out.representation=input.vertices.representation;
    out.parentVertexIndices.resize(out.positions.size());std::iota(out.parentVertexIndices.begin(),out.parentVertexIndices.end(),0);
    out.parentTriangleIndices.resize(out.indices.size()/3);std::iota(out.parentTriangleIndices.begin(),out.parentTriangleIndices.end(),0);
    out.provenance=input.vertices.provenance;out.provenance.parentId=recipe.parentAssetId;out.provenance.assetId.clear();out.provenance.algorithmVersion="rf-asset-edit-v1";
    for(const auto& op:recipe.operations){
        checkCancelled(cancel);
        std::visit([&](const auto& edit){using T=std::decay_t<decltype(edit)>;
            if constexpr(std::is_same_v<T,OrientationEdit>){
                const auto& m=edit.matrix;
                for(double x:m)if(!std::isfinite(x))throw Error("Nonfinite orientation matrix");
                if(m[12]!=0||m[13]!=0||m[14]!=0||m[15]!=1)throw Error("Orientation must be affine");
                Vec3 columns[3]={{m[0],m[4],m[8]},{m[1],m[5],m[9]},{m[2],m[6],m[10]}};
                for(unsigned i=0;i<3;++i)for(unsigned j=0;j<3;++j)
                    if(std::abs(dot(columns[i],columns[j])-(i==j?1.0:0.0))>1e-9)throw Error("Orientation must be rigid; scale needs a known-distance reference");
                const double determinant=m[0]*(m[5]*m[10]-m[6]*m[9])-m[1]*(m[4]*m[10]-m[6]*m[8])+m[2]*(m[4]*m[9]-m[5]*m[8]);
                auto rotate=[&](Vec3 p){return Vec3{m[0]*p.x+m[1]*p.y+m[2]*p.z,m[4]*p.x+m[5]*p.y+m[6]*p.z,m[8]*p.x+m[9]*p.y+m[10]*p.z};};
                for(std::size_t i=0;i<out.positions.size();++i){if(i%1024==0)checkCancelled(cancel);auto p=rotate(out.positions[i]);out.positions[i]={p.x+m[3],p.y+m[7],p.z+m[11]};}
                for(auto& p:out.normals)p=rotate(p);
                if(determinant<0)for(std::size_t i=0;i<out.indices.size();i+=3)std::swap(out.indices[i+1],out.indices[i+2]);
            }else if constexpr(std::is_same_v<T,CropEdit>){
                if(!finite(edit.minimum)||!finite(edit.maximum)||edit.minimum.x>edit.maximum.x||edit.minimum.y>edit.maximum.y||edit.minimum.z>edit.maximum.z)
                    throw Error("Invalid crop bounds");
                auto inside=[&](std::uint32_t i){auto p=out.positions[i];return p.x>=edit.minimum.x&&p.x<=edit.maximum.x&&p.y>=edit.minimum.y&&p.y<=edit.maximum.y&&p.z>=edit.minimum.z&&p.z<=edit.maximum.z;};
                std::vector<std::uint32_t> kept;
                std::vector<std::uint32_t> keptSources;
                for(std::size_t i=0;i<out.indices.size();i+=3){if(i%3072==0)checkCancelled(cancel);if(inside(out.indices[i])&&inside(out.indices[i+1])&&inside(out.indices[i+2])){kept.insert(kept.end(),out.indices.begin()+i,out.indices.begin()+i+3);keptSources.push_back(out.parentTriangleIndices[i/3]);}}
                out.indices.swap(kept);out.parentTriangleIndices.swap(keptSources);compact(out);
            }else if constexpr(std::is_same_v<T,ComponentEdit>){
                if(edit.componentIds.empty()||edit.componentIds.size()>1024)throw Error("Invalid component selection");
                std::vector<std::uint32_t> parent(out.positions.size());std::iota(parent.begin(),parent.end(),0);
                auto root=[&](std::uint32_t x){while(parent[x]!=x){parent[x]=parent[parent[x]];x=parent[x];}return x;};
                for(std::size_t i=0;i<out.indices.size();i+=3){if(i%3072==0)checkCancelled(cancel);for(unsigned k=1;k<3;++k)parent[root(out.indices[i+k])]=root(out.indices[i]);}
                std::map<std::uint32_t,std::uint32_t> ids;
                std::vector<bool> used(parent.size(),false);for(auto i:out.indices)used[i]=true;
                for(std::uint32_t i=0;i<parent.size();++i)if(used[i])ids.try_emplace(root(i),static_cast<std::uint32_t>(ids.size()));
                std::set<std::uint32_t> wanted(edit.componentIds.begin(),edit.componentIds.end());
                if(*wanted.rbegin()>=ids.size())throw Error("Component selection refers to an unavailable component");
                std::vector<std::uint32_t> kept;
                std::vector<std::uint32_t> keptSources;
                for(std::size_t i=0;i<out.indices.size();i+=3)if(wanted.contains(ids.at(root(out.indices[i])))){kept.insert(kept.end(),out.indices.begin()+i,out.indices.begin()+i+3);keptSources.push_back(out.parentTriangleIndices[i/3]);}
                out.indices.swap(kept);out.parentTriangleIndices.swap(keptSources);compact(out);
            }else if constexpr(std::is_same_v<T,MetricReferenceEdit>){
                const double distance=norm(sub(edit.end,edit.start));
                if(!finite(edit.start)||!finite(edit.end)||!(distance>0)||!std::isfinite(distance)||
                   !(edit.knownMeters>0)||!std::isfinite(edit.knownMeters)||edit.uncertaintyMeters<0||!std::isfinite(edit.uncertaintyMeters)||
                   edit.label.empty()||edit.label.size()>256)throw Error("Invalid known-distance measurement evidence");
                const double scale=edit.knownMeters/distance;if(!std::isfinite(scale)||scale<=0)throw Error("Unrepresentable metric conversion");
                const double uncertainty=edit.uncertaintyMeters/edit.knownMeters;
                if(!std::isfinite(uncertainty))throw Error("Unrepresentable metric uncertainty");
                for(std::size_t i=0;i<out.positions.size();++i){if(i%1024==0)checkCancelled(cancel);auto& p=out.positions[i];p={p.x*scale,p.y*scale,p.z*scale};}
                out.measurement.metersPerOriginalUnit*=scale;if(!std::isfinite(out.measurement.metersPerOriginalUnit))throw Error("Metric conversion overflow");
                out.measurement.referenceLabel=edit.label;out.measurement.relativeUncertainty=uncertainty;out.units=Units::Meters;
            }
        },op);
        validate(out.surface(),limits,cancel);
    }
    checkCancelled(cancel);return out;
}
DistanceMeasurement measureDistance(const EditedGeometry& geometry,Vec3 a,Vec3 b){
    if(!finite(a)||!finite(b))throw Error("Invalid measurement endpoints");
    double d=norm(sub(a,b));if(!std::isfinite(d))throw Error("Measurement overflow");
    DistanceMeasurement result{d,geometry.units,std::nullopt};
    if(geometry.units==Units::Meters&&geometry.measurement.relativeUncertainty){
        result.uncertainty=d* *geometry.measurement.relativeUncertainty;
        if(!std::isfinite(*result.uncertainty))throw Error("Measurement uncertainty overflow");
    }
    return result;
}
}
