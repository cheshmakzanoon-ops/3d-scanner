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
enum class PointRepresentation { SparsePoints, DensePoints, Sparse=SparsePoints, Dense=DensePoints };
enum class InitializerModel { Unspecified, Essential, Homography };
struct Result {
    Mat3 rotation{};
    Vec3 translation{}; // unit baseline, NOT meters
    std::vector<Point> points;
    // inputMatches is raw admitted row count. epipolarInliers is distinct-pair
    // model support (legacy member name), not statistically independent support.
    // Published point.matchIndex always addresses the first original input row.
    std::size_t inputMatches{}, epipolarInliers{};
    double medianError{}, medianParallax{};
    PointRepresentation representation{PointRepresentation::SparsePoints};
    InitializerModel initializerModel{InitializerModel::Unspecified};
};
struct Quality {
    double mean{}, standardDeviation{}, laplacianVariance{}, darkFraction{}, brightFraction{};
};
class Error : public std::runtime_error { public: using std::runtime_error::runtime_error; };
enum class InitializationFailure { InvalidInput, CollinearFeatures, PlanarAmbiguity, RotationOnly, InsufficientConsensus, WeakBaseline };
class InitializationError : public Error {
public:
    InitializationError(InitializationFailure value,const char* message):Error(message),code(value){}
    const InitializationFailure code;
};
Mat3 identity();
Mat3 transpose(const Mat3& a);
Mat3 multiply(const Mat3& a, const Mat3& b);
Vec3 multiply(const Mat3& a, Vec3 b);
double determinant(const Mat3& a);
// Inputs must be calibrated, undistorted normalized coordinates; pixel coordinates are not accepted.
Point triangulate(const Match& match, const Mat3& rotation, Vec3 translation);
Result reconstruct(std::span<const Match> matches, const Settings& settings = {});
// Additive calibrated initializer. Rays and observed pixels must share exact row order.
// Observed pixels refer to the real working image, BEFORE Brown-Conrady undistortion.
struct CalibratedImageDomain {
    double fx{}, fy{}, cx{}, cy{}, k1{}, k2{}, p1{}, p2{}, k3{};
    int width{}, height{};
};
struct ObservedMatch { Vec2 a, b; };
Result reconstructCalibrated(std::span<const Match> matches,
    std::span<const ObservedMatch> observed,
    const std::array<CalibratedImageDomain,2>& domains, const Settings& settings = {});

Quality quality(std::span<const std::uint8_t> gray, int width, int height, int stride);
std::string resultJson(const Result& result);
void writePointPly(const Result& result, const std::string& path, PointRepresentation representation);
void writePointGlb(const Result& result, const std::string& path, PointRepresentation representation);
void writePly(const Result& result, const std::string& path);
void writeGlb(const Result& result, const std::string& path);
}

// Stable C ABI used by host tests; JNI calls the SAME C++ implementation.
extern "C" {
// rows: [x1,y1,x2,y2,r,g,b]. Returns malloc-owned JSON; always release via rf_free.
char* rf_reconstruct_json(const double* rows, std::size_t count, double threshold,
                          const char* glb_path, const char* ply_path) noexcept;
// Domain layout per camera: fx,fy,cx,cy,k1,k2,p1,p2,k3,width,height (22values total).
// observed: x1,y1,x2,y2 per original match (count*4values), real/distorted pixels.
char* rf_reconstruct_calibrated_json(const double* rows, std::size_t count,
    const double* observed, std::size_t observed_values,
    const double* domains, std::size_t domain_values, double threshold) noexcept;
void rf_free(void* pointer) noexcept;
}
