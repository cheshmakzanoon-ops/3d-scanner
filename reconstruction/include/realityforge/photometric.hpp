#pragma once
#include <array>
#include <cstddef>
#include <span>
#include <vector>

namespace rf {
// Linear-sRGB samples of the SAME visible surface point, never unrelated pixels.
struct ExposureSample {
    std::size_t first{}, second{};
    std::array<double,3> a{}, b{};
};
struct ExposureBalance {
    std::vector<std::array<double,3>> gains;
    std::size_t supportedPairs{}, acceptedSamples{};
    double medianLogErrorBefore{}, medianLogErrorAfter{};
};
double srgbToLinear(double value);
double linearToSrgb(double value);
// Robust pair medians, graph solve, fixed camera-0 exposure and bounded gains.
// Disconnected/unsupported cameras remain identity. Does not change originals.
ExposureBalance balanceExposure(std::size_t cameras, std::span<const ExposureSample> samples);
}
