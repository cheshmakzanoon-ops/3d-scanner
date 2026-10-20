#pragma once
#include <cstddef>
#include <cstdint>
#include <string>

namespace rf { struct DenseDepthMap; }

namespace rf::depth_checkpoint {
// Exact tile geometry/settings are checked in addition to the operation SHA.
struct TileKey {
    std::uint32_t reference{},tile{},fullWidth{},fullHeight{},x{},y{},width{},height{},stride{},solverVersion{};
    std::string solverIdentity; // bounded canonical full settings, never credentials
};
// Thread-local, scoped per native operation. Never shares mutable state between scans.
// Caller supplies an app-private cache directory and a SHA-256 fingerprint of
// exact rectified pixels, calibrated camera rows, depth bounds and settings.
class Scope {
public:
    Scope(std::string directory, std::string sha256Identity, std::size_t byteBudget = 32u*1024u*1024u);
    ~Scope() noexcept;
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
    const std::string& directory() const noexcept {return directory_;}
    const std::string& identity() const noexcept {return identity_;}
private:
    const Scope* previous_{};
    std::string directory_, identity_;
    std::size_t byteBudget_{};
    friend bool load(std::size_t, DenseDepthMap&);
    friend bool store(std::size_t, const DenseDepthMap&);
    friend bool loadTile(const TileKey&, DenseDepthMap&);
    friend bool storeTile(const TileKey&, const DenseDepthMap&);
};
// Corrupt, wrong-sized and truncated files are cache misses, never accepted geometry.
bool load(std::size_t referenceCamera, DenseDepthMap& map);
// Best effort write; all files are private, bounded, checksummed and atomically published.
bool store(std::size_t referenceCamera, const DenseDepthMap& map);
bool loadTile(const TileKey& key, DenseDepthMap& map);
bool storeTile(const TileKey& key, const DenseDepthMap& map);
} // namespace rf::depth_checkpoint
