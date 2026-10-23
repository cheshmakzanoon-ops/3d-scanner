#include "realityforge/depth_refinement.hpp"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <iostream>
#include <limits>
namespace {
rf::Vec3 unit(rf::Vec3 n){const double length=std::sqrt(n.x*n.x+n.y*n.y+n.z*n.z);return {n.x/length,n.y/length,n.z/length};}
double inner(rf::Vec3 a,rf::Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
rf::DenseView camera(){rf::DenseView v;v.width=41;v.height=35;v.fx=180;v.fy=137;v.cx=17.3;v.cy=18.7;return v;}
template<class Depth> rf::DenseDepthMap observed(const rf::DenseView& v,int stride,int origin,Depth depth){
 rf::DenseDepthMap map{v.width,v.height,std::vector<float>(v.width*v.height,0),std::vector<float>(v.width*v.height,0)};
 for(int y=origin;y<v.height;y+=stride)for(int x=origin;x<v.width;x+=stride){const auto i=y*v.width+x;map.depth[i]=static_cast<float>(depth(x,y));map.confidence[i]=.8f;}
 return map;
}
void peakBoundaryTests(){
 using rf::refinement::parabolicInverseDepth;
 const std::array<double,3> costs{.6,.1,.4},planes{2.,3.,6.};
 const auto rejects=[](auto&& call){bool caught=false;try{call();}catch(const std::invalid_argument&){caught=true;}assert(caught);};
 // Check before touching either span, including indices whose +1 wraps.
 for(const std::size_t best:{std::size_t{0},std::size_t{2},std::size_t{3},
                            std::numeric_limits<std::size_t>::max()-1,
                            std::numeric_limits<std::size_t>::max()})
  rejects([&]{(void)parabolicInverseDepth(costs,planes,best);});
 for(std::size_t size=0;size<3;++size)
  rejects([&]{(void)parabolicInverseDepth(std::span(costs).first(size),std::span(planes).first(size),1);});
 rejects([&]{(void)parabolicInverseDepth(std::span(costs).first(2),planes,1);});
 for(std::size_t slot=0;slot<3;++slot){
  for(double invalid:{0.,-1.,std::numeric_limits<double>::quiet_NaN(),
                      std::numeric_limits<double>::infinity(),std::numeric_limits<double>::denorm_min()}){
   auto bad=planes;bad[slot]=invalid;
   rejects([&]{(void)parabolicInverseDepth(costs,bad,1);});
  }
  for(double invalid:{-.1,2.1,std::numeric_limits<double>::quiet_NaN(),std::numeric_limits<double>::infinity()}){
   auto bad=costs;bad[slot]=invalid;
   rejects([&]{(void)parabolicInverseDepth(bad,planes,1);});
  }
 }
 // Finite inputs can still overflow or extrapolate outside positive depth.
 rejects([&]{(void)parabolicInverseDepth(std::array<double,3>{.4,.1,.6},std::array<double,3>{2e-308,6e-309,1.},1);});
 rejects([&]{(void)parabolicInverseDepth(std::array<double,3>{.4,.1,.6},std::array<double,3>{2.,3.,.01},1);});
 // Preserve the established inverse-depth interpolation for admitted curves.
 const double expected=1./3.+(1./6.-1./3.)*(.5*(.6-.4)/(.6-2*.1+.4));
 assert(parabolicInverseDepth(costs,planes,1)==expected);
 assert(parabolicInverseDepth(std::array<double,3>{.4,.1,.4},planes,1)==1./3.);
 assert(parabolicInverseDepth(std::array<double,3>{.1,.1,.1},planes,1)==1./3.);
}
void planeTests(){
 using namespace rf::refinement;
 const auto v=camera();const auto truth=unit({.45,-.3,1});
 const auto depth=[&](double x,double y){return 4.7/(truth.x*(x-v.cx)/v.fx+truth.y*(y-v.cy)/v.fy+truth.z);};
 // Independent analytic plane/ray intersections, asymmetric calibration and
 // a sparse stride3 grid. Only observed camera-z samples enter the solver.
 for(int stride:{1,3}){
  const int origin=stride==1?0:2,cx=20,cy=17;
  auto map=observed(v,stride,origin,depth);
  const auto fit=planeHypotheses(v,map,cx+.2,cy-.15,stride,origin);
  assert(fit.count==6&&!fit.values[0].observed);
  assert(fit.values[1].observed&&inner(fit.values[1].cameraNormal,truth)>1-1e-8);
  const auto repeat=planeHypotheses(v,map,cx+.2,cy-.15,stride,origin);
  for(std::size_t i=0;i<fit.count;++i){
   const auto n=fit.values[i].cameraNormal;
   assert(fit.values[i].observed==(i!=0));assert(std::abs(inner(n,n)-1)<1e-12);
   assert(inner(n,repeat.values[i].cameraNormal)>1-1e-12);
   if(i>1)assert(inner(n,fit.values[1].cameraNormal)>.99);
  }
  for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){
   const double actual=planeDepth(fit.values[1],v,cx,cy,depth(cx,cy),cx+dx,cy+dy);
   assert(std::abs(actual-depth(cx+dx,cy+dy))<2e-5);
  }
  assert(planeDepth(fit.values[0],v,cx,cy,5,cx+2,cy-2)==5);
  // A missing diagonal is not invisible to a four-direction-only normal fit.
  const auto missing=(cy-stride)*v.width+cx-stride;
  const auto saved=map.depth[missing];map.depth[missing]=0;
  assert(planeHypotheses(v,map,cx,cy,stride,origin).count==1);
  map.depth[missing]=saved;map.confidence[missing]=0;
  assert(planeHypotheses(v,map,cx,cy,stride,origin).count==1);
  map.confidence[missing]=std::numeric_limits<float>::infinity();
  assert(planeHypotheses(v,map,cx,cy,stride,origin).count==1);
  map.confidence[missing]=.8f;map.depth[missing]=std::numeric_limits<float>::quiet_NaN();
  assert(planeHypotheses(v,map,cx,cy,stride,origin).count==1);
 }
 // Camera normals and relative plane depths preserve arbitrary SfM scale.
 for(double scale:{1e-20,1e-6,1e6,1e20}){
  const auto scaled=observed(v,1,0,[&](double x,double y){return scale*depth(x,y);});
  const auto fit=planeHypotheses(v,scaled,20,17,1,0);assert(fit.count==6);
  assert(inner(fit.values[1].cameraNormal,truth)>1-1e-8);
  const double z=planeDepth(fit.values[1],v,20,17,scale*depth(20,17),22,16);
  assert(std::abs(z/(scale*depth(22,16))-1)<1e-5);
 }
 // Smooth curvature yields a local tangent proposal, never a global surface.
 auto curvedView=v;curvedView.fx=120;curvedView.fy=110;
 const auto curved=[&](double x,double y){double rx=(x-v.cx)/120,ry=(y-v.cy)/110;return 4.5+.7*rx-.2*ry+8*rx*rx+6*ry*ry;};
 auto curvedMap=observed(curvedView,1,0,curved);
 const auto fit=planeHypotheses(curvedView,curvedMap,20,17,1,0);assert(fit.count==6);
 const double rx=(20-v.cx)/120,ry=(17-v.cy)/110,z=curved(20,17),zx=.7+16*rx,zy=-.2+12*ry;
 const rf::Vec3 tx{z+rx*zx,ry*zx,zx},ty{rx*zy,z+ry*zy,zy};
 const auto tangent=unit({tx.y*ty.z-tx.z*ty.y,tx.z*ty.x-tx.x*ty.z,tx.x*ty.y-tx.y*ty.x});
 assert(inner(fit.values[1].cameraNormal,tangent)>.9999);
 assert(std::abs(planeDepth(fit.values[1],curvedView,20,17,z,21,16)-curved(21,16))<.003);
 // Discontinuities are rejected even when a modest layer jump fits inside the
 // broad spread gate: the inverse-depth residual guards the plane seed.
 for(double background:{7.,4.1}){
  const auto layers=observed(v,1,0,[&](int x,int){return x<20?4.:background;});
  assert(planeHypotheses(v,layers,20,17,1,0).count==1);
 }
 auto map=observed(v,1,0,depth);
 assert(planeHypotheses(v,map,0,0,1,0).count==1);
 assert(planeHypotheses(v,map,20,17,0,0).count==1);
 assert(planeHypotheses(v,map,20,17,1,-1).count==1);
 assert(planeHypotheses(v,map,std::numeric_limits<double>::quiet_NaN(),17,1,0).count==1);
 map.confidence.pop_back();assert(planeHypotheses(v,map,20,17,1,0).count==1);
 auto invalid=v;invalid.fx=std::numeric_limits<double>::infinity();
 assert(planeHypotheses(invalid,curvedMap,20,17,1,0).count==1);
 // Public hypotheses are checked before intersection: backward, nonunit,
 // grazing, behind-camera, out-of-image and excessive depth ratios stay invalid.
 assert(planeDepth({{0,0,-1},true},v,20,17,5,20,17)==0);
 assert(planeDepth({{0,0,2},true},v,20,17,5,20,17)==0);
 assert(planeDepth({unit({1,0,.01}),true},v,v.cx,v.cy,5,v.cx,v.cy)==0);
 assert(planeDepth({},v,20,17,-5,20,17)==0);
 assert(planeDepth({},v,20,17,5,-1,17)==0);
 assert(planeDepth({},v,20,17,std::numeric_limits<double>::infinity(),20,17)==0);
 auto wide=v;wide.fx=10;
 assert(planeDepth({unit({1,0,1}),true},wide,v.cx,v.cy,5,v.cx-5.1,v.cy)==0);
 assert(planeDepth({unit({1,0,1}),true},wide,v.cx,v.cy,5,v.cx+10.1,v.cy)==0);
}
}
int main(){
 using namespace rf::refinement;
 std::array<SparsePrior,4> priors{{{2,.02,true},{4,.02,true},{8,.02,true},{100,.01,false}}};
 auto p=interval(priors,1,10);assert(p.nearDepth<2&&p.farDepth>8&&!p.conservativeFallback);
 assert(interval(std::span<const SparsePrior>(priors).first(1),1,10).conservativeFallback);
 // Analytic tilted plane evaluated independently at four image locations.
 for(double x:{-.8,-.2,.3,.9}){double truth=5+.7*x;auto r=local(3,5,7,12,[&](double d){return std::min(2.,100*std::pow(1/d-1/truth,2));});assert(r.valid&&std::abs(r.depth-truth)<.02&&r.evaluations==27);}
 auto missing=local(3,5,7,8,[](double){return 2.;});assert(!missing.valid);
 rf::DenseDepthMap m{2,2,{5,5.02f,5.03f,5.05f},{1,1,1,1}};assert(propagate(m,.5,.5)>5);
 m.depth[3]=2;assert(propagate(m,.5,.5)==0);m.depth[3]=0;assert(propagate(m,.5,.5)==0);
 rf::DenseDepthMap sparse{5,5,std::vector<float>(25,0),std::vector<float>(25,0)};for(int y=0;y<5;y+=2)for(int x=0;x<5;x+=2)sparse.depth[y*5+x]=5+.01f*x;assert(propagate(sparse,1,1,.05,2,0)>5);sparse.depth[2*5+2]=0;assert(propagate(sparse,1,1,.05,2,0)==0);
 peakBoundaryTests();
 planeTests();
 std::cout<<"multi-layer priors, bounded local depth/normal hypotheses, curved tangent and invalid/discontinuous sampled-grid checks passed\n";
}
