#include "realityforge/obj_export.hpp"
#include <algorithm>
#include <array>
#include <charconv>
#include <limits>
#include <ostream>
#include <string_view>
#include <type_traits>
#include <utility>

namespace rf::asset {
namespace {
std::string number(double value) {
    std::array<char, 64> buffer{};
    auto [end, error] = std::to_chars(buffer.data(), buffer.data()+buffer.size(), value,
        std::chars_format::general, std::numeric_limits<double>::max_digits10);
    if (error != std::errc{}) throw Error("OBJ numeric formatting failed");
    return {buffer.data(), end};
}
void nameCheck(const std::string& name) {
    if (name.empty() || name.size()>80) throw Error("OBJ basename must contain 1..80 portable characters");
    for (unsigned char c:name)
        if (!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c=='_'||c=='-'))
            throw Error("OBJ basename contains an unsafe path or character");
    std::string upper=name;
    for(char& c:upper)if(c>='a'&&c<='z')c=static_cast<char>(c-'a'+'A');
    if(upper=="CON"||upper=="PRN"||upper=="AUX"||upper=="NUL"||
        (upper.size()==4&&(upper.starts_with("COM")||upper.starts_with("LPT"))&&upper[3]>='1'&&upper[3]<='9'))
        throw Error("OBJ basename is a reserved device filename");
}
std::string comment(std::string_view value) {
    // Hex encoding is lossless and cannot inject OBJ/MTL records or URI paths.
    static constexpr char hex[]="0123456789abcdef";
    if (value.size()>4096) throw Error("OBJ provenance exceeds 4096 bytes");
    std::string out; out.reserve(value.size()*2);
    for (unsigned char c:value) { out+=hex[c>>4]; out+=hex[c&15]; }
    return out;
}
struct Sink {
    std::ostream* stream;
    std::size_t bytes{};
    void put(std::string_view value) {
        if (value.size()>std::numeric_limits<std::size_t>::max()-bytes) throw Error("OBJ byte count overflow");
        if (stream) {
            try { stream->write(value.data(), static_cast<std::streamsize>(value.size())); }
            catch (const std::ios_base::failure&) { throw Error("OBJ output write failed"); }
            if (!*stream) throw Error("OBJ short output write");
        }
        bytes+=value.size();
    }
};
void metadata(Sink& sink,const PointAsset& points) {
    sink.put("# RealityForge OBJ 1; observed data, no inferred closure\n# coordinates right-handed x-right y-up z-backward; winding preserved\n");
    sink.put("# representation "+representationName(points.representation)+"\n");
    sink.put(points.units==Units::Meters?"# units meters\n":"# units arbitrary; no metric accuracy established\n");
    if(!points.colors.empty())sink.put("# vertex_colors omitted; OBJ has no portable per-vertex color fields\n");
    if(!points.confidence.empty()||!points.supportingViews.empty())sink.put("# per_vertex_evidence omitted; preserve the PLY/diagnostic companion for these fields\n");
    for (auto entry:{std::pair{"asset_id",&points.provenance.assetId}, {"parent_id",&points.provenance.parentId},
                    {"source_sha256",&points.provenance.sourceSha256}, {"algorithm_version",&points.provenance.algorithmVersion}})
        sink.put("# "+std::string(entry.first)+"_utf8_hex "+comment(*entry.second)+"\n");
}
void vertices(Sink& out,const PointAsset& asset,const Cancel& cancel) {
    for (const auto& p:asset.positions) {
        checkCancelled(cancel); const auto v=toGltf(p);
        out.put("v "+number(v.x)+" "+number(v.y)+" "+number(v.z)+"\n");
    }
    for (const auto& n:asset.normals) {
        checkCancelled(cancel); const auto v=toGltf(n);
        out.put("vn "+number(v.x)+" "+number(v.y)+" "+number(v.z)+"\n");
    }
}
void faces(Sink& out,const SurfaceAsset& surface,bool textured,const Cancel& cancel) {
    const bool normals=!surface.vertices.normals.empty();
    for (std::size_t i=0;i<surface.indices.size();i+=3) {
        checkCancelled(cancel); std::string line="f";
        for (std::size_t j=0;j<3;++j) {
            auto index=std::to_string(static_cast<std::uint64_t>(surface.indices[i+j])+1);
            line+=" "+index;
            if (textured) line+="/"+index;
            if (normals) line+=(textured?"/":"//")+index;
        }
        out.put(line+"\n");
    }
}
void emit(const PointAsset& asset,Sink& geometry,Sink&,Sink&,const ObjOptions&,const Cancel& cancel) {
    metadata(geometry,asset); vertices(geometry,asset,cancel);
    for (std::size_t i=0;i<asset.positions.size();++i) { checkCancelled(cancel); geometry.put("p "+std::to_string(i+1)+"\n"); }
}
void emit(const SurfaceAsset& asset,Sink& geometry,Sink&,Sink&,const ObjOptions&,const Cancel& cancel) {
    metadata(geometry,asset.vertices); vertices(geometry,asset.vertices,cancel); faces(geometry,asset,false,cancel);
}
void emit(const TexturedAsset& asset,Sink& geometry,Sink& material,Sink& texture,const ObjOptions& options,const Cancel& cancel) {
    metadata(geometry,asset.surface.vertices);
    geometry.put("mtllib "+options.basename+".mtl\n");
    vertices(geometry,asset.surface.vertices,cancel);
    // Wavefront OBJ v=0 is at the image bottom; canonical/GLB v=0 is at top.
    for (const auto& uv:asset.uv) { checkCancelled(cancel); geometry.put("vt "+number(uv.x)+" "+number(1.0-uv.y)+"\n"); }
    geometry.put("usemtl photograph\n"); faces(geometry,asset.surface,true,cancel);
    material.put("# Photographic atlas; no measured PBR material properties\nnewmtl photograph\nKa 0 0 0\nKd 1 1 1\nKs 0 0 0\nd 1\nillum 1\nmap_Kd "+options.basename+".png\n");
    constexpr std::size_t chunk=16384;
    for (std::size_t i=0;i<asset.png.size();i+=chunk) {
        checkCancelled(cancel); const auto n=std::min(chunk,asset.png.size()-i);
        texture.put({reinterpret_cast<const char*>(asset.png.data()+i),n});
    }
}
const PointAsset& points(const PointAsset& value) { return value; }
const PointAsset& points(const SurfaceAsset& value) { return value.vertices; }
const PointAsset& points(const TexturedAsset& value) { return value.surface.vertices; }
template<class Asset> ObjDescriptor describe(const Asset& asset,const ObjOptions& options,const Limits& limits,const Cancel& cancel) {
    nameCheck(options.basename); validate(asset,limits,cancel);
    if constexpr(std::is_same_v<Asset,PointAsset>)
        if(asset.representation!=Representation::SparsePoints&&asset.representation!=Representation::DensePoints)
            throw Error("OBJ point export requires sparse or dense point representation");
    Sink g{nullptr},m{nullptr},t{nullptr}; emit(asset,g,m,t,options,cancel);
    ObjDescriptor descriptor{{{options.basename+".obj","model/obj",g.bytes}},0,points(asset).representation,points(asset).units};
    descriptor.vertexColorsOmitted=!points(asset).colors.empty();
    descriptor.confidenceOmitted=!points(asset).confidence.empty();
    descriptor.supportingViewsOmitted=!points(asset).supportingViews.empty();
    if constexpr (std::is_same_v<Asset,TexturedAsset>) {
        descriptor.files.push_back({options.basename+".mtl","model/mtl",m.bytes});
        descriptor.files.push_back({options.basename+".png","image/png",t.bytes});
    }
    for (const auto& file:descriptor.files) {
        if (file.bytes>limits.maxOutputBytes-descriptor.totalBytes) throw Error("OBJ package exceeds output budget");
        descriptor.totalBytes+=file.bytes;
    }
    return descriptor;
}
void flush(std::ostream& stream) {
    try { stream.flush(); } catch(const std::ios_base::failure&) { throw Error("OBJ output flush failed"); }
    if (!stream) throw Error("OBJ output flush failed");
}
template<class Asset> ObjDescriptor write(const Asset& asset,ObjStreams streams,const ObjOptions& options,const Limits& limits,const Cancel& cancel) {
    const auto descriptor=describe(asset,options,limits,cancel);
    if (!streams.geometry) throw Error("OBJ geometry stream unavailable");
    if constexpr (std::is_same_v<Asset,TexturedAsset>) {
        if (!streams.material || !streams.texture || !*streams.material || !*streams.texture ||
            streams.material==&streams.geometry || streams.texture==&streams.geometry || streams.material==streams.texture)
            throw Error("Textured OBJ requires three distinct healthy staging streams");
    } else if (streams.material || streams.texture) throw Error("Untextured OBJ rejects unused companion streams");
    Sink g{&streams.geometry},m{streams.material},t{streams.texture};
    emit(asset,g,m,t,options,cancel); checkCancelled(cancel);
    flush(streams.geometry); if(streams.material)flush(*streams.material); if(streams.texture)flush(*streams.texture);
    checkCancelled(cancel); return descriptor;
}
}
#define RF_OBJ_OVERLOAD(Asset) \
ObjDescriptor describeObj(const Asset& a,const ObjOptions& o,const Limits& l,const Cancel& c){return describe(a,o,l,c);} \
ObjDescriptor writeObj(const Asset& a,ObjStreams s,const ObjOptions& o,const Limits& l,const Cancel& c){return write(a,s,o,l,c);}
RF_OBJ_OVERLOAD(PointAsset)
RF_OBJ_OVERLOAD(SurfaceAsset)
RF_OBJ_OVERLOAD(TexturedAsset)
#undef RF_OBJ_OVERLOAD
}
