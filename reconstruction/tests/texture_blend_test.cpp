#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_blend.hpp"
#include <cassert>
int main(){
 std::vector<rf::VerifiedTexelSample> samples{{0,4,{.2,.3,.4},{1,1,1},1,true},{1,4,{.22,.32,.42},{1,1,1},1,true}};
 auto r=rf::blendVerifiedTexel(4,samples);assert(r.acceptedSources==2&&r.rgba[3]==255&&r.sourceMask==3);
 assert(std::abs(r.rgba[0]/255.-rf::linearToSrgb(.21))<.005);
 samples[1].chart=5;r=rf::blendVerifiedTexel(4,samples);assert(r.sourceMask==1);
 samples[0].visible=false;r=rf::blendVerifiedTexel(4,samples);assert(r.rgba[3]==0&&r.sourceMask==0);
 samples[0].visible=true;samples[1].chart=4;samples[1].linear={.8,.8,.8};
 r=rf::blendVerifiedTexel(4,samples);assert(r.acceptedSources==1&&r.rejectedSources==1);
 // Tiling cannot mix neighboring charts, holes or high-frequency source detail.
 std::vector<rf::TexelSources> texels{{4,samples},{9,{}}};auto tile=rf::blendVerifiedTile(texels);
 assert(tile[0].rgba==r.rgba&&tile[1].rgba[3]==0);
 for(int i=0;i<100;++i){double color=i%2?.25:.35;std::vector<rf::VerifiedTexelSample> p{{0,0,{color,color,color},{1,1,1},1,true},{1,0,{color,color,color},{1,1,1},1,true}};auto t=rf::blendVerifiedTexel(0,p);assert(std::abs(t.rgba[0]/255.-rf::linearToSrgb(color))<.005);}
}
