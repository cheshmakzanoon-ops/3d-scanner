#pragma once
#include "realityforge/photometric.hpp"
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <limits>
namespace rf {
// Each sample refers to the SAME surface texel, after 057 geometric visibility.
// A source's gain is measured by 060; no sampling across chart/alpha boundaries.
struct VerifiedTexelSample {
    std::size_t source{},chart{};
    std::array<double,3> linear{},gain{1,1,1};
    double weight{}; bool visible{};
};
struct BlendSettings {double maximumLogDisagreement=.30;std::size_t maxSources=8,maxTilePixels=65536;};
struct BlendedTexel {
    std::array<std::uint8_t,4> rgba{};
    std::uint64_t sourceMask{};std::size_t acceptedSources{},rejectedSources{};
    double weightedColorResidual{};
};
inline BlendedTexel blendVerifiedTexel(std::size_t chart,std::span<const VerifiedTexelSample> samples,const BlendSettings& s={}) {
    if(s.maxSources>8||samples.size()>s.maxSources||!std::isfinite(s.maximumLogDisagreement)||s.maximumLogDisagreement<0||s.maximumLogDisagreement>1)
        throw Error("Invalid photographic blend budget");
    cancellation::checkpoint();BlendedTexel out;
    std::array<std::array<double,3>,8> colors{};std::array<bool,8> valid{};
    std::size_t reference=samples.size();double best=-1;
    std::uint64_t seen=0;
    for(std::size_t i=0;i<samples.size();++i){
        const auto& p=samples[i];
        if(p.source>=64||(seen&(std::uint64_t{1}<<p.source)))throw Error("Invalid duplicate blend source");
        seen|=std::uint64_t{1}<<p.source;
        if(!std::isfinite(p.weight)||p.weight<0)throw Error("Invalid photographic weight");
        bool good=p.visible&&p.chart==chart&&p.weight>0;
        for(int c=0;c<3;++c){
            if(!std::isfinite(p.linear[c])||p.linear[c]<0||p.linear[c]>1||!std::isfinite(p.gain[c])||p.gain[c]<.5||p.gain[c]>2)
                throw Error("Invalid photographic radiometry");
            colors[i][c]=p.linear[c]*p.gain[c];
            good=good&&colors[i][c]>=0&&colors[i][c]<=1;
        }
        valid[i]=good;
        if(good&&p.weight>best){best=p.weight;reference=i;}
    }
    if(reference==samples.size()){out.rejectedSources=samples.size();return out;}
    double weight=0;std::array<double,3> mixed{};
    for(std::size_t i=0;i<samples.size();++i){
        bool consistent=valid[i];
        for(int c=0;c<3;++c)consistent=consistent&&std::abs(std::log((colors[i][c]+.01)/(colors[reference][c]+.01)))<=s.maximumLogDisagreement;
        if(!consistent){++out.rejectedSources;continue;}
        ++out.acceptedSources;out.sourceMask|=std::uint64_t{1}<<samples[i].source;
        const double normalizedWeight=samples[i].weight/best;
        weight+=normalizedWeight;
        for(int c=0;c<3;++c)mixed[c]+=normalizedWeight*colors[i][c];
    }
    for(int c=0;c<3;++c){mixed[c]/=weight;out.rgba[c]=static_cast<std::uint8_t>(std::lround(255*linearToSrgb(mixed[c])));}
    out.rgba[3]=255;
    for(std::size_t i=0;i<samples.size();++i)if(out.sourceMask&(std::uint64_t{1}<<samples[i].source))
        for(int c=0;c<3;++c)out.weightedColorResidual+=(samples[i].weight/best)*std::abs(colors[i][c]-mixed[c])/(3*weight);
    return out;
}
struct TexelSources {std::size_t chart{};std::vector<VerifiedTexelSample> samples;};
inline std::vector<BlendedTexel> blendVerifiedTile(std::span<const TexelSources> texels,const BlendSettings& settings={}) {
    if(texels.size()>settings.maxTilePixels||settings.maxTilePixels>65536)throw Error("Photographic tile budget exceeded");
    std::vector<BlendedTexel> result;result.reserve(texels.size());
    for(const auto& p:texels)result.push_back(blendVerifiedTexel(p.chart,p.samples,settings));
    return result;
}
struct ChartBlendTile {
    int width{},height{},halo=2;
    std::vector<TexelSources> texels;
};
struct ChartBlendResult {
    int width{},height{};
    std::vector<BlendedTexel> texels;
};
// Two dyadic weight scales regularize source transitions; source RGB is ALWAYS
// sampled at the original central texel, preserving high-frequency alignment.
// Every stencil has a connected path of same-chart, visible source samples.
// A two-pixel halo gives bit-identical cropped output for tiled/untiled baking.
inline ChartBlendResult blendChartTile(const ChartBlendTile& input,const BlendSettings& settings={}) {
    if(input.width<5||input.height<5||input.width>256||input.height>256||input.halo<2||
       input.width<=2*input.halo||input.height<=2*input.halo||
       input.texels.size()!=static_cast<std::size_t>(input.width)*input.height||input.texels.size()>settings.maxTilePixels)
        throw Error("Invalid multiscale blend tile");
    ChartBlendResult result;result.width=input.width-2*input.halo;result.height=input.height-2*input.halo;
    result.texels.reserve(static_cast<std::size_t>(result.width)*result.height);
    auto sample=[&](int x,int y,std::size_t source,std::size_t chart)->const VerifiedTexelSample* {
        const auto& texel=input.texels[static_cast<std::size_t>(y)*input.width+x];
        if(texel.chart!=chart)return nullptr;
        for(const auto& p:texel.samples)if(p.source==source&&p.visible&&p.chart==chart&&p.weight>0&&std::isfinite(p.weight))return &p;
        return nullptr;
    };
    for(int y=input.halo;y<input.height-input.halo;++y){
        cancellation::checkpoint();
        for(int x=input.halo;x<input.width-input.halo;++x){
            const auto& center=input.texels[static_cast<std::size_t>(y)*input.width+x];
            // Validate all center fields before smoothing; no NaN can be hidden.
            (void)blendVerifiedTexel(center.chart,center.samples,settings);
            auto adjusted=center.samples;
            double maximum=0;for(const auto& p:center.samples)maximum=std::max(maximum,p.weight);
            for(std::size_t i=0;i<adjusted.size();++i){
                const auto& p=center.samples[i];if(!p.visible||p.chart!=center.chart||p.weight<=0)continue;
                double sum=p.weight/maximum,weights=1;
                for(int radius:{1,2})for(int oy:{-radius,0,radius})for(int ox:{-radius,0,radius}){
                    if(ox==0&&oy==0)continue;
                    const int nx=x+ox,ny=y+oy;const auto* neighbor=sample(nx,ny,p.source,center.chart);if(!neighbor)continue;
                    bool connected=true;int px=x,py=y;
                    while(px!=nx){px+=px<nx?1:-1;if(!sample(px,py,p.source,center.chart)){connected=false;break;}}
                    while(connected&&py!=ny){py+=py<ny?1:-1;if(!sample(px,py,p.source,center.chart)){connected=false;break;}}
                    if(!connected)continue;
                    bool coherent=true;
                    for(int c=0;c<3;++c){
                        if(!std::isfinite(neighbor->linear[c])||neighbor->linear[c]<0||neighbor->linear[c]>1||!std::isfinite(neighbor->gain[c])||neighbor->gain[c]<.5||neighbor->gain[c]>2)
                            throw Error("Invalid multiscale source color");
                        coherent=coherent&&std::abs(std::log((neighbor->linear[c]*neighbor->gain[c]+.01)/(p.linear[c]*p.gain[c]+.01)))<=settings.maximumLogDisagreement;
                    }
                    if(coherent){sum+=std::min(1.,neighbor->weight/maximum);++weights;}
                }
                adjusted[i].weight=sum/weights;
            }
            result.texels.push_back(blendVerifiedTexel(center.chart,adjusted,settings));
        }
    }
    return result;
}
struct LodTexelMapping {std::size_t sourceFace{};std::array<double,3> barycentric{};};
template<class AtlasLayout>
std::array<double,2> mapLodTexel(const AtlasLayout& layout,const LodTexelMapping& mapping){
    if(mapping.sourceFace>=layout.charts.size()||layout.charts[mapping.sourceFace].sourceFace!=mapping.sourceFace)
        throw Error("Invalid LOD source-face mapping");
    const auto& chart=layout.charts[mapping.sourceFace];double sum=0;std::array<double,2> uv{};
    for(int i=0;i<3;++i){const double w=mapping.barycentric[i];if(!std::isfinite(w)||w<0||w>1)throw Error("Invalid LOD barycentric provenance");sum+=w;uv[0]+=w*chart.uv[i].u;uv[1]+=w*chart.uv[i].v;}
    if(std::abs(sum-1)>1e-8)throw Error("Invalid LOD barycentric sum");
    return uv;
}

}
