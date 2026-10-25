#include "exact_planar_owner.hpp"
#include <algorithm>
#include <bit>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
using namespace rf::qa::planar;
namespace {
void require(bool v) {
  if (!v)
    throw std::runtime_error("QA assertion");
}
Rational r(int n) { return {n, 1}; }
Source triangle() {
  return {"a",
          {{Point{r(0), r(0), r(5)}, Point{r(1), r(0), r(5)},
            Point{r(0), r(1), r(5)}}}};
}
bool throwing(void *) { throw std::overflow_error("callback, not arithmetic"); }
bool cancelled(void *) { return true; }
} // namespace
int main() {
  auto source = triangle();
  auto out = reconcile(std::span<const Source>(&source, 1));
  require(out.status == Status::exact_qa_only && out.output &&
          out.output->face_count == 3);
  require(out.output->sources[0].corners[0][2].n == 5);
  auto stopped = reconcile(std::span<const Source>(&source, 1), {}, cancelled);
  require(!stopped.output && stopped.reason == Reason::cancelled &&
          stopped.steps == 0);
  bool propagated = false;
  try {
    auto unused = reconcile(std::span<const Source>(&source, 1), {}, throwing);
    (void)unused;
  } catch (const std::overflow_error &) {
    propagated = true;
  }
  require(propagated);
  source.corners[0][0].d = 0;
  require(!reconcile(std::span<const Source>(&source, 1)).output);
  source = triangle();
  source.id = "";
  require(!reconcile(std::span<const Source>(&source, 1)).output);
  require(!exact_binary64(std::numeric_limits<double>::infinity()));
  require(!exact_binary64(std::numeric_limits<double>::quiet_NaN()));
  for (double value : {0., -0., 0.01, -0.01, 5., std::nextafter(5., 6.),
                       std::numeric_limits<double>::denorm_min(),
                       std::numeric_limits<double>::max()}) {
    auto q = exact_binary64(value);
    require(q.has_value());
    if (value == 0.)
      require(q->n == 0 && q->d == 1);
  }
  // Exercise clipping, global noding, fans and winding in the native sanitizer
  // process.
  std::array<Source, 4> patch;
  const std::array<std::string_view, 4> ids{"a0", "a1", "b0", "b1"};
  for (std::size_t i = 0; i < 4; ++i) {
    const Rational shift = i < 2 ? Rational{0, 1} : Rational{1, 3};
    const Rational right{shift.n + shift.d, shift.d};
    const Point a{shift, r(0), r(5)}, b{right, r(0), r(5)};
    const Point c{right, r(1), r(5)}, d{shift, r(1), r(5)};
    patch[i] = Source{ids[i], i % 2 == 0 ? std::array<Point, 3>{a, b, c}
                                         : std::array<Point, 3>{a, c, d}};
  }
  auto overlap = reconcile(patch);
  require(overlap.output && overlap.output->face_count > 6);
  const auto count = overlap.output->face_count;
  std::reverse(patch.begin(), patch.end());
  auto reordered = reconcile(patch);
  require(reordered.output && reordered.output->face_count == count);
  Limits limited;
  limited.faces = 1;
  auto refused = reconcile(patch, limited);
  require(!refused.output && refused.discarded_faces == 1 &&
          refused.reason == Reason::geometry_budget);
  limited = Limits{};
  limited.workspace_bytes = 1;
  require(reconcile(patch, limited).reason == Reason::allocation_budget);
  // Real checked overflow after six earlier faces have already been built.
  std::array<Source, 3> late{patch[3], patch[2], triangle()};
  late[2].id = "z";
  const Integer denominator = Integer(1) << 1023;
  late[2].corners = {
      Point{r(2), r(0), r(5)},
      Point{Rational{2 * denominator + 1, denominator}, r(0), r(5)},
      Point{r(2), Rational{1, denominator}, r(5)}};
  auto overflow = reconcile(late);
  require(!overflow.output && overflow.reason == Reason::arithmetic &&
          overflow.discarded_faces == 6);
  auto tiny = exact_binary64(std::numeric_limits<double>::denorm_min());
  require(tiny->n == 1 && tiny->d == (Integer(1) << 1074));
  auto decimal = exact_binary64(0.01);
  require(decimal->n == Integer("5764607523034235") &&
          decimal->d == (Integer(1) << 59));
  auto maximal = exact_binary64(std::numeric_limits<double>::max());
  require(maximal->n == ((Integer(1) << 53) - 1) * (Integer(1) << 971) &&
          maximal->d == 1);
  std::cout
      << "Exact QA arithmetic/atomicity/binary64 tests passed; Output bytes="
      << sizeof(Output) << '\n';
}
