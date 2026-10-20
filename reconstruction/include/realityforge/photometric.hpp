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
    std::vector<bool> cameraSupported;
    std::vector<double> cameraConfidence;
    std::size_t rejectedSamples{}, rejectedPairs{}, heldOutSamples{};
    double heldOutLogErrorBefore{}, heldOutLogErrorAfter{};
    bool correctionAccepted{};
    std::array<double,3> heldOutChannelErrorBefore{},heldOutChannelErrorAfter{};
    std::array<bool,3> channelCorrectionAccepted{};
};
double srgbToLinear(double value);
double linearToSrgb(double value);
// Robust pair medians, graph solve, fixed camera-0 exposure and bounded gains.
// Disconnected/unsupported cameras remain identity. Does not change originals.
ExposureBalance balanceExposure(std::size_t cameras, std::span<const ExposureSample> samples);
}
