#include "common.hpp"
#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/bundle_adjustment.hpp"
#include <cmath>

namespace {
void response(char* text) {
    require(text != nullptr);
    const auto n = std::strlen(text);
    require(n >= 2 && n < 4 * 1024 * 1024 && text[0] == '{' && text[n-1] == '}');
    rf_free(text);
}
}
extern "C" int LLVMFuzzerTestOneInput(const std::uint8_t* bytes, std::size_t size) {
    Input input{bytes,size};
    const auto mode = input.byte() % 5;
    const auto count = input.byte() % 65;
    const double threshold = input.byte() & 1 ? .002 : input.number();
    if(mode == 0 || mode == 1) {
        const std::size_t width = mode == 0 ? 7 : 5;
        std::vector<double> rows(count * width);
        for(auto& d:rows) d = input.number();
        response(mode == 0 ? rf_reconstruct_json(rows.data(), count, threshold, nullptr, nullptr)
                           : rf_register_camera_json(rows.data(), count, threshold));
    } else if(mode == 2) {
        std::array<double,36> cameras{};
        std::array<double,112> points{};
        std::array<double,224> observations{};
        for(auto& x:cameras) x=input.number();
        for(auto& x:points) x=input.number();
        for(auto& x:observations) x=input.number();
        response(rf_bundle_adjust_json(cameras.data(),3,points.data(),16,
                 observations.data(),32,threshold,input.byte()%28));
    } else if(mode == 3) {
        std::array<std::uint8_t,2*32*32*4> rgba{};
        std::array<double,32> cameras{};
        for(auto& x:rgba) x=input.byte();
        for(auto& x:cameras) x=input.number();
        // Constant allocated dimensions; adversarial settings/poses are decoded
        // only after truthful buffer extents reach the existing C API.
        response(rf_dense_json(rgba.data(),32,32,cameras.data(),2,
                              threshold,input.number(),input.byte()%66,input.byte()%10));
    } else {
        std::vector<std::uint8_t> gray(count);
        for(auto& x:gray) x=input.byte();
        try {
            const auto q=rf::quality(gray,input.byte()%16,input.byte()%16,input.byte()%20);
            require(std::isfinite(q.mean)&&std::isfinite(q.standardDeviation)&&std::isfinite(q.laplacianVariance));
        } catch(const rf::Error&) {}
    }
    return 0;
}
