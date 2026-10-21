#include "realityforge/legacy_asset_export.hpp"
#include "realityforge/obj_export.hpp"
#include "realityforge/stl_export.hpp"
#include <charconv>
#include <cmath>
#include <istream>
#include <locale>
#include <sstream>
namespace rf::asset {
namespace {
constexpr std::size_t maxVertices=200'000,maxFaces=400'000,maxBytes=64*1024*1024;
class Reader {
    std::istream& input; const Cancel& cancel; std::size_t consumed=0;
public:
    Reader(std::istream& i,const Cancel& c):input(i),cancel(c){}
    std::string line(){checkCancelled(cancel);std::string result;char c;
        while(input.get(c)){if(++consumed>maxBytes)throw Error("PLY_BYTE_LIMIT");
            if(c=='\n'){if(!result.empty()&&result.back()=='\r')result.pop_back();return result;}
            if((c<32||c>126)&&c!='\r')throw Error("PLY_CHARACTER");
            if(result.size()>=1024)throw Error("PLY_LINE_LIMIT");
            result+=c;}
        throw Error("PLY_TRUNCATED");}
    void expect(const std::string& wanted){if(line()!=wanted)throw Error("PLY_SCHEMA");}
    std::size_t count(const std::string& prefix,std::size_t maximum){auto s=line();if(!s.starts_with(prefix))throw Error("PLY_SCHEMA");
        std::size_t n=0;auto start=s.data()+prefix.size();auto [end,error]=std::from_chars(start,s.data()+s.size(),n);
        if(error!=std::errc{}||end!=s.data()+s.size()||n==0||n>maximum)throw Error("PLY_COUNT_LIMIT");
        return n;}
};
}
SurfaceAsset LegacySurface::view(const std::string& hash)const{
    if(hash.size()!=64||hash.find_first_not_of("0123456789abcdef")!=std::string::npos)throw Error("SOURCE_IDENTITY");
    PointAsset p;p.positions=positions;p.normals=normals;p.colors=colors;p.representation=Representation::ObservedSurface;
    p.provenance={"legacy-observed-"+hash,"",hash,"rf-legacy-mesh-export-v1"};return {p,indices};
}
LegacySurface readLegacySurface(std::istream& input,const Cancel& cancel){
    Reader r(input,cancel);r.expect("ply");r.expect("format ascii 1.0");
    r.expect("comment RealityForge observed open surface; arbitrary scale; no UV textures");
    auto nv=r.count("element vertex ",maxVertices);
    for(auto name:{"x","y","z","nx","ny","nz"})r.expect(std::string("property float ")+name);
    for(auto name:{"red","green","blue"})r.expect(std::string("property uchar ")+name);
    auto nf=r.count("element face ",maxFaces);r.expect("property list uchar int vertex_indices");r.expect("end_header");
    LegacySurface m;m.positions.reserve(nv);m.normals.reserve(nv);m.colors.reserve(nv);m.indices.reserve(nf*3);
    for(std::size_t i=0;i<nv;++i){std::istringstream row(r.line());row.imbue(std::locale::classic());Vec3 p,n;int a,b,c;
        if(!(row>>p.x>>p.y>>p.z>>n.x>>n.y>>n.z>>a>>b>>c))throw Error("PLY_VERTEX");
        row>>std::ws;if(!row.eof()||a<0||a>255||b<0||b>255||c<0||c>255)throw Error("PLY_VERTEX");
        for(auto v:{p.x,p.y,p.z,n.x,n.y,n.z})if(!std::isfinite(v))throw Error("PLY_NONFINITE");
        m.positions.push_back(p);m.normals.push_back(n);m.colors.push_back({static_cast<std::uint8_t>(a),static_cast<std::uint8_t>(b),static_cast<std::uint8_t>(c)});}
    for(std::size_t i=0;i<nf;++i){std::istringstream row(r.line());row.imbue(std::locale::classic());long long size,a,b,c;
        if(!(row>>size>>a>>b>>c))throw Error("PLY_FACE");
        row>>std::ws;
        if(!row.eof()||size!=3||a<0||b<0||c<0||static_cast<std::size_t>(a)>=nv||static_cast<std::size_t>(b)>=nv||static_cast<std::size_t>(c)>=nv||a==b||b==c||a==c)throw Error("PLY_FACE");
        m.indices.insert(m.indices.end(),{static_cast<std::uint32_t>(a),static_cast<std::uint32_t>(b),static_cast<std::uint32_t>(c)});}
    checkCancelled(cancel);if(input.peek()!=std::char_traits<char>::eof()||input.bad())throw Error("PLY_TRAILING_DATA");return m;
}
void exportLegacySurface(std::istream& input,std::ostream& output,int format,const std::string& hash,const Cancel& cancel){
    if(format!=0&&format!=1)throw Error("FORMAT_UNAVAILABLE");
    auto owned=readLegacySurface(input,cancel);auto surface=owned.view(hash);
    Limits limits;limits.maxVertices=maxVertices;limits.maxTriangles=maxFaces;limits.maxOutputBytes=64*1024*1024;
    if(format==0){writeObj(surface,{output},{"model"},limits,cancel);return;}
    StlOptions options;options.headerLabel="RealityForge arbitrary units; no metric reference; printability not established";options.scale=StlScale{StlScale::Basis::ArbitraryAcknowledged,StlLengthUnit::Millimeters,0.001,"",0};
    // One original arbitrary unit remains one STL numeric unit. This is not a
    // measured millimeter conversion; user acknowledgement is enforced in Kotlin.
    writeStl(surface,output,options,limits,cancel);
}
}
