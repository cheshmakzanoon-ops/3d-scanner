#include "realityforge/ply_export.hpp"
#include <array>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <streambuf>

using namespace rf;
using namespace rf::asset;
namespace {
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
template<class F>void rejects(F fn){bool failed=false;try{fn();}catch(const Error&){failed=true;}require(failed,"expected rejection");}
struct ShortBuffer:std::streambuf{std::streamsize xsputn(const char*,std::streamsize n)override{return n?n-1:0;}};
struct BadFlush:std::stringbuf{int sync()override{return -1;}};
struct ObservingBuffer:std::stringbuf{bool written=false;std::streamsize xsputn(const char*s,std::streamsize n)override{written=true;return std::stringbuf::xsputn(s,n);}};
}
int main(int argc,char**argv){
    require(argc==2,"arguments: output directory");const std::string directory=argv[1];
    std::array<Vec3,3> positions{{{.125,1,2},{3,0,1},{0,4,3}}};const auto master=positions;
    std::array<Vec3,3> normals{{{0,0,1},{0,1,0},{1,0,0}}};
    std::array<std::array<std::uint8_t,3>,3> colors{{{255,0,2},{3,240,4},{5,6,220}}};
    std::array<float,3> confidence{{.25f,.5f,.75f}};
    std::array<std::uint32_t,3> support{{2,3,9}},faces{{0,1,2}};
    PointAsset points;points.positions=positions;points.normals=normals;points.colors=colors;points.confidence=confidence;points.supportingViews=support;
    points.representation=Representation::DensePoints;points.provenance.assetId="actual asset\nnot a header";
    points.provenance.sourceSha256=std::string(64,'a');points.provenance.algorithmVersion="test-v1";
    {
        std::ofstream output(directory+"/dense.ply",std::ios::binary);
        auto descriptor=writePly(points,output);
        require(descriptor.bytes==static_cast<std::size_t>(output.tellp()),"exact byte estimate");
        require(descriptor.vertexStride==59&&descriptor.faces==0,"binary layout");
    }
    points.representation=Representation::SparsePoints;
    {std::ofstream output(directory+"/sparse.ply",std::ios::binary);writePly(points,output,{PlyEncoding::Ascii});}
    points.representation=Representation::PhotographicSurface;
    SurfaceAsset surface{points,faces};
    {std::ofstream output(directory+"/surface.ply",std::ios::binary);auto report=writePly(surface,output);require(report.faces==1&&report.representation==Representation::ObservedSurface,"no photo claim in PLY");}
    {std::ofstream output(directory+"/surface-ascii.ply",std::ios::binary);writePly(surface,output,{PlyEncoding::Ascii});}
    require(positions[0].x==master[0].x&&positions[1].z==master[1].z,"master unchanged");
    std::ostringstream empty;
    rejects([&]{writePly(points,empty);});
    points.representation=Representation::DensePoints;
    Limits tiny;tiny.maxOutputBytes=10;
    rejects([&]{writePly(points,empty,{},tiny);});require(empty.str().empty(),"budget preflight");
    rejects([&]{writePly(points,empty,{},{},[]{return true;});});require(empty.str().empty(),"cancelled preflight");
    ShortBuffer shortBuffer;std::ostream shortStream(&shortBuffer);rejects([&]{writePly(points,shortStream);});
    BadFlush badBuffer;std::ostream badFlush(&badBuffer);rejects([&]{writePly(points,badFlush);});
    ObservingBuffer observedBuffer;std::ostream observed(&observedBuffer);
    rejects([&]{writePly(points,observed,{},{},[&]{return observedBuffer.written;});});
    require(!observedBuffer.str().empty(),"cancellation during staged output cannot succeed");
    positions[0].x=std::numeric_limits<double>::quiet_NaN();rejects([&]{describePly(points);});positions=master;
    confidence[1]=2;rejects([&]{describePly(points);});confidence[1]=.5;
    points.colors=std::span(colors.data(),2);rejects([&]{describePly(points);});points.colors=colors;
    rejects([&]{describePly(points,{static_cast<PlyEncoding>(99)});});
    faces[2]=99;rejects([&]{describePly(surface);});
    return 0;
}
