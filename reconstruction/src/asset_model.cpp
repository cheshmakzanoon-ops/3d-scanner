#include "realityforge/asset_model.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <zlib.h>

namespace rf::asset {
namespace {
bool finite(Vec3 p) { return std::isfinite(p.x)&&std::isfinite(p.y)&&std::isfinite(p.z); }
bool utf8(const std::string& text) {
    for(std::size_t i=0;i<text.size();) {
        const auto lead=static_cast<unsigned char>(text[i++]);if(lead<128)continue;
        unsigned count=0;std::uint32_t code=0,minimum=0;
        if(lead>=0xc2&&lead<=0xdf){count=1;code=lead&31;minimum=0x80;}
        else if(lead>=0xe0&&lead<=0xef){count=2;code=lead&15;minimum=0x800;}
        else if(lead>=0xf0&&lead<=0xf4){count=3;code=lead&7;minimum=0x10000;}
        else return false;
        if(count>text.size()-i)return false;
        while(count--){const auto c=static_cast<unsigned char>(text[i++]);if((c&0xc0)!=0x80)return false;code=(code<<6)|(c&63);}
        if(code<minimum||code>0x10ffff||(code>=0xd800&&code<=0xdfff))return false;
    }
    return true;
}
void cardinality(std::size_t actual,std::size_t count) { if(actual && actual!=count) throw Error("Asset attribute count mismatch"); }
std::uint32_t be32(std::span<const std::uint8_t> p,std::size_t at) {
    return (std::uint32_t(p[at])<<24)|(std::uint32_t(p[at+1])<<16)|(std::uint32_t(p[at+2])<<8)|p[at+3];
}
std::uint32_t crc(std::span<const std::uint8_t> p) {
    std::uint32_t c=0xffffffffU;
    for(auto v:p) { c^=v; for(int k=0;k<8;++k)c=(c>>1)^(0xedb88320U&(~(c&1)+1)); }
    return ~c;
}
}
std::string representationName(Representation r) {
    switch(r) {
        case Representation::SparsePoints:return "sparse_points";
        case Representation::DensePoints:return "dense_points";
        case Representation::ObservedSurface:return "observed_surface";
        case Representation::PhotographicSurface:return "photographic_surface";
    }
    throw Error("Unknown asset representation");
}
void checkCancelled(const Cancel& cancel) { if(cancel&&cancel())throw Error("Asset operation cancelled"); }
Vec3 toGltf(Vec3 p) { return {p.x,-p.y,-p.z}; }
Bounds validate(const PointAsset& a,const Limits& limits,const Cancel& cancel) {
    checkCancelled(cancel); representationName(a.representation);
    const auto n=a.positions.size();
    if(!n||n>limits.maxVertices||n>std::numeric_limits<std::uint32_t>::max())throw Error("Asset vertex budget exceeded or empty");
    cardinality(a.colors.size(),n);cardinality(a.normals.size(),n);cardinality(a.confidence.size(),n);cardinality(a.supportingViews.size(),n);
    if(a.units!=Units::Arbitrary&&a.units!=Units::Meters)throw Error("Unknown asset units");
    if(a.provenance.assetId.size()>1024||a.provenance.parentId.size()>1024||a.provenance.algorithmVersion.size()>1024)throw Error("Asset identity exceeds budget");
    if(!utf8(a.provenance.assetId)||!utf8(a.provenance.parentId)||!utf8(a.provenance.algorithmVersion))throw Error("Invalid UTF8 asset identity");
    if(!a.provenance.sourceSha256.empty()&&(a.provenance.sourceSha256.size()!=64||!std::all_of(a.provenance.sourceSha256.begin(),a.provenance.sourceSha256.end(),[](unsigned char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');})))throw Error("Invalid source SHA256");
    Bounds bounds{a.positions[0],a.positions[0]};
    for(std::size_t i=0;i<n;++i) {
        if(i%1024==0)checkCancelled(cancel);
        const auto p=a.positions[i];
        if(!finite(p)||std::abs(p.x)>1e9||std::abs(p.y)>1e9||std::abs(p.z)>1e9)throw Error("Nonfinite or out-of-range asset position");
        bounds.minimum={std::min(bounds.minimum.x,p.x),std::min(bounds.minimum.y,p.y),std::min(bounds.minimum.z,p.z)};
        bounds.maximum={std::max(bounds.maximum.x,p.x),std::max(bounds.maximum.y,p.y),std::max(bounds.maximum.z,p.z)};
        if(!a.normals.empty()) { const auto v=a.normals[i];const auto l=v.x*v.x+v.y*v.y+v.z*v.z;if(!finite(v)||!std::isfinite(l)||std::abs(l-1)>1e-3)throw Error("Asset normals must be finite unit vectors"); }
        if(!a.confidence.empty()&&(!std::isfinite(a.confidence[i])||a.confidence[i]<0||a.confidence[i]>1))throw Error("Invalid asset confidence");
    }
    return bounds;
}
Bounds validate(const SurfaceAsset& a,const Limits& limits,const Cancel& cancel) {
    if(a.vertices.representation!=Representation::ObservedSurface&&a.vertices.representation!=Representation::PhotographicSurface)throw Error("Triangle asset requires surface representation");
    const auto bounds=validate(a.vertices,limits,cancel);
    if(a.indices.empty()||a.indices.size()%3||a.indices.size()/3>limits.maxTriangles)throw Error("Invalid triangle count or budget");
    for(std::size_t i=0;i<a.indices.size();i+=3) {
        if(i%3072==0)checkCancelled(cancel);
        const auto x=a.indices[i],y=a.indices[i+1],z=a.indices[i+2];
        if(x>=a.vertices.positions.size()||y>=a.vertices.positions.size()||z>=a.vertices.positions.size()||x==y||x==z||y==z)throw Error("Invalid triangle index");
        const auto p=a.vertices.positions[x],q=a.vertices.positions[y],r=a.vertices.positions[z];
        const Vec3 u{q.x-p.x,q.y-p.y,q.z-p.z},v{r.x-p.x,r.y-p.y,r.z-p.z};
        const Vec3 cross{u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x};
        if(cross.x*cross.x+cross.y*cross.y+cross.z*cross.z<=1e-30)throw Error("Degenerate triangle");
    }
    return bounds;
}
Bounds validate(const TexturedAsset& a,const Limits& limits,const Cancel& cancel) {
    if(a.surface.vertices.representation!=Representation::PhotographicSurface)throw Error("Photographic asset requires photographic representation");
    const auto bounds=validate(a.surface,limits,cancel);
    if(a.uv.size()!=a.surface.vertices.positions.size()||a.surface.vertices.normals.empty())throw Error("Photographic asset requires per-vertex normals and UVs");
    for(std::size_t i=0;i<a.uv.size();++i) {if(i%1024==0)checkCancelled(cancel);auto uv=a.uv[i];if(!std::isfinite(uv.x)||!std::isfinite(uv.y)||uv.x<0||uv.x>1||uv.y<0||uv.y>1)throw Error("Invalid photographic UV");}
    if(!a.imageWidth||!a.imageHeight||std::uint64_t(a.imageWidth)*a.imageHeight>limits.maxImagePixels||a.png.size()>limits.maxImageBytes||a.png.size()>std::numeric_limits<uInt>::max()||a.png.size()<57)throw Error("Photographic image budget exceeded");
    constexpr std::array<std::uint8_t,8> signature{137,80,78,71,13,10,26,10};
    if(!std::equal(signature.begin(),signature.end(),a.png.begin()))throw Error("Invalid PNG signature");
    bool data=false,end=false,afterData=false;std::size_t chunks=0;std::uint64_t channels=0;
    std::vector<std::span<const std::uint8_t>> compressed;
    for(std::size_t at=8;at<a.png.size();) {
        checkCancelled(cancel);
        if(++chunks>10000||a.png.size()-at<12)throw Error("Invalid PNG chunk");
        const auto length=be32(a.png,at);
        if(length>a.png.size()-at-12)throw Error("Truncated PNG chunk");
        const std::string type(reinterpret_cast<const char*>(a.png.data()+at+4),4);
        if(!std::all_of(type.begin(),type.end(),[](unsigned char c){return (c>='A'&&c<='Z')||(c>='a'&&c<='z');})||(a.png[at+6]&32))throw Error("Invalid PNG chunk type");
        if(crc(a.png.subspan(at+4,length+4))!=be32(a.png,at+8+length))throw Error("Invalid PNG CRC");
        if(at==8) {
            if(type!="IHDR"||length!=13||be32(a.png,at+8)!=a.imageWidth||be32(a.png,at+12)!=a.imageHeight||a.png[at+16]!=8||(a.png[at+17]!=2&&a.png[at+17]!=6)||a.png[at+18]||a.png[at+19]||a.png[at+20])throw Error("Unsupported PNG header");
            channels=a.png[at+17]==6?4:3;
        } else if(type=="IDAT") {if(!length||afterData)throw Error("Invalid PNG data ordering");data=true;compressed.push_back(a.png.subspan(at+8,length));}
        else if(type=="IEND") {if(length||!data||at+12!=a.png.size())throw Error("Invalid PNG end");end=true;}
        else {if(type=="IHDR"||type=="acTL"||type=="fcTL"||type=="fdAT"||!(a.png[at+4]&32))throw Error("Unsupported PNG chunk");if(data)afterData=true;}
        at+=std::size_t(length)+12;
    }
    if(!end)throw Error("Missing PNG end");
    const std::uint64_t rowBytes=std::uint64_t(a.imageWidth)*channels+1;
    if(rowBytes>std::numeric_limits<std::uint64_t>::max()/a.imageHeight)throw Error("PNG decoded size overflow");
    const auto expected=rowBytes*a.imageHeight;
    z_stream stream{};
    if(::inflateInit(&stream)!=Z_OK)throw Error("Cannot initialize PNG validation");
    struct End {z_stream& stream;~End(){::inflateEnd(&stream);}} guard{stream};
    std::array<std::uint8_t,16384> scratch{};std::uint64_t produced=0;bool finished=false;
    for(auto bytes:compressed) {
        if(finished)throw Error("Trailing PNG compressed data");
        stream.next_in=const_cast<Bytef*>(bytes.data());stream.avail_in=static_cast<uInt>(bytes.size());
        do {
            checkCancelled(cancel);
            stream.next_out=scratch.data();stream.avail_out=static_cast<uInt>(scratch.size());
            const auto inputBefore=stream.avail_in;const auto status=::inflate(&stream,Z_NO_FLUSH);
            const auto count=scratch.size()-stream.avail_out;
            if(status!=Z_OK&&status!=Z_STREAM_END)throw Error("Invalid PNG compressed data");
            if(!count&&inputBefore==stream.avail_in)throw Error("PNG inflater made no progress");
            if(count>expected-produced)throw Error("PNG decompression exceeds dimensions");
            for(std::size_t i=0;i<count;++i)if((produced+i)%rowBytes==0&&scratch[i]>4)throw Error("Invalid PNG row filter");
            produced+=count;finished=status==Z_STREAM_END;
            if(finished)break;
        } while(stream.avail_in||stream.avail_out==0);
        if(stream.avail_in)throw Error("Trailing PNG compressed data");
    }
    if(!finished||produced!=expected)throw Error("Truncated PNG pixel data");
    return bounds;
}
}
