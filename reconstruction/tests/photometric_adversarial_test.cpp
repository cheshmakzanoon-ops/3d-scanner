#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/photometric.hpp"
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include <cassert>
#include <limits>
int main(){
 std::vector<rf::ExposureSample> s;
 for(int i=0;i<100;++i){double x=.1+.003*i;s.push_back({0,1,{x,x*.8,x*.9},{x*1.4,x*.8*.8,x*.9*1.1}});}
 auto r=rf::balanceExposure(3,s);assert(r.correctionAccepted&&r.heldOutSamples==20&&r.heldOutLogErrorAfter<1e-10);
 assert(r.cameraSupported[0]&&r.cameraSupported[1]&&!r.cameraSupported[2]);
 assert(r.gains[2]==(std::array<double,3>{1,1,1}));
 for(auto& x:s) {x.b={.99,.99,.99};}
 r=rf::balanceExposure(3,s);assert(!r.correctionAccepted&&r.rejectedSamples==100);
 bool rejected=false;try{rf::linearToSrgb(std::numeric_limits<double>::quiet_NaN());}catch(const rf::Error&){rejected=true;}assert(rejected);
 std::atomic_bool flag{true};rejected=false;
 try{rf::cancellation::Scope scope(&flag);rf::balanceExposure(2,s);}catch(const rf::cancellation::Cancelled&){rejected=true;}assert(rejected);
 std::vector<rf::ExposureSample> adversarial;
 for(int i=0;i<100;++i)adversarial.push_back({0,1,{.2,.2,.2},{i%5==0?.2:.24,.28,.28}});
 const auto separated=rf::balanceExposure(2,adversarial);
 assert(!separated.channelCorrectionAccepted[0]&&separated.gains[1][0]==1);
 assert(separated.channelCorrectionAccepted[1]&&separated.channelCorrectionAccepted[2]);
 assert(separated.heldOutChannelErrorAfter[0]==0&&separated.correctionAccepted);
 for(auto& p:adversarial){p.b={.28,.2,.2};}
 const auto redOnly=rf::balanceExposure(2,adversarial);
 assert(redOnly.channelCorrectionAccepted[0]&&redOnly.gains[1][0]<1&&redOnly.gains[1][1]==1&&redOnly.gains[1][2]==1);

}
