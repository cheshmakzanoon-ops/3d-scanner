#pragma once
#include "realityforge/dense.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <span>
#include <stdexcept>
namespace rf::refinement {
inline constexpr unsigned SETTINGS_VERSION=1;
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
inline float propagate(const DenseDepthMap& coarse,double x,double y,double maximumRelativeSpread=.05){
 if(coarse.width<2||coarse.height<2||coarse.depth.size()!=static_cast<std::size_t>(coarse.width)*coarse.height||!std::isfinite(x)||!std::isfinite(y)||x<0||y<0||x>=coarse.width-1||y>=coarse.height-1)return 0;
 int ix=static_cast<int>(x),iy=static_cast<int>(y);double lo=1e300,hi=0,z=0,fx=x-ix,fy=y-iy;
 for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx){double d=coarse.depth[(iy+dy)*coarse.width+ix+dx];if(!std::isfinite(d)||d<=0)return 0;lo=std::min(lo,d);hi=std::max(hi,d);z+=d*(dx?fx:1-fx)*(dy?fy:1-fy);}
 return (hi-lo)/hi<=maximumRelativeSpread?static_cast<float>(z):0;
}
}
