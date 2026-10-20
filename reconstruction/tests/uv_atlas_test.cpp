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
 rf::SurfaceMesh cube;
 for(auto point:{rf::Vec3{0,0,0},rf::Vec3{1,0,0},rf::Vec3{1,1,0},rf::Vec3{0,1,0},rf::Vec3{0,0,1},rf::Vec3{1,0,1},rf::Vec3{1,1,1},rf::Vec3{0,1,1}}){rf::SurfaceVertex v;v.xyz=point;cube.vertices.push_back(v);}
 cube.triangles={{0,2,1,0},{0,3,2,0},{4,5,6,0},{4,6,7,0},{0,1,5,0},{0,5,4,0},{1,2,6,0},{1,6,5,0},{2,3,7,0},{2,7,6,0},{3,0,4,0},{3,4,7,0}};
 const auto cubeLayout=rf::layoutSurfaceCharts(cube);assert(cubeLayout.groups.size()==6);
 for(std::size_t face=0;face<cube.triangles.size();++face){
  const auto& chart=cubeLayout.charts[face];const auto t=cube.triangles[face];const std::array<std::uint32_t,3> ids{t.a,t.b,t.c};
  for(int k=0;k<3;++k){const auto worldA=cube.vertices[ids[k]].xyz,worldB=cube.vertices[ids[(k+1)%3]].xyz;const double distance=std::hypot(worldA.x-worldB.x,worldA.y-worldB.y,worldA.z-worldB.z);const double projected=std::hypot(chart.uv[k].u-chart.uv[(k+1)%3].u,chart.uv[k].v-chart.uv[(k+1)%3].v)*cubeLayout.width/cubeLayout.texelsPerUnit;assert(std::abs(projected-distance)<1e-8);}
 }
 cube.triangles.erase(cube.triangles.begin(),cube.triangles.begin()+2);const auto open=rf::layoutSurfaceCharts(cube);assert(open.groups.size()==5&&open.charts.size()==10);
 auto thin=m;thin.vertices[2].xyz={1,1e-5,0};thin.triangles={{0,1,2,0}};const auto thinLayout=rf::layoutSurfaceCharts(thin);const auto& t=thinLayout.charts[0];
 const double oriented=(t.uv[1].u-t.uv[0].u)*(t.uv[2].v-t.uv[0].v)-(t.uv[1].v-t.uv[0].v)*(t.uv[2].u-t.uv[0].u);assert(oriented>0);
 auto curved=m;curved.vertices[3].xyz.z=.2;assert(rf::layoutSurfaceCharts(curved).groups.size()==2); // curved faces remain individual zero-stretch charts
 auto gentle=m;gentle.vertices[3].xyz.z=.02;
 rf::UVAtlasSettings bounded;bounded.maximumProjectionStretch=.02;
 auto curvedLayout=rf::layoutSurfaceCharts(gentle,bounded);assert(curvedLayout.groups.size()==1&&curvedLayout.maximumStretch<=.02);
 for(std::size_t face=0;face<gentle.triangles.size();++face){const auto tri=gentle.triangles[face];const std::array<std::uint32_t,3> ids{tri.a,tri.b,tri.c};const auto& chart=curvedLayout.charts[face];for(int k=0;k<3;++k){const auto p=gentle.vertices[ids[k]].xyz,q=gentle.vertices[ids[(k+1)%3]].xyz;const double distance=std::hypot(p.x-q.x,p.y-q.y,p.z-q.z),uvDistance=std::hypot(chart.local[k].u-chart.local[(k+1)%3].u,chart.local[k].v-chart.local[(k+1)%3].v);assert(distance/uvDistance<=1.02+1e-9);}}
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
