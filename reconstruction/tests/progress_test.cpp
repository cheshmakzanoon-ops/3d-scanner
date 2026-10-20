#include "realityforge/progress.hpp"
#include <atomic>
#include <future>
#include <iostream>
#include <stdexcept>
namespace { void check(bool c) { if (!c) throw std::runtime_error("progress regression"); } }
int main() {
    using namespace rf::progress;
    auto outer = std::make_shared<State>();
    {
        Scope scope(outer); begin(Stage::Depth, 2, 3, 10); advance(4);
        auto inner = std::make_shared<State>();
        { Scope nested(inner); begin(Stage::Texture, 4, 5); advance(11); }
        advance(10);
        check(outer->snapshot().completed == 10 && inner->snapshot().completed == 11);
        bool invalid = false; try { advance(9); } catch (const std::invalid_argument&) { invalid = true; }
        check(invalid);
    }
    check(!current);
    std::weak_ptr<State> stale;
    { auto state = std::make_shared<State>(); stale = state; Scope scope(state); state.reset(); check(!stale.expired()); }
    check(stale.expired());
    auto flag = std::make_shared<std::atomic_bool>(false);
    auto token = flag;
    auto state = std::make_shared<State>();
    std::promise<void> started;
    auto future = std::async(std::launch::async, [state, token, &started] {
        rf::cancellation::Scope cancellation(token); Scope scope(state); begin(Stage::Sparse, 0, 0, 10000000);
        started.set_value();
        try { for (std::uint64_t n=1;n<=10000000;++n) advance(n); }
        catch (const rf::cancellation::Cancelled&) { return true; }
        return false;
    });
    started.get_future().wait(); token.reset(); flag->store(true); flag.reset();
    check(future.get());
    check(state->snapshot().completed < 10000000);
    // Independent simultaneous state does not inherit stopped operation.
    auto healthy = std::async(std::launch::async, [] { auto s=std::make_shared<State>(); Scope scope(s); begin(Stage::Bundle,0,0,2); advance(2); return s->snapshot().completed==2; });
    check(healthy.get());
    std::cout << "scoped progress lifetime, bounds, nested and concurrent cases passed\n";
}
