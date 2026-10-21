#pragma once
#include "realityforge/cancellation.hpp"
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <span>
#include <string>
#include <vector>

namespace rf::vulkan_depth {
struct Budget { std::size_t maxCostEntries{4194304},maxPixels{65536},maxCombinedBytes{67108864}; };
struct CostVolume {
    std::size_t pixels{},planes{};
    std::span<const float> costs; // pixel-major 1-NCC, same sentinel2 and inverse-z planes as CPU046
    std::span<const std::uint32_t> valid; // 0|1, after reference patch validity/variance tests
    float nearDepth{2},farDepth{9},minimumNcc{.72f},minimumUniqueness{.025f};
};
struct Depth {float cameraZ{},confidence{};};
enum class Backend {Cpu,Vulkan};
enum class Selection {CpuDefault,ExperimentalValidation};
enum class Fallback {None,Disabled,Unavailable,Unsupported,Allocation,DriverFailure,OutputMismatch};
struct Result {std::vector<Depth> samples;Backend backend{Backend::Cpu};Fallback fallback{Fallback::Disabled};std::string detail;std::size_t combinedBytes{};};
struct Capability {bool loader{},compute{},hostVisibleStorage{};std::uint64_t maxStorageBytes{};std::string device;};
inline void validate(const CostVolume& v,const Budget& b={}){
    if(b.maxCostEntries>4194304||b.maxPixels>65536||b.maxCombinedBytes>268435456||
       v.pixels==0||v.pixels>b.maxPixels||v.planes<8||v.planes>128||v.pixels>b.maxCostEntries/v.planes||
       v.costs.size()!=v.pixels*v.planes||v.valid.size()!=v.pixels||
       !std::isfinite(v.nearDepth)||!std::isfinite(v.farDepth)||v.nearDepth<1e-5f||v.nearDepth>1e5f||v.farDepth>1e6f||v.farDepth<=v.nearDepth||
       !std::isfinite(1/v.nearDepth)||!std::isfinite(1/v.farDepth)||
       !std::isfinite((1/v.farDepth-1/v.nearDepth)/static_cast<float>(v.planes-1))||
       (1/v.farDepth-1/v.nearDepth)/static_cast<float>(v.planes-1)==0||
       !std::isfinite(v.minimumNcc)||v.minimumNcc<0||v.minimumNcc>1||
       !std::isfinite(v.minimumUniqueness)||v.minimumUniqueness<0||v.minimumUniqueness>2||
       v.pixels*(2*v.planes*sizeof(float)+2*sizeof(std::uint32_t)+3*sizeof(Depth))>b.maxCombinedBytes)
        throw Error("Invalid or over-budget Vulkan depth tile");
    for(auto mask:v.valid)if(mask>1)throw Error("Invalid cost-volume validity mask");
    for(std::size_t i=0;i<v.costs.size();++i){if((i&4095u)==0)cancellation::checkpoint();if(!std::isfinite(v.costs[i])||v.costs[i]<0||v.costs[i]>2)throw Error("Invalid photometric cost");}
}
// Shipping CPU default; experimental selection does real dispatch only after
// capability/budget checks and validates every output against this CPU kernel.
std::vector<Depth> cpuReference(const CostVolume&,const Budget& = {});
Capability capability();
Result reduce(const CostVolume&,const Budget& = {},Selection=Selection::CpuDefault);
}
