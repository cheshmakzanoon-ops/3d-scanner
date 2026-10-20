#pragma once
#include "realityforge/dense.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <span>
#include <stdexcept>
namespace rf::refinement {
inline constexpr unsigned SETTINGS_VERSION=2;
inline constexpr double NORMAL_PERTURBATION=.035;
inline constexpr double MINIMUM_PLANE_RAY_COSINE=.15;
inline constexpr double MAXIMUM_PLANE_DEPTH_RATIO=2.;
struct SparsePrior { double cameraZ{},relativeUncertainty{}; bool visible{}; };
struct DepthPrior { double nearDepth{},farDepth{}; std::size_t support{}; bool conservativeFallback{}; };
// A percentile interval can silently erase thin foreground/background layers.
// Include EVERY conditioned visible observation; weak evidence falls back to
// the caller's full admitted interval and never invents a confident local range.
inline DepthPrior interval(std::span<const SparsePrior> observations,double nearDepth,double farDepth,std::size_t minimumSupport=3){
 if(!std::isfinite(nearDepth)||!std::isfinite(farDepth)||nearDepth<=0||farDepth<=nearDepth||observations.size()>100000)throw std::invalid_argument("Invalid depth prior interval");
 double lo=farDepth,hi=nearDepth;std::size_t count=0;
 for(const auto& p:observations){
  if(!p.visible)continue;
  if(!std::isfinite(p.cameraZ)||!std::isfinite(p.relativeUncertainty)||p.cameraZ<=0||p.relativeUncertainty<0||p.relativeUncertainty>.25)continue;
  if(p.cameraZ<nearDepth||p.cameraZ>farDepth)continue;
  const double margin=std::max(.05,p.relativeUncertainty*3.);
  lo=std::min(lo,p.cameraZ/(1+margin));hi=std::max(hi,p.cameraZ*(1+margin));++count;
 }
 if(count<minimumSupport)return {nearDepth,farDepth,count,true};
 return {std::max(nearDepth,lo),std::min(farDepth,hi),count,false};
}
struct RefinementResult { double depth{},cost{2.},inverseDepthUncertainty{}; unsigned evaluations{}; bool valid{}; };
// Deterministic bounded local search in INVERSE camera-z. Cost returns [0,2],
// where 2 means invalid. It cannot create depth from missing/flat evidence.
// Caller checks cancellation in evaluate; no truth or normals enter this API.
template<class Evaluate> RefinementResult local(double leftDepth,double initialDepth,double rightDepth,
 unsigned iterations,Evaluate&& evaluate){
 if(!std::isfinite(leftDepth)||!std::isfinite(initialDepth)||!std::isfinite(rightDepth)||leftDepth<=0||leftDepth>=initialDepth||initialDepth>=rightDepth||iterations>12)throw std::invalid_argument("Invalid bounded depth refinement");
 double lo=1/rightDepth,hi=1/leftDepth,bestInverse=1/initialDepth;
 auto cost=[&](double q){double c=evaluate(1/q);return std::isfinite(c)&&c>=0&&c<=2?c:2.;};
 double best=cost(bestInverse);unsigned work=1;
 if(best>=2)return {};
 const double boundaryA=cost(lo),boundaryB=cost(hi);work+=2;
 if(std::max({boundaryA,boundaryB,best})-std::min({boundaryA,boundaryB,best})<1e-12)return {};
 for(unsigned i=0;i<iterations;++i){
  double a=lo+(hi-lo)/3,b=hi-(hi-lo)/3,ca=cost(a),cb=cost(b);work+=2;
  if(ca<best){best=ca;bestInverse=a;}if(cb<best){best=cb;bestInverse=b;}
  if(ca<cb)hi=b;else lo=a;
 }
 return {1/bestInverse,best,(hi-lo)*.5,work,true};
}
inline double parabolicInverseDepth(std::span<const double> costs,std::span<const double> planes,std::size_t best){
 if(costs.size()!=planes.size()||best==0||best+1>=planes.size())throw std::invalid_argument("Invalid depth peak");
 const double a=costs[best-1],b=costs[best],c=costs[best+1];
 if(!std::isfinite(a)||!std::isfinite(b)||!std::isfinite(c)||planes[best]<=0||planes[best-1]<=0||planes[best+1]<=0)throw std::invalid_argument("Invalid depth peak data");
 double inv=1/planes[best],denom=a-2*b+c;
 if(denom>1e-8)inv+=(1/planes[best+1]-inv)*std::clamp(.5*(a-c)/denom,-.75,.75);
 return inv;
}
// Coarse validity is propagated only through a fully observed continuous cell.
// Depth discontinuities and missing pixels remain unknown, not interpolated fill.
inline float propagate(const DenseDepthMap& coarse,double x,double y,double maximumRelativeSpread=.05,int stride=1,int origin=0){
 if(coarse.width<2||coarse.height<2||stride<1||stride>8||origin<0||coarse.depth.size()!=static_cast<std::size_t>(coarse.width)*coarse.height||!std::isfinite(x)||!std::isfinite(y)||x<origin||y<origin||x>=coarse.width-1||y>=coarse.height-1||!std::isfinite(maximumRelativeSpread)||maximumRelativeSpread<0||maximumRelativeSpread>1)return 0;
 int ix=origin+static_cast<int>(std::floor((x-origin)/stride))*stride,iy=origin+static_cast<int>(std::floor((y-origin)/stride))*stride;
 if(ix+stride>=coarse.width||iy+stride>=coarse.height)return 0;
 double lo=1e300,hi=0,z=0,fx=(x-ix)/stride,fy=(y-iy)/stride;
 for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx){double d=coarse.depth[(iy+dy*stride)*coarse.width+ix+dx*stride];if(!std::isfinite(d)||d<=0)return 0;lo=std::min(lo,d);hi=std::max(hi,d);z+=d*(dx?fx:1-fx)*(dy?fy:1-fy);}
 return (hi-lo)/hi<=maximumRelativeSpread?static_cast<float>(z):0;
}

// Camera-coordinate normal; +Z is the frontoparallel convention. `observed`
// means a hypothesis has an observed-neighborhood seed, NEVER photometric or
// geometric acceptance. Perturbations remain proposals for048 costs in046.
struct PlaneHypothesis { Vec3 cameraNormal{0,0,1}; bool observed{}; };
struct PlaneHypotheses { std::array<PlaneHypothesis,6> values{};std::size_t count{1}; };
namespace detail {
inline bool calibrated(const DenseView& view){
 return view.width>=3&&view.width<=4096&&view.height>=3&&view.height<=4096&&
  std::isfinite(view.fx)&&std::isfinite(view.fy)&&view.fx>0&&view.fy>0&&
  std::isfinite(view.cx)&&std::isfinite(view.cy);
}
inline double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline bool normalize(Vec3& n){
 const double length=std::hypot(n.x,n.y,n.z);
 if(!std::isfinite(length)||length<=0)return false;
 n={n.x/length,n.y/length,n.z/length};return true;
}
inline Vec3 ray(const DenseView& view,double x,double y){return {(x-view.cx)/view.fx,(y-view.cy)/view.fy,1};}
inline bool facing(Vec3 normal,Vec3 ray){
 return normalize(ray)&&std::isfinite(normal.x)&&std::isfinite(normal.y)&&std::isfinite(normal.z)&&
  normal.z>0&&dot(normal,ray)>=MINIMUM_PLANE_RAY_COSINE;
}
}
// Constant-work3x3 fit on the existing sampled grid. Missing confidence/depth,
// border neighborhoods, excessive relative spread and poor plane residuals
// return ONLY the baseline frontoparallel proposal. No pixels are filled here.
// Inverse camera-z is affine in normalized image coordinates for a true plane.
inline PlaneHypotheses planeHypotheses(const DenseView& view,const DenseDepthMap& map,
 double x,double y,int stride,int origin,double maximumRelativeSpread=.055){
 PlaneHypotheses out;
 if(!detail::calibrated(view)||map.width!=view.width||map.height!=view.height||
  stride<1||stride>8||origin<0||origin>8||!std::isfinite(x)||!std::isfinite(y)||
  x<origin||y<origin||x>=view.width||y>=view.height||
  !std::isfinite(maximumRelativeSpread)||maximumRelativeSpread<=0||maximumRelativeSpread>1)return out;
 const auto size=static_cast<std::size_t>(map.width)*map.height;
 if(map.depth.size()!=size||map.confidence.size()!=size)return out;
 const int ix=origin+static_cast<int>(std::floor((x-origin)/stride+.5))*stride;
 const int iy=origin+static_cast<int>(std::floor((y-origin)/stride+.5))*stride;
 if(ix-stride<origin||iy-stride<origin||ix+stride>=map.width||iy+stride>=map.height)return out;
 std::array<double,9> inverse{};double lo=1e300,hi=0,mean=0,slopeX=0,slopeY=0;
 std::size_t next=0;
 for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
  const auto index=static_cast<std::size_t>(iy+dy*stride)*map.width+ix+dx*stride;
  const double z=map.depth[index],confidence=map.confidence[index];
  if(!std::isfinite(z)||z<=0||!std::isfinite(confidence)||confidence<=0||confidence>1)return out;
  const double q=1/z;if(!std::isfinite(q))return out;
  inverse[next++]=q;lo=std::min(lo,z);hi=std::max(hi,z);mean+=q;
  slopeX+=dx*q;slopeY+=dy*q;
 }
 if((hi-lo)/hi>maximumRelativeSpread)return out;
 mean/=9;slopeX/=6.*stride;slopeY/=6.*stride;
 // Reject depth layers/poor local fits rather than assigning their mean normal.
 // This is a bounded continuity test, not a claim to detect subnoise edges.
 next=0;
 for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
  const double predicted=mean+slopeX*dx*stride+slopeY*dy*stride;
  if(!std::isfinite(predicted)||predicted<=0||std::abs(predicted-inverse[next++])/mean>.005)return out;
 }
 Vec3 normal{view.fx*slopeX,view.fy*slopeY,mean-slopeX*(ix-view.cx)-slopeY*(iy-view.cy)};
 const auto centreRay=detail::ray(view,x,y);
 if(!detail::normalize(normal)||!detail::facing(normal,centreRay))return out;
 out.values[out.count++]={normal,true};
 for(Vec3 delta:std::array<Vec3,4>{{{NORMAL_PERTURBATION,0,0},{-NORMAL_PERTURBATION,0,0},{0,NORMAL_PERTURBATION,0},{0,-NORMAL_PERTURBATION,0}}}){
  Vec3 candidate{normal.x+delta.x,normal.y+delta.y,normal.z+delta.z};
  if(detail::normalize(candidate)&&detail::facing(candidate,centreRay))out.values[out.count++]={candidate,true};
 }
 return out;
}
// Plane through the measured center point, intersected with another camera ray.
// At most one finite scalar result, no allocation. Slant/backfacing/behind-camera
// or >2x/<.5x depth-ratio hypotheses return0 and cannot enter a patch cost.
inline double planeDepth(const PlaneHypothesis& plane,const DenseView& view,
 double centerX,double centerY,double centerZ,double sampleX,double sampleY){
 if(!detail::calibrated(view)||!std::isfinite(centerX)||!std::isfinite(centerY)||
  !std::isfinite(sampleX)||!std::isfinite(sampleY)||!std::isfinite(centerZ)||centerZ<=0||
  centerX<0||centerY<0||centerX>=view.width||centerY>=view.height||
  sampleX<0||sampleY<0||sampleX>=view.width||sampleY>=view.height)return 0;
 const auto n=plane.cameraNormal;
 const double length=std::hypot(n.x,n.y,n.z);
 const auto centerRay=detail::ray(view,centerX,centerY),sampleRay=detail::ray(view,sampleX,sampleY);
 if(!std::isfinite(length)||std::abs(length-1)>1e-6||!detail::facing(n,centerRay)||!detail::facing(n,sampleRay))return 0;
 const double numerator=detail::dot(n,centerRay),denominator=detail::dot(n,sampleRay);
 const double ratio=numerator/denominator;
 const double z=centerZ*ratio;
 return std::isfinite(ratio)&&ratio>=1/MAXIMUM_PLANE_DEPTH_RATIO&&ratio<=MAXIMUM_PLANE_DEPTH_RATIO&&std::isfinite(z)&&z>0?z:0;
}
}
