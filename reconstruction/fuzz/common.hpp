#pragma once
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <string>
#include <unistd.h>
#include <vector>

// Generated synthetic data only. Each read is bounded; counts always describe
// allocated storage because a pointer-only C ABI cannot validate buffer lengths.
struct Input {
    const std::uint8_t* data;
    std::size_t size, at{};
    std::uint8_t byte() { return at < size ? data[at++] : 0; }
    template<class T> T scalar() {
        T value{};
        auto n = std::min(sizeof(T), size - at);
        std::memcpy(&value, data + at, n); at += n;
        return value;
    }
    double number() { return scalar<double>(); }
};
struct Temp {
    std::filesystem::path dir;
    Temp() {
        char name[] = "/tmp/rf-fuzz-XXXXXX";
        auto* made = mkdtemp(name);
        if (!made) std::abort();
        dir = made;
    }
    ~Temp() { std::error_code ec; std::filesystem::remove_all(dir, ec); }
};
inline void require(bool condition) { if (!condition) std::abort(); }
