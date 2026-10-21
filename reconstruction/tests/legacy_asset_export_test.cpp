#include "realityforge/legacy_asset_export.hpp"
#include <cassert>
#include <bit>
#include <cstdint>
#include <sstream>
#include <string>
using namespace rf::asset;
const std::string fixture="ply\nformat ascii 1.0\ncomment RealityForge observed open surface; arbitrary scale; no UV textures\nelement vertex 3\nproperty float x\nproperty float y\nproperty float z\nproperty float nx\nproperty float ny\nproperty float nz\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nelement face 1\nproperty list uchar int vertex_indices\nend_header\n1 2 3 0 0 1 255 0 0\n3 2 3 0 0 1 0 255 0\n1 5 3 0 0 1 0 0 255\n3 0 1 2\n";
std::string encode(const std::string& input,int format=0,const Cancel& cancel={}){
    std::istringstream source(input);std::ostringstream out;exportLegacySurface(source,out,format,std::string(64,'a'),cancel);return out.str();}
template<class F>void fails(F f){bool failed=false;try{f();}catch(const rf::Error&){failed=true;}assert(failed);}
int main(){
    auto obj=encode(fixture);assert(obj.find("v 1 -2 -3")!=std::string::npos);assert(obj.find("f 1//1 2//2 3//3")!=std::string::npos);
    assert(encode(fixture)==obj);auto stl=encode(fixture,1);assert(stl.size()==134&&stl[80]==1);assert(stl.substr(0,80).find("arbitrary units")!=std::string::npos);
    auto scalar=[&](std::size_t offset){std::uint32_t word=0;for(int i=0;i<4;++i)word|=static_cast<std::uint32_t>(static_cast<unsigned char>(stl[offset+i]))<<(i*8);return std::bit_cast<float>(word);};
    assert(scalar(84)==0&&scalar(88)==0&&scalar(92)==-1);assert(scalar(96)==1&&scalar(100)==-2&&scalar(104)==-3);
    fails([&]{encode(fixture+"extra\n");});fails([&]{encode(fixture.substr(0,fixture.size()-1));});
    auto bad=fixture;bad.replace(bad.find("element vertex 3"),16,"element vertex 999999999");fails([&]{encode(bad);});
    bad=fixture;bad.replace(bad.find("3 0 1 2"),7,"3 0 1 9");fails([&]{encode(bad);});
    bad=fixture;bad.replace(bad.find("1 2 3 0"),1,"nan");fails([&]{encode(bad);});
    bad=fixture;bad.replace(bad.find("1 2 3 0"),1,std::string(1025,'1'));fails([&]{encode(bad);});
    fails([&]{encode(fixture,2);});fails([&]{encode(fixture,0,[]{return true;});});
    int count=0;fails([&]{encode(fixture,1,[&]{return ++count>20;});});
    std::istringstream input(fixture);std::ostringstream out;fails([&]{exportLegacySurface(input,out,0,"bad-hash");});
}
