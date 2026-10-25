#include "patch.hpp"
#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <iomanip>
#include <iostream>
#include <limits>
#include <map>
#include <new>
#include <set>
#include <sstream>
#include <vector>
namespace allocation_control {
bool failNext = false;
std::size_t calls = 0;
} // namespace allocation_control
[[gnu::noinline]] void *operator new(std::size_t n) {
  if (allocation_control::failNext) {
    allocation_control::failNext = false;
    throw std::bad_alloc();
  }
  ++allocation_control::calls;
  if (void *p = std::malloc(n))
    return p;
  throw std::bad_alloc();
}
[[gnu::noinline]] void operator delete(void *p) noexcept { std::free(p); }
[[gnu::noinline]] void operator delete(void *p, std::size_t) noexcept {
  std::free(p);
}
using namespace observed_patch;
namespace {
void require(bool yes, const char *why) {
  if (!yes) {
    std::cerr << "FAIL: " << why << '\n';
    std::exit(1);
  }
}
V sub(V a, V b) { return {a.x - b.x, a.y - b.y, a.z - b.z}; }
V cross(V a, V b) {
  return {a.y * b.z - a.z * b.y, a.z * b.x - a.x * b.z, a.x * b.y - a.y * b.x};
}
double norm(V p) { return std::sqrt(p.x * p.x + p.y * p.y + p.z * p.z); }
Source triangle(std::uint64_t id, std::uint32_t cam, double z = 2,
                double sx = 0, double sy = 0) {
  Source s{id, cam, {{{-1, -1, 0}, {1, -1, 0}, {0, 1, 0}}}};
  for (auto &p : s.p)
    p.z = z + sx * p.x + sy * p.y;
  return s;
}
std::array<Camera, 2> cameras() {
  return {Camera{10}, Camera{20, {1, 0, 0, 0, 1, 0, 0, 0, 1}, {-.2, 0, 0}}};
}
double dot(V a, V b) { return a.x * b.x + a.y * b.y + a.z * b.z; }
V add(V a, V b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
V mul(V a, double t) { return {a.x * t, a.y * t, a.z * t}; }
double distanceToFace(V p, const Face &f) {
  const auto a = f.p[0], e = sub(f.p[1], a), g = sub(f.p[2], a), h = sub(p, a),
             n = cross(e, g);
  double nn = dot(n, n);
  if (nn <= 0)
    return INFINITY;
  const auto projected = sub(p, mul(n, dot(h, n) / nn));
  auto v = sub(projected, a);
  double ee = dot(e, e), gg = dot(g, g), eg = dot(e, g), ev = dot(e, v),
         gv = dot(g, v), den = ee * gg - eg * eg;
  if (den > 0) {
    double u = (ev * gg - gv * eg) / den, w = (gv * ee - ev * eg) / den;
    if (u >= 0 && w >= 0 && u + w <= 1)
      return norm(sub(p, projected));
  }
  double best = INFINITY;
  for (unsigned k = 0; k < 3; ++k) {
    auto start = f.p[k], edge = sub(f.p[(k + 1) % 3], start);
    double t = std::clamp(dot(sub(p, start), edge) / dot(edge, edge), 0., 1.);
    best = std::min(best, norm(sub(p, add(start, mul(edge, t)))));
  }
  return best;
}
std::string number(double x) {
  if (!std::isfinite(x))
    return "null";
  std::ostringstream out;
  out << std::setprecision(17) << x;
  return out.str();
}
void report(const char *label, const Result &r, double truthZ = 2,
            double sx = 0, double sy = 0, double ox = 0, double oy = 0,
            double rotation = 0) {
  const auto rotate = [&](V p, double angle) {
    const auto c = std::cos(angle), s = std::sin(angle);
    return V{c * p.x + s * p.z, p.y, -s * p.x + c * p.z};
  };
  double area = 0, maxError = 0, maxAngle = 0;
  std::vector<double> errors;
  std::map<std::uint64_t, V> vertices;
  std::map<std::array<std::uint64_t, 2>, unsigned> edges;
  std::set<std::array<std::uint64_t, 3>> uniqueFaces;
  for (const auto &f : r.faces()) {
    require(f.derivedInterpolated, "derived label");
    auto faceKey = f.edgeKeys;
    std::sort(faceKey.begin(), faceKey.end());
    require(uniqueFaces.insert(faceKey).second, "duplicate derived face");
    for (unsigned k = 0; k < 3; ++k) {
      std::array<std::uint64_t, 2> edge{f.edgeKeys[k], f.edgeKeys[(k + 1) % 3]};
      std::sort(edge.begin(), edge.end());
      require(++edges[edge] <= 2, "nonmanifold derived edge");
    }
    require(f.cameraMask >= 3, "two camera evidence");
    auto n = cross(sub(f.p[1], f.p[0]), sub(f.p[2], f.p[0]));
    area += norm(n) * .5;
    const V truthN = rotate(V{-sx, -sy, 1}, rotation);
    const double cosine =
        std::abs(n.x * truthN.x + n.y * truthN.y + n.z * truthN.z) /
        (norm(n) * norm(truthN));
    maxAngle = std::max(maxAngle, std::acos(std::clamp(cosine, -1., 1.)) * 180 /
                                      3.141592653589793);
    for (unsigned i = 0; i < 3; ++i) {
      auto p = f.p[i];
      auto original = rotate(p, -rotation);
      double e =
          std::abs(original.z - truthZ - sx * original.x - sy * original.y) /
          norm(truthN);
      errors.push_back(e);
      maxError = std::max(maxError, e);
      auto [it, inserted] = vertices.emplace(f.edgeKeys[i], p);
      if (!inserted)
        require(it->second.x == p.x && it->second.y == p.y &&
                    it->second.z == p.z,
                "canonical edge position mismatch");
    }
  }
  std::size_t boundary = 0;
  for (auto &[edge, count] : edges)
    if (count == 1)
      ++boundary;
  std::size_t truthSamples = 0, truthCovered = 0;
  double truthMax = 0;
  for (unsigned y = 0; y <= 20; ++y)
    for (unsigned x = 0; x + y <= 20; ++x) {
      double u = x / 20., v = y / 20.;
      V p{ox - 1 + 2 * u + v, oy - 1 + 2 * v, 0};
      p.z = truthZ + sx * p.x + sy * p.y;
      p = rotate(p, rotation);
      double nearest = INFINITY;
      for (const auto &f : r.faces())
        nearest = std::min(nearest, distanceToFace(p, f));
      ++truthSamples;
      if (nearest <= .01)
        ++truthCovered;
      truthMax = std::max(truthMax, nearest);
    }
  std::sort(errors.begin(), errors.end());
  double p95 =
      errors.empty()
          ? 0
          : errors[static_cast<std::size_t>(.95 * (errors.size() - 1))];
  std::cout << "{\"case\":\"" << label << "\",\"status\":\"" << name(r.status)
            << "\",\"faces\":" << r.faces().size()
            << ",\"attemptedFaces\":" << r.counts.faces << ",\"area\":" << area
            << ",\"fullSourceArea\":" << 2 * std::sqrt(1 + sx * sx + sy * sy)
            << ",\"boundaryEdges\":" << boundary
            << ",\"truthSamples\":" << truthSamples
            << ",\"truthCoveredWithin0_01\":" << truthCovered
            << ",\"maxTruthToGenerated\":" << number(truthMax)
            << ",\"maxTruthError\":"
            << (errors.empty() ? "null" : number(maxError))
            << ",\"p95TruthError\":" << (errors.empty() ? "null" : number(p95))
            << ",\"maxNormalDegrees\":"
            << (errors.empty() ? "null" : number(maxAngle))
            << ",\"ownedBytes\":" << r.ownedBytes
            << ",\"work\":" << r.counts.work
            << ",\"unknownCells\":" << r.counts.unknown
            << ",\"partialCells\":" << r.counts.witnessPartial
            << ",\"layerCells\":" << r.counts.extraLayer
            << ",\"residualCells\":" << r.counts.residual
            << ",\"zeroCells\":" << r.counts.zeroCells
            << ",\"maxResidual\":" << r.maxResidual << "}\n";
}
void noOutput(const Result &r) {
  require(r.status != Status::Success && r.faces().empty() && !r.storage,
          "refusal must expose no partial output");
}
bool cancelled(void *data) {
  auto &calls = *static_cast<unsigned *>(data);
  return ++calls >= 2;
}
} // namespace
int main() {
  std::cout << std::setprecision(17);
  auto cs = cameras();
  std::array<Source, 2> ss{triangle(100, 10), triangle(200, 20)};
  Settings config;
  const auto before = allocation_control::calls;
  auto flat = run(cs, ss, config);
  require(allocation_control::calls - before == 1, "single kernel allocation");
  report("flat", flat);
  require(flat.status == Status::Success, "flat positive");
  require(flat.ownedBytes <= 8 * 1024 * 1024, "memory cap");
  for (auto &f : flat.faces())
    for (auto p : f.p)
      require(std::abs(p.z - 2) < 1e-12, "flat truth");
  std::reverse(cs.begin(), cs.end());
  std::reverse(ss.begin(), ss.end());
  auto perm = run(cs, ss, config);
  report("permutation", perm);
  require(perm.status == flat.status &&
              perm.faces().size() == flat.faces().size() &&
              perm.basis == flat.basis,
          "permutation count/basis");
  for (std::size_t i = 0; i < flat.faces().size(); ++i) {
    require(flat.faces()[i].edgeKeys == perm.faces()[i].edgeKeys &&
                flat.faces()[i].sourceIds == perm.faces()[i].sourceIds,
            "permutation provenance");
    for (unsigned k = 0; k < 3; ++k)
      require(norm(sub(flat.faces()[i].p[k], perm.faces()[i].p[k])) == 0,
              "permutation geometry");
  }
  cs = cameras();
  ss = {triangle(100, 10, 2, .15, .1), triangle(200, 20, 2, .15, .1)};
  auto slopeConfig = config;
  slopeConfig.maxResidual = .08;
  auto slope = run(cs, ss, slopeConfig);
  report("slanted", slope, 2, .15, .1);
  require(slope.status == Status::Success, "slanted positive");
  for (auto &f : slope.faces())
    for (auto p : f.p)
      require(std::abs(p.z - 2 - .15 * p.x - .1 * p.y) < .002, "slanted truth");
  for (double gap : {.01, .02, .020001, .04}) {
    ss = {triangle(100, 10), triangle(200, 20, 2 + gap)};
    auto r = run(cs, ss, config);
    report(gap < .02    ? "separated_below_bound"
           : gap == .02 ? "separated_at_bound"
           : gap < .04  ? "separated_above_bound"
                        : "separated_far",
           r);
    if (gap >= .02) {
      noOutput(r);
      require(r.status == Status::IncompatiblePair, "separated refusal");
    }
  }
  ss = {triangle(100, 10, 1.995), triangle(200, 20, 2.005)};
  auto symmetric = run(cs, ss, config);
  report("symmetric_bias_unresolved", symmetric);
  require(symmetric.status == Status::Success, "below-bound sensitivity");
  ss = {triangle(100, 10, 2.01), triangle(200, 20, 2.01)};
  auto common = run(cs, ss, config);
  report("common_mode_bias_undetectable", common);
  require(common.status == Status::Success, "common-mode limitation retained");
  for (auto &f : common.faces())
    for (auto p : f.p)
      require(std::abs(p.z - 2) >= .0099,
              "do not conceal common-mode truth error");
  cs[1].t.z = .04;
  ss = {
      triangle(100, 10),
      triangle(200, 20)}; // Poses with already-world triangles: changing both
                          // transform and ray does not imply changed geometry.
  auto pose = run(cs, ss, config);
  report("pose_transform_same_world_geometry", pose);
  require(pose.status == Status::Success,
          "pose transform consistent world source");
  // Explicit pose-induced geometry bias is represented in reconstructed world
  // points.
  cs = cameras();
  ss = {triangle(100, 10), triangle(200, 20, 2.04)};
  auto bias = run(cs, ss, config);
  report("pose_induced_geometry_bias", bias);
  noOutput(bias);
  std::array<Source, 3> layers{triangle(100, 10), triangle(200, 20),
                               triangle(300, 10, 1.5)};
  auto occluded = run(cs, layers, config);
  report("foreground_extra_layer", occluded);
  noOutput(occluded);
  require(occluded.counts.extraLayer > 0, "extra layer diagnosed");
  std::array<Camera, 3> three{cs[0], cs[1], Camera{30}};
  std::array<Source, 3> witness{triangle(100, 10), triangle(200, 20),
                                triangle(300, 30, 1.5)};
  auto contradiction = run(three, witness, config);
  report("third_view_foreground", contradiction);
  noOutput(contradiction);
  auto opposing = cameras();
  opposing[1].r = {-1, 0, 0, 0, 1, 0, 0, 0, -1};
  opposing[1].t = {0, 0, 4};
  ss = {triangle(100, 10), triangle(200, 20, 2.01)};
  auto thin = run(opposing, ss, config);
  report("thin_opposing", thin);
  noOutput(thin);
  ss = {triangle(100, 10), triangle(200, 20)};
  for (auto &p : ss[1].p)
    p.x += 4;
  auto disjoint = run(cs, ss, config);
  report("disjoint", disjoint);
  noOutput(disjoint);
  ss = {triangle(100, 10), triangle(200, 20)};
  for (auto &p : ss[1].p)
    p.x += 2;
  auto touch = run(cs, ss, config);
  report("edge_touch", touch);
  noOutput(touch);
  ss = {triangle(100, 10), triangle(200, 20)};
  for (auto &p : ss[1].p)
    p.x += .4;
  auto partial = run(cs, ss, config);
  report("partial_overlap", partial);
  require(partial.status == Status::Success &&
              partial.faces().size() < flat.faces().size(),
          "partial support accounting");
  ss = {triangle(100, 10), triangle(200, 20)};
  auto odd = config;
  odd.edge = 31;
  auto exact = run(cs, ss, odd);
  report("exact_zero", exact);
  noOutput(exact);
  require(exact.counts.zeroCells > 0, "zero no jitter");
  auto cap = config;
  cap.faceCap = 1;
  auto overflow = run(cs, ss, cap);
  report("output_cap", overflow);
  noOutput(overflow);
  require(overflow.status == Status::OutputCap, "output cap status");
  cap = config;
  cap.workCap = 100;
  auto work = run(cs, ss, cap);
  report("work_cap", work);
  noOutput(work);
  require(work.status == Status::WorkCap, "work cap status");
  unsigned calls = 0;
  auto cancel = run(cs, ss, config, cancelled, &calls);
  report("cancel", cancel);
  noOutput(cancel);
  require(cancel.status == Status::Cancelled, "cancel status");
  ss[0].p[0].x = INFINITY;
  auto invalid = run(cs, ss, config);
  report("nonfinite", invalid);
  noOutput(invalid);
  require(invalid.status == Status::Invalid, "nonfinite status");
  cs = cameras();
  ss = {triangle(100, 10), triangle(200, 20)};
  for (auto &s : ss)
    for (auto &p : s.p) {
      p.x += 10;
      p.y -= 3;
      p.z += 5;
    }
  for (auto &c : cs) {
    c.t.x -= 10;
    c.t.y += 3;
    c.t.z -= 5;
  }
  auto translated = run(cs, ss, config);
  report("rigid_translation", translated, 7, 0, 0, 10, -3);
  require(translated.status == Status::Success, "gauge translation");
  for (auto &f : translated.faces())
    for (auto p : f.p)
      require(std::abs(p.z - 7) < 1e-12, "translated truth");
  cs = cameras();
  ss = {triangle(100, 10), triangle(200, 20)};
  allocation_control::failNext = true;
  auto oom = run(cs, ss, config);
  report("allocation_failure", oom);
  noOutput(oom);
  require(oom.status == Status::Allocation, "allocation refusal");
  // Camera-plane degeneracy is invalid before any division or extraction.
  ss[0].p = {V{-1, -1, 0}, V{1, -1, 0}, V{0, 1, 0}};
  auto horizon = run(cs, ss, config);
  report("camera_plane", horizon);
  noOutput(horizon);
  // Hole is represented by absence of source triangles, not a depth zero
  // surface.
  std::array<Source, 4> hole{
      Source{100, 10, {V{-1, -1, 2}, V{1, -1, 2}, V{0, -.3, 2}}},
      Source{200, 20, {V{-1, -1, 2}, V{1, -1, 2}, V{0, -.3, 2}}},
      Source{300, 10, {V{-1, 1, 2}, V{0, .3, 2}, V{1, 1, 2}}},
      Source{400, 20, {V{-1, 1, 2}, V{0, .3, 2}, V{1, 1, 2}}}};
  auto holeResult = run(cs, hole, config);
  report("missing_middle", holeResult);
  require(holeResult.status == Status::Success, "one supported side of hole");
  for (auto &f : holeResult.faces())
    for (auto p : f.p)
      require(p.y < -.3, "hole not bridged");
  // Rigid gauge rotation: world x'=z,z'=-x, cameras transformed inversely.
  ss = {triangle(100, 10), triangle(200, 20)};
  for (auto &s : ss)
    for (auto &p : s.p)
      p = {p.z, p.y, -p.x};
  for (auto &c : cs)
    c.r = {0, 0, -1, 0, 1, 0, 1, 0, 0};
  auto rotated = run(cs, ss, config);
  require(rotated.status == Status::Success, "gauge rotation");
  for (auto &f : rotated.faces())
    for (auto p : f.p)
      require(std::abs(p.x - 2) < 1e-12, "rotated truth");
  cs = cameras();
  ss = {triangle(100, 10), triangle(200, 20)};
  const double angle = .37, c = std::cos(angle), s = std::sin(angle);
  for (auto &source : ss)
    for (auto &p : source.p)
      p = {c * p.x + s * p.z, p.y, -s * p.x + c * p.z};
  for (auto &camera : cs)
    camera.r = {c, 0, -s, 0, 1, 0, s, 0, c};
  auto arbitraryGauge = run(cs, ss, slopeConfig);
  report("nonaxis_rigid_gauge", arbitraryGauge, 2, 0, 0, 0, 0, angle);
  require(arbitraryGauge.status == Status::Success, "nonaxis gauge");
  for (auto &f : arbitraryGauge.faces())
    for (auto p : f.p)
      require(std::abs(s * p.x + c * p.z - 2) < .002, "nonaxis truth");
  std::cout << "{\"syntheticControls\":\"PASS_IMPLEMENTATION_EXPECTATIONS\","
               "\"safeSurfaceDisambiguation\":\"FAIL_SUB_BOUND_DISTINCT_"
               "LAYERS\",\"commonModeBias\":\"UNDETECTABLE\","
               "\"physicalQuality\":\"NOT_ESTABLISHED\"}\n";
}
