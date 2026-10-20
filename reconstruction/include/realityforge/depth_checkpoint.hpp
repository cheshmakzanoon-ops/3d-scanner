#pragma once
#include <cstddef>
#include <string>

namespace rf { struct DenseDepthMap; }

namespace rf::depth_checkpoint {
// Thread-local, scoped per native operation. Never shares mutable state between scans.
// Caller supplies an app-private cache directory and a SHA-256 fingerprint of
// exact rectified pixels, calibrated camera rows, depth bounds and settings.
class Scope {
public:
    Scope(std::string directory, std::string sha256Identity);
    ~Scope() noexcept;
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
    const std::string& directory() const noexcept {return directory_;}
    const std::string& identity() const noexcept {return identity_;}
private:
    const Scope* previous_{};
    std::string directory_, identity_;
    friend bool load(std::size_t, DenseDepthMap&);
    friend bool store(std::size_t, const DenseDepthMap&);
};
// Read-only access for a dependent accepted-surface checkpoint; lifetime remains scoped by caller.
const Scope* current() noexcept;
// Corrupt, wrong-sized and truncated files are cache misses, never accepted geometry.
bool load(std::size_t referenceCamera, DenseDepthMap& map);
// Best effort write; all files are private, bounded, checksummed and atomically published.
bool store(std::size_t referenceCamera, const DenseDepthMap& map);
} // namespace rf::depth_checkpoint
