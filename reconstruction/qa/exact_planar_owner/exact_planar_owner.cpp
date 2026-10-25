#include "exact_planar_owner.hpp"
#include <algorithm>
#include <bit>
#include <exception>
#include <limits>
#include <memory_resource>
#include <stdexcept>
#include <vector>

namespace rf::qa::planar {
namespace {
struct Refusal {
  Reason reason;
};
struct CallbackError {
  std::exception_ptr error;
};
struct Guard {
  Limits limits;
  Cancel cancel;
  void *context;
  std::size_t steps = 0;
  std::size_t completed_faces = 0;
  void cancellation() {
    bool stop = false;
    try {
      stop = cancel && cancel(context);
    } catch (...) {
      throw CallbackError{std::current_exception()};
    }
    if (stop)
      throw Refusal{Reason::cancelled};
  }
  void tick() {
    if (++steps > limits.steps)
      throw Refusal{Reason::work_budget};
    if (steps % 64 == 1)
      cancellation();
  }
  void check(const Integer &x) {
    tick();
    if (x != 0 && boost::multiprecision::msb(x < 0 ? -x : x) >= limits.bits)
      throw Refusal{Reason::arithmetic};
  }
};
struct Q {
  Integer n = 0, d = 1;
  Guard *g = nullptr;
  Q() = default;
  Q(std::int64_t v) : n(v) {} // small exact constants only
  Q(Integer a, Integer b, Guard *guard)
      : n(std::move(a)), d(std::move(b)), g(guard) {
    if (!g || d == 0)
      throw Refusal{Reason::invalid_input};
    g->check(n);
    g->check(d);
    if (d < 0) {
      n = -n;
      d = -d;
    }
    Integer x = n < 0 ? -n : n, y = d;
    while (y != 0) {
      g->tick();
      Integer r = x % y;
      x = y;
      y = r;
    }
    n /= x;
    d /= x;
    g->check(n);
    g->check(d);
  }
  Rational raw() const { return {n, d}; }
};
Guard *guard(const Q &a, const Q &b) {
  auto *g = a.g ? a.g : b.g;
  if (!g)
    throw Refusal{Reason::arithmetic};
  g->tick();
  return g;
}
Q operator+(const Q &a, const Q &b) {
  auto *g = guard(a, b);
  return Q(a.n * b.d + b.n * a.d, a.d * b.d, g);
}
Q operator-(const Q &a, const Q &b) {
  auto *g = guard(a, b);
  return Q(a.n * b.d - b.n * a.d, a.d * b.d, g);
}
Q operator*(const Q &a, const Q &b) {
  auto *g = guard(a, b);
  return Q(a.n * b.n, a.d * b.d, g);
}
Q operator/(const Q &a, const Q &b) {
  auto *g = guard(a, b);
  if (b.n == 0)
    throw Refusal{Reason::arithmetic};
  return Q(a.n * b.d, a.d * b.n, g);
}
bool operator==(const Q &a, const Q &b) {
  guard(a, b);
  return a.n == b.n && a.d == b.d;
}
bool operator<(const Q &a, const Q &b) {
  guard(a, b);
  return a.n * b.d < b.n * a.d;
}
bool operator>(const Q &a, const Q &b) { return b < a; }
bool operator<=(const Q &a, const Q &b) { return !(b < a); }
bool operator>=(const Q &a, const Q &b) { return !(a < b); }
using P2 = std::array<Q, 2>;
using P3 = std::array<Q, 3>;
using Tri = std::array<P2, 3>;
using Poly = std::pmr::vector<P2>;
P3 sub(const P3 &a, const P3 &b) {
  return {a[0] - b[0], a[1] - b[1], a[2] - b[2]};
}
P3 cross(const P3 &a, const P3 &b) {
  return {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2],
          a[0] * b[1] - a[1] * b[0]};
}
Q dot(const P3 &a, const P3 &b) {
  return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
}
Q orient(const P2 &a, const P2 &b, const P2 &c) {
  return (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
}
Q area(const Poly &p, Guard &g) {
  Q sum(0, 1, &g);
  for (std::size_t i = 0; i < p.size(); ++i) {
    auto &b = p[(i + 1) % p.size()];
    sum = sum + p[i][0] * b[1] - p[i][1] * b[0];
  }
  return sum;
}
void push(Poly &p, const P2 &v, Guard &g) {
  g.tick();
  if (p.size() >= g.limits.polygon_vertices)
    throw Refusal{Reason::geometry_budget};
  p.push_back(v);
}
Poly clean(Poly p, Guard &g) {
  std::size_t n = 0;
  for (std::size_t i = 0; i < p.size(); ++i)
    if (n == 0 || p[i] != p[n - 1])
      p[n++] = p[i];
  p.resize(n);
  if (n > 1 && p.front() == p.back())
    p.pop_back();
  if (p.size() < 3 || area(p, g) <= Q(0))
    p.clear();
  return p;
}
Poly clip(const Poly &p, const P2 &a, const P2 &b, bool inside, Guard &g,
          std::pmr::memory_resource *mr) {
  Poly out(mr);
  for (std::size_t i = 0; i < p.size(); ++i) {
    const auto &u = p[i];
    const auto &v = p[(i + 1) % p.size()];
    auto du = orient(a, b, u), dv = orient(a, b, v);
    bool ui = inside ? du >= Q(0) : du <= Q(0),
         vi = inside ? dv >= Q(0) : dv <= Q(0);
    if (ui)
      push(out, u, g);
    if (ui != vi) {
      auto t = du / (du - dv);
      push(out, {u[0] + t * (v[0] - u[0]), u[1] + t * (v[1] - u[1])}, g);
    }
  }
  return clean(std::move(out), g);
}
struct Fragment {
  std::size_t owner;
  Poly poly;
};
void validate_limits(const Limits &l) {
  const Limits d;
  if (l.sources < 1 || l.sources > d.sources || l.fragments < 1 ||
      l.fragments > d.fragments || l.faces < 1 || l.faces > d.faces ||
      l.bits < 1 || l.bits > d.bits || l.steps < 1 || l.steps > d.steps ||
      l.polygon_vertices < 3 || l.polygon_vertices > d.polygon_vertices ||
      l.nodes < 1 || l.nodes > d.nodes || l.workspace_bytes < 1 ||
      l.workspace_bytes > d.workspace_bytes)
    throw Refusal{Reason::invalid_input};
}
std::unique_ptr<Output> run(std::span<const Source> input, Guard &g) {
  validate_limits(g.limits);
  g.cancellation();
  if (input.empty() || input.size() > g.limits.sources)
    throw Refusal{Reason::invalid_input};
  std::array<std::size_t, 32> order{};
  for (std::size_t i = 0; i < input.size(); ++i) {
    g.tick();
    order[i] = i;
    auto id = input[i].id;
    if (id.empty() || id.size() > 128)
      throw Refusal{Reason::invalid_input};
    for (unsigned char c : id)
      if (c < 33 || c > 126)
        throw Refusal{Reason::invalid_input};
  }
  std::sort(order.begin(),
            order.begin() + static_cast<std::ptrdiff_t>(input.size()),
            [&](auto a, auto b) {
              g.tick();
              return input[a].id < input[b].id;
            });
  for (std::size_t i = 1; i < input.size(); ++i)
    if (input[order[i - 1]].id == input[order[i]].id)
      throw Refusal{Reason::invalid_input};
  auto storage = std::make_unique<std::byte[]>(g.limits.workspace_bytes);
  std::pmr::monotonic_buffer_resource mr(storage.get(),
                                         g.limits.workspace_bytes,
                                         std::pmr::null_memory_resource());
  auto output = std::make_unique<Output>();
  output->source_count = input.size();
  std::pmr::vector<std::array<P3, 3>> originals(&mr);
  originals.reserve(input.size());
  for (std::size_t i = 0; i < input.size(); ++i) {
    const auto &s = input[order[i]];
    std::array<P3, 3> t;
    auto &r = output->sources[i];
    r.id_size = s.id.size();
    std::copy(s.id.begin(), s.id.end(), r.id.begin());
    for (std::size_t j = 0; j < 3; ++j)
      for (std::size_t k = 0; k < 3; ++k) {
        t[j][k] = Q(s.corners[j][k].n, s.corners[j][k].d, &g);
        r.corners[j][k] = t[j][k].raw();
      }
    originals.push_back(std::move(t));
  }
  const auto a = originals[0][0];
  const auto normal = cross(sub(originals[0][1], a), sub(originals[0][2], a));
  std::size_t axis = 0;
  auto absq = [](const Q &q) { return q < Q(0) ? Q(0) - q : q; };
  for (std::size_t k = 1; k < 3; ++k)
    if (absq(normal[k]) > absq(normal[axis]))
      axis = k;
  if (normal[axis] == Q(0))
    throw Refusal{Reason::orientation};
  std::array<std::size_t, 2> axes{};
  for (std::size_t k = 0, j = 0; k < 3; ++k)
    if (k != axis)
      axes[j++] = k;
  auto project = [&](const P3 &p) -> P2 { return {p[axes[0]], p[axes[1]]}; };
  auto lift = [&](const P2 &p) {
    P3 q;
    q[axes[0]] = p[0];
    q[axes[1]] = p[1];
    q[axis] = a[axis] - (normal[axes[0]] * (p[0] - a[axes[0]]) +
                         normal[axes[1]] * (p[1] - a[axes[1]])) /
                            normal[axis];
    return q;
  };
  std::pmr::vector<Tri> triangles(&mr);
  triangles.reserve(input.size());
  for (const auto &t : originals) {
    for (const auto &p : t)
      if (dot(normal, sub(p, a)) != Q(0))
        throw Refusal{Reason::noncoplanar};
    if (dot(cross(sub(t[1], t[0]), sub(t[2], t[0])), normal) <= Q(0))
      throw Refusal{Reason::orientation};
    Tri tri{project(t[0]), project(t[1]), project(t[2])};
    if (orient(tri[0], tri[1], tri[2]) < Q(0))
      std::swap(tri[1], tri[2]);
    triangles.push_back(std::move(tri));
  }
  std::pmr::vector<Fragment> fragments(&mr);
  for (std::size_t owner = 0; owner < triangles.size(); ++owner) {
    std::pmr::vector<Poly> pieces(&mr);
    pieces.push_back(
        Poly(triangles[owner].begin(), triangles[owner].end(), &mr));
    for (std::size_t earlier = 0; earlier < owner; ++earlier) {
      std::pmr::vector<Poly> next(&mr);
      const auto &c = triangles[earlier];
      for (const auto &p : pieces) {
        Poly remainder(p, &mr);
        for (std::size_t j = 0; j < 3; ++j) {
          auto outside = clip(remainder, c[j], c[(j + 1) % 3], false, g, &mr);
          if (!outside.empty()) {
            if (next.size() + fragments.size() >= g.limits.fragments)
              throw Refusal{Reason::geometry_budget};
            next.push_back(std::move(outside));
          }
          remainder = clip(remainder, c[j], c[(j + 1) % 3], true, g, &mr);
          if (remainder.empty())
            break;
        }
      }
      pieces = std::move(next);
    }
    for (auto &p : pieces) {
      if (fragments.size() >= g.limits.fragments)
        throw Refusal{Reason::geometry_budget};
      fragments.push_back({owner, std::move(p)});
    }
  }
  Poly points(&mr);
  auto addpoint = [&](const P2 &p) {
    g.tick();
    if (points.size() >= g.limits.nodes)
      throw Refusal{Reason::geometry_budget};
    points.push_back(p);
  };
  for (const auto &f : fragments)
    for (const auto &p : f.poly)
      addpoint(p);
  for (const auto &t : triangles)
    for (const auto &p : t)
      addpoint(p);
  std::sort(points.begin(), points.end());
  points.erase(std::unique(points.begin(), points.end()), points.end());
  for (const auto &f : fragments) {
    Poly boundary(&mr);
    const auto &p = f.poly;
    for (std::size_t i = 0; i < p.size(); ++i) {
      const auto &u = p[i];
      const auto &v = p[(i + 1) % p.size()];
      std::size_t k = u[0] != v[0] ? 0 : 1;
      std::pmr::vector<std::pair<Q, P2>> nodes(&mr);
      for (const auto &candidate : points) {
        g.tick();
        if (orient(u, v, candidate) == Q(0)) {
          auto t = (candidate[k] - u[k]) / (v[k] - u[k]);
          if (t >= Q(0) && t < Q(1))
            nodes.push_back({t, candidate});
        }
      }
      std::sort(nodes.begin(), nodes.end(),
                [](const auto &x, const auto &y) { return x.first < y.first; });
      for (const auto &node : nodes) {
        if (boundary.size() >= g.limits.nodes)
          throw Refusal{Reason::geometry_budget};
        boundary.push_back(node.second);
      }
    }
    P2 center{Q(0, 1, &g), Q(0, 1, &g)};
    for (const auto &v : p)
      for (std::size_t k = 0; k < 2; ++k)
        center[k] = center[k] + v[k];
    for (auto &x : center)
      x = x / Q(static_cast<std::int64_t>(p.size()));
    const auto &t = triangles[f.owner];
    auto den = orient(t[0], t[1], t[2]);
    for (std::size_t i = 0; i < boundary.size(); ++i) {
      if (output->face_count >= g.limits.faces)
        throw Refusal{Reason::geometry_budget};
      std::array<P2, 3> corners{center, boundary[i],
                                boundary[(i + 1) % boundary.size()]};
      if (orient(corners[0], corners[1], corners[2]) <= Q(0))
        throw Refusal{Reason::orientation};
      Face face;
      face.owner = f.owner;
      std::array<P3, 3> lifted;
      for (std::size_t j = 0; j < 3; ++j) {
        const auto &v = corners[j];
        P3 w{orient(t[1], t[2], v) / den, orient(t[2], t[0], v) / den,
             orient(t[0], t[1], v) / den};
        if (w[0] < Q(0) || w[1] < Q(0) || w[2] < Q(0) ||
            w[0] + w[1] + w[2] != Q(1))
          throw Refusal{Reason::orientation};
        for (std::size_t k = 0; k < 3; ++k) {
          auto original = project(originals[f.owner][k]);
          std::size_t index = 0;
          while (index < 3 && t[index] != original)
            ++index;
          if (index == 3)
            throw Refusal{Reason::orientation};
          face.barycentrics[j][k] = w[index].raw();
        }
        lifted[j] = lift(v);
        for (std::size_t k = 0; k < 3; ++k)
          face.positions[j][k] = lifted[j][k].raw();
      }
      if (dot(cross(sub(lifted[1], lifted[0]), sub(lifted[2], lifted[0])),
              normal) < Q(0)) {
        std::swap(face.positions[1], face.positions[2]);
        std::swap(face.barycentrics[1], face.barycentrics[2]);
      }
      output->faces[output->face_count++] = std::move(face);
      g.completed_faces = output->face_count;
    }
  }
  g.cancellation();
  return output;
}
} // namespace
Result reconcile(std::span<const Source> sources, Limits limits, Cancel cancel,
                 void *context) {
  Guard g{limits, cancel, context};
  Result r;
  try {
    r.output = run(sources, g);
    r.status = Status::exact_qa_only;
  } catch (const CallbackError &e) {
    std::rethrow_exception(e.error);
  } catch (const Refusal &e) {
    r.reason = e.reason;
  } catch (const std::overflow_error &) {
    r.reason = Reason::arithmetic;
  } catch (const std::range_error &) {
    r.reason = Reason::arithmetic;
  } catch (const std::bad_alloc &) {
    r.reason = Reason::allocation_budget;
  }
  r.steps = g.steps;
  r.discarded_faces = r.output ? 0 : g.completed_faces;
  return r;
}
std::optional<Rational> exact_binary64(double value) {
  static_assert(sizeof(double) == sizeof(std::uint64_t) &&
                std::numeric_limits<double>::is_iec559 &&
                std::numeric_limits<double>::digits == 53);
  auto bits = std::bit_cast<std::uint64_t>(value);
  auto exponent = (bits >> 52) & 0x7ff;
  auto fraction = bits & ((std::uint64_t{1} << 52) - 1);
  if (exponent == 0x7ff)
    return std::nullopt;
  if (exponent == 0 && fraction == 0)
    return Rational{};
  Integer n = exponent == 0 ? fraction : ((std::uint64_t{1} << 52) | fraction),
          d = 1;
  int shift = exponent == 0 ? -1074 : static_cast<int>(exponent) - 1023 - 52;
  while (shift < 0 && (n % 2) == 0) {
    n /= 2;
    ++shift;
  }
  // Bounded <=1074 exact multiplies avoid a GCC/Boost variable-shift warning.
  while (shift > 0) {
    n *= 2;
    --shift;
  }
  while (shift < 0) {
    d *= 2;
    ++shift;
  }
  if (bits >> 63)
    n = -n;
  return Rational{n, d};
}
} // namespace rf::qa::planar
