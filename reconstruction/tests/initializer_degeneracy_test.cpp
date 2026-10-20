#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <random>
#include <string>
#include <cstdlib>
#include <new>
#include <filesystem>
#include <chrono>
std::vector<rf::Match> scene(double baseline){std::vector<rf::Match> m;std::mt19937 rng(81);std::uniform_real_distribution<double> u(-1.,1.),z(3.,7.);for(int i=0;i<120;++i){rf::Vec3 p{u(rng),u(rng),z(rng)};m.push_back({{p.x/p.z,p.y/p.z},{(p.x-baseline)/p.z,p.y/p.z}});}return m;}
static int failAfter=-1;
void* operator new(std::size_t size){if(failAfter==0)throw std::bad_alloc();if(failAfter>0)--failAfter;void* p=std::malloc(size?size:1);if(!p)throw std::bad_alloc();return p;}
void operator delete(void* p) noexcept {std::free(p);}
void operator delete(void* p,std::size_t) noexcept {std::free(p);}
int main(int argc,char** argv){
 for(int failure=0;failure<8;++failure){failAfter=failure;char* value=rf_reconstruct_json(nullptr,0,.002,nullptr,nullptr);failAfter=-1;if(value)rf_free(value);}
 auto progressState=std::make_shared<rf::progress::State>();rf::progress::Scope reporting(progressState);
 auto m=scene(1.);auto r=rf::reconstruct(m);auto report=progressState->snapshot();assert(report.stage==rf::progress::Stage::Sparse&&report.tile==0&&report.completed>=81&&!report.total);assert(r.points.size()>=115);assert(r.translation.x<-.999);assert(r.medianError<1e-7);
 bool rotation=false;try{rf::reconstruct(scene(0.));}catch(const rf::InitializationError& e){rotation=e.code==rf::InitializationFailure::RotationOnly;}assert(rotation);
 auto line=m;for(int i=0;i<120;++i){line[i].a.y=.2*line[i].a.x;line[i].b.y=.2*line[i].b.x;}
 bool collinear=false;try{rf::reconstruct(line);}catch(const rf::InitializationError& e){collinear=e.code==rf::InitializationFailure::CollinearFeatures;}assert(collinear);
 bool bounded=false;rf::Settings settings;settings.iterations=100000;auto large=scene(1.);large.resize(1000,large.front());try{rf::reconstruct(large,settings);}catch(const rf::InitializationError& e){bounded=e.code==rf::InitializationFailure::InvalidInput;}assert(bounded);
 std::atomic_bool stop{true};rf::cancellation::Scope scope(&stop);bool cancelled=false;try{rf::reconstruct(m);}catch(const rf::cancellation::Cancelled&){cancelled=true;}assert(cancelled);
 char* error=rf_reconstruct_json(nullptr,0,.002,nullptr,nullptr);assert(error&&std::string(error).find("error")!=std::string::npos);rf_free(error);
 const auto output=argc>1?std::filesystem::path(argv[1]):std::filesystem::temp_directory_path()/("rf-037-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
 std::filesystem::create_directories(output);rf::Result cloud;cloud.points.push_back({{1,2,3},{11,22,33},0,0,0});
 rf::writeGlb(cloud,(output/"typed-sparse.glb").string());rf::writePly(cloud,(output/"typed-sparse.ply").string());
 cloud.representation=rf::PointRepresentation::DensePoints;rf::writeGlb(cloud,(output/"typed-dense.glb").string());rf::writePly(cloud,(output/"typed-dense.ply").string());
 bool cannotFakePose=false;try{rf::resultJson(cloud);}catch(const rf::Error&){cannotFakePose=true;}assert(cannotFakePose);
 if(argc<=1)std::filesystem::remove_all(output);
 std::cout<<"initializer independent scene, rotation, collinearity, work budget, cancellation and ABI passed\n";
}
