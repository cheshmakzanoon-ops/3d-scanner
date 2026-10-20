#pragma once
#include <algorithm>
#include <cstddef>
#include <cmath>
#include <cstdint>
#include <limits>
#include <vector>
#include <stdexcept>
namespace rf::tiles {
inline constexpr unsigned EXECUTION_VERSION=1;
struct Tile {int x0{},y0{},x1{},y1{},haloX0{},haloY0{},haloX1{},haloY1{};};
inline std::vector<Tile> plan(int width,int height,int origin,int stride,int halo,int edge){
 if(width<1||height<1||width>640||height>640||origin<0||stride<1||stride>8||halo<0||halo>16||edge<8||edge>640)throw std::invalid_argument("Invalid bounded tile plan");
 std::vector<Tile> out;const int extent=std::max(stride,edge/stride*stride);
 for(int y=origin;y<height-origin-1;y+=extent)for(int x=origin;x<width-origin-1;x+=extent){
  int x1=std::min(width-origin-1,x+extent),y1=std::min(height-origin-1,y+extent);
  out.push_back({x,y,x1,y1,std::max(0,x-halo),std::max(0,y-halo),std::min(width,x1+halo),std::min(height,y1+halo)});
 }
 return out;
}
struct Admission {std::size_t inputBytes{},mapBytes{},sampleBytes{},scratchBytes{},totalBytes{};};
inline Admission admit(std::size_t views,std::size_t pixels,std::size_t samples,std::size_t pointBudget,std::size_t planes,std::size_t budget,bool referenceMode){
 if(views<2||views>8||pixels>320000||samples>100000||pointBudget>50000||planes>128||budget==0)throw std::invalid_argument("Invalid depth memory admission");
 // Caller-owned RGBA still counts. Include retained maps, provenance, sort
 // pointers, voxel hash conservative overhead, serialized checkpoint payload,
 // costs and vector capacities. No defaults are raised on synthetic evidence.
 Admission a;a.inputBytes=views*pixels*8;a.mapBytes=views*pixels*8;
 a.sampleBytes=samples*128+pointBudget*96; // includes additive observed normal evidence
 a.scratchBytes=pixels*16+planes*16+(referenceMode?4*pixels*sizeof(float):0)+64*1024;
 a.totalBytes=a.inputBytes+a.mapBytes+a.sampleBytes+a.scratchBytes;
 if(a.totalBytes>budget)throw std::invalid_argument("Depth working-set byte budget exceeded");
 return a;
}
inline bool voxelCoordinate(double coordinate,double voxel,long long& out){
 if(!std::isfinite(coordinate)||!std::isfinite(voxel)||voxel<=0)return false;
 const long double q=std::floor(static_cast<long double>(coordinate)/voxel);
 if(q<static_cast<long double>(std::numeric_limits<long long>::min())||q>=-static_cast<long double>(std::numeric_limits<long long>::min()))return false;
 out=static_cast<long long>(q);return true;
}
}
