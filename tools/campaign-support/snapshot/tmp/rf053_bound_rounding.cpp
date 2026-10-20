#include "realityforge/surface_fusion.hpp"
#include <iostream>
#include <iomanip>
#include <cmath>
int main(){
 rf::DenseView v;v.width=v.height=128;v.fx=v.fy=1e6;v.cx=v.cy=63-2.5e13;v.pose.rotation=rf::identity();
 rf::VerifiedDepthSample a;a.x=a.y=63;a.xyz={500000,500000,.02};
 for(int k=1;k<=5000;++k){
  rf::Vec3 n;rf::surface_fusion::unitNormal({1,-(1-k*1e-8),0},n);
  auto result=rf::surface_fusion::planePixelEnclosingRadius(v,a,n,.0001,3);if(!result||*result>=600)continue;
  rf::Vec3 u;rf::surface_fusion::unitNormal(n,u);long double b=0;
  const long double h=(long double)u.x*a.xyz.x+(long double)u.y*a.xyz.y+(long double)u.z*a.xyz.z;
  for(int x:{-1,1})for(int y:{-1,1})for(int side:{-1,1}){
   const long double rx=((long double)a.x+x*1.8L-v.cx)/v.fx,ry=((long double)a.y+y*1.8L-v.cy)/v.fy;
   const long double den=(long double)u.x*rx+(long double)u.y*ry+u.z;
   const long double t=(h+side*.0001L)/den;
   const long double dx=t*rx-a.xyz.x,dy=t*ry-a.xyz.y,dz=t-a.xyz.z;
   b=std::max(b,std::sqrt(dx*dx+dy*dy+dz*dz));
  }
  if(b>*result){std::cout<<std::setprecision(18)<<"k="<<k<<" double_bound="<<*result<<" long_double_corner="<<b<<" excess="<<b-*result<<" normal="<<u.x<<','<<u.y<<','<<u.z<<'\n';return 1;}
 }
 std::cout<<"no_underbound_found\n";
}
