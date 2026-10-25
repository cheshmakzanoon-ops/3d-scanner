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
#include <limits>
#include <algorithm>
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
 // Exact observation support, including planar initialization, preserves raw provenance.
 for(bool plane:{false,true}){
  std::vector<rf::Observation> base;
  for(int i=0;i<32;++i){rf::Vec3 p{(i%8-4)*.15,(i/8-2)*.13,plane?4.:3.5+(i%7)*.17};
   base.push_back({p,{(p.x+.21)/(p.z+.3),(p.y-.12)/(p.z+.3)}});}
  const auto expected=rf::registerCamera(base,.001);
  std::vector<rf::Observation> duplicated;std::vector<std::size_t> originals;
  for(const auto& row:base){originals.push_back(duplicated.size());duplicated.push_back(row);
   auto signedZero=row;
   for(double* value:{&signedZero.world.x,&signedZero.world.y,&signedZero.world.z,&signedZero.image.x,&signedZero.image.y})
    if(*value==0)*value=-0.;
   duplicated.push_back(signedZero);duplicated.push_back(row);}
  const auto actual=rf::registerCamera(duplicated,.001);
  assert(actual.inputCount==duplicated.size());assert(actual.inliers.size()==expected.inliers.size());
  assert(actual.rotation==expected.rotation);assert(actual.translation.x==expected.translation.x);
  assert(actual.translation.y==expected.translation.y&&actual.translation.z==expected.translation.z);
  assert(actual.medianError==expected.medianError&&actual.hypothesesTested==expected.hypothesesTested);
  assert(actual.usedPlanarInitialization==expected.usedPlanarInitialization);
  for(std::size_t i=0;i<actual.inliers.size();++i)assert(actual.inliers[i]==originals[expected.inliers[i]]);
  std::vector<rf::Observation> inflated;
  for(int copy=0;copy<4;++copy)inflated.insert(inflated.end(),base.begin(),base.begin()+15);
  bool distinctRejected=false;try{rf::registerCamera(inflated,.001);}
  catch(const rf::Error& error){distinctRejected=std::string(error.what()).find("16 distinct")!=std::string::npos;}
  assert(distinctRejected);
  // All raw rows must validate even if earlier duplicates already lack support.
  inflated.back().image.x=std::numeric_limits<double>::quiet_NaN();
  bool invalidRejected=false;try{rf::registerCamera(inflated,.001);}
  catch(const rf::Error& error){invalidRejected=std::string(error.what()).find("PnP coordinate")!=std::string::npos;}
  assert(invalidRejected);
  // Raw work limits cannot be bypassed by repetition of otherwise valid rows.
  std::vector<rf::Observation> over(5001,base.front());bool bounded=false;
  try{rf::registerCamera(over,.001);}catch(const rf::Error& error){bounded=std::string(error.what()).find("input size")!=std::string::npos;}assert(bounded);
  std::vector<double> raw;for(const auto& row:duplicated)for(double v:{row.world.x,row.world.y,row.world.z,row.image.x,row.image.y})raw.push_back(v);
  char* json=rf_register_camera_json(raw.data(),duplicated.size(),.001);
  assert(json&&std::string(json).find("\"inputTracks\":96")!=std::string::npos);rf_free(json);
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
