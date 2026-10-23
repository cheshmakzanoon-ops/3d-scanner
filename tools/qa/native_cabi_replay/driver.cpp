#include "realityforge/core.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
#include <array>
#include <bit>
#include <cstdint>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <vector>
#include <limits>

namespace fs = std::filesystem;

template<class T>
void read(std::istream& in, T* data, std::size_t count) {
    if (!in.read(reinterpret_cast<char*>(data), count * sizeof(T)))
        throw std::runtime_error("truncated input");
}

void response(char* raw, const fs::path& output) {
    std::unique_ptr<char, decltype(&rf_free)> p(raw, rf_free);
    if (!p)
        throw std::runtime_error("null native result");
    std::ofstream out(output);
    out << p.get();
    if (!out)
        throw std::runtime_error("output failure");
}

int main(int argc, char** argv) {
    try {
        if (argc != 4)
            throw std::runtime_error("fixture output repetitions required");
        if (std::endian::native != std::endian::little || sizeof(double) != 8 ||
            !std::numeric_limits<double>::is_iec559)
            throw std::runtime_error("unsupported input architecture");

        std::size_t parsed = 0;
        const std::string repetitionText = argv[3];
        const int repeats = std::stoi(repetitionText, &parsed);
        if (parsed != repetitionText.size())
            throw std::runtime_error("repetitions syntax");
        if (repeats < 1 || repeats > 10)
            throw std::runtime_error("repetitions");

        std::ifstream in(argv[1], std::ios::binary);
        std::array<std::uint32_t, 7> h{};
        read(in, h.data(), h.size());
        if (h[0] != 0x52464331 || h[1] > 2)
            throw std::runtime_error("format/mode");
        const auto mode = h[1], n = h[2], w = h[3], height = h[4], pw = h[5], ph = h[6];
        std::vector<double> cameras, rows;
        std::vector<std::uint8_t> depth, photos;

        if (mode == 0) {
            if (n < 8 || n > 1000 || w || height || pw || ph)
                throw std::runtime_error("sparse shape");
            rows.resize(n * 7);
            read(in, rows.data(), rows.size());
        } else {
            if (n < 2 || n > 8 || w < 32 || w > 640 || height < 32 || height > 640 ||
                std::uint64_t(w) * height > 320000 || pw < 32 || pw > 1024 || ph < 32 || ph > 1024)
                throw std::runtime_error("image shape");
            cameras.resize(n * 16);
            depth.resize(std::size_t(n) * w * height * 4);
            photos.resize(std::size_t(n) * pw * ph * 4);
            read(in, cameras.data(), cameras.size());
            read(in, depth.data(), depth.size());
            read(in, photos.data(), photos.size());
        }
        if (in.peek() != std::char_traits<char>::eof())
            throw std::runtime_error("trailing input");

        fs::path root = argv[2];
        fs::create_directories(root);
        for (int i = 0; i < repeats; ++i) {
            auto dir = root / std::to_string(i);
            if (!fs::create_directory(dir))
                throw std::runtime_error("output must be fresh");
            auto glb = (dir / "mesh.glb").string(),
                 ply = (dir / "mesh.ply").string(),
                 tex = (dir / "texture.glb").string(),
                 png = (dir / "atlas.png").string(),
                 bad = (dir / "missing" / "out.glb").string();

            if (mode == 0) {
                response(rf_reconstruct_json(rows.data(), n, .002, glb.c_str(), ply.c_str()),
                         dir / "success.json");
                response(rf_reconstruct_json(rows.data(), n, .002, bad.c_str(), nullptr),
                         dir / "export-error.json");
                response(rf_reconstruct_json(nullptr, 0, .002, nullptr, nullptr),
                         dir / "invalid.json");
                response(rf_reconstruct_json(rows.data(), n, .002, nullptr, nullptr),
                         dir / "recovery.json");
            } else if (mode == 1) {
                response(rf_surface_json(depth.data(), w, height, cameras.data(), n,
                                         6, 80, 64, 3, glb.c_str(), ply.c_str()),
                         dir / "success.json");
                response(rf_surface_json(depth.data(), w, height, cameras.data(), n,
                                         6, 80, 64, 3, bad.c_str(), nullptr),
                         dir / "export-error.json");
                response(rf_surface_json(nullptr, w, height, cameras.data(), n,
                                         6, 80, 64, 3, nullptr, nullptr),
                         dir / "invalid.json");
                response(rf_surface_json(depth.data(), w, height, cameras.data(), n,
                                         6, 80, 64, 3, nullptr, nullptr),
                         dir / "recovery.json");
            } else {
                response(rf_textured_surface_json(
                             depth.data(), w, height, photos.data(), pw, ph, cameras.data(), n,
                             6, 80, 64, 3, glb.c_str(), ply.c_str(), tex.c_str(), png.c_str()),
                         dir / "success.json");
                response(rf_textured_surface_json(
                             depth.data(), w, height, photos.data(), pw, ph, cameras.data(), n,
                             6, 80, 64, 3, nullptr, nullptr, bad.c_str(), nullptr),
                         dir / "export-error.json");
                response(rf_textured_surface_json(
                             nullptr, w, height, photos.data(), pw, ph, cameras.data(), n,
                             6, 80, 64, 3, nullptr, nullptr, nullptr, nullptr),
                         dir / "invalid.json");
                response(rf_textured_surface_json(
                             depth.data(), w, height, photos.data(), pw, ph, cameras.data(), n,
                             6, 80, 64, 3, nullptr, nullptr, nullptr, nullptr),
                         dir / "recovery.json");
            }
        }
        rf_free(nullptr);
        return 0;
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
