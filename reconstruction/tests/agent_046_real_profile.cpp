// Real-photo qualification driver. Published disparity is never accepted here.
#include "realityforge/dense.hpp"
#include <algorithm>
#include <fstream>
#include <iostream>
#include <stdexcept>

namespace {
template<class T> void read(std::istream& input,T* data,std::size_t count){
    if(!input.read(reinterpret_cast<char*>(data),static_cast<std::streamsize>(count*sizeof(T))))
        throw std::runtime_error("Truncated real-photo fixture");
}
std::vector<rf::DenseView> views(std::istream& input,int w,int h,int count){
    std::vector<double> cameras(static_cast<std::size_t>(count)*16);read(input,cameras.data(),cameras.size());
    std::vector<rf::DenseView> result;
    for(int i=0;i<count;++i){rf::DenseView v;v.width=w;v.height=h;const auto* row=cameras.data()+i*16;
        std::copy_n(row,9,v.pose.rotation.begin());v.pose.translation={row[9],row[10],row[11]};
        v.fx=row[12];v.fy=row[13];v.cx=row[14];v.cy=row[15];v.rgba.resize(static_cast<std::size_t>(w)*h*4);
        read(input,v.rgba.data(),v.rgba.size());result.push_back(std::move(v));}
    return result;
}
}
int main(int argc,char** argv){try{
    if(argc!=4)throw std::runtime_error("Expected fixture, profile, and exact input SHA256");
    std::ifstream input(argv[1],std::ios::binary);std::uint32_t shape[5];read(input,shape,5);
    const auto valid=[](std::uint32_t w,std::uint32_t h){return w>=32&&h>=32&&w<=640&&h<=640&&std::uint64_t(w)*h<=320000;};
    if(!valid(shape[0],shape[1])||!valid(shape[3],shape[4])||shape[2]<2||shape[2]>8)throw std::runtime_error("Fixture dimensions");
    auto full=views(input,shape[0],shape[1],shape[2]);auto coarse=views(input,shape[3],shape[4],shape[2]);
    if(input.peek()!=std::char_traits<char>::eof())throw std::runtime_error("Trailing fixture bytes");
    rf::DenseSettings settings;settings.nearDepth=6;settings.farDepth=80;settings.depthPlanes=64;settings.pixelStride=3;settings.discontinuityAwareConsistency=true;
    rf::DenseRefinementInputs refinement{{},coarse,argv[3]};
    const std::string mode=argv[2];if(mode!="strict"&&mode!="strict-refined"&&mode!="refined")throw std::runtime_error("Unknown profile");
    if(mode!="strict")settings.localRefinementIterations=6;
    if(mode=="strict-refined"){settings.normalRefinementIterations=3;settings.normalConsistency=true;}
    if(mode=="refined")settings.discontinuityAwareConsistency=false;
    std::cout<<rf::denseJson(rf::reconstructDense(full,settings,mode!="strict"?&refinement:nullptr));
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
