#include "realityforge/dense.hpp"
#include "realityforge/depth_tiles.hpp"
#include "realityforge/progress.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include <filesystem>
#include <fstream>
#include <chrono>
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
 // Completed tiles are independently reusable even after full-reference
 // files are removed, and a single rejected tile recomputes independently.
 const auto directory=std::filesystem::temp_directory_path()/("rf-tile-resume-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
 std::filesystem::create_directory(directory);settings.maxPoints=20000;
 {rf::depth_checkpoint::Scope cache(directory.string(),std::string(64,'a'));
  auto first=rf::reconstructDense(images,settings);assert(first.writtenDepthTiles==first.totalTiles);
  for(int camera=0;camera<3;++camera)std::filesystem::remove(directory/("ref-"+std::to_string(camera)+".bin"));
  auto replay=rf::reconstructDense(images,settings);assert(replay.resumedDepthTiles==first.totalTiles&&replay.recomputedDepthTiles==0);
  for(std::size_t i=0;i<first.maps.size();++i)assert(first.maps[i].depth==replay.maps[i].depth&&first.maps[i].confidence==replay.maps[i].confidence);
  for(int camera=0;camera<3;++camera)std::filesystem::remove(directory/("ref-"+std::to_string(camera)+".bin"));
  {std::ofstream corrupt(directory/"tile-0-1.bin",std::ios::app|std::ios::binary);corrupt.put('X');}
  auto repaired=rf::reconstructDense(images,settings);assert(repaired.resumedDepthTiles+1==first.totalTiles&&repaired.recomputedDepthTiles==1&&repaired.writtenDepthTiles==1&&repaired.rejectedDepthTiles==1);
 }
 std::filesystem::remove_all(directory);
 // Coarse images below are derived from input photographs by exact2x area
 // reduction, not rendered truth/depth. Sparse priors are explicitly labeled
 // synthetic measured-estimate inputs and never substitute for quality proof.
 auto coarse=images;for(auto& image:coarse){const auto source=image.rgba;int fullWidth=image.width,fullHeight=image.height;image.width/=2;image.height/=2;double sx=double(image.width)/fullWidth,sy=double(image.height)/fullHeight;image.fx*=sx;image.fy*=sy;image.cx=(image.cx+.5)*sx-.5;image.cy=(image.cy+.5)*sy-.5;image.rgba.assign(static_cast<std::size_t>(image.width)*image.height*4,0);for(int y=0;y<image.height;++y)for(int x=0;x<image.width;++x){for(int c=0;c<3;++c){unsigned sum=0;for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx)sum+=source[(static_cast<std::size_t>(2*y+dy)*fullWidth+2*x+dx)*4+c];image.rgba[(static_cast<std::size_t>(y)*image.width+x)*4+c]=static_cast<std::uint8_t>((sum+2)/4);}image.rgba[(static_cast<std::size_t>(y)*image.width+x)*4+3]=255;}}
 std::array<rf::DenseSparseDepthPrior,3> priors{{{0,8,8,3.9,.1,true},{0,12,8,5.2,.1,true},{0,8,12,6.1,.1,true}}};
 rf::DenseRefinementInputs inputs{priors,coarse,std::string(64,'b')};
 auto refined=rf::reconstructDense(images,settings,&inputs);assert(refined.coarseDepthMaps==images.size()&&refined.sparsePriorTiles>0&&refined.propagatedHypotheses>0);
 auto invalidCoarse=coarse;invalidCoarse[0].cx+=.5;inputs.coarseViews=invalidCoarse;bool geometryRejected=false;try{(void)rf::reconstructDense(images,settings,&inputs);}catch(const rf::Error&){geometryRejected=true;}assert(geometryRejected);inputs.coarseViews=coarse;
 auto packCameras=[](const auto& cameras){std::vector<double> rows;for(const auto& camera:cameras){rows.insert(rows.end(),camera.pose.rotation.begin(),camera.pose.rotation.end());rows.insert(rows.end(),{camera.pose.translation.x,camera.pose.translation.y,camera.pose.translation.z,camera.fx,camera.fy,camera.cx,camera.cy});}return rows;};
 auto packPixels=[](const auto& cameras){std::vector<std::uint8_t> pixels;for(const auto& camera:cameras)pixels.insert(pixels.end(),camera.rgba.begin(),camera.rgba.end());return pixels;};
 const auto fullRows=packCameras(images),coarseRows=packCameras(coarse);const auto fullPixels=packPixels(images),coarsePixels=packPixels(coarse);
 char* abi=rf_dense_refined_json(fullPixels.data(),images[0].width,images[0].height,fullRows.data(),images.size(),settings.nearDepth,settings.farDepth,settings.depthPlanes,settings.pixelStride,nullptr,0,coarsePixels.data(),coarse[0].width,coarse[0].height,coarseRows.data(),std::string(64,'b').c_str());
 assert(abi&&std::string(abi).find("error")==std::string::npos&&std::string(abi).find("dense_points")!=std::string::npos);rf_free(abi);
 settings.workingSetBudgetBytes=1;bool rejected=false;try{rf::reconstructDense(images,settings);}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
 auto* invalid=rf_dense_json(nullptr,32,32,nullptr,2,2,9,56,3);assert(invalid&&std::string(invalid).find("error")!=std::string::npos);rf_free(invalid);
 std::cout<<"tiled/reference float-bit equality, halo coverage, camera-order point equality, byte admission, voxel bounds and C ABI rejection passed\n";
}
