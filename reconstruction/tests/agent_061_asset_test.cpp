#include "realityforge/glb_export.hpp"
#include <cassert>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <limits>
#include <sstream>
#include <vector>
using namespace rf;using namespace rf::asset;
template<class F> void rejects(F f) {bool failed=false;try{f();}catch(const Error&){failed=true;}assert(failed);}
class ShortBuffer:public std::streambuf {std::streamsize xsputn(const char*,std::streamsize)override{return 0;}};
int main(int argc,char** argv) {
    assert(argc==3);const std::filesystem::path out=argv[1];std::filesystem::create_directories(out);
    std::vector<Vec3> positions{{-2,1,3},{4,1,3},{-2,5,3}};const auto original=positions;
    std::vector<Vec3> normals(3,Vec3{0,0,1});
    std::vector<std::array<std::uint8_t,3>> colors{{255,0,0},{0,255,0},{0,0,255}};
    std::vector<std::uint32_t> indices{0,1,2};std::vector<Vec2> uv{{0,0},{1,0},{0,1}};
    std::ifstream pngFile(argv[2],std::ios::binary);std::vector<std::uint8_t> png{std::istreambuf_iterator<char>(pngFile),{}};
    PointAsset p;p.positions=positions;p.colors=colors;p.normals=normals;p.provenance.assetId="fixture";p.provenance.algorithmVersion="synthetic-1";
    writeGlbFile(p,out/"sparse.glb");p.representation=Representation::DensePoints;writeGlbFile(p,out/"dense.glb");
    p.representation=Representation::ObservedSurface;SurfaceAsset s{p,indices};writeGlbFile(s,out/"surface.glb");
    s.vertices.representation=Representation::PhotographicSurface;TexturedAsset t{s,uv,png,2,2};writeGlbFile(t,out/"texture.glb");
    assert(positions[0].x==original[0].x&&positions[1].y==original[1].y);
    auto bad=p;bad.positions={};rejects([&]{validate(bad);});bad=p;bad.colors=std::span(colors).first(1);rejects([&]{validate(bad);});
    positions[0].x=std::numeric_limits<double>::infinity();rejects([&]{validate(t);});positions[0].x=-2;
    indices[2]=3;rejects([&]{validate(t);});indices[2]=1;rejects([&]{validate(t);});indices[2]=2;
    uv[0].y=-1;rejects([&]{validate(t);});uv[0].y=0;
    normals[0].z=0;rejects([&]{validate(t);});normals[0].z=1;
    auto invalidPng=t;std::vector<std::uint8_t> corrupt=png;corrupt.back()^=1;invalidPng.png=corrupt;rejects([&]{validate(invalidPng);});
    Limits tiny;tiny.maxOutputBytes=64;std::ostringstream untouched;rejects([&]{writeGlb(t,untouched,tiny);});assert(untouched.str().empty());
    std::ofstream(out/"preserve.glb")<<"original";rejects([&]{writeGlbFile(t,out/"preserve.glb",{},[]{return true;});});
    std::ifstream preserved(out/"preserve.glb");std::string text;preserved>>text;assert(text=="original");
    int polls=0;rejects([&]{std::ostringstream target;writeGlb(t,target,{},[&]{return ++polls>8;});});
    ShortBuffer shortBuffer;std::ostream shortStream(&shortBuffer);rejects([&]{writeGlb(t,shortStream);});
    p.representation=Representation::DensePoints;rejects([&]{writeGlb(s,shortStream);});
    auto over=t;Limits small;small.maxVertices=2;rejects([&]{validate(over,small);});
    return 0;
}
