#include "realityforge/uv_atlas.hpp"
#include <iomanip>
#include <iostream>
int main(){
 rf::SurfaceMesh mesh;
 for(int i=0;i<48;++i){
  const double x=4.*i,w=.43+(i%7)*.18,h=.39+(i%5)*.21;
  const auto start=static_cast<std::uint32_t>(mesh.vertices.size());
  for(auto p:{rf::Vec3{x,0,0},rf::Vec3{x+w,0,0},rf::Vec3{x,h,0},rf::Vec3{x+w,h,0}}){rf::SurfaceVertex v;v.xyz=p;mesh.vertices.push_back(v);}
  mesh.triangles.push_back({start,start+1,start+2,0});mesh.triangles.push_back({start+1,start+3,start+2,0});
 }
 std::cout<<std::setprecision(17)<<'[';bool first=true;
 for(int edge:{2048,4096})for(int padding:{2,3,5,11,16}){
  rf::UVAtlasSettings s;s.edge=edge;s.padding=padding;s.requestedTexelsPerUnit=113.7;
  const auto layout=rf::layoutSurfaceCharts(mesh,s);
  if(!first){std::cout<<',';}
  first=false;
  std::cout<<"{\"edge\":"<<edge<<",\"padding\":"<<padding<<",\"groups\":[";
  bool f=true;for(const auto& g:layout.groups){if(!f)std::cout<<',';f=false;std::cout<<'['<<g.x<<','<<g.y<<','<<g.width<<','<<g.height<<']';}
  std::cout<<"],\"charts\":[";f=true;
  for(const auto& c:layout.charts){if(!f)std::cout<<',';f=false;std::cout<<"{\"group\":"<<c.chartId<<",\"uv\":[";for(int k=0;k<3;++k){if(k)std::cout<<',';std::cout<<'['<<c.uv[k].u<<','<<c.uv[k].v<<']';}std::cout<<"]}";}
  std::cout<<"]}";
 }
 std::cout<<"]\n";
}
