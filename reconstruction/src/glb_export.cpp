#include "realityforge/glb_export.hpp"
#include <algorithm>
#include <atomic>
#include <bit>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <fcntl.h>
#include <iomanip>
#include <limits>
#include <locale>
#include <ostream>
#include <sstream>
#include <streambuf>
#include <unistd.h>
#include <vector>

namespace rf::asset {
namespace {
std::string quote(const std::string& text) {
    std::ostringstream s;s<<'"';
    for(unsigned char c:text) { if(c=='"'||c=='\\')s<<'\\'<<c;else if(c<32)s<<"\\u00"<<std::hex<<std::setw(2)<<std::setfill('0')<<int(c)<<std::dec;else s<<c; }
    s<<'"';return s.str();
}
void raw(std::ostream& s,const void* data,std::size_t n) {s.write(static_cast<const char*>(data),static_cast<std::streamsize>(n));if(!s)throw Error("GLB short write");}
void u32(std::ostream& s,std::uint32_t n) {std::uint8_t p[4]{std::uint8_t(n),std::uint8_t(n>>8),std::uint8_t(n>>16),std::uint8_t(n>>24)};raw(s,p,4);}
void f32(std::ostream& s,double d) {float f=static_cast<float>(d);if(!std::isfinite(f))throw Error("GLB float overflow");u32(s,std::bit_cast<std::uint32_t>(f));}
std::size_t align(std::size_t n) {if(n>std::numeric_limits<std::size_t>::max()-3)throw Error("GLB alignment overflow");return (n+3)&~std::size_t(3);}
struct View {std::size_t offset,size;int target;};
void serialize(const PointAsset& a,const SurfaceAsset* surface,const TexturedAsset* texture,std::ostream& out,const Limits& limits,const Cancel& cancel) {
    if(texture)validate(*texture,limits,cancel);else if(surface)validate(*surface,limits,cancel);else {
        if(a.representation!=Representation::SparsePoints&&a.representation!=Representation::DensePoints)throw Error("Point writer requires point representation");
        validate(a,limits,cancel);
    }
    if(a.representation==Representation::PhotographicSurface&&!texture)throw Error("Photographic representation requires an embedded photographic texture");
    std::vector<View> views;std::size_t binary=0;
    auto add=[&](std::size_t count,std::size_t unit,int target) {
        if(count>limits.maxOutputBytes/unit||binary>limits.maxOutputBytes-count*unit)throw Error("GLB output budget exceeded");
        binary=align(binary);views.push_back({binary,count*unit,target});binary+=count*unit;return views.size()-1;
    };
    const auto count=a.positions.size();
    const auto positionView=add(count,12,34962);
    const bool hasNormal=!a.normals.empty(),hasColor=!a.colors.empty()&&!texture;
    const auto normalView=hasNormal?add(count,12,34962):0;
    const auto colorView=hasColor?add(count,16,34962):0;
    const auto uvView=texture?add(count,8,34962):0;
    const auto indexView=surface?add(surface->indices.size(),4,34963):0;
    const auto imageView=texture?add(texture->png.size(),1,0):0;
    binary=align(binary);
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::setprecision(9);
    auto first=toGltf(a.positions[0]);Bounds bounds{first,first};
    for(auto v:a.positions) {const auto p=toGltf(v);bounds.minimum={std::min(bounds.minimum.x,p.x),std::min(bounds.minimum.y,p.y),std::min(bounds.minimum.z,p.z)};bounds.maximum={std::max(bounds.maximum.x,p.x),std::max(bounds.maximum.y,p.y),std::max(bounds.maximum.z,p.z)};}
    s<<"{\"asset\":{\"version\":\"2.0\",\"generator\":\"RealityForge canonical GLB v1\"},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0}],\"extras\":{\"schemaVersion\":1,\"representation\":"<<quote(representationName(a.representation))<<",\"units\":"<<quote(a.units==Units::Meters?"meters":"arbitrary")<<",\"coordinateTransform\":\"x,-y,-z\",\"assetId\":"<<quote(a.provenance.assetId)<<",\"parentId\":"<<quote(a.provenance.parentId)<<",\"sourceSha256\":"<<quote(a.provenance.sourceSha256)<<",\"algorithmVersion\":"<<quote(a.provenance.algorithmVersion)<<"},\"buffers\":[{\"byteLength\":"<<binary<<"}],\"bufferViews\":[";
    for(std::size_t i=0;i<views.size();++i) {if(i)s<<',';const auto v=views[i];s<<"{\"buffer\":0,\"byteOffset\":"<<v.offset<<",\"byteLength\":"<<v.size;if(v.target)s<<",\"target\":"<<v.target;s<<'}';}
    s<<"],\"accessors\":[{\"bufferView\":"<<positionView<<",\"componentType\":5126,\"count\":"<<count<<",\"type\":\"VEC3\",\"min\":["<<static_cast<float>(bounds.minimum.x)<<','<<static_cast<float>(bounds.minimum.y)<<','<<static_cast<float>(bounds.minimum.z)<<"],\"max\":["<<static_cast<float>(bounds.maximum.x)<<','<<static_cast<float>(bounds.maximum.y)<<','<<static_cast<float>(bounds.maximum.z)<<"]}";
    int next=1,normalAccessor=-1,colorAccessor=-1,uvAccessor=-1,indexAccessor=-1;
    if(hasNormal) {normalAccessor=next++;s<<",{\"bufferView\":"<<normalView<<",\"componentType\":5126,\"count\":"<<count<<",\"type\":\"VEC3\"}";}
    if(hasColor) {colorAccessor=next++;s<<",{\"bufferView\":"<<colorView<<",\"componentType\":5126,\"count\":"<<count<<",\"type\":\"VEC4\"}";}
    if(texture) {uvAccessor=next++;s<<",{\"bufferView\":"<<uvView<<",\"componentType\":5126,\"count\":"<<count<<",\"type\":\"VEC2\"}";}
    if(surface) {indexAccessor=next++;s<<",{\"bufferView\":"<<indexView<<",\"componentType\":5125,\"count\":"<<surface->indices.size()<<",\"type\":\"SCALAR\"}";}
    s<<"],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0";
    if(hasNormal)s<<",\"NORMAL\":"<<normalAccessor;
    if(hasColor)s<<",\"COLOR_0\":"<<colorAccessor;
    if(texture)s<<",\"TEXCOORD_0\":"<<uvAccessor;
    s<<"},\"mode\":"<<(surface?4:0);if(surface)s<<",\"indices\":"<<indexAccessor;s<<",\"material\":0}]}],\"materials\":[{\"doubleSided\":true,\"pbrMetallicRoughness\":{\"metallicFactor\":0,\"roughnessFactor\":1";
    if(texture)s<<",\"baseColorTexture\":{\"index\":0}";
    s<<"},\"extensions\":{\"KHR_materials_unlit\":{}}";
    if(texture)s<<",\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5";
    s<<"}],\"extensionsUsed\":[\"KHR_materials_unlit\"]";
    if(texture)s<<",\"textures\":[{\"source\":0,\"sampler\":0}],\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":33071,\"wrapT\":33071}],\"images\":[{\"bufferView\":"<<imageView<<",\"mimeType\":\"image/png\"}]";
    s<<'}';auto json=s.str();json.resize(align(json.size()),' ');
    if(json.size()>262144||binary>std::numeric_limits<std::uint32_t>::max()-28-json.size()||28+json.size()+binary>limits.maxOutputBytes)throw Error("GLB output budget exceeded");
    checkCancelled(cancel);
    u32(out,0x46546c67);u32(out,2);u32(out,static_cast<std::uint32_t>(28+json.size()+binary));u32(out,static_cast<std::uint32_t>(json.size()));u32(out,0x4e4f534a);raw(out,json.data(),json.size());u32(out,static_cast<std::uint32_t>(binary));u32(out,0x004e4942);
    std::size_t emitted=0;
    auto pad=[&](std::size_t target) {while(emitted<target){const char zero=0;raw(out,&zero,1);++emitted;}};
    for(std::size_t i=0;i<count;++i) {if(i%1024==0)checkCancelled(cancel);auto v=toGltf(a.positions[i]);f32(out,v.x);f32(out,v.y);f32(out,v.z);}emitted=count*12;
    if(hasNormal) {pad(views[normalView].offset);for(std::size_t i=0;i<count;++i){if(i%1024==0)checkCancelled(cancel);auto v=toGltf(a.normals[i]);f32(out,v.x);f32(out,v.y);f32(out,v.z);}emitted+=count*12;}
    if(hasColor) {
        pad(views[colorView].offset);
        for(std::size_t i=0;i<count;++i) {
            if(i%1024==0)checkCancelled(cancel);
            for(auto channel:a.colors[i]) {const auto srgb=channel/255.0;f32(out,srgb<=.04045?srgb/12.92:std::pow((srgb+.055)/1.055,2.4));}
            f32(out,1.0);
        }
        emitted+=count*16;
    }
    if(texture) {pad(views[uvView].offset);for(std::size_t i=0;i<count;++i){if(i%1024==0)checkCancelled(cancel);f32(out,texture->uv[i].x);f32(out,texture->uv[i].y);}emitted+=count*8;}
    if(surface) {pad(views[indexView].offset);for(std::size_t i=0;i<surface->indices.size();++i){if(i%1024==0)checkCancelled(cancel);u32(out,surface->indices[i]);}emitted+=surface->indices.size()*4;}
    if(texture) {pad(views[imageView].offset);for(std::size_t i=0;i<texture->png.size();i+=65536){checkCancelled(cancel);raw(out,texture->png.data()+i,std::min(std::size_t(65536),texture->png.size()-i));}emitted+=texture->png.size();}
    pad(binary);checkCancelled(cancel);
}
class FdBuffer final:public std::streambuf {
    int fd_;
public:
    explicit FdBuffer(int fd):fd_(fd) {}
    std::streamsize xsputn(const char* p,std::streamsize count)override {std::streamsize at=0;while(at<count){auto n=::write(fd_,p+at,static_cast<std::size_t>(count-at));if(n<0&&errno==EINTR)continue;if(n<=0)break;at+=n;}return at;}
    int_type overflow(int_type c)override {if(traits_type::eq_int_type(c,traits_type::eof()))return traits_type::not_eof(c);char byte=traits_type::to_char_type(c);return xsputn(&byte,1)==1?c:traits_type::eof();}
};
template<class Asset> void file(const Asset& a,const std::filesystem::path& path,const Limits& limits,const Cancel& cancel) {
    validate(a,limits,cancel);
    auto name=path.string()+".XXXXXX.partial";std::vector<char> temporary(name.begin(),name.end());temporary.push_back(0);
    const int fd=::mkstemps(temporary.data(),8);if(fd<0)throw Error("Cannot create temporary GLB file");
    bool open=true;
    try {FdBuffer buffer(fd);std::ostream stream(&buffer);writeGlb(a,stream,limits,cancel);stream.flush();if(!stream||::fsync(fd)!=0)throw Error("Cannot flush GLB file");if(::close(fd)!=0){open=false;throw Error("Cannot close GLB file");}open=false;checkCancelled(cancel);std::filesystem::rename(temporary.data(),path);}
    catch(...) {if(open)::close(fd);::unlink(temporary.data());throw;}
}
}
void writeGlb(const PointAsset& a,std::ostream& s,const Limits& l,const Cancel& c){serialize(a,nullptr,nullptr,s,l,c);}
void writeGlb(const SurfaceAsset& a,std::ostream& s,const Limits& l,const Cancel& c){serialize(a.vertices,&a,nullptr,s,l,c);}
void writeGlb(const TexturedAsset& a,std::ostream& s,const Limits& l,const Cancel& c){serialize(a.surface.vertices,&a.surface,&a,s,l,c);}
void writeGlbFile(const PointAsset& a,const std::filesystem::path& p,const Limits& l,const Cancel& c){file(a,p,l,c);}
void writeGlbFile(const SurfaceAsset& a,const std::filesystem::path& p,const Limits& l,const Cancel& c){file(a,p,l,c);}
void writeGlbFile(const TexturedAsset& a,const std::filesystem::path& p,const Limits& l,const Cancel& c){file(a,p,l,c);}
}
