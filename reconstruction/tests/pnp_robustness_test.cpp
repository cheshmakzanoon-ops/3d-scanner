#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/pnp.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <cassert>
#include <cmath>
#include <atomic>
#include <iostream>
#include <string>
#include <thread>
#include <chrono>
#include <cstdlib>
#include <new>
static int failAfter=-1;
void* operator new(std::size_t size){if(failAfter==0)throw std::bad_alloc();if(failAfter>0)--failAfter;void* p=std::malloc(size?size:1);if(!p)throw std::bad_alloc();return p;}
void operator delete(void* p) noexcept {std::free(p);}
void operator delete(void* p,std::size_t) noexcept {std::free(p);}
rf::Mat3 rotation(double x,double y,double z){rf::Mat3 a{1,0,0,0,std::cos(x),-std::sin(x),0,std::sin(x),std::cos(x)};
 rf::Mat3 b{std::cos(y),0,std::sin(y),0,1,0,-std::sin(y),0,std::cos(y)},c{std::cos(z),-std::sin(z),0,std::sin(z),std::cos(z),0,0,0,1};return rf::multiply(c,rf::multiply(b,a));}
int main(){
 auto state=std::make_shared<rf::progress::State>();rf::progress::Scope reporting(state);
 for(double scale:{.01,1.,100.})for(bool plane:{false,true}){
  std::vector<rf::Observation> rows;
  for(int i=0;i<100;++i){rf::Vec3 p{scale*((i%10)-4.5)*.13,scale*((i/10)-4.5)*.11,scale*(plane?4.:3.5+(i%7)*.17)};
   rf::Vec3 q{p.x+.21*scale,p.y-.12*scale,p.z+.3*scale};
   rf::Vec2 uv{q.x/q.z+std::sin(i)*.0001,q.y/q.z+std::cos(i)*.0001};
   if(i%5==0)uv={.8*std::sin(i),.8*std::cos(i)};
   rows.push_back({p,uv});}
  auto p=rf::registerCamera(rows,.001);assert(p.hypothesesTested>=61&&p.hypothesesTested<=450);assert(p.usedPlanarInitialization==plane);assert(p.worldPlanarityRatio>=0&&p.worldPlanarityRatio<=1);
  assert(p.inliers.size()>=78);assert(p.medianError<.0002);
  assert(std::abs(p.translation.x/scale-.21)<.015);
  assert(std::abs(p.translation.y/scale+.12)<.015);
  assert(std::abs(p.translation.z/scale-.3)<.015);
  for(int j=0;j<9;++j)assert(std::abs(p.rotation[j]-(j%4==0?1.:0.))<.005);
 }
 for(double angle:{-.25,.3})for(double thickness:{0.,.00001,.02}){
  auto plane=rotation(.2,-.15,.1),truth=rotation(angle,-angle*.6,angle*.4);rf::Vec3 translation{.13,-.07,.4};std::vector<rf::Observation> rows;
  for(int i=0;i<120;++i){rf::Vec3 local{(i%12-5.5)*.12,(i/12-4.5)*.1,thickness*std::sin(i)};auto p=rf::multiply(plane,local);p.z+=4;
   auto q=rf::multiply(truth,p);q.x+=translation.x;q.y+=translation.y;q.z+=translation.z;
   rf::Vec2 uv{q.x/q.z+std::sin(i)*.00003,q.y/q.z+std::cos(i)*.00003};if(i%6==0)uv={.6*std::sin(i),.6*std::cos(i)};rows.push_back({p,uv});}
  auto pose=rf::registerCamera(rows,.0015);assert(pose.inliers.size()>=95);for(int j=0;j<9;++j)assert(std::abs(pose.rotation[j]-truth[j])<.015);
  assert(std::abs(pose.translation.x-translation.x)<.06);assert(std::abs(pose.translation.y-translation.y)<.06);assert(std::abs(pose.translation.z-translation.z)<.06);
 }
 auto snapshot=state->snapshot();assert(snapshot.stage==rf::progress::Stage::Sparse&&snapshot.tile==1&&snapshot.completed>0&&!snapshot.total);
 for(int failure=0;failure<8;++failure){failAfter=failure;char* value=rf_register_camera_json(nullptr,0,.001);failAfter=-1;if(value)rf_free(value);}
 {std::vector<rf::Observation> many;for(int i=0;i<5000;++i){rf::Vec3 p{std::sin(i)*.8,std::cos(i*.7)*.7,3.+(i%10)*.2};many.push_back({p,{(p.x-.2)/p.z,p.y/p.z}});}
  std::atomic_bool runningStop{false};rf::cancellation::Scope runningScope(&runningStop);std::thread stopper([&]{std::this_thread::sleep_for(std::chrono::milliseconds(1));runningStop=true;});
  bool interrupted=false;try{rf::registerCamera(many,.001);}catch(const rf::cancellation::Cancelled&){interrupted=true;}stopper.join();assert(interrupted);}
 std::vector<rf::Observation> rows(20,{{0,0,4},{0,0}});
 bool rejected=false;try{rf::registerCamera(rows);}catch(const rf::Error&){rejected=true;}assert(rejected);
 std::atomic_bool stop{true};rf::cancellation::Scope scope(&stop);
 bool cancelled=false;try{rf::registerCamera(rows);}catch(const rf::cancellation::Cancelled&){cancelled=true;}assert(cancelled);
 char* error=rf_register_camera_json(nullptr,0,.001);assert(error&&std::string(error).find("error")!=std::string::npos);rf_free(error);
 std::cout<<"pnp robustness: six scale/planarity/outlier fixtures, cancellation and C ABI passed\n";
}
