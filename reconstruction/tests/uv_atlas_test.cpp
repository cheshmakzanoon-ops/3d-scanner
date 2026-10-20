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
 const auto& x=a.charts[0];const auto& y=a.charts[1];
 assert(x.x+x.width<=y.x||y.x+y.width<=x.x||x.y+x.height<=y.y||y.y+y.height<=x.y);
 m.triangles[0].b=0;bool rejected=false;try{rf::layoutSurfaceCharts(m);}catch(const rf::Error&){rejected=true;}assert(rejected);
 m.triangles={{0,1,2,0},{1,3,2,0}};
 for(int budget:{2048,4096}){
  auto large=m;large.triangles.clear();const std::size_t faces=budget==2048?30000:80000;
  for(std::size_t f=0;f<faces;++f)large.triangles.push_back({0,1,2,0});
  rf::UVAtlasSettings settings;settings.edge=budget;
  const auto start=std::chrono::steady_clock::now();auto layout=rf::layoutSurfaceCharts(large,settings);
  assert(layout.charts.size()==faces&&layout.texelsPerUnit>0&&layout.rectangleUtilization<=1&&layout.surfaceUtilization>0);
  std::vector<const rf::UVChart*> ordered;for(const auto& c:layout.charts)ordered.push_back(&c);
  std::sort(ordered.begin(),ordered.end(),[](auto left,auto right){return left->y==right->y?left->x<right->x:left->y<right->y;});
  for(std::size_t f=1;f<ordered.size();++f){const auto& left=*ordered[f-1];const auto& right=*ordered[f];assert(left.y==right.y?left.x+left.width<=right.x:left.y+left.height<=right.y);}
  auto repeat=rf::layoutSurfaceCharts(large,settings);assert(layout.texelsPerUnit==repeat.texelsPerUnit);
  for(std::size_t f=0;f<faces;++f)assert(layout.charts[f].x==repeat.charts[f].x&&layout.charts[f].sourceFace==f);
  std::cout<<budget<<" profile faces="<<faces<<" density="<<layout.texelsPerUnit<<" rectangleUtilization="<<layout.rectangleUtilization<<" surfaceUtilization="<<layout.surfaceUtilization<<" layoutBytes="<<layout.charts.size()*sizeof(rf::UVChart)<<" seconds="<<std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<<'\n';
 }
 std::atomic_bool stop{true};bool cancelled=false;try{rf::cancellation::Scope scope(&stop);rf::layoutSurfaceCharts(m);}catch(const rf::cancellation::Cancelled&){cancelled=true;}assert(cancelled);

}
