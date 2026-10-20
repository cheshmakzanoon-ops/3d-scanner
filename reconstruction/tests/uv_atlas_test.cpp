#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/uv_atlas.hpp"
#include <cassert>
#include <iostream>
#include <chrono>
int main(){
 rf::SurfaceMesh m;
 for(auto p:{rf::Vec3{0,0,0},rf::Vec3{1,0,0},rf::Vec3{0,1,0},rf::Vec3{1,1,0}}){rf::SurfaceVertex v;v.xyz=p;m.vertices.push_back(v);}
 m.triangles={{0,1,2,0},{1,3,2,0}};
 auto a=rf::layoutSurfaceCharts(m);auto b=rf::layoutSurfaceCharts(m);
 assert(a.charts.size()==2&&a.texelsPerUnit==b.texelsPerUnit);
 for(const auto& c:a.charts){
  auto uv=rf::chartTexel(c,{.2,.3,.5});
  assert(uv.u>0&&uv.v>0&&uv.u<1&&uv.v<1);
  const auto du=c.uv[1].u-c.uv[0].u,dv=c.uv[1].v-c.uv[0].v;
  assert(std::abs(std::hypot(du,dv)*a.width-a.texelsPerUnit)<1e-8);
 }
 assert(a.groups.size()==1&&a.charts[0].chartId==a.charts[1].chartId);
 assert(std::abs(a.charts[0].worldWidth*a.charts[0].worldHeight-1.)<1e-12);
 m.triangles[0].b=0;bool rejected=false;try{rf::layoutSurfaceCharts(m);}catch(const rf::Error&){rejected=true;}assert(rejected);
 m.triangles={{0,1,2,0},{1,3,2,0}};
 for(int budget:{2048,4096}){
  rf::SurfaceMesh large;const int columns=budget==2048?150:200,rows=budget==2048?100:200;
  for(int y=0;y<=rows;++y)for(int x=0;x<=columns;++x){rf::SurfaceVertex v;v.xyz={static_cast<double>(x),static_cast<double>(y),0};large.vertices.push_back(v);}
  for(int y=0;y<rows;++y)for(int x=0;x<columns;++x){const auto a=static_cast<std::uint32_t>(y*(columns+1)+x),b=a+1,c=a+columns+1,d=c+1;large.triangles.push_back({a,b,c,0});large.triangles.push_back({b,d,c,0});}
  const std::size_t faces=large.triangles.size();
  rf::UVAtlasSettings settings;settings.edge=budget;
  const auto start=std::chrono::steady_clock::now();auto layout=rf::layoutSurfaceCharts(large,settings);
  assert(layout.charts.size()==faces&&layout.groups.size()<faces/2&&layout.texelsPerUnit>0&&layout.rectangleUtilization<=1&&layout.surfaceUtilization>0);
  std::vector<const rf::UVChartGroup*> ordered;for(const auto& c:layout.groups)ordered.push_back(&c);
  std::sort(ordered.begin(),ordered.end(),[](auto left,auto right){return left->y==right->y?left->x<right->x:left->y<right->y;});
  for(std::size_t f=1;f<ordered.size();++f){const auto& left=*ordered[f-1];const auto& right=*ordered[f];assert(left.y==right.y?left.x+left.width<=right.x:left.y+left.height<=right.y);}
  auto perFaceSettings=settings;perFaceSettings.maxChartFaces=1;
  auto perFace=rf::layoutSurfaceCharts(large,perFaceSettings);
  assert(layout.texelsPerUnit>perFace.texelsPerUnit&&layout.surfaceUtilization>perFace.surfaceUtilization);
  const double usefulPhotographicPixels=layout.surfaceUtilization*budget*budget;
  const double controlledWholePhotoPixels=.25*budget*budget; // independently declared object occupancy in synthetic whole-photo baseline
  assert(usefulPhotographicPixels>controlledWholePhotoPixels);
  auto repeat=rf::layoutSurfaceCharts(large,settings);assert(layout.texelsPerUnit==repeat.texelsPerUnit);
  for(std::size_t f=0;f<faces;++f)assert(layout.charts[f].x==repeat.charts[f].x&&layout.charts[f].sourceFace==f);
  std::cout<<budget<<" profile faces="<<faces<<" groups="<<layout.groups.size()<<" perFaceDensity="<<perFace.texelsPerUnit<<" wholePhotoUsefulPixels="<<controlledWholePhotoPixels<<" compactUsefulPixels="<<usefulPhotographicPixels<<" density="<<layout.texelsPerUnit<<" rectangleUtilization="<<layout.rectangleUtilization<<" surfaceUtilization="<<layout.surfaceUtilization<<" layoutBytes="<<layout.charts.size()*sizeof(rf::UVChart)<<" seconds="<<std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<<'\n';
 }
 std::atomic_bool stop{true};bool cancelled=false;try{rf::cancellation::Scope scope(&stop);rf::layoutSurfaceCharts(m);}catch(const rf::cancellation::Cancelled&){cancelled=true;}assert(cancelled);

}
