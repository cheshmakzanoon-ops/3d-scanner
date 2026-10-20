#include "realityforge/depth_consistency.hpp"
#include <cassert>
#include <iostream>
int main(){
 rf::DenseDepthMap m{8,8,std::vector<float>(64,0),std::vector<float>(64,0)};
 for(int y=1;y<8;y+=2)for(int x=1;x<8;x+=2){m.depth[y*8+x]=5+.01*x+.02*y;m.confidence[y*8+x]=.8;}
 auto a=rf::consistency::sampleDepth(m,2,2,2,1);assert(a.classification==rf::consistency::Visibility::Supported&&std::abs(a.depth-5.06)<1e-6);
 m.depth[3*8+3]=3;assert(rf::consistency::sampleDepth(m,2,2,2,1).classification==rf::consistency::Visibility::Discontinuity);
 m.depth[3*8+3]=0;assert(rf::consistency::sampleDepth(m,2,2,2,1).classification==rf::consistency::Visibility::Unknown);
 assert(rf::consistency::sampleDepth(m,-1,2,2,1).classification==rf::consistency::Visibility::Outside);
 using V=rf::consistency::Visibility;rf::consistency::Sample s{V::Supported,3,.8,2,2};
 assert(rf::consistency::verify(s,5,0,.05,1).classification==V::Occluded);
 assert(rf::consistency::verify(s,2,0,.05,1).classification==V::Inconsistent);
 assert(rf::consistency::verify(s,3,.5,.05,1).classification==V::Supported);
 assert(rf::consistency::verify(s,3,2,.05,1).classification==V::Inconsistent);
 std::cout<<"continuous interpolation, thin-edge unknown, occlusion and reverse projection checks passed\n";
}
