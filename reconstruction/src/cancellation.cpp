#include "realityforge/cancellation.hpp"

namespace rf::cancellation {
namespace {thread_local const std::atomic_bool* flag=nullptr;}
Scope::Scope(const std::atomic_bool* newFlag) noexcept:previous_(flag){flag=newFlag;}
Scope::Scope(std::shared_ptr<const std::atomic_bool> newFlag) noexcept
    :previous_(flag),lease_(std::move(newFlag)){flag=lease_.get();}
Scope::~Scope() noexcept {flag=previous_;}
void checkpoint(){if(flag && flag->load(std::memory_order_relaxed))throw Cancelled();}
}
