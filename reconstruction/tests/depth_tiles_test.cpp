#include "realityforge/dense.hpp"
#include "realityforge/depth_tiles.hpp"
#include "realityforge/progress.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <iostream>
#include <random>
#include <memory>
std::vector<rf::DenseView> fixture(int count=3,bool texture=true,bool occlude=false){
    constexpr int w=104,h=76;
    std::mt19937 engine(504);std::uniform_int_distribution<int> rgb(0,255);
    std::vector<std::array<int,3>> surface(256*256);
    for(auto& p:surface)p={rgb(engine),rgb(engine),rgb(engine)};
    auto sample=[&](double x,double y,int channel){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        int ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto pix=[&](int dx,int dy){return surface[(iy+dy)*256+ix+dx][channel];};
        return (1-a)*(1-b)*pix(0,0)+a*(1-b)*pix(1,0)+(1-a)*b*pix(0,1)+a*b*pix(1,1);
    };
    std::vector<rf::DenseView> views;
    for(int cam=0;cam<count;++cam){
        rf::DenseView view;view.width=w;view.height=h;
        view.fx=116;view.fy=116;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-cam*.55,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            std::size_t idx=(y*w+x)*4;
            double wx=(x-view.cx)/view.fx*5.0+cam*.55;
            double wy=(y-view.cy)/view.fy*5.0;
            for(int c=0;c<3;++c)view.rgba[idx+c]=texture?static_cast<std::uint8_t>(std::round(sample(wx,wy,c))):128;
            view.rgba[idx+3]=occlude && cam==2 && x>20&&x<70&&y>15&&y<60?0:255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
int main(){
 long long voxel=0;assert(!rf::tiles::voxelCoordinate(std::ldexp(1.,63),1,voxel));assert(!rf::tiles::voxelCoordinate(1e300,.003,voxel));assert(!rf::tiles::voxelCoordinate(-1e300,.003,voxel));assert(rf::tiles::voxelCoordinate(-.5,.1,voxel)&&voxel==-5);
 auto tiles=rf::tiles::plan(104,76,3,2,3,24);std::vector<int> visits(104*76);
 for(const auto& t:tiles){assert(t.haloX0<=t.x0&&t.haloY0<=t.y0&&t.haloX1>=t.x1&&t.haloY1>=t.y1);for(int y=t.y0;y<t.y1;y+=2)for(int x=t.x0;x<t.x1;x+=2)++visits[y*104+x];}
 for(int y=3;y<72;y+=2)for(int x=3;x<100;x+=2)assert(visits[y*104+x]==1);
 auto images=fixture();rf::DenseSettings settings;settings.nearDepth=3.1;settings.farDepth=7;settings.depthPlanes=40;settings.pixelStride=3;settings.tileEdge=24;
 auto state=std::make_shared<rf::progress::State>();rf::progress::Scope scope(state);
 auto tiled=rf::reconstructDense(images,settings);settings.referenceMode=true;auto reference=rf::reconstructDense(images,settings);
 assert(tiled.maps.size()==reference.maps.size()&&tiled.completedTiles==tiled.totalTiles);
 for(std::size_t i=0;i<tiled.maps.size();++i){assert(tiled.maps[i].depth==reference.maps[i].depth&&tiled.maps[i].confidence==reference.maps[i].confidence);}
 assert(tiled.peakLuminanceBytes==0&&reference.peakLuminanceBytes>0&&tiled.consistentPixels==reference.consistentPixels);
 settings.referenceMode=false;settings.maxPoints=25;auto ordered=rf::reconstructDense(images,settings);std::reverse(images.begin(),images.end());auto reversed=rf::reconstructDense(images,settings);
 assert(ordered.points.size()==reversed.points.size());for(std::size_t i=0;i<ordered.points.size();++i){assert(ordered.points[i].xyz.x==reversed.points[i].xyz.x&&ordered.points[i].xyz.y==reversed.points[i].xyz.y&&ordered.points[i].xyz.z==reversed.points[i].xyz.z);}
 settings.workingSetBudgetBytes=1;bool rejected=false;try{rf::reconstructDense(images,settings);}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
 auto* invalid=rf_dense_json(nullptr,32,32,nullptr,2,2,9,56,3);assert(invalid&&std::string(invalid).find("error")!=std::string::npos);rf_free(invalid);
 std::cout<<"tiled/reference float-bit equality, halo coverage, camera-order point equality, byte admission, voxel bounds and C ABI rejection passed\n";
}
