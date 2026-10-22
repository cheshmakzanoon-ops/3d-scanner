#include "realityforge/dense.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
void expectError(auto&& callback){bool failed=false;try{callback();}catch(const rf::Error&){failed=true;}require(failed,"invalid geometry accepted");}
// A textured physically planar surface, observed by cameras at different positions.
// Images are actually rendered from world coordinates, not copied and shifted arbitrarily.
std::vector<rf::DenseView> fixture(int count=3,bool texture=true,bool occlude=false){
    constexpr int w=104,h=76;
    std::mt19937 engine(504);std::uniform_int_distribution<int> rgb(0,255);
    std::vector<std::array<int,3>> surface(256*256);
    for(auto& p:surface)p={rgb(engine),rgb(engine),rgb(engine)};
    auto sample=[&](double x,double y,int channel){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        int ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto pix=[&](int dx,int dy){return surface[(iy+dy)*256+ix+dx][channel];};
        return (1-a)*(1-b)*pix(0,0)+a*(1-b)*pix(1,0)+(1-a)*b*pix(0,1)+a*b*pix(1,1);
    };
    std::vector<rf::DenseView> views;
    for(int cam=0;cam<count;++cam){
        rf::DenseView view;view.width=w;view.height=h;
        view.fx=116;view.fy=116;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-cam*.55,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            std::size_t idx=(y*w+x)*4;
            double wx=(x-view.cx)/view.fx*5.0+cam*.55;
            double wy=(y-view.cy)/view.fy*5.0;
            for(int c=0;c<3;++c)view.rgba[idx+c]=texture?static_cast<std::uint8_t>(std::round(sample(wx,wy,c))):128;
            view.rgba[idx+3]=occlude && cam==2 && x>20&&x<70&&y>15&&y<60?0:255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
}

// Independent pixel sampler and scalar covariance oracle for the fixture's
// parallel, equal-intrinsics cameras. No production warp/cost helper is reused.
double objective(const std::vector<rf::DenseView>& views,int x,int y,double depth){
 require(std::isfinite(depth)&&depth>=3.1&&depth<=7.,"nonfinite/out-of-range oracle depth");
 const auto gray=[](const rf::DenseView& v,int x,int y){auto i=(y*v.width+x)*4;return (77.*v.rgba[i]+150.*v.rgba[i+1]+29.*v.rgba[i+2])/256.;};
 const auto bilinear=[&](const rf::DenseView& v,double x,double y){int ix=int(std::floor(x)),iy=int(std::floor(y));require(ix>=0&&iy>=0&&ix+1<v.width&&iy+1<v.height,"oracle sample outside");double a=x-ix,b=y-iy;return (1-a)*(1-b)*gray(v,ix,iy)+a*(1-b)*gray(v,ix+1,iy)+(1-a)*b*gray(v,ix,iy+1)+a*b*gray(v,ix+1,iy+1);};
 std::array<double,25> a{},b{};double ma=0,mb=0;int i=0;
 for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){a[i]=gray(views[0],x+dx,y+dy);b[i]=bilinear(views[1],x+dx-views[0].fx*.55/depth,y+dy);ma+=a[i];mb+=b[i];++i;}
 ma/=25;mb/=25;double va=0,vb=0,c=0;for(i=0;i<25;++i){va+=(a[i]-ma)*(a[i]-ma);vb+=(b[i]-mb)*(b[i]-mb);c+=(a[i]-ma)*(b[i]-mb);}return 1-c/std::sqrt(va*vb);
}


int main(){
 auto fine=fixture(2),coarse=fine;
 for(auto& v:coarse){const auto old=v;v.width/=2;v.height/=2;v.fx/=2;v.fy/=2;v.cx=(v.cx+.5)/2-.5;v.cy=(v.cy+.5)/2-.5;v.rgba.resize(v.width*v.height*4);
  for(int y=0;y<v.height;++y)for(int x=0;x<v.width;++x)for(int c=0;c<4;++c){int sum=0;for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx)sum+=old.rgba[((2*y+dy)*old.width+2*x+dx)*4+c];v.rgba[(y*v.width+x)*4+c]=(sum+2)/4;}}
 rf::DenseRefinementInputs input;input.identitySha256=std::string(64,'a');input.coarseViews=coarse;
 require(coarse[0].width==52&&coarse[0].height==38,"actual half-resolution pyramid required");
 rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7;s.depthPlanes=32;s.pixelStride=2;s.normalRefinementIterations=0;s.normalConsistency=false;s.discontinuityAwareConsistency=true;s.workingSetBudgetBytes=128*1024*1024;
 for(unsigned iterations:{0u,6u}){
  s.localRefinementIterations=iterations;
  const auto baseline=rf::reconstructDense(fine,s),actual=rf::reconstructDense(fine,s,&input);
  const auto index=7*104+59;require(baseline.maps[0].depth[index]>0&&actual.maps[0].depth[index]>0,"missing observed depth");
  const double before=objective(fine,59,7,baseline.maps[0].depth[index]),after=objective(fine,59,7,actual.maps[0].depth[index]);
  std::cerr<<"local="<<iterations<<" objective before="<<before<<" after="<<after<<" proposals="<<actual.propagatedHypotheses<<'\n';
  require(std::isfinite(before)&&std::isfinite(after)&&after<=before+1e-6,"coarse proposal worsened incumbent objective");
  require(actual.propagatedHypotheses>0,"coarse path was not exercised");
  auto invalidCoarse=coarse;for(auto& v:invalidCoarse)for(std::size_t i=3;i<v.rgba.size();i+=4)v.rgba[i]=0;
  auto invalidInputs=input;invalidInputs.coarseViews=invalidCoarse;
  const auto unsupported=rf::reconstructDense(fine,s,&invalidInputs);
  require(unsupported.propagatedHypotheses==0,"invalid source pixels created a proposal");
  for(std::size_t i=0;i<baseline.maps.size();++i)require(unsupported.maps[i].depth==baseline.maps[i].depth,"missing coarse support changed incumbent depths");
 }
 for(double invalid:{std::numeric_limits<double>::quiet_NaN(),std::numeric_limits<double>::infinity()}){
  auto invalidFine=fine;invalidFine[0].fx=invalid;expectError([&]{rf::reconstructDense(invalidFine,s,&input);});
  auto invalidCoarse=coarse;invalidCoarse[0].fx=invalid;auto invalidInput=input;invalidInput.coarseViews=invalidCoarse;expectError([&]{rf::reconstructDense(fine,s,&invalidInput);});
 }
 std::cout<<"coarse incumbent objective, local0/local6, unsupported pixels and nonfinite calibration passed\n";
}
