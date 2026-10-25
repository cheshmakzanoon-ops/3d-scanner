#pragma once
// Opt-in HOST QA only. No SurfaceMesh adapter or application dependency.
#include <array>
#include <boost/multiprecision/cpp_int.hpp>
#include <boost/version.hpp>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <optional>
#include <span>
#include <string_view>

namespace rf::qa::planar {
static_assert(BOOST_VERSION == 108600,
              "QA arithmetic reviewed with Boost 1.86.0 only");
// Forbid Boost's fixed-backend Karatsuba path, which allocates variable
// scratch.
static_assert(
    boost::multiprecision::backends::karatsuba_cutoff == 4097,
    "Build through the isolated QA target with bounded schoolbook arithmetic");
using Integer = boost::multiprecision::number<
    boost::multiprecision::cpp_int_backend<
        4096, 4096, boost::multiprecision::signed_magnitude,
        boost::multiprecision::checked, void>,
    boost::multiprecision::et_off>;
struct Rational {
  Integer n = 0, d = 1;
};
using Point = std::array<Rational, 3>;
struct Source {
  std::string_view id;
  std::array<Point, 3> corners;
};
struct RetainedSource {
  std::array<char, 128> id{};
  std::size_t id_size = 0;
  std::array<Point, 3> corners;
};
// All corners are INTERPOLATED from this ONE owner, in its original corner
// order.
struct Face {
  std::size_t owner = 0;
  std::array<Point, 3> positions, barycentrics;
};
struct Output {
  std::array<RetainedSource, 32> sources;
  std::size_t source_count = 0;
  std::array<Face, 2048> faces;
  std::size_t face_count = 0;
};
struct Limits {
  std::size_t sources = 32, fragments = 512, faces = 2048, bits = 4096,
              steps = 200000;
  std::size_t polygon_vertices = 128, nodes = 8192,
              workspace_bytes = 64 * 1024 * 1024;
};
enum class Status { exact_qa_only, unchanged };
enum class Reason {
  none,
  invalid_input,
  noncoplanar,
  orientation,
  arithmetic,
  work_budget,
  geometry_budget,
  allocation_budget,
  cancelled
};
struct Result {
  Status status = Status::unchanged;
  Reason reason = Reason::none;
  std::size_t steps = 0;
  std::size_t discarded_faces =
      0; // QA atomicity evidence only, never replacement geometry.
  std::unique_ptr<Output>
      output; // Never non-null on refusal; caller retains originals.
};
using Cancel = bool (*)(void *);
// Callback exceptions propagate unchanged. Input memory remains caller owned.
Result reconcile(std::span<const Source> sources, Limits limits = {},
                 Cancel cancel = nullptr, void *context = nullptr);
// Exact IEEE754 decoding, no epsilon/decimal conversion. NaN/infinity refuse.
std::optional<Rational> exact_binary64(double value);
} // namespace rf::qa::planar
