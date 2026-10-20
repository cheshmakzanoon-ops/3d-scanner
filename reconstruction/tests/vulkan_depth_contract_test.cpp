#include "realityforge/vulkan_depth.hpp"
#include <atomic>
#include <iostream>
#include <memory>
#include <limits>
#include <stdexcept>
namespace {void check(bool c){if(!c)throw std::runtime_error("Vulkan depth contract regression");}}
int main(int argc,char**){
    using namespace rf::vulkan_depth;
    std::vector<float> costs(17*16,2);std::vector<std::uint32_t> masks(17,1);
    for(std::size_t i=0;i<17;++i){costs[i*16+7]=.08f;costs[i*16+6]=.12f;costs[i*16+8]=.13f;}
    masks[2]=0;costs[3*16]=.01f; // masked and endpoint minima must stay invalid
    CostVolume volume{17,16,costs,masks,2,9,.72f,.025f};
    auto cpu=reduce(volume);check(cpu.backend==Backend::Cpu&&cpu.fallback==Fallback::Disabled&&cpu.samples[0].cameraZ>2&&cpu.samples[2].cameraZ==0&&cpu.samples[3].cameraZ==0);
    auto gpu=reduce(volume,{},Selection::ExperimentalValidation);
    if(argc>1)check(gpu.backend==Backend::Vulkan&&gpu.fallback==Fallback::None);
    for(std::size_t i=0;i<17;++i){check(std::abs(cpu.samples[i].cameraZ-gpu.samples[i].cameraZ)<1e-3f);check(std::abs(cpu.samples[i].confidence-gpu.samples[i].confidence)<1e-5f);}
    bool invalid=false;costs[0]=NAN;try{(void)reduce(volume);}catch(const rf::Error&){invalid=true;}check(invalid);costs[0]=2;
    Budget tiny;tiny.maxCombinedBytes=8;invalid=false;try{(void)reduce(volume,tiny);}catch(const rf::Error&){invalid=true;}check(invalid);
    for(float near:{std::numeric_limits<float>::denorm_min(),std::numeric_limits<float>::max()}){auto bad=volume;bad.nearDepth=near;invalid=false;try{(void)reduce(bad);}catch(const rf::Error&){invalid=true;}check(invalid);}
    Budget huge;huge.maxPixels=std::numeric_limits<std::size_t>::max();invalid=false;try{(void)reduce(volume,huge);}catch(const rf::Error&){invalid=true;}check(invalid);
    auto stop=std::make_shared<std::atomic_bool>(true);rf::cancellation::Scope scoped(stop.get());invalid=false;try{(void)reduce(volume);}catch(const rf::cancellation::Cancelled&){invalid=true;}check(invalid);
    std::cout<<"backend="<<(gpu.backend==Backend::Vulkan?"Vulkan":"CPU fallback")<<" detail="<<gpu.detail<<" combinedBytes="<<gpu.combinedBytes<<'\n';
}
