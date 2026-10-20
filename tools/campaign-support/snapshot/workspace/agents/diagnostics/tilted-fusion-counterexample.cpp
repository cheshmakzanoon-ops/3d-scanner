#include "realityforge/surface_fusion.hpp"
#include "realityforge/depth_consistency.hpp"
#include <iostream>
#include <iomanip>
#include <cmath>
#include <stdexcept>
namespace {
void check(bool ok,const char* text){if(!ok)throw std::runtime_error(text);}
rf::Vec3 camera(const rf::DenseView& v,rf::Vec3 p){auto q=rf::multiply(v.pose.rotation,p);return{q.x+v.pose.translation.x,q.y+v.pose.translation.y,q.z+v.pose.translation.z};}
rf::Vec2 project(const rf::DenseView& v,rf::Vec3 p){auto q=camera(v,p);return{v.fx*q.x/q.z+v.cx,v.fy*q.y/q.z+v.cy};}
double length(rf::Vec3 p){return std::hypot(p.x,p.y,p.z);}
}
int main(){
 constexpr int width=128,step=3,center=63;constexpr double focal=200,baseline=.03,z0=4;
 const double slope=std::tan(70*std::acos(-1.)/180.);
 std::array<rf::DenseView,2> views;std::array<rf::DenseDepthMap,2> maps;
 for(int i=0;i<2;++i){
  auto& v=views[i];v.width=width;v.height=width;v.fx=focal;v.fy=focal;v.cx=center;v.cy=center;v.pose.rotation=rf::identity();v.pose.translation={-i*baseline,0,0};
  auto& m=maps[i];m.width=width;m.height=width;m.depth.resize(width*width);m.confidence.resize(width*width);
  for(int y=3;y<width-3;y+=step)for(int x=3;x<width-3;x+=step){const auto k=y*width+x;m.depth[k]=static_cast<float>((z0-slope*i*baseline)/(1+slope*(x-center)/focal));m.confidence[k]=.8f;}
 }
 const rf::Vec3 p{0,0,maps[0].depth[center*width+center]},q{baseline,0,maps[1].depth[center*width+center]};
 const rf::Vec3 delta{q.x-p.x,q.y-p.y,q.z-p.z};const double distance=length(delta);
 const auto first=rf::estimateDepthNeighborhoodNormal(views[0],maps[0],center,center,step,.08);
 const auto second=rf::estimateDepthNeighborhoodNormal(views[1],maps[1],center,center,step,.08);
 check(first.validity==rf::NormalValidity::Supported&&second.validity==rf::NormalValidity::Supported,"actual 9-neighbor normal support failed");
 check(rf::surface_fusion::compatibleNormal(first.direction,second.direction),"actual normal compatibility failed");
 const double r0=rf::surface_fusion::observedFusionRadius(p.z,focal,step),r1=rf::surface_fusion::observedFusionRadius(q.z,focal,step),r=std::min(r0,r1);
 check(distance>r0&&distance>r1,"isotropic bound must reject");
 const double n0=std::abs(delta.x*first.direction.x+delta.y*first.direction.y+delta.z*first.direction.z);
 const double n1=std::abs(delta.x*second.direction.x+delta.y*second.direction.y+delta.z*second.direction.z);
 double errors[2]{},depthErrors[2]{},metrics[2]{};
 for(int i=0;i<2;++i){
  const auto a=project(views[i],p),b=project(views[i],q);errors[i]=std::hypot(a.x-b.x,a.y-b.y);
  check(errors[i]<.6*step,"dual source-pixel gate failed");
  const auto other=i==0?q:p;const auto projected=project(views[i],other);const auto cc=camera(views[i],other);
  const auto observed=rf::consistency::sampleDepth(maps[i],projected.x,projected.y,step,3,.08);
  const auto verified=rf::consistency::verify(observed,cc.z,0,.08,1);
  check(verified.classification==rf::consistency::Visibility::Supported,"actual independently observed depth visibility failed");
  check(observed.confidence>.12,"confidence gate failed");depthErrors[i]=verified.relativeError;
  metrics[i]=std::pow(errors[i]/(.6*step),2)+std::pow(std::max(n0,n1)/r,2);
  check(metrics[i]<1,"conservative squared anisotropic gate failed");
 }
 std::cout<<std::setprecision(17)<<"{\"synthetic\":true,\"tiltDegrees\":70,\"focal\":200,\"stride\":3,\"principal\":[63,63],\"baseline\":0.03,\"P\":[0,0,"<<p.z<<"],\"Q\":[0.03,0,"<<q.z<<"],\"worldDistance\":"<<distance<<",\"localRadii\":["<<r0<<','<<r1<<"],\"dualPixelDistances\":["<<errors[0]<<','<<errors[1]<<"],\"normalValidity\":[\"Supported\",\"Supported\"],\"normalConditioning\":["<<first.uncertainty.conditioning<<','<<second.uncertainty.conditioning<<"],\"normalDisplacements\":["<<n0<<','<<n1<<"],\"visibility\":[\"Supported\",\"Supported\"],\"relativeDepthErrors\":["<<depthErrors[0]<<','<<depthErrors[1]<<"],\"squaredMetrics\":["<<metrics[0]<<','<<metrics[1]<<"]}\n";
}
