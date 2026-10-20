#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_blend.hpp"
#include <cassert>
#include <iostream>
#include <bit>
#include <optional>

// Pure tile jobs can be retained and recomputed in any order. This fixture
// models interruption between completed tiles; it does not claim durable files.
void verifyInterruptedTwoDimensionalTiles(){
 constexpr int width=69,height=65,tileWidth=13,tileHeight=11;
 auto sourceAt=[](int x,int y){
  const std::size_t chart=10+(x>=33?1:0)+(y>=31?2:0);
  rf::TexelSources result;result.chart=chart;
  if((x-32)*(x-32)+(y-31)*(y-31)<16||(x==18&&y>8&&y<53))return result;
  const double color=.20+.005*((x%3+3)%3+(y%3+3)%3);
  const bool swapped=(x/7+y/5)%2==0;
  result.samples={{0,chart,{color,color,color},{1,1,1},swapped?1.:.15,true},
                  {1,chart,{color+.01,color+.01,color+.01},{1,1,1},swapped?.15:1.,x!=17&&y!=29}};
  if(x==34&&y%3==0)result.samples[1].chart=chart+7;
  return result;
 };
 auto encode=[](const std::vector<rf::BlendedTexel>& pixels){
  std::vector<std::uint8_t> bytes;
  auto integer=[&](std::uint64_t value){for(int i=0;i<8;++i)bytes.push_back(static_cast<std::uint8_t>(value>>(8*i)));};
  for(const auto& pixel:pixels){bytes.insert(bytes.end(),pixel.rgba.begin(),pixel.rgba.end());integer(pixel.sourceMask);integer(pixel.acceptedSources);integer(pixel.rejectedSources);integer(std::bit_cast<std::uint64_t>(pixel.weightedColorResidual));}
  return bytes;
 };
 rf::ChartBlendTile input;input.width=width+4;input.height=height+4;input.halo=2;
 for(int y=-2;y<height+2;++y)for(int x=-2;x<width+2;++x)input.texels.push_back(sourceAt(x,y));
 const auto untiled=encode(rf::blendChartTile(input).texels);
 struct Job{int x,y,width,height;};std::vector<Job> jobs;
 for(int y=0;y<height;y+=tileHeight)for(int x=0;x<width;x+=tileWidth)jobs.push_back({x,y,std::min(tileWidth,width-x),std::min(tileHeight,height-y)});
 auto bake=[&](std::size_t id){
  const auto job=jobs[id];rf::ChartBlendTile tile;tile.width=job.width+4;tile.height=job.height+4;tile.halo=2;
  for(int y=-2;y<job.height+2;++y)for(int x=-2;x<job.width+2;++x)tile.texels.push_back(sourceAt(job.x+x,job.y+y));
  return encode(rf::blendChartTile(tile).texels);
 };
 constexpr std::size_t recordBytes=4+4*8;
 auto place=[&](std::vector<std::uint8_t>& destination,std::size_t id,const std::vector<std::uint8_t>& payload){
  const auto job=jobs[id];assert(payload.size()==static_cast<std::size_t>(job.width*job.height)*recordBytes);
  for(int y=0;y<job.height;++y)std::copy_n(payload.begin()+static_cast<std::ptrdiff_t>(y*job.width*recordBytes),job.width*recordBytes,destination.begin()+static_cast<std::ptrdiff_t>(((job.y+y)*width+job.x)*recordBytes));
 };
 std::vector<std::uint8_t> clean(width*height*recordBytes);
 for(std::size_t i=0;i<jobs.size();++i)place(clean,i,bake(i));
 assert(clean==untiled);
 std::vector<std::optional<std::vector<std::uint8_t>>> completed(jobs.size());
 // A non-raster prefix covers both row and column boundaries before interruption.
 for(std::size_t i=0;i<11;++i){const auto id=i*13%jobs.size();assert(!completed[id]);completed[id]=bake(id);}
 const auto before=completed;
 std::atomic_bool stop{true};bool cancelled=false;
 try{rf::cancellation::Scope scope(&stop);(void)bake(11*13%jobs.size());}catch(const rf::cancellation::Cancelled&){cancelled=true;}
 assert(cancelled&&completed==before);
 // A new caller retains only encoded completed results and recreates every halo.
 auto retained=completed;completed.clear();
 std::vector<std::uint8_t> resumed(width*height*recordBytes);
 for(std::size_t i=jobs.size();i-->0;){const auto payload=retained[i]?*retained[i]:bake(i);place(resumed,i,payload);}
 assert(resumed==clean&&resumed==untiled);
 std::cout<<"pure-tile interrupted/reordered jobs="<<jobs.size()<<" retained=11 comparedBytes="<<resumed.size()<<'\n';
}

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

 verifyInterruptedTwoDimensionalTiles();

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
