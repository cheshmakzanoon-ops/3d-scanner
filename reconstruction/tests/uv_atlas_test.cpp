#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/uv_atlas.hpp"
#include <cassert>
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
}
