#include "realityforge/stl_export.hpp"
#include <array>
#include <fstream>
#include <limits>
#include <sstream>
#include <stdexcept>
#include <streambuf>

using namespace rf;
using namespace rf::asset;
namespace {
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
template<class F>void rejects(F fn){bool rejected=false;try{fn();}catch(const Error&){rejected=true;}require(rejected,"expected rejection");}
struct ShortBuffer:std::streambuf{std::streamsize xsputn(const char*,std::streamsize n)override{return n?n-1:0;}};
struct BadFlush:std::stringbuf{int sync()override{return -1;}};
struct ObservingBuffer:std::stringbuf{bool written=false;std::streamsize xsputn(const char*s,std::streamsize n)override{written=true;return std::stringbuf::xsputn(s,n);}};
}
int main(int argc,char**argv){
    require(argc==2,"arguments: output stl");
    std::array<Vec3,4> vertices{{{0,0,0},{2,0,0},{0,3,0},{0,0,4}}};
    const auto original=vertices;
    std::array<std::uint32_t,6> faces{{0,1,2,0,3,1}};
    PointAsset points;points.positions=vertices;points.representation=Representation::ObservedSurface;
    SurfaceAsset surface{points,faces};
    StlOptions options;options.scale=StlScale{StlScale::Basis::KnownReference,StlLengthUnit::Millimeters,.01,"known 20 mm edge",.01};
    std::ofstream output(argv[1],std::ios::binary);
    auto report=writeStl(surface,output,options);
    require(report.triangles==2&&report.outputBytes==184,"binary counts");
    require(report.boundaryEdges==4&&!report.closedOrientedByEdgeIncidence,"open diagnostics");
    require(!report.printabilityEstablished&&!report.selfIntersectionsChecked,"no print ready inference");
    require(report.outputUnitsPerAssetUnit==10&&report.hasMetricReference,"scale");
    require(vertices[1].x==original[1].x&&vertices[2].y==original[2].y,"source unchanged");
    std::ostringstream empty;
    rejects([&]{writeStl(surface,empty,{});});require(empty.str().empty(),"missing scale writes nothing");
    StlOptions metric;metric.scale=StlScale{StlScale::Basis::MetricAsset,StlLengthUnit::Millimeters,0,"",0};
    rejects([&]{describeStl(surface,metric);});
    points.units=Units::Meters;surface.vertices=points;
    require(describeStl(surface,metric).outputUnitsPerAssetUnit==1000,"metric scale not doubled");
    surface.vertices.units=Units::Arbitrary;
    options.scale->basis=StlScale::Basis::ArbitraryAcknowledged;
    require(!describeStl(surface,options).hasMetricReference,"arbitrary remains unmeasured");
    options.scale->metersPerAssetUnit=0;rejects([&]{writeStl(surface,empty,options);});options.scale->metersPerAssetUnit=.01;
    Limits tiny;tiny.maxOutputBytes=183;
    rejects([&]{writeStl(surface,empty,options,tiny);});require(empty.str().empty(),"budget preflight");
    rejects([&]{writeStl(surface,empty,options,{},[]{return true;});});
    require(empty.str().empty(),"cancellation preflight");
    ShortBuffer shortBuffer;std::ostream shortStream(&shortBuffer);
    rejects([&]{writeStl(surface,shortStream,options);});
    BadFlush badBuffer;std::ostream badFlush(&badBuffer);rejects([&]{writeStl(surface,badFlush,options);});
    ObservingBuffer observedBuffer;std::ostream observed(&observedBuffer);
    rejects([&]{writeStl(surface,observed,options,{},[&]{return observedBuffer.written;});});
    require(observedBuffer.str().size()==84,"cancellation after header cannot succeed");
    options.maxTopologyEdges=2;rejects([&]{describeStl(surface,options);});options.maxTopologyEdges=100;
    vertices[0].x=std::numeric_limits<double>::infinity();rejects([&]{describeStl(surface,options);});vertices=original;
    vertices[1]=vertices[0];rejects([&]{describeStl(surface,options);});vertices=original;
    vertices[0].x=1e100;rejects([&]{describeStl(surface,options);});vertices=original;
    faces[2]=99;rejects([&]{describeStl(surface,options);});
    return 0;
}
