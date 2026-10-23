#include "realityforge/c_abi_support.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/core.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <new>

extern "C" char *rf_reconstruct_calibrated_json(const double *rows, std::size_t count,
                                                const double *observed, std::size_t observedValues,
                                                const double *domains, std::size_t domainValues,
                                                double threshold) noexcept {
    try {
        if (!rows || !observed || !domains || count < 20 || count > 20000 ||
            observedValues != count * 4 || domainValues != 22)
            throw rf::InitializationError(rf::InitializationFailure::InvalidInput,
                                          "Invalid calibrated initializer buffer shape");
        rf::cancellation::checkpoint();
        std::array<rf::CalibratedImageDomain, 2> calibration{};
        for (std::size_t i = 0; i < 2; ++i) {
            const auto *k = domains + i * 11;
            if (!std::isfinite(k[9]) || !std::isfinite(k[10]) || k[9] < 3 || k[9] > 32768 ||
                k[10] < 3 || k[10] > 32768 || std::floor(k[9]) != k[9] ||
                std::floor(k[10]) != k[10])
                throw rf::InitializationError(rf::InitializationFailure::InvalidInput,
                                              "Invalid calibrated image dimensions");
            calibration[i] = {k[0],
                              k[1],
                              k[2],
                              k[3],
                              k[4],
                              k[5],
                              k[6],
                              k[7],
                              k[8],
                              static_cast<int>(k[9]),
                              static_cast<int>(k[10])};
        }
        std::vector<rf::Match> matches;
        matches.reserve(count);
        std::vector<rf::ObservedMatch> pixels;
        pixels.reserve(count);
        for (std::size_t i = 0; i < count; ++i) {
            if (i % 128 == 0)
                rf::cancellation::checkpoint();
            const auto *p = rows + i * 7;
            const auto *q = observed + i * 4;
            rf::Match m{{p[0], p[1]}, {p[2], p[3]}, {}};
            for (int c = 0; c < 3; ++c) {
                if (!std::isfinite(p[4 + c]))
                    throw rf::InitializationError(rf::InitializationFailure::InvalidInput,
                                                  "Invalid match color");
                m.color[c] = static_cast<std::uint8_t>(std::clamp(p[4 + c], 0., 255.));
            }
            matches.push_back(m);
            pixels.push_back({{q[0], q[1]}, {q[2], q[3]}});
        }
        rf::Settings settings;
        settings.threshold = threshold;
        auto result = rf::reconstructCalibrated(matches, pixels, calibration, settings);
        auto json = rf::resultJson(result);
        rf::cancellation::checkpoint();
        auto *output = static_cast<char *>(std::malloc(json.size() + 1));
        if (!output)
            return nullptr;
        std::memcpy(output, json.c_str(), json.size() + 1);
        return output;
    } catch (const rf::cancellation::Cancelled &) {
        return rf::abi::error("CANCELLED", "Reconstruction cancelled");
    } catch (const rf::InitializationError &e) {
        const char *code = "INVALID_INPUT";
        switch (e.code) {
        case rf::InitializationFailure::InvalidInput:
            break;
        case rf::InitializationFailure::CollinearFeatures:
            code = "COLLINEAR_FEATURES";
            break;
        case rf::InitializationFailure::PlanarAmbiguity:
            code = "PLANAR_AMBIGUITY";
            break;
        case rf::InitializationFailure::RotationOnly:
            code = "ROTATION_ONLY";
            break;
        case rf::InitializationFailure::InsufficientConsensus:
            code = "INSUFFICIENT_CONSENSUS";
            break;
        case rf::InitializationFailure::WeakBaseline:
            code = "WEAK_BASELINE";
            break;
        }
        return rf::abi::error(code, e.what());
    } catch (const std::bad_alloc &) {
        return rf::abi::error("RESOURCE_EXHAUSTED", "Native allocation failed");
    } catch (const rf::Error &e) {
        return rf::abi::error("INVALID_INPUT", e.what());
    } catch (const std::exception &) {
        return rf::abi::error("NATIVE_FAILURE", "Native boundary failure");
    } catch (...) {
        return rf::abi::error("UNKNOWN_NATIVE_FAILURE", "Unknown native boundary failure");
    }
}
