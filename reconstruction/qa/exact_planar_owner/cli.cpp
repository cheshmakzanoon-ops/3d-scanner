// Bounded text transport for differential HOST QA, not a runtime/cache format.
#include "exact_planar_owner.hpp"
#include <bit>
#include <iomanip>
#include <iostream>
#include <string>
using namespace rf::qa::planar;
namespace {
std::string token() {
  std::string s;
  if (!(std::cin >> std::setw(1236) >> s) || s.size() > 1235)
    throw 0;
  return s;
}
Integer integer() {
  auto s = token();
  std::size_t i = s[0] == '-' ? 1 : 0;
  if (i == s.size())
    throw 0;
  Integer n = 0;
  for (; i < s.size(); ++i) {
    if (s[i] < '0' || s[i] > '9')
      throw 0;
    n = n * 10 + (s[i] - '0');
  }
  return s[0] == '-' ? -n : n;
}
std::size_t size() {
  auto n = integer();
  if (n < 0 || n > 67108864)
    throw 0;
  return n.convert_to<std::size_t>();
}
void rational(const Rational &r) { std::cout << r.n << ' ' << r.d << ' '; }
struct Cancellation {
  std::size_t calls = 0, at = 0;
};
bool cancel(void *p) {
  auto &c = *static_cast<Cancellation *>(p);
  return c.at && ++c.calls >= c.at;
}
} // namespace
int main(int argc, char **argv) {
  try {
    if (argc == 2 && std::string_view(argv[1]) == "--decode-binary64") {
      const auto count = size();
      if (count > 1024)
        throw 0;
      for (std::size_t i = 0; i < count; ++i) {
        auto bits = integer();
        if (bits < 0 || bits > (Integer(1) << 64) - 1)
          throw 0;
        auto q = exact_binary64(
            std::bit_cast<double>(bits.convert_to<std::uint64_t>()));
        if (q) {
          rational(*q);
          std::cout << '\n';
        } else
          std::cout << "NONFINITE\n";
      }
      std::cin >> std::ws;
      if (std::cin.peek() != std::char_traits<char>::eof())
        throw 0;
      return 0;
    }
    if (argc != 1)
      throw 0;
    const auto n = size();
    if (n > 32)
      throw 0;
    Limits l;
    l.sources = size();
    l.fragments = size();
    l.faces = size();
    l.bits = size();
    l.steps = size();
    l.polygon_vertices = size();
    l.nodes = size();
    l.workspace_bytes = size();
    Cancellation c;
    c.at = size();
    std::array<Source, 32> sources;
    std::array<std::string, 32> ids;
    for (std::size_t i = 0; i < n; ++i) {
      ids[i] = token();
      sources[i].id = ids[i];
      for (auto &p : sources[i].corners)
        for (auto &r : p) {
          r.n = integer();
          r.d = integer();
        }
    }
    std::cin >> std::ws;
    if (std::cin.peek() != std::char_traits<char>::eof())
      throw 0;
    auto r =
        reconcile(std::span<const Source>(sources.data(), n), l, cancel, &c);
    if (!r.output) {
      std::cout << "UNCHANGED " << static_cast<int>(r.reason) << ' ' << r.steps
                << ' ' << r.discarded_faces << '\n';
      return 0;
    }
    std::cout << "EXACT_QA_ONLY " << r.output->face_count << ' ' << r.steps
              << ' ' << r.discarded_faces << '\n';
    for (std::size_t i = 0; i < r.output->face_count; ++i) {
      const auto &f = r.output->faces[i];
      auto &s = r.output->sources[f.owner];
      std::cout << std::string_view(s.id.data(), s.id_size) << ' ';
      for (const auto &p : f.positions)
        for (const auto &v : p)
          rational(v);
      for (const auto &p : f.barycentrics)
        for (const auto &v : p)
          rational(v);
      std::cout << '\n';
    }
    return 0;
  } catch (...) {
    std::cout << "UNCHANGED 1 0 0\n";
    return 0;
  }
}
