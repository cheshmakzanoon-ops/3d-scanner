#include "realityforge/obj_export.hpp"
#include <array>
#include <fstream>
#include <iterator>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <streambuf>
#include <vector>

using namespace rf;
using namespace rf::asset;
namespace {
void require(bool value,const char* message) { if(!value)throw std::runtime_error(message); }
template<class F> void rejects(F fn) { bool rejected=false;try { fn(); }catch(const Error&){rejected=true;}require(rejected,"expected export rejection"); }
struct ShortBuffer:std::streambuf {
    std::streamsize xsputn(const char*,std::streamsize n)override{return n? n-1:0;}
    int overflow(int)override{return traits_type::eof();}
};
struct BadFlush:std::stringbuf { int sync()override{return -1;} };
struct ObservingBuffer:std::stringbuf {
    bool written=false;
    std::streamsize xsputn(const char* s,std::streamsize n)override{written=true;return std::stringbuf::xsputn(s,n);}
};
}
int main(int argc,char**argv) {
    require(argc==3,"arguments: directory checker.png");
    const std::string directory=argv[1];
    std::ifstream pngInput(argv[2],std::ios::binary);
    std::vector<std::uint8_t> png{std::istreambuf_iterator<char>(pngInput),{}};
    std::array<Vec3,4> positions{{{0,0,0},{2,0,0},{0,3,0},{0,0,4}}};
    const auto original=positions;
    std::array<Vec3,4> normals{{{0,0,1},{0,0,1},{0,0,1},{0,1,0}}};
    std::array<std::uint32_t,6> indices{{0,1,2,0,3,1}};
    std::array<Vec2,4> uv{{{0,0},{1,0},{0,1},{1,1}}};
    PointAsset points;points.positions=positions;points.normals=normals;
    points.representation=Representation::PhotographicSurface;points.provenance.assetId="test\nmtllib /secret";
    SurfaceAsset surface{points,indices};
    TexturedAsset textured{surface,uv,png,2,2};
    const auto descriptor=describeObj(textured,{"asymmetric"});
    require(descriptor.files.size()==3,"companion descriptor");
    std::ofstream obj(directory+"/asymmetric.obj",std::ios::binary);
    std::ofstream mtl(directory+"/asymmetric.mtl",std::ios::binary);
    std::ofstream texture(directory+"/asymmetric.png",std::ios::binary);
    auto written=writeObj(textured,{obj,&mtl,&texture},{"asymmetric"});
    require(written.totalBytes==descriptor.totalBytes,"exact size");
    require(obj.tellp()==static_cast<std::streamoff>(descriptor.files[0].bytes),"exact OBJ size");
    require(positions[1].x==original[1].x&&positions[2].y==original[2].y,"master unchanged");
    points.representation=Representation::DensePoints;
    std::ofstream pointFile(directory+"/points.obj",std::ios::binary);
    writeObj(points,{pointFile});
    std::ostringstream empty;
    rejects([&]{describeObj(textured,{"../outside"});});
    rejects([&]{describeObj(textured,{"cOn"});});
    rejects([&]{describeObj(textured,{"LPT1"});});
    rejects([&]{writeObj(textured,{empty});});
    rejects([&]{writeObj(textured,{empty,&empty,&empty});});
    Limits tiny;tiny.maxOutputBytes=8;
    rejects([&]{writeObj(points,{empty},{},tiny);});require(empty.str().empty(),"preflight cannot emit bytes");
    rejects([&]{writeObj(points,{empty},{},{},[]{return true;});});
    require(empty.str().empty(),"cancelled preflight cannot emit bytes");
    ShortBuffer shortBuffer;std::ostream shortStream(&shortBuffer);
    rejects([&]{writeObj(points,{shortStream});});
    ShortBuffer shortTextureBuffer;std::ostream shortTexture(&shortTextureBuffer);
    std::ostringstream stagedObj,stagedMtl;
    rejects([&]{writeObj(textured,{stagedObj,&stagedMtl,&shortTexture});});
    BadFlush badBuffer;std::ostream badFlush(&badBuffer);
    rejects([&]{writeObj(points,{badFlush});});
    ObservingBuffer observedBuffer;std::ostream observed(&observedBuffer);
    rejects([&]{writeObj(points,{observed},{},{},[&]{return observedBuffer.written;});});
    require(!observedBuffer.str().empty(),"cancellation while writing must fail the staged export");
    points.positions=std::span<const Vec3>(positions.data(),2);
    rejects([&]{describeObj(points);}); // Normals must match the vertex count.
    points.positions=positions;
    positions[0].x=std::numeric_limits<double>::infinity();
    rejects([&]{describeObj(points);});positions=original;
    points.representation=Representation::ObservedSurface;
    indices[0]=99;
    rejects([&]{describeObj(SurfaceAsset{points,indices});});
    return 0;
}
