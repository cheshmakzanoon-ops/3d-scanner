#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_blend.hpp"
#include <cassert>
#include <iostream>
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
 samples={{0,4,{.2,.3,.4},{1,1,1},std::numeric_limits<double>::max(),true},{1,4,{.22,.32,.42},{1,1,1},std::numeric_limits<double>::max(),true}};
 r=rf::blendVerifiedTexel(4,samples);assert(r.acceptedSources==2&&r.rgba[3]==255);
 samples[1].weight=std::numeric_limits<double>::denorm_min();r=rf::blendVerifiedTexel(4,samples);assert(r.rgba[3]==255);
 samples[1].weight=std::numeric_limits<double>::quiet_NaN();bool rejected=false;try{rf::blendVerifiedTexel(4,samples);}catch(const rf::Error&){rejected=true;}assert(rejected);
 rf::ChartBlendTile image;image.width=12;image.height=10;image.halo=2;
 for(int y=0;y<image.height;++y)for(int x=0;x<image.width;++x){
  const double color=x%2?.2:.3;image.texels.push_back({7,{{0,7,{color,color,color},{1,1,1},x<6?1.:.2,true},{1,7,{color+.01,color+.01,color+.01},{1,1,1},x<6?.2:1.,true}}});
 }
 auto full=rf::blendChartTile(image);assert(full.width==8&&full.height==6);
 rf::ChartBlendTile part;part.width=8;part.height=10;part.halo=2;
 for(int y=0;y<10;++y)for(int x=0;x<8;++x)part.texels.push_back(image.texels[y*12+x]);
 auto left=rf::blendChartTile(part);
 for(int y=0;y<6;++y)for(int x=0;x<4;++x)assert(left.texels[y*4+x].rgba==full.texels[y*8+x].rgba);
 image.texels[5*12+5].samples.clear();auto hole=rf::blendChartTile(image);assert(hole.texels[3*8+3].rgba[3]==0);
 struct P{double u,v;};struct C{std::size_t sourceFace;std::array<P,3> uv;};struct L{std::vector<C> charts;};
 L layout{{{0,{{P{0,0},P{1,0},P{0,1}}}}}};auto mapped=rf::mapLodTexel(layout,{0,{.2,.3,.5}});assert(mapped[0]==.3&&mapped[1]==.5);

 auto constant=image;
 for(auto& texel:constant.texels)if(!texel.samples.empty()){texel.samples[0].linear={.2,.2,.2};texel.samples[1].linear={.22,.22,.22};}
 // Restore the deliberately removed central hole for the seam measurement.
 constant.texels[5*12+5]=constant.texels[4*12+5];
 const auto beforeLeft=rf::blendVerifiedTexel(7,constant.texels[5*12+5].samples),beforeRight=rf::blendVerifiedTexel(7,constant.texels[5*12+6].samples);
 const auto smoothed=rf::blendChartTile(constant);
 const double beforeSeam=std::abs(rf::srgbToLinear(beforeLeft.rgba[0]/255.)-rf::srgbToLinear(beforeRight.rgba[0]/255.));
 const double afterSeam=std::abs(rf::srgbToLinear(smoothed.texels[3*8+3].rgba[0]/255.)-rf::srgbToLinear(smoothed.texels[3*8+4].rgba[0]/255.));
 assert(afterSeam<beforeSeam*.8);std::cout<<"independent constant-surface seam before="<<beforeSeam<<" after="<<afterSeam<<'\n';

}
