#include "realityforge/glb_export.hpp"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <sstream>
#include <vector>
#include <zlib.h>
using namespace rf;using namespace rf::asset;
template<class F> void rejects(F f) {bool failed=false;try{f();}catch(const Error&){failed=true;}assert(failed);}
class ShortBuffer:public std::streambuf {std::streamsize xsputn(const char*,std::streamsize)override{return 0;}};
int main(int argc,char** argv) {
    assert(argc==3);const std::filesystem::path out=argv[1];std::filesystem::create_directories(out);
    std::vector<Vec3> positions{{-2,1,3},{4,1,3},{-2,5,3}};const auto original=positions;
    std::vector<Vec3> normals(3,Vec3{0,0,1});
    std::vector<std::array<std::uint8_t,3>> colors{{255,0,0},{128,64,32},{0,0,255}};
    std::vector<std::uint32_t> indices{0,1,2};std::vector<Vec2> uv{{0,0},{1,0},{0,1}};
    std::ifstream pngFile(argv[2],std::ios::binary);std::vector<std::uint8_t> png{std::istreambuf_iterator<char>(pngFile),{}};
    PointAsset p;p.positions=positions;p.colors=colors;p.normals=normals;p.provenance.assetId="fixture";p.provenance.algorithmVersion="synthetic-1";
    writeGlbFile(p,out/"sparse.glb");p.representation=Representation::DensePoints;writeGlbFile(p,out/"dense.glb");
    p.units=Units::Meters;writeGlbFile(p,out/"metric.glb");p.units=Units::Arbitrary;
    p.representation=Representation::ObservedSurface;SurfaceAsset s{p,indices};writeGlbFile(s,out/"surface.glb");
    s.vertices.representation=Representation::PhotographicSurface;TexturedAsset t{s,uv,png,2,2};writeGlbFile(t,out/"texture.glb");
    assert(positions[0].x==original[0].x&&positions[1].y==original[1].y);
    auto bad=p;bad.positions={};rejects([&]{validate(bad);});bad=p;bad.colors=std::span(colors).first(1);rejects([&]{validate(bad);});
    positions[0].x=std::numeric_limits<double>::infinity();rejects([&]{validate(t);});positions[0].x=-2;
    indices[2]=3;rejects([&]{validate(t);});indices[2]=1;rejects([&]{validate(t);});indices[2]=2;
    uv[0].y=-1;rejects([&]{validate(t);});uv[0].y=0;
    normals[0].z=0;rejects([&]{validate(t);});normals[0].z=1;
    auto invalidPng=t;std::vector<std::uint8_t> corrupt=png;corrupt.back()^=1;invalidPng.png=corrupt;rejects([&]{validate(invalidPng);});
    // Correct PNG CRC cannot conceal invalid zlib or a scanline expansion bomb.
    auto malformedPng=png;
    for(std::size_t at=8;at+12<malformedPng.size();) {
        const std::size_t n=(std::uint32_t(malformedPng[at])<<24)|(std::uint32_t(malformedPng[at+1])<<16)|(std::uint32_t(malformedPng[at+2])<<8)|malformedPng[at+3];
        if(std::string(reinterpret_cast<const char*>(malformedPng.data()+at+4),4)=="IDAT") {
            malformedPng[at+8]^=255;
            const auto crc=::crc32(0,malformedPng.data()+at+4,static_cast<uInt>(n+4));
            for(int i=0;i<4;++i)malformedPng[at+8+n+i]=std::uint8_t(crc>>(24-8*i));
            break;
        }
        at+=n+12;
    }
    invalidPng.png=malformedPng;rejects([&]{validate(invalidPng);});
    auto expansion=t;auto wrongDimensions=png;
    wrongDimensions[20]=wrongDimensions[21]=wrongDimensions[22]=0;wrongDimensions[23]=1;
    const auto headerCrc=::crc32(0,wrongDimensions.data()+12,17);
    for(int i=0;i<4;++i)wrongDimensions[29+i]=std::uint8_t(headerCrc>>(24-8*i));
    expansion.imageHeight=1;expansion.png=wrongDimensions;rejects([&]{validate(expansion);});
    auto invalidChunk=t;auto reservedChunk=png;
    std::array<std::uint8_t,12> chunk{0,0,0,0,'t','e','s','t',0,0,0,0};
    const auto chunkCrc=::crc32(0,chunk.data()+4,4);
    for(int i=0;i<4;++i)chunk[8+i]=std::uint8_t(chunkCrc>>(24-8*i));
    reservedChunk.insert(reservedChunk.begin()+33,chunk.begin(),chunk.end());invalidChunk.png=reservedChunk;
    rejects([&]{validate(invalidChunk);});
    Limits tiny;tiny.maxOutputBytes=64;std::ostringstream untouched;rejects([&]{writeGlb(t,untouched,tiny);});assert(untouched.str().empty());
    std::ofstream(out/"preserve.glb")<<"original";rejects([&]{writeGlbFile(t,out/"preserve.glb",{},[]{return true;});});
    std::ifstream preserved(out/"preserve.glb");std::string text;preserved>>text;assert(text=="original");
    int polls=0;rejects([&]{std::ostringstream target;writeGlb(t,target,{},[&]{return ++polls>8;});});
    int dryPolls=0;std::ostringstream dryRun;writeGlb(t,dryRun,{},[&]{++dryPolls;return false;});
    int filePolls=0;rejects([&]{writeGlbFile(t,out/"preserve.glb",{},[&]{return ++filePolls>dryPolls;});});
    std::ifstream latePreserved(out/"preserve.glb");latePreserved>>text;assert(text=="original");
    for(const auto& f:std::filesystem::directory_iterator(out))assert(f.path().extension()!=".partial");
    ShortBuffer shortBuffer;std::ostream shortStream(&shortBuffer);rejects([&]{writeGlb(t,shortStream);});
    p.representation=Representation::DensePoints;rejects([&]{writeGlb(s,shortStream);});
    auto over=t;Limits small;small.maxVertices=2;rejects([&]{validate(over,small);});
    return 0;
}
