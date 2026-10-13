#pragma once
#include "realityforge/core.hpp"
#include <atomic>

namespace rf::cancellation {
// Cancellation is scoped to the currently executing native reconstruction
// thread. There is deliberately NO global "cancel all" flag, to prevent one
// scan from aborting a different scan concurrently using the shared library.
class Cancelled final : public Error {
public:
    Cancelled():Error("Reconstruction cancelled"){}
};
class Scope final {
public:
    explicit Scope(const std::atomic_bool* flag) noexcept;
    ~Scope() noexcept;
    Scope(const Scope&)=delete;
    Scope& operator=(const Scope&)=delete;
private:
    const std::atomic_bool* previous_{};
};
void checkpoint(); // throws Cancelled; does not modify result or exported files
}
