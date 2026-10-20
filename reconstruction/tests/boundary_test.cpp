#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
#include "realityforge/multiview.hpp"
#include "realityforge/bundle_adjustment.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
#include <atomic>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <new>
#include <string>

namespace {
std::atomic_bool failAllocation{false};
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
std::string read(const std::filesystem::path& path) {
    std::ifstream input(path, std::ios::binary);
    return {std::istreambuf_iterator<char>(input), std::istreambuf_iterator<char>()};
}
}

// This executable injects a real C++ allocation failure inside each exported ABI,
// including construction of an invalid-input exception, without changing production code.
void* operator new(std::size_t size) {
    if (failAllocation.load()) throw std::bad_alloc();
    if (void* pointer = std::malloc(size ? size : 1)) return pointer;
    throw std::bad_alloc();
}
void* operator new[](std::size_t size) { return ::operator new(size); }
void operator delete(void* pointer) noexcept { std::free(pointer); }
void operator delete[](void* pointer) noexcept { std::free(pointer); }
void operator delete(void* pointer, std::size_t) noexcept { std::free(pointer); }
void operator delete[](void* pointer, std::size_t) noexcept { std::free(pointer); }

int main() {
    using Boundary = char* (*)();
    const Boundary boundaries[] = {
        +[] { return rf_reconstruct_json(nullptr, 0, .002, nullptr, nullptr); },
        +[] { return rf_register_camera_json(nullptr, 0, .002); },
        +[] { return rf_multiview_json(nullptr, 0, nullptr, 0, .002, 1); },
        +[] { return rf_bundle_adjust_json(nullptr, 0, nullptr, 0, nullptr, 0, .002, 1); },
        +[] { return rf_dense_json(nullptr, 0, 0, nullptr, 0, 1, 2, 8, 1); },
        +[] { return rf_surface_json(nullptr, 0, 0, nullptr, 0, 1, 2, 8, 1, nullptr, nullptr); },
        +[] { return rf_textured_surface_json(nullptr, 0, 0, nullptr, 0, 0, nullptr, 0, 1, 2, 8, 1,
                    nullptr, nullptr, nullptr, nullptr); },
    };
    try {
        for (auto boundary : boundaries) {
            failAllocation.store(true);
            char* response = boundary();
            failAllocation.store(false);
            require(response != nullptr, "Error response allocation failed");
            const std::string text(response);
            rf_free(response);
            require(text.find("RESOURCE_EXHAUSTED") != std::string::npos,
                    "C allocation failure did not preserve its typed cause");
            response = boundary();
            require(response != nullptr, "A prior failure poisoned the next ABI call");
            rf_free(response);
        }
        const auto directory = std::filesystem::temp_directory_path() /
            ("rf-boundary-" + std::to_string(reinterpret_cast<std::uintptr_t>(&failAllocation)));
        std::filesystem::create_directory(directory);
        struct RemoveRoot {
            std::filesystem::path path;
            ~RemoveRoot() { std::error_code error; std::filesystem::remove_all(path, error); }
        } cleanup{directory};
        rf::Result result;
        result.points.push_back({{1, 2, 3}, {42, 84, 126}, 0, 0, 0});
        rf::writePointGlb(result, (directory / "dense.glb").string(), rf::PointRepresentation::Dense);
        rf::writePointPly(result, (directory / "dense.ply").string(), rf::PointRepresentation::Dense);
        rf::writeGlb(result, (directory / "sparse.glb").string());
        const auto dense = read(directory / "dense.glb");
        require(dense.find("\"representation\":\"dense_points\"") != std::string::npos,
                "Dense GLB mislabeled as sparse");
        require(dense.find("\"mode\":0") != std::string::npos, "Dense points mislabeled as triangles");
        require(read(directory / "dense.ply").find("dense_points") != std::string::npos,
                "Dense PLY representation missing");
        require(read(directory / "sparse.glb").find("\"representation\":\"sparse_points\"") != std::string::npos,
                "Legacy sparse representation changed");
        bool rejected = false;
        try {
            rf::writePointGlb(result, (directory / "invalid.glb").string(), static_cast<rf::PointRepresentation>(42));
        } catch (const rf::Error&) { rejected = true; }
        require(rejected && !std::filesystem::exists(directory / "invalid.glb"),
                "Invalid representation created an artifact");
        std::cout << "7 allocation-failure boundaries and point representation exports passed\n";
        return 0;
    } catch (const std::exception& failure) {
        failAllocation.store(false);
        std::cerr << failure.what() << '\n';
        return 1;
    }
}
