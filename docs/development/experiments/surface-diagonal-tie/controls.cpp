#include "realityforge/surface.hpp"
#include <iostream>
#include <fstream>
#include <cmath>
#include <algorithm>
#include <stdexcept>
struct Fixture{std::vector<rf::DenseView> views;rf::DenseResult dense;};
Fixture fixture(double shift,bool rotate,bool reverse,bool partial,int shape,bool rectangular){
 Fixture f;f.views.resize(2);f.dense.maps.resize(2);
 for(int c=0;c<2;++c){int physical=reverse?1-c:c;auto&v=f.views[c];v.width=v.height=40;v.fx=100;v.fy=rectangular?110:100;v.cx=v.cy=19;v.pose={rf::identity(),{-physical*shift,0,0}};
 bool rot=physical&&rotate;if(rot)v.pose.rotation={0,-1,0,1,0,0,0,0,1};
 auto&m=f.dense.maps[c];m.width=m.height=40;m.depth.assign(1600,0);m.confidence.assign(1600,0);
 for(int y=3;y<37;y+=2)for(int x=3;x<37;x+=2){
 if(partial&&physical&&x<19)continue;
 double rx=(x-v.cx)/v.fx,ry=(y-v.cy)/v.fy; if(rot){double q=rx;rx=ry;ry=-q;}
 // Synthetic analytic surfaces only, directly test the dense-to-mesh API.
 double z=5;
 if(shape==1)z=10/(1+std::sqrt(1-.08*(rx*rx+ry*ry))); // paraboloid z=5+.004(x²+y²)
 if(shape==2)z=rx<0?5:6;
 if(shape==3)z=5/(1-.1*rx-.07*ry);
 double ox=physical*shift,oy=0;if(rot){ox=0;oy=-physical*shift;}
 rf::Vec3 p{rx*z+ox,ry*z+oy,z};m.depth[y*40+x]=z;m.confidence[y*40+x]=.9;
 f.dense.verifiedSamples.push_back({p,{128,128,128},.9f,2,(uint8_t)c,(uint16_t)x,(uint16_t)y});}}
 return f;
}
int main(){
 for(auto name:{"rotated","reverse","translated","partial","curved","step","nontie"}){
 std::string n=name;auto f=fixture(n=="translated"?.01:0,n!="translated"&&n!="nontie",n=="reverse",n=="partial",n=="curved"?1:n=="step"?2:n=="nontie"?3:0,n=="nontie");
 rf::SurfaceSettings s;s.pixelStride=2;auto m=rf::fuseDepthSurfaces(f.views,f.dense,s);double area=0;for(auto&t:m.triangles){auto a=m.vertices[t.a].xyz,b=m.vertices[t.b].xyz,c=m.vertices[t.c].xyz;double ux=b.x-a.x,uy=b.y-a.y,uz=b.z-a.z,vx=c.x-a.x,vy=c.y-a.y,vz=c.z-a.z;area+=.5*std::hypot(uy*vz-uz*vy,uz*vx-ux*vz,ux*vy-uy*vx);}
 double maximumLostRefDistance=0;std::size_t lostRefs=0,unrepresentedLostRefs=0;std::vector<bool> retained(f.dense.verifiedSamples.size());
 for(const auto&o:m.observations)retained[o.sourceSampleId]=true;
 for(std::size_t i=0;i<retained.size();++i)if(!retained[i]){++lostRefs;const auto&p=f.dense.verifiedSamples[i].xyz;
 double nearest=INFINITY;for(const auto&v:m.vertices)nearest=std::min(nearest,std::hypot(v.xyz.x-p.x,v.xyz.y-p.y,v.xyz.z-p.z));maximumLostRefDistance=std::max(maximumLostRefDistance,nearest);
 if(std::none_of(m.vertices.begin(),m.vertices.end(),[&](const auto&v){return v.xyz.x==p.x&&v.xyz.y==p.y&&v.xyz.z==p.z;}))++unrepresentedLostRefs;}
 std::cout<<"maximumLostRefPositionDistance="<<maximumLostRefDistance<<" lostRefs="<<lostRefs<<" lostRefsWithoutExactSurvivingPosition="<<unrepresentedLostRefs<<" "<<n<<" input="<<m.inputSamples<<" accepted="<<m.acceptedSamples<<" refs="<<m.observations.size()<<" vertices="<<m.vertices.size()<<" faces="<<m.triangles.size()<<" nonmanifold="<<m.topology.nonmanifoldEdges<<" intersections="<<m.topology.intersections<<" incomplete="<<m.topology.intersectionBudgetExceeded<<" area="<<area<<'\n';std::ofstream(n+".json")<<rf::surfaceJson(m);
 }
}
