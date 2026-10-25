#include "patch.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <new>
#include <utility>
namespace observed_patch {
namespace {
V add(V a, V b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V mul(V a, double s) { return {a.x * s, a.y * s, a.z * s}; }
double dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V cross(V a, V b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
bool finite(V a) {
  return std::isfinite(a.x) && std::isfinite(a.y) && std::isfinite(a.z);
}
V transform(const Camera &c, V p) {
  return {c.r[0] * p.x + c.r[1] * p.y + c.r[2] * p.z + c.t.x,
          c.r[3] * p.x + c.r[4] * p.y + c.r[5] * p.z + c.t.y,
          c.r[6] * p.x + c.r[7] * p.y + c.r[8] * p.z + c.t.z};
}
struct P {
  double x{}, y{};
};
double orient(P a, P b, P c) {
  return (b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x);
}
struct Interval {
  double lo{}, hi{};
};
struct Prepared {
  Source source{};
  std::size_t camera{};
  std::array<P, 3> uv{};
  V n{};
  double d{};
  V lo{}, hi{};
  std::array<Interval, 3> ni{};
  Interval di{};
  std::array<std::array<Interval, 3>, 3> cone{};
  double coneSign{};
  std::array<Interval, 2> bounds{};
};
double down(double x) { return std::nextafter(x, -INFINITY); }
double up(double x) { return std::nextafter(x, INFINITY); }
Interval plus(Interval a, Interval b) {
  return {down(a.lo + b.lo), up(a.hi + b.hi)};
}
Interval times(Interval a, Interval b) {
  std::array<double, 4> p{a.lo * b.lo, a.lo * b.hi, a.hi * b.lo, a.hi * b.hi};
  return {down(*std::min_element(p.begin(), p.end())),
          up(*std::max_element(p.begin(), p.end()))};
}
Interval neg(Interval a) { return {-a.hi, -a.lo}; }
Interval scalar(double x) { return {x, x}; }
Interval transformed(const Camera &c, V p, unsigned row) {
  return plus(plus(plus(times(scalar(c.r[row * 3]), scalar(p.x)),
                        times(scalar(c.r[row * 3 + 1]), scalar(p.y))),
                   times(scalar(c.r[row * 3 + 2]), scalar(p.z))),
              scalar(row == 0   ? c.t.x
                     : row == 1 ? c.t.y
                                : c.t.z));
}
Interval hull(Interval a, Interval b) {
  return {std::min(a.lo, b.lo), std::max(a.hi, b.hi)};
}
bool valid(Interval a) {
  return std::isfinite(a.lo) && std::isfinite(a.hi) && a.lo <= a.hi;
}
// Directed interval operations include source-plane construction and camera
// transform rounding, not only evaluation of rounded scalar plane coefficients.
bool residualBound(const Prepared &s, const Camera &c,
                   std::span<const V> points, double tolerance) {
  Interval z{INFINITY, -INFINITY}, den{INFINITY, -INFINITY};
  for (auto p : points) {
    auto qx = transformed(c, p, 0), qy = transformed(c, p, 1),
         qz = transformed(c, p, 2);
    z = hull(z, qz);
    den = hull(den, plus(plus(times(s.ni[0], qx), times(s.ni[1], qy)),
                         times(s.ni[2], qz)));
  }
  if (!valid(z) || !valid(den) || z.lo <= 0 || (den.lo <= 0 && den.hi >= 0))
    return false;
  const auto numerator = times(z, plus(s.di, neg(den)));
  const Interval inverse{down(1. / den.hi), up(1. / den.lo)};
  const auto result = times(numerator, inverse);
  // Numerical allowance consumes tolerance rather than extending admission.
  return valid(result) && result.lo >= -tolerance * .999999 &&
         result.hi <= tolerance * .999999;
}
bool value(const Prepared &s, const Camera &c, V p, double &result) {
  const auto q = transform(c, p);
  const auto den = dot(s.n, q);
  if (q.z <= 0 || !finite(q) || !std::isfinite(den) || std::abs(den) < 1e-12)
    return false;
  result = q.z * (s.d - den) / den;
  return std::isfinite(result);
}
P project(const Camera &c, V p) {
  const auto q = transform(c, p);
  return {c.fx * q.x / q.z + c.cx, c.fy * q.y / q.z + c.cy};
}
Interval divide(Interval a, Interval b) {
  if (!valid(b) || (b.lo <= 0 && b.hi >= 0))
    return {-INFINITY, INFINITY};
  return times(a, {down(1 / b.hi), up(1 / b.lo)});
}
std::array<Interval, 2> projectionBounds(const Camera &c,
                                         std::span<const V> points) {
  std::array<Interval, 2> result{Interval{INFINITY, -INFINITY},
                                 Interval{INFINITY, -INFINITY}};
  for (auto p : points) {
    const auto z = transformed(c, p, 2);
    if (!valid(z) || z.lo <= 1e-8)
      return {Interval{-INFINITY, INFINITY}, Interval{-INFINITY, INFINITY}};
    for (unsigned axis = 0; axis < 2; ++axis) {
      const auto q = plus(
          times(scalar(axis ? c.fy : c.fx), divide(transformed(c, p, axis), z)),
          scalar(axis ? c.cy : c.cx));
      result[axis] = hull(result[axis], q);
    }
  }
  return result;
}
bool contains(const Prepared &s, const Camera &c, std::span<const V> points) {
  for (auto p : points) {
    std::array<Interval, 3> q{transformed(c, p, 0), transformed(c, p, 1),
                              transformed(c, p, 2)};
    if (q[2].lo <= 1e-8)
      return false;
    for (auto edge : s.cone) {
      const auto d = plus(plus(times(edge[0], q[0]), times(edge[1], q[1])),
                          times(edge[2], q[2]));
      if (!valid(d) || (s.coneSign > 0 ? d.lo <= 0 : d.hi >= 0))
        return false;
    }
  }
  return true;
}
bool overlapBounds(const std::array<Interval, 2> &a,
                   const std::array<Interval, 2> &b) {
  return !(a[0].hi < b[0].lo || b[0].hi < a[0].lo || a[1].hi < b[1].lo ||
           b[1].hi < a[1].lo);
}
bool aabb(const Prepared &a, const Prepared &b, double padding) {
  return a.lo.x - padding <= b.hi.x + padding &&
         b.lo.x - padding <= a.hi.x + padding &&
         a.lo.y - padding <= b.hi.y + padding &&
         b.lo.y - padding <= a.hi.y + padding &&
         a.lo.z - padding <= b.hi.z + padding &&
         b.lo.z - padding <= a.hi.z + padding;
}
} // namespace
struct Workspace {
  std::array<Camera, MaxCameras> cameras{};
  std::array<Prepared, MaxSources> sources{};
  std::array<double, MaxNodes> field{};
  std::array<std::uint8_t, MaxNodes> known{};
  std::array<Face, MaxFaces> output{};
};
static_assert(sizeof(Workspace) <= 8u * 1024u * 1024u);
Result::Result() = default;
Result::~Result() = default;
Result::Result(Result &&) noexcept = default;
Result &Result::operator=(Result &&) noexcept = default;
std::span<const Face> Result::faces() const {
  return storage && status == Status::Success
             ? std::span<const Face>(storage->output.data(), counts.faces)
             : std::span<const Face>{};
}
std::size_t workspaceBytes() { return sizeof(Workspace); }
const char *name(Status s) {
  switch (s) {
  case Status::Success:
    return "SUCCESS_DERIVED_INTERPOLATED_HOST_QA";
  case Status::Invalid:
    return "INVALID_INPUT";
  case Status::NoOverlap:
    return "NO_OVERLAP";
  case Status::IncompatiblePair:
    return "INCOMPATIBLE_FIRST_PAIR";
  case Status::NoSupportedCells:
    return "NO_SUPPORTED_CELLS";
  case Status::AmbiguousZero:
    return "ZERO_OR_NUMERIC_SIGN_AMBIGUITY";
  case Status::OutputCap:
    return "OUTPUT_CAP";
  case Status::WorkCap:
    return "WORK_CAP";
  case Status::Cancelled:
    return "CANCELLED";
  case Status::Allocation:
    return "ALLOCATION";
  case Status::Numeric:
    return "NUMERIC_REFUSAL";
  }
  return "INVALID_STATUS";
}
Result run(std::span<const Camera> cameras, std::span<const Source> sources,
           const Settings &settings, Cancel cancel, void *context) {
  Result result;
  result.maxResidual = settings.maxResidual;
  result.ownedBytes = sizeof(Workspace);
  const auto fail = [&](Status s) -> Result {
    result.status = s;
    result.storage.reset();
    return std::move(result);
  };
  if (cameras.size() < 2 || cameras.size() > MaxCameras || sources.size() < 2 ||
      sources.size() > MaxSources || settings.edge < 4 || settings.edge > 32 ||
      !std::isfinite(settings.maxResidual) || settings.maxResidual <= 0 ||
      settings.maxResidual > 1e3 || settings.faceCap == 0 ||
      settings.faceCap > MaxFaces || settings.workCap == 0 ||
      settings.workCap > 20000000)
    return fail(Status::Invalid);
  if (cancel && cancel(context))
    return fail(Status::Cancelled);
  try {
    result.storage = std::make_unique<Workspace>();
  } catch (const std::bad_alloc &) {
    return fail(Status::Allocation);
  }
  auto &w = *result.storage;
  std::copy(cameras.begin(), cameras.end(), w.cameras.begin());
  std::sort(w.cameras.begin(), w.cameras.begin() + cameras.size(),
            [](auto &a, auto &b) { return a.id < b.id; });
  result.cameraCount = cameras.size();
  for (std::size_t i = 0; i < cameras.size(); ++i) {
    const auto &c = w.cameras[i];
    result.cameraIds[i] = c.id;
    if (i && c.id == w.cameras[i - 1].id)
      return fail(Status::Invalid);
    if (!finite(c.t) || !std::isfinite(c.fx) || !std::isfinite(c.fy) ||
        c.fx <= 0 || c.fy <= 0 || !std::isfinite(c.cx) || !std::isfinite(c.cy))
      return fail(Status::Invalid);
    for (auto x : c.r)
      if (!std::isfinite(x))
        return fail(Status::Invalid);
    for (unsigned a = 0; a < 3; ++a)
      for (unsigned b = 0; b < 3; ++b) {
        double d = 0;
        for (unsigned k = 0; k < 3; ++k)
          d += c.r[a * 3 + k] * c.r[b * 3 + k];
        if (std::abs(d - (a == b ? 1. : 0.)) > 1e-10)
          return fail(Status::Invalid);
      }
    V r0{c.r[0], c.r[1], c.r[2]}, r1{c.r[3], c.r[4], c.r[5]},
        r2{c.r[6], c.r[7], c.r[8]};
    if (dot(cross(r0, r1), r2) < .999999)
      return fail(Status::Invalid);
  }
  for (std::size_t i = 0; i < sources.size(); ++i)
    w.sources[i].source = sources[i];
  std::sort(
      w.sources.begin(), w.sources.begin() + sources.size(),
      [](const auto &a, const auto &b) { return a.source.id < b.source.id; });
  Status stopped = Status::Success;
  const auto charge = [&]() {
    if (++result.counts.work > settings.workCap) {
      stopped = Status::WorkCap;
      return false;
    }
    if ((result.counts.work & 63u) == 0 && cancel && cancel(context)) {
      stopped = Status::Cancelled;
      return false;
    }
    return true;
  };
  for (std::size_t i = 0; i < sources.size(); ++i) {
    if (!charge())
      return fail(stopped);
    auto &s = w.sources[i];
    if (!s.source.id || (i && s.source.id == w.sources[i - 1].source.id))
      return fail(Status::Invalid);
    s.camera = cameras.size();
    for (std::size_t j = 0; j < cameras.size(); ++j)
      if (w.cameras[j].id == s.source.camera)
        s.camera = j;
    if (s.camera == cameras.size())
      return fail(Status::Invalid);
    const auto &c = w.cameras[s.camera];
    s.lo = {INFINITY, INFINITY, INFINITY};
    s.hi = {-INFINITY, -INFINITY, -INFINITY};
    std::array<V, 3> q{};
    for (unsigned k = 0; k < 3; ++k) {
      auto p = s.source.p[k];
      if (!finite(p) ||
          std::max({std::abs(p.x), std::abs(p.y), std::abs(p.z)}) > 1e6)
        return fail(Status::Invalid);
      q[k] = transform(c, p);
      if (q[k].z <= 1e-8 || !finite(q[k]))
        return fail(Status::Invalid);
      s.uv[k] = project(c, p);
      s.lo = {std::min(s.lo.x, p.x), std::min(s.lo.y, p.y),
              std::min(s.lo.z, p.z)};
      s.hi = {std::max(s.hi.x, p.x), std::max(s.hi.y, p.y),
              std::max(s.hi.z, p.z)};
    }
    s.n = cross(sub(q[1], q[0]), sub(q[2], q[0]));
    const double norm = std::sqrt(dot(s.n, s.n));
    if (!std::isfinite(norm) || norm < 1e-10)
      return fail(Status::Invalid);
    s.n = mul(s.n, 1 / norm);
    s.d = dot(s.n, q[0]);
    std::array<std::array<Interval, 3>, 3> qi{};
    for (unsigned v = 0; v < 3; ++v)
      for (unsigned axis = 0; axis < 3; ++axis)
        qi[v][axis] = transformed(c, s.source.p[v], axis);
    std::array<Interval, 3> e1{}, e2{};
    for (unsigned axis = 0; axis < 3; ++axis) {
      e1[axis] = plus(qi[1][axis], neg(qi[0][axis]));
      e2[axis] = plus(qi[2][axis], neg(qi[0][axis]));
    }
    for (unsigned axis = 0; axis < 3; ++axis) {
      auto j = (axis + 1) % 3, k = (axis + 2) % 3;
      s.ni[axis] = plus(times(e1[j], e2[k]), neg(times(e1[k], e2[j])));
    }
    s.di = plus(plus(times(s.ni[0], qi[0][0]), times(s.ni[1], qi[0][1])),
                times(s.ni[2], qi[0][2]));
    for (unsigned edge = 0; edge < 3; ++edge)
      for (unsigned axis = 0; axis < 3; ++axis) {
        auto j = (axis + 1) % 3, k = (axis + 2) % 3;
        s.cone[edge][axis] =
            plus(times(qi[edge][j], qi[(edge + 1) % 3][k]),
                 neg(times(qi[edge][k], qi[(edge + 1) % 3][j])));
      }
    s.coneSign = dot(cross(q[0], q[1]), q[2]);
    s.bounds = projectionBounds(c, s.source.p);
    if (!valid(s.bounds[0]) || !valid(s.bounds[1]) || s.coneSign == 0)
      return fail(Status::Numeric);
    if (std::abs(s.d) < 1e-10 ||
        std::abs(orient(s.uv[0], s.uv[1], s.uv[2])) < 1e-10)
      return fail(Status::Invalid);
    // Floating construction must reconstruct all original corners comfortably
    // inside the declared discrepancy tolerance; this is NOT measurement
    // uncertainty.
    for (auto p : s.source.p) {
      double v;
      if (!value(s, c, p, v) || std::abs(v) > settings.maxResidual * 1e-6)
        return fail(Status::Numeric);
    }
  }
  std::size_t first = sources.size(), second = sources.size();
  for (std::size_t a = 0; a < sources.size() && first == sources.size(); ++a)
    for (std::size_t b = a + 1; b < sources.size(); ++b) {
      if (!charge())
        return fail(stopped);
      if (w.sources[a].camera == w.sources[b].camera)
        continue;
      ++result.counts.pairsVisited;
      if (!aabb(w.sources[a], w.sources[b], settings.maxResidual)) {
        ++result.counts.pairsNoOverlap;
        continue;
      }
      first = a;
      second = b;
      break;
    }
  if (first == sources.size())
    return fail(Status::NoOverlap);
  const auto &a = w.sources[first];
  const auto &b = w.sources[second];
  result.basis = {a.source.id, b.source.id};
  // Opposed viewing directions are not a single signed sheet hypothesis.
  const auto facing = [&](const Prepared &s) {
    const auto &c = w.cameras[s.camera];
    const auto n = mul(s.n, s.d > 0 ? -1. : 1.);
    return V{c.r[0] * n.x + c.r[3] * n.y + c.r[6] * n.z,
             c.r[1] * n.x + c.r[4] * n.y + c.r[7] * n.z,
             c.r[2] * n.x + c.r[5] * n.y + c.r[8] * n.z};
  };
  if (dot(facing(a), facing(b)) <= 0)
    return fail(Status::IncompatiblePair);
  // First spatially overlapping pair is final, even if rejected; no quality
  // search.
  if (!residualBound(a, w.cameras[a.camera], b.source.p,
                     settings.maxResidual) ||
      !residualBound(b, w.cameras[b.camera], a.source.p, settings.maxResidual))
    return fail(Status::IncompatiblePair);
  const double p = settings.maxResidual * 2;
  result.lower = {std::min(a.lo.x, b.lo.x) - p, std::min(a.lo.y, b.lo.y) - p,
                  std::min(a.lo.z, b.lo.z) - p};
  result.upper = {std::max(a.hi.x, b.hi.x) + p, std::max(a.hi.y, b.hi.y) + p,
                  std::max(a.hi.z, b.hi.z) + p};
  const unsigned n = settings.edge;
  const auto node = [&](unsigned x, unsigned y, unsigned z) {
    return static_cast<std::uint32_t>((z * n + y) * n + x);
  };
  const auto position = [&](std::uint32_t id) {
    const unsigned x = id % n, y = (id / n) % n, z = id / (n * n);
    return V{result.lower.x + (result.upper.x - result.lower.x) * x / (n - 1),
             result.lower.y + (result.upper.y - result.lower.y) * y / (n - 1),
             result.lower.z + (result.upper.z - result.lower.z) * z / (n - 1)};
  };
  for (unsigned z = 0; z < n; ++z)
    for (unsigned y = 0; y < n; ++y)
      for (unsigned x = 0; x < n; ++x) {
        if (!charge())
          return fail(stopped);
        const auto id = node(x, y, z);
        const auto point = position(id);
        double va{}, vb{};
        if (value(a, w.cameras[a.camera], point, va) &&
            value(b, w.cameras[b.camera], point, vb)) {
          w.field[id] = (va + vb) * .5;
          w.known[id] = std::isfinite(w.field[id]);
        }
      }
  constexpr unsigned offsets[8][3] = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0},
                                      {0, 1, 0}, {0, 0, 1}, {1, 0, 1},
                                      {1, 1, 1}, {0, 1, 1}};
  // Freudenthal decomposition around global lower->upper body diagonal.
  // Identical shared square diagonals in adjacent cells. Edge keys use sorted
  // global nodes.
  constexpr unsigned tetra[6][4] = {{0, 1, 2, 6}, {0, 2, 3, 6}, {0, 3, 7, 6},
                                    {0, 7, 4, 6}, {0, 4, 5, 6}, {0, 5, 1, 6}};
  for (unsigned z = 0; z + 1 < n; ++z)
    for (unsigned y = 0; y + 1 < n; ++y)
      for (unsigned x = 0; x + 1 < n; ++x) {
        if (!charge())
          return fail(stopped);
        ++result.counts.cells;
        std::array<std::uint32_t, 8> ids{};
        std::array<V, 8> corners{};
        bool unknown = false, zero = false;
        for (unsigned k = 0; k < 8; ++k) {
          ids[k] =
              node(x + offsets[k][0], y + offsets[k][1], z + offsets[k][2]);
          corners[k] = position(ids[k]);
          unknown |= !w.known[ids[k]];
          zero |=
              std::abs(w.field[ids[k]]) <=
              64 * std::numeric_limits<double>::epsilon() *
                  (1 + std::max({std::abs(corners[k].x), std::abs(corners[k].y),
                                 std::abs(corners[k].z)}));
        }
        if (unknown) {
          ++result.counts.unknown;
          continue;
        }
        std::array<std::uint64_t, MaxCameras> sourceIds{};
        std::uint8_t mask = 0;
        bool admit = true;
        for (std::size_t ci = 0; ci < cameras.size() && admit; ++ci) {
          const auto box = projectionBounds(w.cameras[ci], corners);
          if (!valid(box[0]) || !valid(box[1])) {
            ++result.counts.unknown;
            admit = false;
            break;
          }
          std::size_t hit = sources.size();
          for (std::size_t si = 0; si < sources.size(); ++si) {
            if (!charge())
              return fail(stopped);
            const auto &s = w.sources[si];
            if (s.camera != ci)
              continue;
            if (!overlapBounds(box, s.bounds))
              continue;
            if (hit != sources.size()) {
              ++result.counts.extraLayer;
              admit = false;
              break;
            }
            hit = si;
          }
          if (!admit)
            break;
          if (hit == sources.size()) {
            if (ci == a.camera || ci == b.camera) {
              ++result.counts.unknown;
              admit = false;
            }
            continue;
          }
          if ((ci == a.camera && hit != first) ||
              (ci == b.camera && hit != second)) {
            ++result.counts.extraLayer;
            admit = false;
            break;
          }
          const auto &s = w.sources[hit];
          if (!contains(s, w.cameras[ci], corners)) {
            ++result.counts.witnessPartial;
            admit = false;
            break;
          }
          if (!residualBound(s, w.cameras[ci], corners, settings.maxResidual)) {
            ++result.counts.residual;
            admit = false;
            break;
          }
          sourceIds[ci] = s.source.id;
          mask |= static_cast<std::uint8_t>(1u << ci);
        }
        if (!admit)
          continue;
        if (!(mask & (1u << a.camera)) || !(mask & (1u << b.camera)))
          return fail(Status::Numeric);
        if (zero) {
          ++result.counts.zeroCells;
          continue;
        }
        ++result.counts.supportedCells;
        for (const auto &t : tetra) {
          if (!charge())
            return fail(stopped);
          std::array<unsigned, 4> pos{}, negative{};
          unsigned np = 0, nn = 0;
          for (auto k : t) {
            if (w.field[ids[k]] > 0)
              pos[np++] = k;
            else
              negative[nn++] = k;
          }
          if (np == 0 || nn == 0)
            continue;
          struct Vertex {
            V p;
            std::uint64_t key;
          };
          const auto edge = [&](unsigned u, unsigned v) {
            auto i = ids[u], j = ids[v];
            if (i > j)
              std::swap(i, j);
            double f = w.field[i] / (w.field[i] - w.field[j]);
            return Vertex{
                add(position(i), mul(sub(position(j), position(i)), f)),
                (static_cast<std::uint64_t>(i) << 32) | j};
          };
          const auto emit = [&](Vertex u, Vertex v, Vertex q) {
            if (!charge())
              return false;
            if (result.counts.faces >= settings.faceCap) {
              stopped = Status::OutputCap;
              return false;
            }
            auto normal = cross(sub(v.p, u.p), sub(q.p, u.p));
            if (!finite(u.p) || !finite(v.p) || !finite(q.p) ||
                dot(normal, normal) <= 1e-28) {
              stopped = Status::Numeric;
              return false;
            }
            std::array<V, 3> points{u.p, v.p, q.p};
            for (std::size_t ci = 0; ci < cameras.size(); ++ci)
              if (mask & (1u << ci)) {
                const Prepared *s = nullptr;
                for (std::size_t si = 0; si < sources.size(); ++si) {
                  if (!charge())
                    return false;
                  if (w.sources[si].source.id == sourceIds[ci]) {
                    s = &w.sources[si];
                    break;
                  }
                }
                if (!s || !contains(*s, w.cameras[ci], points) ||
                    !residualBound(*s, w.cameras[ci], points,
                                   settings.maxResidual)) {
                  stopped = Status::Numeric;
                  return false;
                }
              }
            // Orient toward first basis camera using camera-space face centroid
            // direction.
            const auto centroid = mul(add(add(u.p, v.p), q.p), 1. / 3);
            const auto cameraNormal =
                V{w.cameras[a.camera].r[0] * normal.x +
                      w.cameras[a.camera].r[1] * normal.y +
                      w.cameras[a.camera].r[2] * normal.z,
                  w.cameras[a.camera].r[3] * normal.x +
                      w.cameras[a.camera].r[4] * normal.y +
                      w.cameras[a.camera].r[5] * normal.z,
                  w.cameras[a.camera].r[6] * normal.x +
                      w.cameras[a.camera].r[7] * normal.y +
                      w.cameras[a.camera].r[8] * normal.z};
            if (dot(cameraNormal, transform(w.cameras[a.camera], centroid)) > 0)
              std::swap(v, q);
            auto &f = w.output[result.counts.faces++];
            f.p = {u.p, v.p, q.p};
            f.edgeKeys = {u.key, v.key, q.key};
            f.sourceIds = sourceIds;
            f.cameraMask = mask;
            return true;
          };
          if (np == 1) {
            if (!emit(edge(pos[0], negative[0]), edge(pos[0], negative[1]),
                      edge(pos[0], negative[2])))
              return fail(stopped);
          } else if (nn == 1) {
            if (!emit(edge(negative[0], pos[0]), edge(negative[0], pos[1]),
                      edge(negative[0], pos[2])))
              return fail(stopped);
          } else {
            const auto p0 = edge(pos[0], negative[0]),
                       p1 = edge(pos[0], negative[1]),
                       p2 = edge(pos[1], negative[0]),
                       p3 = edge(pos[1], negative[1]);
            if (!emit(p0, p1, p2) || !emit(p1, p3, p2))
              return fail(stopped);
          }
        }
      }
  if (cancel && cancel(context))
    return fail(Status::Cancelled);
  if (result.counts.faces == 0)
    return fail(result.counts.zeroCells ? Status::AmbiguousZero
                                        : Status::NoSupportedCells);
  result.status = Status::Success;
  return result;
}
} // namespace observed_patch
