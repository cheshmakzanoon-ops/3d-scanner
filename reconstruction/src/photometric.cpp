#include "realityforge/photometric.hpp"
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <map>

namespace rf {
namespace {
double median(std::vector<double> values) {
    if (values.empty()) return 0;
    auto m=values.begin()+static_cast<std::ptrdiff_t>(values.size()/2);
    std::nth_element(values.begin(),m,values.end());
    return *m;
}
struct PairFit {
    std::size_t a{}, b{}, count{};
    std::array<double,3> target{};
    std::vector<ExposureSample> samples;
};
bool solve(double a[7][8], int n, std::array<double,7>& x) {
    for (int i=0;i<n;++i) {
        int p=i;
        for (int j=i+1;j<n;++j) if (std::abs(a[j][i])>std::abs(a[p][i])) p=j;
        if (!std::isfinite(a[p][i]) || std::abs(a[p][i])<1e-12) return false;
        for (int k=i;k<=n;++k) std::swap(a[i][k],a[p][k]);
        double scale=a[i][i];
        for (int k=i;k<=n;++k) a[i][k]/=scale;
        for (int j=0;j<n;++j) if (j!=i) {
            double f=a[j][i];
            for (int k=i;k<=n;++k) a[j][k]-=f*a[i][k];
        }
    }
    for (int i=0;i<n;++i) { x[i]=a[i][n]; if (!std::isfinite(x[i])) return false; }
    return true;
}
}
double srgbToLinear(double x) {
    if (!std::isfinite(x)) throw Error("Nonfinite sRGB color");
    x=std::clamp(x,0.,1.);
    return x<=.04045 ? x/12.92 : std::pow((x+.055)/1.055,2.4);
}
double linearToSrgb(double x) {
    if (!std::isfinite(x)) throw Error("Nonfinite sRGB color");
    x=std::clamp(x,0.,1.);
    return x<=.0031308 ? 12.92*x : 1.055*std::pow(x,1./2.4)-.055;
}
ExposureBalance balanceExposure(std::size_t count, std::span<const ExposureSample> input) {
    if (count<2 || count>8 || input.size()>64000) throw Error("Invalid exposure sample budget");
    ExposureBalance result; result.gains.assign(count,{1,1,1});
    result.cameraSupported.assign(count,false); result.cameraConfidence.assign(count,0.);
    std::size_t sampleOrdinal=0;
    std::map<std::pair<std::size_t,std::size_t>,std::vector<ExposureSample>> grouped;
    for (const auto& s:input) {
        if ((sampleOrdinal++ & 127u)==0) cancellation::checkpoint();
        if (s.first>=count || s.second>=count || s.first>=s.second) throw Error("Invalid exposure camera pair");
        bool valid=true;
        for (int k=0;k<3;++k) {
            if (!std::isfinite(s.a[k]) || !std::isfinite(s.b[k])) throw Error("Nonfinite exposure color");
            valid=valid && s.a[k]>.01 && s.b[k]>.01 && s.a[k]<.92 && s.b[k]<.92;
        }
        if (valid) grouped[{s.first,s.second}].push_back(s); else ++result.rejectedSamples;
    }
    std::vector<PairFit> fits;
    for (const auto& [key,samples]:grouped) {
        cancellation::checkpoint();
        if (samples.size()<32) {++result.rejectedPairs; continue;}
        PairFit fit; fit.a=key.first; fit.b=key.second; fit.count=samples.size(); fit.samples=samples;
        double largestSpread=0;
        for (int k=0;k<3;++k) {
            std::vector<double> values; values.reserve(samples.size());
            // Every fifth co-visible measurement is withheld from fitting.
            for (std::size_t i=0;i<samples.size();++i) if (i%5!=0)
                values.push_back(std::log(samples[i].a[k]/samples[i].b[k]));
            fit.target[k]=median(values);
            for (double& v:values) v=std::abs(v-fit.target[k]);
            largestSpread=std::max(largestSpread,median(values));
        }
        // Do not interpret strong view-dependent color or poor alignment as exposure.
        if (largestSpread>.25) {++result.rejectedPairs; continue;}
        fits.push_back(std::move(fit));
    }
    std::vector<bool> reachable(count,false); reachable[0]=true;
    for (std::size_t pass=0;pass<count;++pass) for (const auto& p:fits) {
        if (reachable[p.a] || reachable[p.b]) reachable[p.a]=reachable[p.b]=true;
    }
    std::array<int,8> index{}; index.fill(-1); int n=0;
    for (std::size_t i=1;i<count;++i) if (reachable[i]) index[i]=n++;
    if (n==0) return result;
    for (int channel=0;channel<3;++channel) {
        cancellation::checkpoint();
        double a[7][8]{};
        for (const auto& p:fits) if (reachable[p.a] && reachable[p.b]) {
            // g_b - g_a = median(log(I_a/I_b)); camera zero fixes gauge.
            double weight=std::min<std::size_t>(p.count,512);
            const int ia=index[p.a], ib=index[p.b];
            if (ia>=0) { a[ia][ia]+=weight; a[ia][n]-=weight*p.target[channel]; }
            if (ib>=0) { a[ib][ib]+=weight; a[ib][n]+=weight*p.target[channel]; }
            if (ia>=0 && ib>=0) { a[ia][ib]-=weight; a[ib][ia]-=weight; }
        }
        std::array<double,7> x{};
        if (!solve(a,n,x)) { result.gains.assign(count,{1,1,1}); return result; }
        for (std::size_t cam=1;cam<count;++cam) if (index[cam]>=0)
            result.gains[cam][channel]=std::exp(std::clamp(x[index[cam]],-std::log(2.),std::log(2.)));
    }
    // RGB medians must not hide a regressing white-balance channel.
    for(int channel=0;channel<3;++channel){
        cancellation::checkpoint();std::vector<double> baseline,candidate;
        for(const auto& p:fits)if(reachable[p.a]&&reachable[p.b])for(std::size_t i=0;i<p.samples.size();i+=5){
            const auto& sample=p.samples[i];const double error=std::log(sample.a[channel]/sample.b[channel]);
            baseline.push_back(std::abs(error));candidate.push_back(std::abs(error+std::log(result.gains[p.a][channel]/result.gains[p.b][channel])));
        }
        const double before=median(baseline),after=median(candidate);result.heldOutChannelErrorBefore[channel]=before;
        const bool accepted=!baseline.empty()&&after<before-1e-6;result.channelCorrectionAccepted[channel]=accepted;
        result.heldOutChannelErrorAfter[channel]=accepted?after:before;
        if(!accepted)for(auto& gain:result.gains)gain[channel]=1;
    }
    std::vector<double> before,after,withheldBefore,withheldAfter;
    for (const auto& p:fits) if (reachable[p.a] && reachable[p.b]) {
        cancellation::checkpoint();
        ++result.supportedPairs; result.acceptedSamples+=p.samples.size();
        for (auto camera:{p.a,p.b}) {
            result.cameraSupported[camera]=true;
            result.cameraConfidence[camera]=std::min(1.,result.cameraConfidence[camera]+p.count/128.);
        }
        for (std::size_t i=0;i<p.samples.size();i+=5) {
            ++result.heldOutSamples;
            for (int k=0;k<3;++k) {
                const auto& s=p.samples[i]; const double error=std::log(s.a[k]/s.b[k]);
                withheldBefore.push_back(std::abs(error));
                withheldAfter.push_back(std::abs(error+std::log(result.gains[p.a][k]/result.gains[p.b][k])));
            }
        }
        for (const auto& s:p.samples) for (int k=0;k<3;++k) {
            const double error=std::log(s.a[k]/s.b[k]);
            before.push_back(std::abs(error));
            after.push_back(std::abs(error+std::log(result.gains[p.a][k]/result.gains[p.b][k])));
        }
    }
    result.medianLogErrorBefore=median(before); result.medianLogErrorAfter=median(after);
    result.heldOutLogErrorBefore=median(withheldBefore);
    result.heldOutLogErrorAfter=median(withheldAfter);
    // Publication gate: an unsupported improvement must never alter photo colors.
    if (result.medianLogErrorAfter>result.medianLogErrorBefore+1e-6 ||
        result.heldOutLogErrorAfter>result.heldOutLogErrorBefore+1e-6) {
        result.gains.assign(count,{1,1,1}); result.medianLogErrorAfter=result.medianLogErrorBefore;
        result.heldOutLogErrorAfter=result.heldOutLogErrorBefore;
        result.channelCorrectionAccepted.fill(false);result.heldOutChannelErrorAfter=result.heldOutChannelErrorBefore;
    }
    result.correctionAccepted=std::any_of(result.channelCorrectionAccepted.begin(),result.channelCorrectionAccepted.end(),[](bool accepted){return accepted;});
    return result;
}
}
