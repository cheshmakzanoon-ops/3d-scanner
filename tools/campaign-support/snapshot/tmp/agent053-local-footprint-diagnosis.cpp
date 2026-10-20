#include "realityforge/surface.hpp"
#include <iostream>
#include <set>
#include <stdexcept>
#include <vector>
std::pair<std::vector<rf::DenseView>,rf::DenseResult> scene(bool near){
 std::vector<rf::DenseView> views(2);rf::DenseResult dense;dense.maps.resize(2);
 for(int c=0;c<2;++c){
  auto& v=views[c];v.width=v.height=96;v.fx=v.fy=200;v.cx=v.cy=45;v.pose.rotation=rf::identity();v.pose.translation.x=-.2*c;
  auto& m=dense.maps[c];m.width=m.height=96;m.depth.assign(96*96,0);m.confidence.assign(96*96,0);
  for(int y=3;y<91;y+=3)for(int x=3;x<91;x+=3){
   if(x>57&&x<66)continue;
   bool far=x>=66;if(!far&&!near)continue;
   double z=far?50:5;m.depth[y*96+x]=z;m.confidence[y*96+x]=.95;
   rf::Vec3 xyz{(x-v.cx)*z/v.fx-v.pose.translation.x,(y-v.cy)*z/v.fy,z};
   dense.verifiedSamples.push_back({xyz,{150,140,130},.95f,2,static_cast<std::uint8_t>(c),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
  }
 }
 return{views,dense};
}
std::size_t farFusions(const rf::SurfaceMesh& m){
 std::set<std::uint32_t> seen;std::size_t count=0;
 for(const auto& o:m.observations)if(m.vertices[o.vertex].xyz.z>25&&!seen.insert(o.vertex).second)++count;
 return count;
}
int main(){auto[a,b]=scene(false);auto far=rf::fuseDepthSurfaces(a,b);auto[c,d]=scene(true);auto mixed=rf::fuseDepthSurfaces(c,d);std::cout<<"far-only fused "<<far.fusedObservations<<" retained-far "<<farFusions(far)<<"; unrelated near patch mixed fused "<<mixed.fusedObservations<<" retained-far "<<farFusions(mixed)<<"\n";}
