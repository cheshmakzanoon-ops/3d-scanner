#pragma once
// Shared synthetic fixture and byte helpers for accepted-surface regressions.
#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <bit>
#include <chrono>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <thread>
#include <sys/stat.h>
#if defined(__linux__) || defined(__ANDROID__)
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace {
constexpr char HASH[]="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
inline void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
inline std::vector<std::uint8_t> read(const std::filesystem::path& path) {
    std::ifstream stream(path,std::ios::binary);require(static_cast<bool>(stream),"missing fixture file");
    return {std::istreambuf_iterator<char>(stream),std::istreambuf_iterator<char>()};
}
inline void write(const std::filesystem::path& path,std::span<const std::uint8_t> bytes) {
    std::ofstream stream(path,std::ios::binary|std::ios::trunc);stream.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    require(static_cast<bool>(stream),"fixture write failed");
}
inline void checksum(std::vector<std::uint8_t>& bytes) {
    rf::checkpoint::Sha256 digest;digest.update(std::span(bytes.data(),bytes.size()-32));const auto hash=digest.finish();
    std::copy(hash.begin(),hash.end(),bytes.end()-32);
}
inline void u32(std::vector<std::uint8_t>& bytes,std::size_t offset,std::uint32_t value) {
    for(int i=0;i<4;++i)bytes.at(offset+static_cast<std::size_t>(i))=static_cast<std::uint8_t>(value>>(8*i));
}
inline std::uint32_t u32(std::span<const std::uint8_t> bytes,std::size_t offset) {
    return std::uint32_t(bytes[offset])|(std::uint32_t(bytes[offset+1])<<8)|(std::uint32_t(bytes[offset+2])<<16)|(std::uint32_t(bytes[offset+3])<<24);
}
struct Temp {
    std::filesystem::path project,directory;
    Temp() {
        project=std::filesystem::temp_directory_path()/("rf-surface-checkpoint-"+
            std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        directory=project/".depth-checkpoints-m18"/HASH;std::filesystem::create_directories(directory);
    }
    ~Temp(){std::error_code ec;std::filesystem::remove_all(project,ec);}
};

/** Synthetic photographs of a random-colored plane at z=5; never physical scan evidence. */
inline std::vector<rf::DenseView> photographs() {
    constexpr int width=104,height=76;
    std::mt19937 random(188);std::uniform_int_distribution<int> color(0,255);
    std::vector<std::array<int,3>> source(256*256);for(auto& p:source)p={color(random),color(random),color(random)};
    const auto sample=[&](double x,double y,int channel) {
        x=std::clamp(x*31+128.,0.,254.999);y=std::clamp(y*31+128.,0.,254.999);
        const int ix=static_cast<int>(x),iy=static_cast<int>(y);const auto dx=x-ix,dy=y-iy;
        const auto pixel=[&](int xx,int yy){return source[static_cast<std::size_t>(iy+yy)*256+ix+xx][channel];};
        return (1-dx)*(1-dy)*pixel(0,0)+dx*(1-dy)*pixel(1,0)+(1-dx)*dy*pixel(0,1)+dx*dy*pixel(1,1);
    };
    std::vector<rf::DenseView> out;
    for(int camera=0;camera<3;++camera) {
        rf::DenseView view;view.width=width;view.height=height;view.fx=view.fy=116;
        view.cx=(width-1)/2.;view.cy=(height-1)/2.;view.pose={rf::identity(),{-camera*.55,0,0}};
        view.rgba.resize(width*height*4);
        for(int y=0;y<height;++y)for(int x=0;x<width;++x) {
            const double worldX=(x-view.cx)*5/view.fx+camera*.55,worldY=(y-view.cy)*5/view.fy;
            const auto pixel=static_cast<std::size_t>(y*width+x)*4;
            for(int channel=0;channel<3;++channel)view.rgba[pixel+channel]=static_cast<std::uint8_t>(std::round(sample(worldX,worldY,channel)));
            view.rgba[pixel+3]=255;
        }
        out.push_back(std::move(view));
    }
    return out;
}
struct Packed {
    std::vector<std::uint8_t> rgba;
    std::vector<double> cameras;
    explicit Packed(std::span<const rf::DenseView> views) {
        for(const auto& view:views) {
            rgba.insert(rgba.end(),view.rgba.begin(),view.rgba.end());
            cameras.insert(cameras.end(),view.pose.rotation.begin(),view.pose.rotation.end());
            for(const auto value:{view.pose.translation.x,view.pose.translation.y,view.pose.translation.z,view.fx,view.fy,view.cx,view.cy})cameras.push_back(value);
        }
    }
};
inline std::string text(char* value){require(value!=nullptr,"native result allocation failed");std::string out(value);rf_free(value);return out;}
}
