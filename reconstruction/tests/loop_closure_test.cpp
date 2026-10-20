#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/loop_closure.hpp"
#include "realityforge/cancellation.hpp"
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
int main(){
    rf::LoopProposal p;p.sourcePhotoId="first";p.revisitPhotoId="revisit";p.componentId=p.revisitComponentId="object-A";
    p.currentPose={rf::identity(),{-.24,.08,.05}};p.revisitCamera=8;
    const rf::Vec3 t{-.2,.03,.01};
    for(std::size_t i=0;i<100;++i){const rf::Vec3 w{double(i%10)*.17-.7,double(i/10)*.16-.6,3.5+double(i%7)*.2};
        p.landmarks.push_back({1000+i,w,{(w.x+t.x)/(w.z+t.z),(w.y+t.y)/(w.z+t.z)},3});}
    const auto d=rf::verifyLoopClosure(p);assert(d.accepted&&d.constraint.supportingTrackIds.size()>90);
    assert(std::hypot(d.constraint.correctedPose.translation.x-t.x,d.constraint.correctedPose.translation.y-t.y)<1e-5);
    auto falseLoop=p;falseLoop.revisitComponentId="object-B";assert(rf::verifyLoopClosure(falseLoop).reason=="disconnected_components");
    falseLoop=p;falseLoop.landmarks[1].stableTrack=falseLoop.landmarks[0].stableTrack;assert(!rf::verifyLoopClosure(falseLoop).accepted);
    falseLoop=p;for(std::size_t i=0;i<falseLoop.landmarks.size();++i)falseLoop.landmarks[i].normalizedRevisit={double((i*17)%31)/30.,double((i*23)%37)/36.};
    assert(!rf::verifyLoopClosure(falseLoop).accepted);
    std::atomic_bool cancel=true;bool stopped=false;try{rf::cancellation::Scope scope(&cancel);rf::verifyLoopClosure(p);}catch(const rf::cancellation::Cancelled&){stopped=true;}assert(stopped);
    std::cout<<"verified loop resection, false-loop rejection, stable identity and cancellation passed\n";
}
