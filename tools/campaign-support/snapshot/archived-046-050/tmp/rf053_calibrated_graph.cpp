#include "realityforge/surface_fusion.hpp"
#include "realityforge/depth_consistency.hpp"
#include <fstream>
#include <iostream>
#include <algorithm>
#include <functional>
#include <tuple>
#include <iomanip>
struct Entry {const rf::VerifiedDepthSample* sample;rf::ObservedNormal normal;double radius;};
rf::Vec3 cameraPoint(const rf::DenseView& v,rf::Vec3 p){auto q=rf::multiply(v.pose.rotation,p);return {q.x+v.pose.translation.x,q.y+v.pose.translation.y,q.z+v.pose.translation.z};}
template<class T>void read(std::istream& f,T* p,std::size_t n){if(!f.read(reinterpret_cast<char*>(p),n*sizeof(T)))throw std::runtime_error("fixture read");}
std::size_t matching(const std::vector<std::vector<int>>& edges,std::size_t right){std::vector<int> owner(right,-1);std::size_t count=0;for(std::size_t left=0;left<edges.size();++left){std::vector<bool> visited(right);std::function<bool(int)> add=[&](int from){for(int to:edges[from])if(!visited[to]){visited[to]=true;if(owner[to]<0||add(owner[to])){owner[to]=from;return true;}}return false;};if(add(left))++count;}return count;}
int main(int argc,char** argv){try{
 if(argc!=2)throw std::runtime_error("fixture path");
 std::ifstream f(argv[1],std::ios::binary);std::uint32_t shape[5];read(f,shape,5);if(shape[2]!=2||shape[0]>640||shape[1]>640)throw std::runtime_error("fixture shape");
 std::vector<double> rows(shape[2]*16);read(f,rows.data(),rows.size());std::vector<rf::DenseView> views;
 for(unsigned i=0;i<shape[2];++i){rf::DenseView v;v.width=shape[0];v.height=shape[1];std::copy_n(rows.data()+i*16,9,v.pose.rotation.begin());v.pose.translation={rows[i*16+9],rows[i*16+10],rows[i*16+11]};v.fx=rows[i*16+12];v.fy=rows[i*16+13];v.cx=rows[i*16+14];v.cy=rows[i*16+15];v.rgba.resize(std::size_t(v.width)*v.height*4);read(f,v.rgba.data(),v.rgba.size());views.push_back(std::move(v));}
 rf::DenseSettings settings;settings.nearDepth=6;settings.farDepth=80;settings.depthPlanes=64;settings.pixelStride=3;auto dense=rf::reconstructDense(views,settings);
 std::vector<float> spacings;std::vector<Entry> left,right;std::size_t support=0;std::array<std::size_t,5> classifications{};std::size_t missing=0,jump=0,residualLayers=0,illConditioning=0;
 for(const auto& p:dense.verifiedSamples){const auto& v=views[p.camera];const double spacing=cameraPoint(v,p.xyz).z*3/std::min(v.fx,v.fy);spacings.push_back(static_cast<float>(spacing));auto n=rf::estimateDepthNeighborhoodNormal(v,dense.maps[p.camera],p.x,p.y,3,.08);++classifications[static_cast<std::size_t>(n.validity)];bool hole=false,step=false;const auto& dm=dense.maps[p.camera];const double centre=dm.depth[std::size_t(p.y)*dm.width+p.x];for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){int x=int(p.x)+3*dx,y=int(p.y)+3*dy;if(x<0||y<0||x>=dm.width||y>=dm.height){hole=true;continue;}double z=dm.depth[std::size_t(y)*dm.width+x];if(z<=0||!std::isfinite(z))hole=true;else if(std::abs(z-centre)/centre>.08)step=true;}if(n.validity==rf::NormalValidity::Discontinuity){if(hole)++missing;if(step)++jump;if(!hole&&!step)++residualLayers;}if(n.validity==rf::NormalValidity::IllConditioned&&n.uncertainty.conditioning<.02)++illConditioning;if(p.confidence<.12||n.validity!=rf::NormalValidity::Supported)continue;++support;const double radius=std::max(.0001,static_cast<double>(std::clamp(static_cast<float>(spacing),.00001f,1000.f))*.60);(p.camera==0?left:right).push_back({&p,std::move(n),radius});}
 std::nth_element(spacings.begin(),spacings.begin()+spacings.size()/2,spacings.end());double global=std::max(.0001,double(std::clamp(spacings[spacings.size()/2],.00001f,1000.f))*.6);

 const auto delta=[](rf::Vec3 a,rf::Vec3 b){return rf::Vec3{a.x-b.x,a.y-b.y,a.z-b.z};};
 const auto length=[](rf::Vec3 v){return std::hypot(v.x,v.y,v.z);};
 const auto dot=[](rf::Vec3 a,rf::Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;};
 const auto inverse=[](const rf::Mat3& a){
   const double d=rf::determinant(a);if(!std::isfinite(d)||std::abs(d)<1e-12)throw std::runtime_error("singular rotation");
   rf::Mat3 r{a[4]*a[8]-a[5]*a[7],a[2]*a[7]-a[1]*a[8],a[1]*a[5]-a[2]*a[4],a[5]*a[6]-a[3]*a[8],a[0]*a[8]-a[2]*a[6],a[2]*a[3]-a[0]*a[5],a[3]*a[7]-a[4]*a[6],a[1]*a[6]-a[0]*a[7],a[0]*a[4]-a[1]*a[3]};
   for(auto& value:r)value/=d;return r;
 };
 const auto bound=[&](const Entry& e){
   const auto& p=*e.sample;const auto& v=views[p.camera];const auto pc=cameraPoint(v,p.xyz);
   const auto inv=inverse(v.pose.rotation);const auto normal=rf::multiply(rf::transpose(inv),e.normal.direction);
   const double h=dot(normal,pc),T=.6*settings.pixelStride;double b=0;
   if(!std::isfinite(h)||std::abs(h)<=e.radius)return 0.;
   for(double sx:{-1.,1.})for(double sy:{-1.,1.}){
     rf::Vec3 ray{(p.x+sx*T-v.cx)/v.fx,(p.y+sy*T-v.cy)/v.fy,1};
     const double den=dot(normal,ray);
     if(!std::isfinite(den)||den*h<=0||std::abs(den)<1e-12)return 0.;
     for(double sign:{-1.,1.}){
       const double t=(h+sign*e.radius)/den;if(!std::isfinite(t)||t<=0)return 0.;
       const auto displacement=rf::multiply(inv,delta({t*ray.x,t*ray.y,t},pc));
       b=std::max(b,length(displacement));
     }
   }
   return std::min(600.,std::nextafter(b,std::numeric_limits<double>::infinity()));
 };
 const auto projectionError=[&](const Entry& e,rf::Vec3 q){
   const auto& p=*e.sample;const auto& v=views[p.camera];const auto pc=cameraPoint(v,q);
   if(!std::isfinite(pc.z)||pc.z<=0)return std::numeric_limits<double>::infinity();
   return std::hypot(v.fx*pc.x/pc.z+v.cx-p.x,v.fy*pc.y/pc.z+v.cy-p.y)/(.6*settings.pixelStride);
 };
 const auto visible=[&](const Entry& e,rf::Vec3 q){
   const auto& v=views[e.sample->camera];const auto p=cameraPoint(v,q);if(!std::isfinite(p.z)||p.z<=0)return false;
   auto observed=rf::consistency::sampleDepth(dense.maps[e.sample->camera],v.fx*p.x/p.z+v.cx,v.fy*p.y/p.z+v.cy,3,3,.08);
   return rf::consistency::verify(observed,p.z,0,.08,1).classification==rf::consistency::Visibility::Supported;
 };
 std::vector<double> leftBounds,rightBounds;for(const auto& e:left)leftBounds.push_back(bound(e));for(const auto& e:right)rightBounds.push_back(bound(e));
 std::vector<std::vector<int>> worldEdges(left.size()),metricEdges(left.size()),centroidEdges(left.size());
 std::size_t worldCount=0,metricCount=0,centroidCount=0,invalidBounds=0,anglePass=0,metricPass=0,visibilityPass=0;
 for(double b:leftBounds)if(b<=0)++invalidBounds;for(double b:rightBounds)if(b<=0)++invalidBounds;
 for(std::size_t i=0;i<left.size();++i)for(std::size_t j=0;j<right.size();++j){
   const auto& a=left[i];const auto& b=right[j];const auto& pa=*a.sample;const auto& pb=*b.sample;
   if(!rf::surface_fusion::compatibleNormal(a.normal.direction,b.normal.direction))continue;++anglePass;
   const auto d=delta(pb.xyz,pa.xyz);const double distance=length(d),r=std::min(a.radius,b.radius);
   const double n=std::max(std::abs(dot(a.normal.direction,d)),std::abs(dot(b.normal.direction,d)))/r;
   const double ei=projectionError(a,pb.xyz),eq=projectionError(b,pa.xyz);
   const bool metric=leftBounds[i]>0&&rightBounds[j]>0&&distance<=std::min(leftBounds[i],rightBounds[j])&&n*n+ei*ei<=1&&n*n+eq*eq<=1;
   const bool world=distance<r;if(!metric&&!world)continue;if(metric)++metricPass;
   if(!visible(a,pb.xyz)||!visible(b,pa.xyz))continue;++visibilityPass;
   if(world){worldEdges[i].push_back(j);++worldCount;}
   if(!metric)continue;metricEdges[i].push_back(j);++metricCount;
   const double wa=std::max(.005f,pa.confidence)*pa.supportingViews,wb=std::max(.005f,pb.confidence)*pb.supportingViews;
   const rf::Vec3 centroid{(wa*pa.xyz.x+wb*pb.xyz.x)/(wa+wb),(wa*pa.xyz.y+wb*pb.xyz.y)/(wa+wb),(wa*pa.xyz.z+wb*pb.xyz.z)/(wa+wb)};
   bool valid=true;for(const Entry* e:{&a,&b}){
     const double normalGap=std::abs(dot(e->normal.direction,delta(centroid,e->sample->xyz)))/r,pixel=projectionError(*e,centroid);
     if(normalGap*normalGap+pixel*pixel>1||!visible(*e,centroid))valid=false;
   }
   if(valid){centroidEdges[i].push_back(j);++centroidCount;}
 }
 std::cout<<std::setprecision(12)<<"verified="<<dense.verifiedSamples.size()<<" supported_normals="<<support<<" left="<<left.size()<<" right="<<right.size()<<" invalid_bounds="<<invalidBounds<<" global_radius="<<global<<" angle_pass="<<anglePass<<" metric_pass_before_visibility="<<metricPass<<" visibility_pass="<<visibilityPass<<" local_world_dual_visibility_edges="<<worldCount<<" local_world_max_matching="<<matching(worldEdges,right.size())<<" fixed_v5_edges="<<metricCount<<" fixed_v5_max_matching="<<matching(metricEdges,right.size())<<" fixed_v5_centroid_edges="<<centroidCount<<" fixed_v5_centroid_max_matching="<<matching(centroidEdges,right.size())<<'\n';

}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
