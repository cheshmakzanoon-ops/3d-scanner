#include "realityforge/ply_export.hpp"
#include <array>
#include <bit>
#include <charconv>
#include <limits>
#include <ostream>
#include <string_view>
#include <type_traits>

namespace rf::asset {
namespace {
std::string number(double value) {
    std::array<char,64> text{};
    auto[end,error]=std::to_chars(text.data(),text.data()+text.size(),value,std::chars_format::general,std::numeric_limits<double>::max_digits10);
    if(error!=std::errc{})throw Error("PLY number formatting failed");
    return {text.data(),end};
}
std::string hex(std::string_view value) {
    constexpr char digits[]="0123456789abcdef";
    if(value.size()>4096)throw Error("PLY metadata exceeds budget");
    std::string result;result.reserve(value.size()*2);
    for(unsigned char c:value){result+=digits[c>>4];result+=digits[c&15];}return result;
}
const PointAsset& points(const PointAsset& asset){return asset;}
const PointAsset& points(const SurfaceAsset& asset){return asset.vertices;}
std::span<const std::uint32_t> indices(const PointAsset&){return {};}
std::span<const std::uint32_t> indices(const SurfaceAsset& asset){return asset.indices;}
struct Sink {
    std::ostream* output{};
    std::size_t bytes{},budget{};
    void put(std::string_view value) {
        if(bytes>budget||value.size()>budget-bytes)throw Error("PLY output budget exceeded");
        if(output) {
            try{output->write(value.data(),static_cast<std::streamsize>(value.size()));}
            catch(const std::ios_base::failure&){throw Error("PLY output write failed");}
            if(!*output)throw Error("PLY short output write");
        }
        bytes+=value.size();
    }
};
template<class Value>void binary(Sink& out,Value value) {
    using Bits=std::conditional_t<sizeof(Value)==8,std::uint64_t,std::uint32_t>;
    Bits bits;
    if constexpr(std::is_floating_point_v<Value>)bits=std::bit_cast<Bits>(value);
    else bits=static_cast<Bits>(value);
    std::array<char,sizeof(Value)> data{};
    for(std::size_t i=0;i<data.size();++i)data[i]=static_cast<char>(bits>>(8*i));
    out.put({data.data(),data.size()});
}
template<class Asset>std::string header(const Asset& asset,const PlyOptions& options,PlyDescriptor& descriptor) {
    const auto& p=points(asset);const auto faces=indices(asset);
    descriptor.vertices=p.positions.size();descriptor.faces=faces.size()/3;descriptor.units=p.units;
    descriptor.normals=!p.normals.empty();descriptor.colors=!p.colors.empty();
    descriptor.confidence=!p.confidence.empty();descriptor.supportingViews=!p.supportingViews.empty();
    descriptor.vertexStride=24+(descriptor.normals?24:0)+(descriptor.colors?3:0)+(descriptor.confidence?4:0)+(descriptor.supportingViews?4:0);
    if constexpr(std::is_same_v<Asset,PointAsset>)descriptor.representation=p.representation;
    else descriptor.representation=Representation::ObservedSurface;
    std::string text="ply\nformat ";
    switch(options.encoding) {
        case PlyEncoding::BinaryLittleEndian:text+="binary_little_endian 1.0\n";break;
        case PlyEncoding::Ascii:text+="ascii 1.0\n";break;
        default:throw Error("Unsupported PLY encoding");
    }
    text+="comment RealityForge PLY 1\ncomment representation "+representationName(descriptor.representation)+"\n";
    text+="comment source_representation "+representationName(p.representation)+"\n";
    text+="comment coordinates right_handed_x_right_y_up_z_backward\ncomment winding preserved\n";
    text+=p.units==Units::Meters?"comment units meters\n":"comment units arbitrary\n";
    if(p.representation==Representation::PhotographicSurface)text+="comment photographic_texture omitted_in_ply\n";
    text+="comment asset_id_utf8_hex "+hex(p.provenance.assetId)+"\ncomment parent_id_utf8_hex "+hex(p.provenance.parentId)+
          "\ncomment source_sha256 "+p.provenance.sourceSha256+"\ncomment algorithm_version_utf8_hex "+hex(p.provenance.algorithmVersion)+"\n";
    text+="element vertex "+std::to_string(descriptor.vertices)+"\nproperty double x\nproperty double y\nproperty double z\n";
    if(descriptor.normals)text+="property double nx\nproperty double ny\nproperty double nz\n";
    if(descriptor.colors)text+="property uchar red\nproperty uchar green\nproperty uchar blue\n";
    if(descriptor.confidence)text+="property float confidence\n";
    if(descriptor.supportingViews)text+="property uint supporting_views\n";
    if constexpr(std::is_same_v<Asset,SurfaceAsset>)
        text+="element face "+std::to_string(descriptor.faces)+"\nproperty list uchar uint vertex_indices\n";
    return text+"end_header\n";
}
template<class Asset>void emit(const Asset& asset,const PlyOptions& options,const std::string& head,Sink& out,const Cancel& cancel) {
    out.put(head);const auto& p=points(asset);
    for(std::size_t i=0;i<p.positions.size();++i) {
        checkCancelled(cancel);const auto position=toGltf(p.positions[i]);
        if(options.encoding==PlyEncoding::Ascii) {
            auto row=number(position.x)+" "+number(position.y)+" "+number(position.z);
            if(!p.normals.empty()){auto n=toGltf(p.normals[i]);row+=" "+number(n.x)+" "+number(n.y)+" "+number(n.z);}
            if(!p.colors.empty())for(auto c:p.colors[i])row+=" "+std::to_string(c);
            if(!p.confidence.empty())row+=" "+number(p.confidence[i]);
            if(!p.supportingViews.empty())row+=" "+std::to_string(p.supportingViews[i]);
            out.put(row+"\n");
        }else {
            binary(out,position.x);binary(out,position.y);binary(out,position.z);
            if(!p.normals.empty()){auto n=toGltf(p.normals[i]);binary(out,n.x);binary(out,n.y);binary(out,n.z);}
            if(!p.colors.empty())out.put({reinterpret_cast<const char*>(p.colors[i].data()),3});
            if(!p.confidence.empty())binary(out,p.confidence[i]);
            if(!p.supportingViews.empty())binary(out,p.supportingViews[i]);
        }
    }
    const auto faces=indices(asset);
    for(std::size_t i=0;i<faces.size();i+=3) {
        checkCancelled(cancel);
        if(options.encoding==PlyEncoding::Ascii)out.put("3 "+std::to_string(faces[i])+" "+std::to_string(faces[i+1])+" "+std::to_string(faces[i+2])+"\n");
        else {out.put(std::string_view("\3",1));binary(out,faces[i]);binary(out,faces[i+1]);binary(out,faces[i+2]);}
    }
}
template<class Asset>PlyDescriptor describe(const Asset& asset,const PlyOptions& options,const Limits& limits,const Cancel& cancel) {
    validate(asset,limits,cancel);
    if constexpr(std::is_same_v<Asset,PointAsset>)
        if(asset.representation!=Representation::SparsePoints&&asset.representation!=Representation::DensePoints)
            throw Error("PLY point export requires sparse or dense point representation");
    PlyDescriptor descriptor;
    const auto head=header(asset,options,descriptor);
    Sink counter{nullptr,0,limits.maxOutputBytes};emit(asset,options,head,counter,cancel);descriptor.bytes=counter.bytes;
    return descriptor;
}
template<class Asset>PlyDescriptor write(const Asset& asset,std::ostream& stream,const PlyOptions& options,const Limits& limits,const Cancel& cancel) {
    auto descriptor=describe(asset,options,limits,cancel);
    if(!stream)throw Error("PLY staging stream unavailable");
    const auto head=header(asset,options,descriptor);Sink out{&stream,0,limits.maxOutputBytes};emit(asset,options,head,out,cancel);
    checkCancelled(cancel);
    try{stream.flush();}catch(const std::ios_base::failure&){throw Error("PLY output flush failed");}
    if(!stream)throw Error("PLY output flush failed");
    checkCancelled(cancel);return descriptor;
}
}
#define RF_PLY_OVERLOAD(Asset) \
PlyDescriptor describePly(const Asset& a,const PlyOptions& o,const Limits& l,const Cancel& c){return describe(a,o,l,c);} \
PlyDescriptor writePly(const Asset& a,std::ostream& s,const PlyOptions& o,const Limits& l,const Cancel& c){return write(a,s,o,l,c);}
RF_PLY_OVERLOAD(PointAsset)
RF_PLY_OVERLOAD(SurfaceAsset)
#undef RF_PLY_OVERLOAD
}
