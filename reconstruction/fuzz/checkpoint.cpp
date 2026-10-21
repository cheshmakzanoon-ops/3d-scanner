#include "common.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/dense.hpp"
#include <cmath>
#include <fstream>

extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* bytes, std::size_t size) {
    static Temp temp;
    // Keep disk writes <= 16 KiB, map allocations fixed at 32x32. The raw
    // corpus includes a valid serialized map so mutations reach CRC and floats.
    if(size > 16384) return 0;
    const auto file=temp.dir/"ref-0.bin";
    {std::ofstream out(file,std::ios::binary|std::ios::trunc);
     out.write(reinterpret_cast<const char*>(bytes),static_cast<std::streamsize>(size));}
    rf::depth_checkpoint::Scope scope(temp.dir.string(),std::string(64,'a'));
    rf::DenseDepthMap map{32,32,{123.f},{.25f}};
    const bool loaded=rf::depth_checkpoint::load(0,map);
    if(loaded) {
        require(map.depth.size()==1024&&map.confidence.size()==1024);
        for(std::size_t i=0;i<1024;++i) require(std::isfinite(map.depth[i])&&std::isfinite(map.confidence[i])&&
            map.depth[i]>=0&&map.depth[i]<=100000&&map.confidence[i]>=0&&map.confidence[i]<=1&&
            (map.depth[i]!=0||map.confidence[i]==0));
    } else require(map.depth==std::vector<float>{123.f}&&map.confidence==std::vector<float>{.25f});
    return 0;
}
