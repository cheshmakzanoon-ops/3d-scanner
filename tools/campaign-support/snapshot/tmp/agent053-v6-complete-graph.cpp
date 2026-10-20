#include "realityforge/surface_fusion.hpp"
#include "realityforge/depth_consistency.hpp"
#include <algorithm>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <tuple>
struct Entry{std::size_t id;rf::Vec3 normal;double front,bound;};
template<class T>void read(std::istream& in,T* p,std::size_t size){if(!in.read(reinterpret_cast<char*>(p),size*sizeof(T)))throw std::runtime_error("short fixture");}
std::size_t maximumMatching(const std::vector<std::vector<std::size_t>>& edges,std::size_t right){
 std::vector<std::int64_t> owner(right,-1);std::size_t result=0;
 for(std::size_t left=0;left<edges.size();++left){std::vector<bool> seen(right);
  std::function<bool(std::size_t)> augment=[&](std::size_t from){for(auto to:edges[from])if(!seen[to]){seen[to]=true;if(owner[to]<0||augment(static_cast<std::size_t>(owner[to]))){owner[to]=static_cast<std::int64_t>(from);return true;}}return false;};
  if(augment(left))++result;
 }return result;
}
int main(int argc,char** argv){try{
 if(argc!=2)throw std::runtime_error("fixture argument required");
 std::ifstream in(argv[1],std::ios::binary);std::uint32_t shape[5];read(in,shape,5);
 if(shape[2]!=2||shape[0]<32||shape[1]<32||shape[0]>640||shape[1]>640)throw std::runtime_error("fixture shape");
 std::vector<double> rows(32);read(in,rows.data(),rows.size());std::vector<rf::DenseView> views;
 for(int camera=0;camera<2;++camera){rf::DenseView v;v.width=shape[0];v.height=shape[1];std::copy_n(rows.data()+camera*16,9,v.pose.rotation.begin());v.pose.translation={rows[camera*16+9],rows[camera*16+10],rows[camera*16+11]};v.fx=rows[camera*16+12];v.fy=rows[camera*16+13];v.cx=rows[camera*16+14];v.cy=rows[camera*16+15];v.rgba.resize(std::size_t(v.width)*v.height*4);read(in,v.rgba.data(),v.rgba.size());views.push_back(std::move(v));}
 rf::DenseSettings depthSettings;depthSettings.nearDepth=6;depthSettings.farDepth=80;depthSettings.depthPlanes=64;depthSettings.pixelStride=3;
 const auto dense=rf::reconstructDense(views,depthSettings);rf::SurfaceSettings settings;
 const auto cameraPoint=[](const rf::DenseView& v,rf::Vec3 p){auto c=rf::multiply(v.pose.rotation,p);return rf::Vec3{c.x+v.pose.translation.x,c.y+v.pose.translation.y,c.z+v.pose.translation.z};};
 const auto visible=[&](std::size_t camera,rf::Vec3 p){const auto& v=views[camera];const auto c=cameraPoint(v,p);if(!std::isfinite(c.z)||c.z<=0)return false;const auto observed=rf::consistency::sampleDepth(dense.maps[camera],v.fx*c.x/c.z+v.cx,v.fy*c.y/c.z+v.cy,settings.pixelStride,3,settings.maximumRelativeDepthJump);return rf::consistency::verify(observed,c.z,0,settings.maximumRelativeDepthJump,1).classification==rf::consistency::Visibility::Supported;};
 std::array<std::size_t,5> classes{};std::vector<Entry> left,right;std::size_t invalidBounds=0;
 for(std::size_t id=0;id<dense.verifiedSamples.size();++id){const auto& p=dense.verifiedSamples[id];const auto& v=views[p.camera];const auto n=rf::estimateDepthNeighborhoodNormal(v,dense.maps[p.camera],p.x,p.y,settings.pixelStride,settings.maximumRelativeDepthJump);++classes[static_cast<std::size_t>(n.validity)];rf::Vec3 normal{};
  if(p.confidence<settings.minimumConfidence||n.validity!=rf::NormalValidity::Supported||!rf::surface_fusion::unitNormal(n.direction,normal))continue;
  const double front=rf::surface_fusion::observedFusionRadius(cameraPoint(v,p.xyz).z,std::min(v.fx,v.fy),settings.pixelStride);
  const auto bound=rf::surface_fusion::planePixelEnclosingRadius(v,p,normal,front,settings.pixelStride);if(!bound){++invalidBounds;continue;}
  (p.camera==0?left:right).push_back({id,normal,front,*bound});
 }
 if(left.size()>5000||right.size()>5000)throw std::runtime_error("diagnostic exhaustive graph cap");
 std::vector<std::vector<std::size_t>> pairs(left.size()),eligible(left.size());std::size_t pairEdges=0,eligibleEdges=0;
 for(std::size_t i=0;i<left.size();++i)for(std::size_t j=0;j<right.size();++j){const auto& a=left[i];const auto& b=right[j];const auto& pa=dense.verifiedSamples[a.id];const auto& pb=dense.verifiedSamples[b.id];
  if(!rf::surface_fusion::originalPairMetric(views[pa.camera],pa,a.normal,a.front,a.bound,views[pb.camera],pb,b.normal,b.front,b.bound,settings.pixelStride)||!visible(pa.camera,pb.xyz)||!visible(pb.camera,pa.xyz))continue;
  pairs[i].push_back(j);++pairEdges;
  const auto earlier=[](const rf::VerifiedDepthSample& x,const rf::VerifiedDepthSample& y){return std::tie(x.xyz.x,x.xyz.y,x.xyz.z,x.camera,x.y,x.x)<std::tie(y.xyz.x,y.xyz.y,y.xyz.z,y.camera,y.y,y.x);};
  const bool aFirst=earlier(pa,pb);const auto& first=aFirst?pa:pb;const auto& second=aFirst?pb:pa;const auto& firstNormal=aFirst?a.normal:b.normal;const auto& secondNormal=aFirst?b.normal:a.normal;
  const double wa=std::max(.005f,first.confidence)*first.supportingViews,wb=std::max(.005f,second.confidence)*second.supportingViews;
  if(!rf::surface_fusion::compatibleNormal({firstNormal.x*wa,firstNormal.y*wa,firstNormal.z*wa},secondNormal)||!visible(second.camera,first.xyz))continue;
  const double inverseWeight=1./(wa+wb);const rf::Vec3 centroid{(first.xyz.x*wa+second.xyz.x*wb)*inverseWeight,(first.xyz.y*wa+second.xyz.y*wb)*inverseWeight,(first.xyz.z*wa+second.xyz.z*wb)*inverseWeight};
  if(!rf::surface_fusion::originalAnchorMetric(views[pa.camera],pa,a.normal,a.front,a.bound,centroid,settings.pixelStride)||!rf::surface_fusion::originalAnchorMetric(views[pb.camera],pb,b.normal,b.front,b.bound,centroid,settings.pixelStride)||!visible(pa.camera,centroid)||!visible(pb.camera,centroid))continue;
  eligible[i].push_back(j);++eligibleEdges;
 }
 const auto mesh=rf::fuseDepthSurfaces(views,dense,settings);
 std::cout<<std::setprecision(17)<<"verified="<<dense.verifiedSamples.size()<<" supported="<<classes[0]<<" sparse="<<classes[1]<<" discontinuity="<<classes[2]<<" illconditioned="<<classes[3]<<" invalid="<<classes[4]<<" usableLeft="<<left.size()<<" usableRight="<<right.size()<<" invalidBounds="<<invalidBounds<<" pairEdges="<<pairEdges<<" pairMaximum="<<maximumMatching(pairs,right.size())<<" eligibleEdges="<<eligibleEdges<<" eligibleMaximum="<<maximumMatching(eligible,right.size())<<" actualFused="<<mesh.fusedObservations<<" actualVertices="<<mesh.vertices.size()<<" actualTriangles="<<mesh.triangles.size()<<'\n';
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
