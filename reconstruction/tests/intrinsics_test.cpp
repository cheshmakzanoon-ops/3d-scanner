#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/intrinsics.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
int main(){
    rf::IntrinsicModel m; m.width=1920;m.height=1080;m.lensCropIdentity="synthetic-asymmetric-v1";
    m.parameters={1330,1280,941.2,527.7,-.11,.025,.002,-.001,.004};
    for(auto q:{rf::Vec2{-.65,-.38},rf::Vec2{.7,.4},rf::Vec2{.02,-.03}}){
        const auto p=rf::projectIntrinsic(m,q);const auto inv=rf::unprojectIntrinsic(m,p.pixel);
        assert(std::hypot(inv.x-q.x,inv.y-q.y)<1e-9);
        for(std::size_t k=0;k<9;++k){auto a=m,b=m;const double h=k<4?1e-4:1e-6;a.parameters[k]+=h;b.parameters[k]-=h;
            const auto pa=rf::projectIntrinsic(a,q).pixel,pb=rf::projectIntrinsic(b,q).pixel;
            assert(std::abs((pa.x-pb.x)/(2*h)-p.parameterJacobian[k])<2e-5);
            assert(std::abs((pa.y-pb.y)/(2*h)-p.parameterJacobian[9+k])<2e-5);
        }
        const double h=1e-6;
        for(int k=0;k<2;++k){auto a=q,b=q;(k==0?a.x:a.y)+=h;(k==0?b.x:b.y)-=h;
            const auto pa=rf::projectIntrinsic(m,a).pixel,pb=rf::projectIntrinsic(m,b).pixel;
            assert(std::abs((pa.x-pb.x)/(2*h)-p.rayJacobian[k])<2e-5);
            assert(std::abs((pa.y-pb.y)/(2*h)-p.rayJacobian[2+k])<2e-5);}
    }
    m.refinable[0]=true;m.refinable[4]=true;
    assert(rf::intrinsicObservability(m,{4,200,.7,1.01})==rf::IntrinsicObservability::WeakDepthExtent);
    rf::IntrinsicPrior prior;prior.centre=m.parameters;prior.sigma.fill(.1);prior.lower.fill(-1e6);prior.upper.fill(1e6);
    const std::array<double,9> step{5,5,5,5,.01,5,5,5,5};
    assert(rf::applyIntrinsicStep(m,prior,step,rf::IntrinsicObservability::WeakDepthExtent).parameters==m.parameters);
    const auto changed=rf::applyIntrinsicStep(m,prior,step,rf::IntrinsicObservability::Observable);
    assert(changed.parameters[1]==m.parameters[1]&&changed.parameters[0]<=m.parameters[0]+.300001);
    bool rejected=false;try{m.parameters[0]=0;rf::projectIntrinsic(m,{0,0});}catch(const rf::Error&){rejected=true;}assert(rejected);
    std::cout<<"intrinsic finite differences, ray inversion and prior admission passed\n";
}
