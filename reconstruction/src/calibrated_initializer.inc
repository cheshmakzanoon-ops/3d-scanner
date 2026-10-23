// Private helpers included by core.cpp to reuse its fixed-size math without duplicate implementations.
namespace planar {
#include "homography_decomposition.inc"
Mat3 fit(std::span<const Match> matches, const std::vector<std::size_t> &ids) {
    auto a = normalizer(matches, ids, false), b = normalizer(matches, ids, true);
    std::array<double, 81> ata{};
    for (auto id : ids) {
        auto m = matches[id];
        double x = (m.a.x - a.center.x) * a.factor, y = (m.a.y - a.center.y) * a.factor,
               u = (m.b.x - b.center.x) * b.factor, v = (m.b.y - b.center.y) * b.factor;
        for (auto row : {std::array<double, 9>{-x, -y, -1, 0, 0, 0, u * x, u * y, u},
                         std::array<double, 9>{0, 0, 0, -x, -y, -1, v * x, v * y, v}})
            for (int i = 0; i < 9; i++)
                for (int j = 0; j < 9; j++)
                    ata[i * 9 + j] += row[i] * row[j];
    }
    auto e = eigen<9>(ata);
    if (e.values[1] < 1e-10 * std::max(e.values[8], 1e-20))
        throw Error("Rank deficient H sample");
    Mat3 h{};
    for (int i = 0; i < 9; i++)
        h[i] = e.vectors[i * 9];
    Mat3 bi{1 / b.factor, 0, b.center.x, 0, 1 / b.factor, b.center.y, 0, 0, 1};
    h = multiply(multiply(bi, h), a.matrix);
    double norm = 0;
    for (double x : h)
        norm += x * x;
    norm = std::sqrt(norm);
    if (!finite(h) || norm < 1e-14)
        throw Error("Invalid H");
    for (double &x : h)
        x /= norm;
    return h;
}
double residual(const Mat3 &h, const Match &m) {
    auto p = multiply(h, Vec3{m.a.x, m.a.y, 1});
    if (!std::isfinite(p.z) || std::abs(p.z) < 1e-12)
        return INFINITY;
    return std::hypot(p.x / p.z - m.b.x, p.y / p.z - m.b.y);
}
std::vector<std::size_t> support(const Mat3 &h, std::span<const Match> ms, double threshold) {
    std::vector<std::size_t> ids;
    for (std::size_t i = 0; i < ms.size(); i++)
        if (residual(h, ms[i]) < threshold)
            ids.push_back(i);
    return ids;
}
bool dominant(std::size_t inliers, std::size_t total) { return inliers * 10 > total * 9; }
bool runnerAdmissible(std::size_t runner, std::size_t winner) { return runner * 4 < winner * 3; }
struct Diagnostic {
    int hIterations{};
    std::size_t scoringWorkBound{};
    Mat3 h{};
    std::vector<std::size_t> ids;
    std::vector<Motion> motions;
    std::vector<std::size_t> good, positive;
    std::string status;
    Result result;
};
using ImageDomain = CalibratedImageDomain;

void validateObservations(std::span<const Match> matches, std::span<const ObservedMatch> observed,
                          const std::array<CalibratedImageDomain, 2> &domains) {
    auto invalid = []() {
        throw InitializationError(InitializationFailure::InvalidInput,
                                  "Invalid or misaligned calibrated observations");
    };
    if (observed.size() != matches.size())
        invalid();
    for (const auto &k : domains) {
        for (double v : {k.fx, k.fy, k.cx, k.cy, k.k1, k.k2, k.p1, k.p2, k.k3})
            if (!std::isfinite(v))
                invalid();
        if (k.width < 3 || k.height < 3 || k.width > 32768 || k.height > 32768 || k.fx <= 0 ||
            k.fy <= 0 || k.fx > 1e9 || k.fy > 1e9 || std::abs(k.cx) > 1e9 || std::abs(k.cy) > 1e9 ||
            std::abs(k.k1) > 1.5 || std::abs(k.k2) > 1.5 || std::abs(k.k3) > 1.5 ||
            std::abs(k.p1) > .2 || std::abs(k.p2) > .2)
            invalid();
    }
    for (std::size_t i = 0; i < matches.size(); ++i) {
        if (i % 128 == 0)
            cancellation::checkpoint();
        for (int camera = 0; camera < 2; camera++) {
            const auto &k = domains[camera];
            auto ray = camera ? matches[i].b : matches[i].a;
            auto pixel = camera ? observed[i].b : observed[i].a;
            if (!std::isfinite(ray.x) || !std::isfinite(ray.y) || std::abs(ray.x) > 100 ||
                std::abs(ray.y) > 100 || !std::isfinite(pixel.x) || !std::isfinite(pixel.y) ||
                pixel.x < 0 || pixel.y < 0 || pixel.x >= k.width || pixel.y >= k.height)
                invalid();
            double x = ray.x, y = ray.y, r2 = x * x + y * y,
                   radial = 1 + r2 * (k.k1 + r2 * (k.k2 + r2 * k.k3));
            double px = k.fx * (x * radial + 2 * k.p1 * x * y + k.p2 * (r2 + 2 * x * x)) + k.cx;
            double py = k.fy * (y * radial + k.p1 * (r2 + 2 * y * y) + 2 * k.p2 * x * y) + k.cy;
            if (!std::isfinite(px) || !std::isfinite(py) ||
                std::hypot(px - pixel.x, py - pixel.y) > .01)
                invalid();
        }
    }
}

int validate(std::span<const Match> ms, const Settings &s) {
    if (ms.size() < std::max(std::size_t{8}, s.minInliers) || ms.size() > 20000 ||
        !std::isfinite(s.threshold) || s.threshold <= 0 || s.threshold > .05 || s.iterations < 1 ||
        s.iterations > 100000 || !std::isfinite(s.minParallaxDegrees) ||
        s.minParallaxDegrees <= 0 || s.minParallaxDegrees > 30 || s.minInliers < 8 ||
        ms.size() * static_cast<std::size_t>(s.iterations) > 50000000)
        throw InitializationError(InitializationFailure::InvalidInput,
                                  "Invalid settings or match budget");
    const std::size_t capacity = 50000000 / ms.size();
    const std::size_t reserved = static_cast<std::size_t>(s.iterations) + 6;
    if (capacity <= reserved)
        throw InitializationError(
            InitializationFailure::InvalidInput,
            "No bounded budget for homography analysis plus unchanged essential fallback");
    return static_cast<int>(
        std::min({std::size_t{1200}, static_cast<std::size_t>(s.iterations), capacity - reserved}));
}
Diagnostic select(std::span<const Match> ms, const std::array<ImageDomain, 2> &domains,
                  const Settings &settings, std::span<const ObservedMatch> observed) {
    const int hIterations = validate(ms, settings);
    cancellation::checkpoint();
    validateObservations(ms, observed, domains);

    Diagnostic d;
    d.hIterations = hIterations;
    d.scoringWorkBound = ms.size() * (static_cast<std::size_t>(hIterations) +
                                      static_cast<std::size_t>(settings.iterations) + 6);
    if (ms.size() < 4 || ms.size() > 20000)
        throw Error("Invalid bounded match count");
    for (auto m : ms)
        for (double x : {m.a.x, m.a.y, m.b.x, m.b.y})
            if (!std::isfinite(x) || std::abs(x) > 100)
                throw Error("Invalid coordinates");
    std::mt19937 rng(settings.seed);
    double best = INFINITY;
    progress::begin(progress::Stage::Sparse, 0, 1, static_cast<std::uint64_t>(hIterations));
    for (int trial = 0; trial < hIterations; trial++) {
        cancellation::checkpoint();
        std::vector<std::size_t>
            ids; // Bounded partial shuffle sampling, no rejection-loop work uncertainty.
        std::vector<std::size_t> pool(ms.size());
        std::iota(pool.begin(), pool.end(), 0);
        for (std::size_t j = 0; j < 4; j++) {
            std::uniform_int_distribution<std::size_t> pick(j, pool.size() - 1);
            std::swap(pool[j], pool[pick(rng)]);
            ids.push_back(pool[j]);
        }
        try {
            auto h = fit(ms, ids);
            auto in = support(h, ms, settings.threshold);
            double sum = 0;
            for (auto i : in) {
                double e = residual(h, ms[i]);
                sum += e * e;
            }
            if (in.size() > d.ids.size() || (in.size() == d.ids.size() && sum < best)) {
                d.h = h;
                d.ids = in;
                best = sum;
            }
        } catch (const cancellation::Cancelled &) {
            throw;
        } catch (const Error &) {
        }
        progress::advance(static_cast<std::uint64_t>(trial + 1));
    }
    bool wasDominant = dominant(d.ids.size(), ms.size());
    for (int round = 0; round < 3 && d.ids.size() >= 4; round++) {
        cancellation::checkpoint();
        try {
            auto h = fit(ms, d.ids);
            auto in = support(h, ms, settings.threshold);
            if (in.size() < .9 * d.ids.size())
                break;
            d.h = h;
            d.ids = in;
            wasDominant = wasDominant || dominant(d.ids.size(), ms.size());
        } catch (const cancellation::Cancelled &) {
            throw;
        } catch (const Error &) {
            break;
        }
    }
    if (!dominant(d.ids.size(), ms.size())) {
        d.status = wasDominant ? "PlanarAmbiguity:H_SUPPORT_UNSTABLE" : "H_NOT_DOMINANT";
        return d;
    }
    for (bool second : {false, true}) {
        Vec2 center{};
        for (auto i : d.ids) {
            auto p = second ? ms[i].b : ms[i].a;
            center.x += p.x;
            center.y += p.y;
        }
        center.x /= d.ids.size();
        center.y /= d.ids.size();
        double xx = 0, xy = 0, yy = 0;
        for (auto i : d.ids) {
            auto p = second ? ms[i].b : ms[i].a;
            double x = p.x - center.x, y = p.y - center.y;
            xx += x * x;
            xy += x * y;
            yy += y * y;
        }

        if (xx + yy < 1e-12 || (xx * yy - xy * xy) / ((xx + yy) * (xx + yy)) < 1e-4 ||
            detail::occupiedObservedCells(observed, d.ids, domains[second ? 1 : 0], second) < 4) {
            d.status = "PlanarAmbiguity:SPATIAL_SUPPORT";
            return d;
        }
    }
    try {
        d.motions = decompose(d.h);
    } catch (const Error &) {
        d.status = "PlanarAmbiguity:DECOMPOSITION";
        return d;
    }
    std::size_t runner = 0;
    d.result.inputMatches = ms.size();
    d.result.epipolarInliers = d.ids.size();
    for (auto motion : d.motions) {
        cancellation::checkpoint();
        std::vector<Point> points;
        std::size_t positive = 0;
        double norm = length(motion.t);
        auto rt = multiply(transpose(motion.r), motion.r);
        double orth = 0;
        for (int i = 0; i < 3; i++) {
            double row = 0;
            for (int j = 0; j < 3; j++)
                row += std::abs(rt[i * 3 + j] - (i == j));
            orth = std::max(orth, row);
        }
        if (norm >= 1e-8 && std::isfinite(norm) && finite(motion.r) && orth <= 1e-6 &&
            std::abs(determinant(motion.r) - 1) <= 1e-6) {
            auto t = scale(motion.t, 1 / norm);
            for (auto i : d.ids)
                try {
                    if (i % 128 == 0)
                        cancellation::checkpoint();
                    auto p = triangulate(ms[i], motion.r, t);
                    positive++;
                    p.matchIndex = i;
                    if (std::isfinite(length(p.xyz)) && p.error < 3 * settings.threshold &&
                        p.parallax >= std::max(.8, settings.minParallaxDegrees) &&
                        length(p.xyz) < 10000)
                        points.push_back(p);
                } catch (const cancellation::Cancelled &) {
                    throw;
                } catch (const Error &) {
                }
            if (points.size() > d.result.points.size()) {
                runner = d.result.points.size();
                d.result.rotation = motion.r;
                d.result.translation = t;
                d.result.points = points;
            } else
                runner = std::max(runner, points.size());
        }
        d.good.push_back(points.size());
        d.positive.push_back(positive);
    }
    std::vector<double> angles, errors;
    for (auto p : d.result.points) {
        angles.push_back(p.parallax);
        errors.push_back(p.error);
    }
    auto med = [](std::vector<double> a) {
        if (a.empty())
            return 0.;
        std::sort(a.begin(), a.end());
        return (a[(a.size() - 1) / 2] + a[a.size() / 2]) / 2;
    };
    d.result.medianParallax = med(angles);
    d.result.medianError = med(errors);
    if (d.result.points.size() < std::max(std::size_t{50}, settings.minInliers) ||
        !dominant(d.result.points.size(), d.ids.size()) ||
        d.result.medianParallax < std::max(1., settings.minParallaxDegrees))
        d.status = "PlanarAmbiguity:WEAK_BASELINE_OR_SUPPORT";
    else if (!runnerAdmissible(runner, d.result.points.size()))
        d.status = "PlanarAmbiguity:AMBIGUOUS_POSE";
    else
        d.status = "ACCEPTED";
    return d;
}
} // namespace planar
