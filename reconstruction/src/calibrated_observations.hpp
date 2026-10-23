#pragma once
#include "realityforge/core.hpp"
#include <algorithm>
namespace rf::detail {
// Called only after validation. Grid cells use observed (possibly distorted) pixels,
// never reprojected undistorted rays. Kept separate for direct domain regression tests.
inline std::size_t occupiedObservedCells(std::span<const ObservedMatch> observed,
                                         std::span<const std::size_t> ids,
                                         const CalibratedImageDomain &domain, bool second) {
    bool cells[16]{};
    for (auto i : ids) {
        const auto p = second ? observed[i].b : observed[i].a;
        if (p.x >= 0 && p.y >= 0 && p.x < domain.width && p.y < domain.height)
            cells[std::min(3, int(p.y / domain.height * 4)) * 4 +
                  std::min(3, int(p.x / domain.width * 4))] = true;
    }
    return static_cast<std::size_t>(std::count(cells, cells + 16, true));
}
} // namespace rf::detail
