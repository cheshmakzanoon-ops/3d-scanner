#include "../src/calibrated_observations.hpp"
#include "calibrated_initializer_fixtures.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/core.hpp"
#include "realityforge/progress.hpp"
#include <bit>
#include <cassert>
#include <chrono>
#include <cmath>
#include <iostream>
#include <numeric>
#include <random>
#include <thread>
using namespace rf;
std::vector<Match> matches(std::span<const double> rows) {
    std::vector<Match> out;
    for (std::size_t i = 0; i < rows.size(); i += 4)
        out.push_back({{rows[i], rows[i + 1]},
                       {rows[i + 2], rows[i + 3]},
                       {std::uint8_t(i), std::uint8_t(i * 3), std::uint8_t(i * 7)}});
    return out;
}
Vec2 pixel(Vec2 q, const CalibratedImageDomain &k) {
    double x = q.x, y = q.y, r2 = x * x + y * y, rad = 1 + r2 * (k.k1 + r2 * (k.k2 + r2 * k.k3));
    return {k.fx * (x * rad + 2 * k.p1 * x * y + k.p2 * (r2 + 2 * x * x)) + k.cx,
            k.fy * (y * rad + k.p1 * (r2 + 2 * y * y) + 2 * k.p2 * x * y) + k.cy};
}
std::vector<ObservedMatch> pixels(std::span<const Match> ms,
                                  const std::array<CalibratedImageDomain, 2> &k) {
    std::vector<ObservedMatch> out;
    for (auto m : ms)
        out.push_back({pixel(m.a, k[0]), pixel(m.b, k[1])});
    return out;
}
template <class F> void fails(F run, InitializationFailure code) {
    bool caught = false;
    try {
        run();
    } catch (const InitializationError &e) {
        caught = e.code == code;
    }
    assert(caught);
}
void same(double a, double b) {
    assert(std::bit_cast<std::uint64_t>(a) == std::bit_cast<std::uint64_t>(b));
}
int main() {
    std::array<CalibratedImageDomain, 2> k{{{480, 480, 191.5, 143.5, 0, 0, 0, 0, 0, 384, 288},
                                            {480, 480, 191.5, 143.5, 0, 0, 0, 0, 0, 384, 288}}};
    auto wide = k;
    for (auto &c : wide) {
        c.width = 768;
        c.height = 576;
        c.cx = 383.5;
        c.cy = 287.5;
    }
    Settings s;
    s.threshold = .003125;
    auto anchor = matches(fixtures::anchor);
    auto observed = pixels(anchor, k);
    auto state = std::make_shared<progress::State>();
    Result a;
    {
        progress::Scope scope(state);
        a = reconstructCalibrated(anchor, observed, k, s);
    }
    assert(a.points.size() == 191);
    assert(a.initializerModel == InitializerModel::Homography);
    assert(resultJson(a).find("\"homographyInliers\":191") != std::string::npos);
    auto snap = state->snapshot();
    assert(snap.stage == progress::Stage::Sparse && snap.tile == 1 && snap.completed == 1200 &&
           snap.total == 1200);
    for (auto p : a.points) {
        assert(p.matchIndex < anchor.size());
        assert(p.color == anchor[p.matchIndex].color);
    }
    auto lost = matches(fixtures::lost);
    auto lostPixels = pixels(lost, wide);
    bool lostRejected = false;
    try {
        reconstructCalibrated(lost, lostPixels, wide, s);
    } catch (const InitializationError &e) {
        lostRejected = e.code == InitializationFailure::PlanarAmbiguity &&
                       std::string(e.what()).find("lost stable support") != std::string::npos;
    }
    assert(lostRejected);
    auto ambiguous = matches(fixtures::ambiguous);
    fails([&] { reconstructCalibrated(ambiguous, pixels(ambiguous, wide), wide, s); },
          InitializationFailure::PlanarAmbiguity);
    auto nonplanar = matches(fixtures::nonplanar);
    std::mt19937 rng(17200);
    for (int i = 0; i < 4; i++) {
        std::shuffle(nonplanar.begin(), nonplanar.end(), rng);
        s.seed = 20261009 + i;
        auto original = reconstruct(nonplanar, s);
        auto routed = reconstructCalibrated(nonplanar, pixels(nonplanar, wide), wide, s);
        assert(original.initializerModel == InitializerModel::Unspecified);
        assert(routed.initializerModel == InitializerModel::Essential);
        assert(resultJson(original).find("initializerModel") == std::string::npos);
        assert(original.points.size() == routed.points.size());
        for (int j = 0; j < 9; j++)
            same(original.rotation[j], routed.rotation[j]);
        same(original.translation.x, routed.translation.x);
        same(original.translation.y, routed.translation.y);
        same(original.translation.z, routed.translation.z);
        for (std::size_t j = 0; j < original.points.size(); j++) {
            auto x = original.points[j], y = routed.points[j];
            same(x.xyz.x, y.xyz.x);
            same(x.xyz.y, y.xyz.y);
            same(x.xyz.z, y.xyz.z);
            same(x.error, y.error);
            same(x.parallax, y.parallax);
            assert(x.matchIndex == y.matchIndex && x.color == y.color);
        }
    }
    s.seed = 20261009;
    for (double threshold : {0., std::numeric_limits<double>::quiet_NaN()}) {
        auto invalid = s;
        invalid.threshold = threshold;
        fails([&] { reconstructCalibrated(anchor, observed, k, invalid); },
              InitializationFailure::InvalidInput);
    }
    auto strict = s;
    strict.minInliers = 192;
    fails([&] { reconstructCalibrated(anchor, observed, k, strict); },
          InitializationFailure::PlanarAmbiguity);
    strict = s;
    strict.minParallaxDegrees = 30;
    fails([&] { reconstructCalibrated(anchor, observed, k, strict); },
          InitializationFailure::PlanarAmbiguity);
    auto bad = observed;
    bad[0].a.x += 1;
    fails([&] { reconstructCalibrated(anchor, bad, k, s); }, InitializationFailure::InvalidInput);
    bad = observed;
    bad.pop_back();
    fails([&] { reconstructCalibrated(anchor, bad, k, s); }, InitializationFailure::InvalidInput);
    bad = observed;
    bad[0].b.y = std::numeric_limits<double>::infinity();
    fails([&] { reconstructCalibrated(anchor, bad, k, s); }, InitializationFailure::InvalidInput);
    bad = observed;
    bad[0].b.x = k[1].width;
    fails([&] { reconstructCalibrated(anchor, bad, k, s); }, InitializationFailure::InvalidInput);
    // Nonzero distortion changes actual cell membership; virtual K*ray cells differ.
    CalibratedImageDomain lens{100, 100, 50, 50, 1, 0, 0, 0, 0, 100, 100};
    std::vector<ObservedMatch> real{{pixel({.24, .05}, lens), {}}, {pixel({.1, .05}, lens), {}}},
        virtualPixels{{{74, 55}, {}}, {{60, 55}, {}}};
    std::array<std::size_t, 2> ids{0, 1};
    assert(detail::occupiedObservedCells(real, ids, lens, false) == 2);
    assert(detail::occupiedObservedCells(virtualPixels, ids, lens, false) == 1);
    auto distorted = wide;
    for (auto &c : distorted) {
        c.k1 = .4;
        c.k2 = -.05;
        c.p1 = .005;
    }
    auto realPixels = pixels(anchor, distorted);
    try {
        reconstructCalibrated(anchor, realPixels, distorted, s);
    } catch (const InitializationError &e) {
        assert(e.code != InitializationFailure::InvalidInput);
    }
    fails([&] { reconstructCalibrated(anchor, pixels(anchor, wide), distorted, s); },
          InitializationFailure::InvalidInput);
    std::atomic_bool flag{true};
    bool cancelled = false;
    try {
        cancellation::Scope scope(&flag);
        reconstructCalibrated(anchor, observed, k, s);
    } catch (const cancellation::Cancelled &) {
        cancelled = true;
    }
    assert(cancelled);

    auto invalidIterations = s;
    invalidIterations.iterations = 0;
    fails([&] { reconstructCalibrated(anchor, observed, k, invalidIterations); },
          InitializationFailure::InvalidInput);
    auto excessSupport = s;
    excessSupport.minInliers = anchor.size() + 1;
    fails([&] { reconstructCalibrated(anchor, observed, k, excessSupport); },
          InitializationFailure::InvalidInput);
    std::vector<Match> maximum(20000);
    for (std::size_t i = 0; i < maximum.size(); i++)
        maximum[i] = anchor[i % anchor.size()];
    auto maximumPixels = pixels(maximum, k);
    auto saturated = s;
    saturated.iterations = 2494;
    fails([&] { reconstructCalibrated(maximum, maximumPixels, k, saturated); },
          InitializationFailure::InvalidInput);
    maximum.push_back(anchor[0]);
    maximumPixels.push_back(observed[0]);
    fails([&] { reconstructCalibrated(maximum, maximumPixels, k, s); },
          InitializationFailure::InvalidInput);
    maximum.pop_back();
    maximumPixels.pop_back();
    // Observe an active H phase before cancelling: not only an already-cancelled operation.
    flag = false;
    std::atomic_bool done{false}, cancelledDuring{false};
    auto liveProgress = std::make_shared<progress::State>();
    std::thread worker([&] {
        try {
            cancellation::Scope cancellationScope(&flag);
            progress::Scope progressScope(liveProgress);
            reconstructCalibrated(maximum, maximumPixels, k, s);
        } catch (const cancellation::Cancelled &) {
            cancelledDuring = true;
        }
        done = true;
    });
    while (!done && liveProgress->snapshot().tile != 1)
        std::this_thread::sleep_for(std::chrono::milliseconds(1));
    assert(!done);
    flag = true;
    worker.join();
    assert(cancelledDuring);
    std::vector<double> rows, flatPixels, domains;
    for (std::size_t i = 0; i < anchor.size(); i++) {
        auto m = anchor[i];
        auto p = observed[i];
        rows.insert(rows.end(), {m.a.x, m.a.y, m.b.x, m.b.y, 120, 130, 140});
        flatPixels.insert(flatPixels.end(), {p.a.x, p.a.y, p.b.x, p.b.y});
    }
    for (auto c : k)
        domains.insert(domains.end(), {c.fx, c.fy, c.cx, c.cy, c.k1, c.k2, c.p1, c.p2, c.k3,
                                       double(c.width), double(c.height)});
    auto invoke = [&](std::size_t pn, std::size_t dn) {
        char *output = rf_reconstruct_calibrated_json(rows.data(), anchor.size(), flatPixels.data(),
                                                      pn, domains.data(), dn, s.threshold);
        assert(output);
        std::string text(output);
        rf_free(output);
        return text;
    };
    assert(invoke(flatPixels.size(), 22).find("\"pointCount\":191") != std::string::npos);
    assert(invoke(flatPixels.size() - 1, 22).find("INVALID_INPUT") != std::string::npos);
    assert(invoke(flatPixels.size(), 21).find("INVALID_INPUT") != std::string::npos);
    domains[9] = 384.5;
    assert(invoke(flatPixels.size(), 22).find("INVALID_INPUT") != std::string::npos);
    domains[9] = 384;
    {
        cancellation::Scope scope(&flag);
        assert(invoke(flatPixels.size(), 22).find("CANCELLED") != std::string::npos);
    }
    std::cout << "Calibrated initializer regression suite passed\n";
}
