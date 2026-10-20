#include "realityforge/depth_refinement.hpp"
#include <cassert>
#include <iostream>
int main(){
 using namespace rf::refinement;
 std::array<SparsePrior,4> priors{{{2,.02,true},{4,.02,true},{8,.02,true},{100,.01,false}}};
 auto p=interval(priors,1,10);assert(p.nearDepth<2&&p.farDepth>8&&!p.conservativeFallback);
 assert(interval(std::span<const SparsePrior>(priors).first(1),1,10).conservativeFallback);
 // Analytic tilted plane evaluated independently at four image locations.
 for(double x:{-.8,-.2,.3,.9}){double truth=5+.7*x;auto r=local(3,5,7,12,[&](double d){return std::min(2.,100*std::pow(1/d-1/truth,2));});assert(r.valid&&std::abs(r.depth-truth)<.02&&r.evaluations==25);}
 auto missing=local(3,5,7,8,[](double){return 2.;});assert(!missing.valid);
 rf::DenseDepthMap m{2,2,{5,5.02f,5.03f,5.05f},{1,1,1,1}};assert(propagate(m,.5,.5)>5);
 m.depth[3]=2;assert(propagate(m,.5,.5)==0);m.depth[3]=0;assert(propagate(m,.5,.5)==0);
 std::cout<<"multi-layer priors, slanted local inverse-depth refinement and discontinuity-safe pyramid checks passed\n";
}
