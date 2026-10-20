
#include "realityforge/surface_evidence.hpp"
#include "baseline.hpp"
#include <random>
#include <iostream>
#include <stdexcept>
void require(bool condition){if(!condition)throw std::runtime_error("independent weighted provider review failed");}
int main(){
 std::mt19937_64 random(540596);std::uniform_real_distribution<double> coordinate(-.3,.3),noise(-.02,.02),confidence(.01,1.);
 unsigned parity=0,reliability=0,layer=0;
 for(int fixture=0;fixture<400;++fixture){
  const std::size_t count=5+random()%60;std::vector<rf::NormalObservation> current;std::vector<rf::BaselineObservation> old;
  for(std::size_t i=0;i<count;++i){const rf::Vec3 point{coordinate(random),coordinate(random),1+noise(random)};current.push_back({point,{0,0,0},i});old.push_back({point,{0,0,0},i});}
  const auto baseline=rf::baselineNormal(old,{0,0,1});
  for(double weight:{1.,.125,1e-300}){
   for(auto& o:current)o.weight=weight;
   const auto result=rf::estimateObservedNormal(current,{0,0,1});
   require(static_cast<int>(baseline.validity)==static_cast<int>(result.validity));
   require(result.direction.x==baseline.direction.x&&result.direction.y==baseline.direction.y&&result.direction.z==baseline.direction.z);
   require(result.uncertainty.planeResidual==baseline.uncertainty.planeResidual&&result.uncertainty.conditioning==baseline.uncertainty.conditioning&&result.uncertainty.estimatedPositionSigma==baseline.uncertainty.estimatedPositionSigma);
   require(result.supportingObservationIds==baseline.supportingObservationIds);
   require(result.uncertainty.effectiveSupport==count);++parity;
  }
  long double total=0,square=0;for(auto& o:current){o.weight=confidence(random);total+=o.weight;square+=static_cast<long double>(o.weight)*o.weight;}
  const auto result=rf::estimateObservedNormal(current,{0,0,1});
  require(std::isfinite(result.uncertainty.effectiveSupport)&&std::abs(result.uncertainty.effectiveSupport-static_cast<double>(total*total/square))<1e-11);
  require(!result.uncertainty.calibrated);
  if(baseline.validity==rf::BaselineValidity::Discontinuity)require(result.validity!=rf::NormalValidity::Supported);
  ++reliability;
 }
 // Arbitrary nonuniform weights cannot override the old explicit layer veto.
 for(int fixture=0;fixture<100;++fixture){
  std::vector<rf::NormalObservation> observations;
  for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x){const auto i=observations.size();observations.push_back({{x*.1,y*.1,2+(i%2)*.02},{0,0,0},i,confidence(random)});}
  require(rf::estimateObservedNormal(observations,{0,0,2}).validity!=rf::NormalValidity::Supported);++layer;
 }
 std::cout<<"{\"uniform_parity_cases\":"<<parity<<",\"random_reliability_cases\":"<<reliability<<",\"parallel_layer_safety_cases\":"<<layer<<"}\n";
}
