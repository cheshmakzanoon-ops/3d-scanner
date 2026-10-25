#pragma once
// Isolated HOST QA experiment; no production SurfaceMesh/cache/export ABI.
#include <array>
#include <cstddef>
#include <cstdint>
#include <memory>
#include <span>
namespace observed_patch {
struct V {
  double x{}, y{}, z{};
};
struct Camera {
  std::uint32_t id{};
  std::array<double, 9> r{1, 0, 0, 0, 1, 0, 0, 0, 1};
  V t{};
  double fx{1}, fy{1}, cx{}, cy{};
};
struct Source {
  std::uint64_t id{};
  std::uint32_t camera{};
  std::array<V, 3> p{};
};
inline constexpr std::size_t MaxNodes = 32 * 32 * 32, MaxSources = 256,
                             MaxCameras = 8, MaxFaces = 32768;
struct Settings {
  unsigned edge = 32;
  double maxResidual = .02;
  std::size_t faceCap = MaxFaces;
  std::uint64_t workCap = 20000000;
};
enum class Status {
  Success,
  Invalid,
  NoOverlap,
  IncompatiblePair,
  NoSupportedCells,
  AmbiguousZero,
  OutputCap,
  WorkCap,
  Cancelled,
  Allocation,
  Numeric
};
struct Face {
  std::array<V, 3> p{};
  std::array<std::uint64_t, 3> edgeKeys{};
  std::array<std::uint64_t, MaxCameras> sourceIds{};
  std::uint8_t cameraMask{};
  bool derivedInterpolated = true;
};
struct Counters {
  std::uint64_t work{}, pairsVisited{}, pairsNoOverlap{}, cells{}, unknown{},
      extraLayer{}, witnessPartial{}, residual{}, degenerate{}, zeroCells{},
      supportedCells{};
  std::size_t faces{};
};
struct Workspace;
using Cancel = bool (*)(void *);
struct Result {
  Status status = Status::Invalid;
  Counters counts{};
  std::array<std::uint64_t, 2> basis{};
  std::array<std::uint32_t, MaxCameras> cameraIds{};
  std::size_t cameraCount{}, ownedBytes{};
  double maxResidual{};
  V lower{}, upper{};
  std::unique_ptr<Workspace> storage;
  Result();
  ~Result();
  Result(Result &&) noexcept;
  Result &operator=(Result &&) noexcept;
  Result(const Result &) = delete;
  Result &operator=(const Result &) = delete;
  std::span<const Face> faces() const;
};
Result run(std::span<const Camera>, std::span<const Source>,
           const Settings & = Settings{}, Cancel = nullptr,
           void *context = nullptr);
const char *name(Status);
std::size_t workspaceBytes();
} // namespace observed_patch
