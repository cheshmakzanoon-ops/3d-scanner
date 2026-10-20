#pragma once
#include "realityforge/cancellation.hpp"
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <optional>
#include <stdexcept>

namespace rf::progress {
// Work is local to the named stage, never a guessed whole-scan percentage.
enum class Stage { Sparse, Bundle, Depth, Consistency, Surface, Texture, Checkpoint };
struct Snapshot {
    Stage stage{Stage::Sparse};
    std::uint64_t reference{}, tile{}, completed{}, sequence{};
    std::optional<std::uint64_t> total;
};
class State final {
public:
    Snapshot snapshot() const { std::lock_guard lock(mutex_); return value_; }
    void begin(Stage stage, std::uint64_t reference, std::uint64_t tile,
               std::optional<std::uint64_t> total = {}) {
        std::lock_guard lock(mutex_);
        value_ = {stage, reference, tile, 0, value_.sequence + 1, total};
    }
    void advance(std::uint64_t completed) {
        std::lock_guard lock(mutex_);
        if (completed < value_.completed || (value_.total && completed > *value_.total))
            throw std::invalid_argument("Progress work count outside declared stage");
        if (completed != value_.completed) { value_.completed = completed; ++value_.sequence; }
    }
private:
    mutable std::mutex mutex_;
    Snapshot value_;
};
// State is leased during execution. Polling is constant memory and contains no
// JNI/UI callback, raw user pointer or event queue that could outlive its owner.
inline thread_local std::shared_ptr<State> current;
class Scope final {
public:
    explicit Scope(std::shared_ptr<State> state) noexcept : previous_(std::move(current)) {
        current = std::move(state);
    }
    ~Scope() noexcept { current = std::move(previous_); }
    Scope(const Scope&) = delete;
    Scope& operator=(const Scope&) = delete;
private:
    std::shared_ptr<State> previous_;
};
inline void begin(Stage stage, std::uint64_t reference = 0, std::uint64_t tile = 0,
                  std::optional<std::uint64_t> total = {}) {
    cancellation::checkpoint();
    if (current) current->begin(stage, reference, tile, total);
}
inline void advance(std::uint64_t completed) {
    cancellation::checkpoint();
    if (current) current->advance(completed);
}
// Call once per bounded block, not per pixel. The latest snapshot is pulled by
// the bridge at its own cadence; producers never post main-thread messages.
inline void checkpoint() { cancellation::checkpoint(); }
}
