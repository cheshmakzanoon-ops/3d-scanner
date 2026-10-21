#include "realityforge/stl_export.hpp"
#include <algorithm>
#include <array>
#include <bit>
#include <cmath>
#include <cstring>
#include <limits>
#include <ostream>
#include <unordered_map>

namespace rf::asset {
namespace {
double scale(const SurfaceAsset& asset,const StlOptions& options) {
    if(!options.scale)throw Error("STL requires explicit scale units and provenance or arbitrary-scale acknowledgement");
    const auto& value=*options.scale;
    if(value.referenceId.size()>4096||!std::isfinite(value.relativeUncertainty)||value.relativeUncertainty<0||value.relativeUncertainty>1)
        throw Error("STL scale evidence or uncertainty invalid");
    double meters=0;
    switch(value.basis) {
        case StlScale::Basis::MetricAsset:
            if(asset.vertices.units!=Units::Meters)throw Error("STL metric scale requires a metric asset");
            meters=1;break;
        case StlScale::Basis::KnownReference:
            if(value.referenceId.empty()||value.referenceId.size()>4096)throw Error("STL known scale requires a bounded reference identity");
            if(!std::isfinite(value.relativeUncertainty)||value.relativeUncertainty<0||value.relativeUncertainty>1)
                throw Error("STL scale uncertainty must be finite in [0,1]");
            meters=value.metersPerAssetUnit;break;
        case StlScale::Basis::ArbitraryAcknowledged:meters=value.metersPerAssetUnit;break;
        default:throw Error("STL scale basis unsupported");
    }
    double perMeter=0;
    switch(value.outputUnit) {
        case StlLengthUnit::Meters:perMeter=1;break;
        case StlLengthUnit::Millimeters:perMeter=1000;break;
        case StlLengthUnit::Inches:perMeter=1/0.0254;break;
        default:throw Error("STL output unit unsupported");
    }
    const auto result=meters*perMeter;
    if(!std::isfinite(result)||result<=0)throw Error("STL conversion must be finite and positive");
    return result;
}
Vec3 transformed(Vec3 value,double factor) {
    value=toGltf(value);value={value.x*factor,value.y*factor,value.z*factor};
    const auto max=static_cast<double>(std::numeric_limits<float>::max());
    for(auto coordinate:{value.x,value.y,value.z})
        if(!std::isfinite(coordinate)||std::abs(coordinate)>max)throw Error("STL coordinate exceeds finite float32 range");
    return value;
}
std::array<Vec3,4> facet(const SurfaceAsset& asset,std::size_t triangle,double factor) {
    std::array<Vec3,4> result{};
    for(std::size_t j=0;j<3;++j)result[j+1]=transformed(asset.vertices.positions[asset.indices[triangle*3+j]],factor);
    // Normals must agree with what an independent STL reader gets from the
    // *encoded* float32 positions, not just pre-quantized doubles.
    auto quantized=[](Vec3 p){return Vec3{static_cast<float>(p.x),static_cast<float>(p.y),static_cast<float>(p.z)};};
    auto a=quantized(result[1]),b=quantized(result[2]),c=quantized(result[3]);
    Vec3 u{b.x-a.x,b.y-a.y,b.z-a.z},v{c.x-a.x,c.y-a.y,c.z-a.z};
    Vec3 n{u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x};
    auto length=std::hypot(n.x,n.y,n.z);
    if(!std::isfinite(length)||length==0)throw Error("STL facet degenerates after float32 conversion");
    result[0]={n.x/length,n.y/length,n.z/length};return result;
}
struct Edge { std::uint32_t count{}; std::int64_t orientation{}; };
void append32(std::uint8_t* out,std::uint32_t value) {
    for(int i=0;i<4;++i)out[i]=static_cast<std::uint8_t>(value>>(8*i));
}
void appendFloat(std::uint8_t* out,double value) { append32(out,std::bit_cast<std::uint32_t>(static_cast<float>(value))); }
void write(std::ostream& out,const std::uint8_t* data,std::size_t count) {
    try{out.write(reinterpret_cast<const char*>(data),static_cast<std::streamsize>(count));}
    catch(const std::ios_base::failure&){throw Error("STL output write failed");}
    if(!out)throw Error("STL short output write");
}
}
StlReport describeStl(const SurfaceAsset& asset,const StlOptions& options,const Limits& limits,const Cancel& cancel) {
    validate(asset,limits,cancel);
    if(options.headerLabel.empty()||options.headerLabel.size()>80||
       options.headerLabel.find_first_not_of(" ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789;:,.=-_()")!=std::string::npos||
       options.headerLabel.starts_with("solid"))throw Error("STL header label must be bounded printable ASCII");
    const auto factor=scale(asset,options);
    const auto count=asset.indices.size()/3;
    if(count>std::numeric_limits<std::uint32_t>::max()||count>(std::numeric_limits<std::size_t>::max()-84)/50)
        throw Error("STL triangle count overflow");
    const auto bytes=84+count*50;
    if(bytes>limits.maxOutputBytes)throw Error("STL output exceeds byte budget");
    if(options.maxTopologyEdges==0||options.maxTopologyEdges>12'000'000)throw Error("STL topology scratch budget out of range");
    StlReport report;report.outputBytes=bytes;report.triangles=count;report.outputUnitsPerAssetUnit=factor;
    report.outputUnit=options.scale->outputUnit;
    report.hasMetricReference=options.scale->basis!=StlScale::Basis::ArbitraryAcknowledged;
    report.scaleReferenceId=options.scale->referenceId;
    report.relativeScaleUncertainty=options.scale->relativeUncertainty;
    report.sourceProvenance=asset.vertices.provenance;
    std::unordered_map<std::uint64_t,Edge> edges;
    edges.reserve(std::min(asset.indices.size(),options.maxTopologyEdges));
    for(std::size_t i=0;i<count;++i) {
        checkCancelled(cancel);(void)facet(asset,i,factor);
        for(std::size_t j=0;j<3;++j) {
            auto a=asset.indices[i*3+j],b=asset.indices[i*3+(j+1)%3];
            const auto lo=std::min(a,b),hi=std::max(a,b);
            const auto key=(static_cast<std::uint64_t>(lo)<<32)|hi;
            auto at=edges.find(key);
            if(at==edges.end()) {
                if(edges.size()>=options.maxTopologyEdges)throw Error("STL topology edge budget exhausted");
                at=edges.emplace(key,Edge{}).first;
            }
            ++at->second.count;at->second.orientation+=(a<b?1:-1);
        }
    }
    for(const auto& [key,edge]:edges) {
        (void)key;checkCancelled(cancel);
        if(edge.count==1)++report.boundaryEdges;
        if(edge.count>2)++report.nonManifoldEdges;
        if(edge.count==2&&edge.orientation!=0)++report.orientationConflicts;
    }
    report.closedOrientedByEdgeIncidence=report.boundaryEdges==0&&report.nonManifoldEdges==0&&report.orientationConflicts==0;
    return report;
}
StlReport writeStl(const SurfaceAsset& asset,std::ostream& output,const StlOptions& options,const Limits& limits,const Cancel& cancel) {
    const auto report=describeStl(asset,options,limits,cancel);
    if(!output)throw Error("STL staging stream unavailable");
    std::array<std::uint8_t,84> header{};
    const std::string& label=options.headerLabel;
    std::memcpy(header.data(),label.data(),std::min<std::size_t>(80,label.size()));
    append32(header.data()+80,static_cast<std::uint32_t>(report.triangles));write(output,header.data(),header.size());
    for(std::size_t i=0;i<report.triangles;++i) {
        checkCancelled(cancel);const auto values=facet(asset,i,report.outputUnitsPerAssetUnit);
        std::array<std::uint8_t,50> encoded{};
        for(std::size_t j=0;j<4;++j) {
            appendFloat(encoded.data()+j*12,values[j].x);appendFloat(encoded.data()+j*12+4,values[j].y);appendFloat(encoded.data()+j*12+8,values[j].z);
        }
        write(output,encoded.data(),encoded.size());
    }
    checkCancelled(cancel);
    try{output.flush();}catch(const std::ios_base::failure&){throw Error("STL flush failed");}
    if(!output)throw Error("STL flush failed");
    checkCancelled(cancel);return report;
}
}
