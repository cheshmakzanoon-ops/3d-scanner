#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/multiview.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <string>
#include <cstdlib>
#include <new>
static int failAfter=-1;
void* operator new(std::size_t size){if(failAfter==0)throw std::bad_alloc();if(failAfter>0)--failAfter;void* p=std::malloc(size?size:1);if(!p)throw std::bad_alloc();return p;}
void operator delete(void* p) noexcept {std::free(p);}
void operator delete(void* p,std::size_t) noexcept {std::free(p);}
int main(){
 auto state=std::make_shared<rf::progress::State>();rf::progress::Scope reporting(state);
 for(int failure=0;failure<8;++failure){failAfter=failure;char* value=rf_multiview_json(nullptr,0,nullptr,0,.003,0);failAfter=-1;if(value)rf_free(value);}
 for(double scale:{.01,1.,100.}){
  std::vector<rf::ViewPose> poses;for(int i=0;i<5;++i)poses.push_back({rf::identity(),{-i*.3*scale,0,0}});
  std::vector<rf::TrackObservation> obs;
  for(std::size_t track=0;track<40;++track){rf::Vec3 p{(double(track%8)-3.5)*.1*scale,(double(track/8)-2)*.1*scale,(3.+double(track%3)*.2)*scale};
   for(std::size_t cam=0;cam<5;++cam){auto q=rf::multiply(poses[cam].rotation,p);q.x+=poses[cam].translation.x;double err=cam==4&&track%3==0?.3:0.;obs.push_back({track+300,cam,{q.x/q.z,q.y/q.z+err},{1,2,3}});}}
  rf::MultiViewSettings settings;settings.iterations=0;auto result=rf::reconstructMultiView(poses,obs,settings);assert(result.tracks.size()==40);
  for(auto& t:result.tracks){assert(t.track>=300);assert(t.supportingViews>=4);assert(t.supportingCameras.size()==t.supportingViews);assert(t.angularCondition>=1&&std::isfinite(t.angularCondition));
   auto index=t.track-300;assert(std::abs(t.point.xyz.z/scale-(3.+double(index%3)*.2))<1e-6);}
 }
 for(int collection:{30,80,250}){
  for(int first=0;first<collection;first+=2){
   std::vector<rf::ViewPose> poses{{rf::identity(),{}},{rf::identity(),{-.5,0,0}}};
   std::vector<rf::TrackObservation> rows{{0,0,{.1,.02},{}},{0,1,{0,.02},{}}};
   rf::NativeWindowMapping map{{"photo-"+std::to_string(first),"photo-"+std::to_string(first+1)},{"stable-track-"+std::to_string(first)}};
   rf::MultiViewSettings settings;settings.iterations=0;auto out=rf::reconstructMultiViewWindow(poses,rows,map,settings);
   assert(out.size()==1&&out[0].trackId==map.trackIds[0]&&out[0].cameraIds==map.cameraIds);
   assert(std::abs(out[0].estimate.point.xyz.z-5)<1e-8);
  }
 }
 {std::vector<rf::ViewPose> poses{{rf::identity(),{}},{rf::identity(),{-1e-5,0,0}}};std::vector<rf::TrackObservation> rows{{0,0,{.1,.02},{}},{0,1,{.1-2e-6,.02},{}}};
  bool weak=false;try{rf::reconstructMultiView(poses,rows);}catch(const rf::Error&){weak=true;}assert(weak);}
 auto measured=state->snapshot();assert(measured.stage==rf::progress::Stage::Sparse&&measured.tile==2&&measured.completed==measured.total);
 char* error=rf_multiview_json(nullptr,0,nullptr,0,.003,0);assert(error&&std::string(error).find("error")!=std::string::npos);rf_free(error);
 std::atomic_bool stop{true};rf::cancellation::Scope scope(&stop);std::vector<rf::ViewPose> cameras{{rf::identity(),{}},{rf::identity(),{-1,0,0}}};std::vector<rf::TrackObservation> rows{{1,0,{0,0},{}},{1,1,{-.2,0},{}}};
 bool cancelled=false;try{rf::reconstructMultiView(cameras,rows);}catch(const rf::cancellation::Cancelled&){cancelled=true;}assert(cancelled);
 std::cout<<"triangulation: independent scales, outlier support/provenance, conditioning and cancellation passed\n";
}
