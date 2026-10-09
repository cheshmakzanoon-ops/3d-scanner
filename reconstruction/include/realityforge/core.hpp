#pragma once
#include <array>
#include <cstddef>
#include <cstdint>
#include <span>
#include <stdexcept>
#include <string>
#include <vector>

namespace rf {
struct Vec2 { double x{}, y{}; };
struct Vec3 { double x{}, y{}, z{}; };
using Mat3 = std::array<double,9>; // row-major, world-to-camera
struct Match { Vec2 a,b; std::array<std::uint8_t,3> color{180,210,220}; };
struct Point { Vec3 xyz; std::array<std::uint8_t,3> color; double error{}, parallax{}; std::size_t matchIndex{}; };
struct Settings {
    double threshold = 0.002; // Sampson distance in normalized camera coordinates
    double minParallaxDegrees = 0.8;
    int iterations = 1200;
    std::uint32_t seed = 20261009;
    std::size_t minInliers = 20;
};
struct Result {
    Mat3 rotation{};
    Vec3 translation{}; // unit baseline, NOT meters
    std::vector<Point> points;
    std::size_t inputMatches{}, epipolarInliers{};
    double medianError{}, medianParallax{};
};
struct Quality {
    double mean{}, standardDeviation{}, laplacianVariance{}, darkFraction{}, brightFraction{};
};
class Error : public std::runtime_error { public: using std::runtime_error::runtime_error; };
Mat3 identity();
Mat3 transpose(const Mat3& a);
Mat3 multiply(const Mat3& a, const Mat3& b);
Vec3 multiply(const Mat3& a, Vec3 b);
double determinant(const Mat3& a);
// Inputs must be calibrated, undistorted normalized coordinates; pixel coordinates are not accepted.
Point triangulate(const Match& match, const Mat3& rotation, Vec3 translation);
Result reconstruct(std::span<const Match> matches, const Settings& settings = {});
Quality quality(std::span<const std::uint8_t> gray, int width, int height, int stride);
std::string resultJson(const Result& result);
void writePly(const Result& result, const std::string& path);
void writeGlb(const Result& result, const std::string& path);
}

// Stable C ABI used by host tests; JNI calls the SAME C++ implementation.
extern "C" {
// rows: [x1,y1,x2,y2,r,g,b]. Returns malloc-owned JSON; always release via rf_free.
char* rf_reconstruct_json(const double* rows, std::size_t count, double threshold,
                          const char* glb_path, const char* ply_path);
void rf_free(void* pointer);
}
