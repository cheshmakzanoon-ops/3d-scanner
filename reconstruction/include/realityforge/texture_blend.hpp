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
        weight+=samples[i].weight;
        for(int c=0;c<3;++c)mixed[c]+=samples[i].weight*colors[i][c];
    }
    for(int c=0;c<3;++c){mixed[c]/=weight;out.rgba[c]=static_cast<std::uint8_t>(std::lround(255*linearToSrgb(mixed[c])));}
    out.rgba[3]=255;
    for(std::size_t i=0;i<samples.size();++i)if(out.sourceMask&(std::uint64_t{1}<<samples[i].source))
        for(int c=0;c<3;++c)out.weightedColorResidual+=samples[i].weight*std::abs(colors[i][c]-mixed[c])/(3*weight);
    return out;
}
struct TexelSources {std::size_t chart{};std::vector<VerifiedTexelSample> samples;};
inline std::vector<BlendedTexel> blendVerifiedTile(std::span<const TexelSources> texels,const BlendSettings& settings={}) {
    if(texels.size()>settings.maxTilePixels||settings.maxTilePixels>65536)throw Error("Photographic tile budget exceeded");
    std::vector<BlendedTexel> result;result.reserve(texels.size());
    for(const auto& p:texels)result.push_back(blendVerifiedTexel(p.chart,p.samples,settings));
    return result;
}
}
