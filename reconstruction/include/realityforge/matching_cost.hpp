#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstddef>
#include <span>
#include <stdexcept>
namespace rf::matching {
// Sensor intensities are sampled in the supplied encoding, matching baseline
// ZNCC. No gamma/linear-light claim is made. Invalid support never has cost 0.
struct Cost { double value{2.}; std::size_t support{}; bool valid{}; };
inline Cost zncc(std::span<const double> reference, std::span<const double> source,
                 std::span<const unsigned char> valid={}, double minimumVariance=18.) {
    if(reference.size()!=source.size()||reference.empty()||reference.size()>49||
       (!valid.empty()&&valid.size()!=reference.size())||!std::isfinite(minimumVariance)||minimumVariance<0)
        throw std::invalid_argument("Invalid bounded photometric window");
    double ar=0,as=0;
    for(std::size_t i=0;i<reference.size();++i){
        if((!valid.empty()&&valid[i]!=255)||!std::isfinite(reference[i])||!std::isfinite(source[i])||
           reference[i]<0||reference[i]>255||source[i]<0||source[i]>255)return {};
        ar+=reference[i];as+=source[i];
    }
    ar/=reference.size();as/=reference.size();double vr=0,vs=0,cross=0;
    for(std::size_t i=0;i<reference.size();++i){
        const double a=reference[i]-ar,b=source[i]-as;vr+=a*a;vs+=b*b;cross+=a*b;
    }
    vr/=reference.size();vs/=reference.size();
    if(vr<minimumVariance||vs<minimumVariance||vr<=0||vs<=0)return {};
    return {1.-std::clamp(cross/reference.size()/std::sqrt(vr*vs),-1.,1.),reference.size(),true};
}
struct Aggregation { double cost{2.}; std::size_t usableSources{}; double disagreement{}; bool valid{}; };
// Median aggregation requires independent agreement; exact best-source remains
// available to compare against the established reference. At most seven peers.
inline Aggregation aggregate(std::span<const Cost> costs,bool robust=false){
    if(costs.size()>7)throw std::invalid_argument("Too many matching sources");
    std::array<double,7> values{};std::size_t n=0;
    for(const auto& c:costs)if(c.valid){if(!std::isfinite(c.value)||c.value<0||c.value>2)throw std::invalid_argument("Invalid cost");values[n++]=c.value;}
    if(!n)return {};
    for(std::size_t i=1;i<n;++i){const double value=values[i];std::size_t j=i;while(j>0&&values[j-1]>value){values[j]=values[j-1];--j;}values[j]=value;}
    const double median=values[n/2];
    return {robust?median:values[0],n,values[n-1]-values[0],true};
}
// Invalid cost 2 is absence of evidence, never a competing depth minimum.
// A uniqueness margin requires an observed alternative outside the peak basin.
inline constexpr unsigned UNIQUENESS_SETTINGS_VERSION=2;
struct Ambiguity { std::size_t best{}; double margin{}; bool valid{}; };
inline Ambiguity distinctMinimum(std::span<const double> costs,std::size_t exclusion=3){
    if(costs.empty()||costs.size()>128)throw std::invalid_argument("Invalid cost curve size");
    std::size_t best=0;for(std::size_t i=0;i<costs.size();++i){if(!std::isfinite(costs[i])||costs[i]<0||costs[i]>2)throw std::invalid_argument("Invalid cost curve");if(costs[i]<costs[best])best=i;}
    double runner=2.;bool found=false;
    for(std::size_t i=0;i<costs.size();++i)if(costs[i]<2.&&(i>best?i-best:best-i)>exclusion){runner=std::min(runner,costs[i]);found=true;}
    return {best,found?runner-costs[best]:0.,found&&costs[best]<2.};
}
}
