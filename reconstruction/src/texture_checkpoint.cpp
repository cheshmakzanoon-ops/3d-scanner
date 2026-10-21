#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/c_abi_support.hpp"
#include <algorithm>
#include <cerrno>
#include <cstring>
#include <filesystem>
#include <string_view>
#include <sys/stat.h>

namespace {
std::string boundedPath(const char* value) {
    if(!value)throw std::invalid_argument("Missing accepted-surface path");
    const auto size=::strnlen(value,1201);
    if(size==0||size>1200)throw std::invalid_argument("Invalid accepted-surface path length");
    return std::string(value,size);
}
void outputPath(const std::string& output,const std::filesystem::path& depthDirectory) {
    const std::filesystem::path path(output);
    const auto filename=path.filename().string();
    if(depthDirectory.parent_path().filename()!=".depth-checkpoints-m18"||
        path.parent_path()!=depthDirectory.parent_path().parent_path()||!path.is_absolute()||
        path.lexically_normal()!=path||filename.empty()||filename.size()>200||!filename.ends_with(".partial")||
        !std::all_of(filename.begin(),filename.end(),[](char c){return (c>='a'&&c<='z')||(c>='A'&&c<='Z')||
            (c>='0'&&c<='9')||c=='.'||c=='-'||c=='_';}))
        throw std::invalid_argument("Texture output must be an owned partial in the private project directory");
    struct stat status{};
    if(::lstat(path.c_str(),&status)==0)throw std::invalid_argument("Texture partial already exists");
    if(errno!=ENOENT)throw std::invalid_argument("Texture output parent is unavailable");
}
std::string quotedError(std::string_view value) {
    std::string out;
    for(const char c:value.substr(0,400)){if(c=='"'||c=='\\')out+='\\';if(static_cast<unsigned char>(c)>=32)out+=c;}
    return out;
}
}

extern "C" char* rf_texture_accepted_surface_json(
    const std::uint8_t* depth_rgba,int width,int height,
    const std::uint8_t* photograph_rgba,int photograph_width,int photograph_height,
    const double* camera_rows,std::size_t camera_count,
    const char* mesh_checkpoint_path,const char* textured_glb_path,const char* texture_png_path,
    const char* depth_directory,const char* depth_identity) noexcept try {
    rf::cancellation::checkpoint();
    if(!depth_rgba||!photograph_rgba||!camera_rows||camera_count<2||camera_count>rf::MAX_DENSE_VIEWS||
        width<32||height<32||width>640||height>640||static_cast<std::int64_t>(width)*height>320000||
        photograph_width<32||photograph_height<32||photograph_width>1024||photograph_height>1024||
        static_cast<std::int64_t>(photograph_width)*photograph_height>1048576)
        throw std::invalid_argument("Invalid bounded texture-only buffers");
    const auto directory=boundedPath(depth_directory),identity=boundedPath(depth_identity);
    const auto meshPath=boundedPath(mesh_checkpoint_path),glbPath=boundedPath(textured_glb_path),pngPath=boundedPath(texture_png_path);
    if(glbPath==pngPath)throw std::invalid_argument("Texture output paths must be distinct");
    rf::depth_checkpoint::Scope depthScope(directory,identity);
    const auto depthBytes=static_cast<std::size_t>(width)*height*4;
    const auto photoBytes=static_cast<std::size_t>(photograph_width)*photograph_height*4;
    std::vector<rf::DenseView> views;views.reserve(camera_count);
    for(std::size_t i=0;i<camera_count;++i) {
        rf::cancellation::checkpoint();
        rf::DenseView view;view.width=width;view.height=height;
        for(std::size_t k=0;k<9;++k)view.pose.rotation[k]=camera_rows[i*16+k];
        view.pose.translation={camera_rows[i*16+9],camera_rows[i*16+10],camera_rows[i*16+11]};
        view.fx=camera_rows[i*16+12];view.fy=camera_rows[i*16+13];view.cx=camera_rows[i*16+14];view.cy=camera_rows[i*16+15];
        view.rgba.assign(depth_rgba+i*depthBytes,depth_rgba+(i+1)*depthBytes);views.push_back(std::move(view));
    }
    rf::surface_checkpoint::AcceptedSurface accepted;
    if(!rf::surface_checkpoint::load(meshPath,views,accepted))
        return rf::abi::error("CHECKPOINT_REJECTED","Accepted surface or matching depth evidence is unavailable");
    // Successful loading validates the complete no-follow private directory before any output.
    outputPath(glbPath,directory);outputPath(pngPath,directory);
    std::vector<rf::TextureSource> sources;sources.reserve(camera_count);
    for(std::size_t i=0;i<camera_count;++i) {
        rf::cancellation::checkpoint();const auto& view=views[i];
        rf::TextureSource source;source.width=photograph_width;source.height=photograph_height;source.pose=view.pose;
        const double sx=static_cast<double>(photograph_width)/width,sy=static_cast<double>(photograph_height)/height;
        source.fx=view.fx*sx;source.fy=view.fy*sy;source.cx=(view.cx+.5)*sx-.5;source.cy=(view.cy+.5)*sy-.5;
        source.rgba.assign(photograph_rgba+i*photoBytes,photograph_rgba+(i+1)*photoBytes);sources.push_back(std::move(source));
    }
    std::string response="{\"schema\":10,\"geometryReused\":true,\"surfaceCheckpointAvailable\":true,\"surfaceCheckpointWritten\":false,\"dense\":"+rf::denseJson(accepted.dense)+
        ",\"mesh\":"+rf::surfaceJson(accepted.mesh);
    try {
        // This entry point intentionally has no depth search, fusion or meshing call.
        const auto baked=rf::bakePhotographicAtlas(accepted.mesh,views,accepted.dense.maps,sources);
        rf::cancellation::checkpoint();rf::writeTexturedGlb(accepted.mesh,baked,glbPath);
        rf::cancellation::checkpoint();rf::writeTexturePng(baked,pngPath);
        rf::cancellation::checkpoint();response+=",\"texture\":"+rf::texturedSurfaceJson(baked);
    } catch(const rf::cancellation::Cancelled&){throw;}
    catch(const std::bad_alloc&){throw;}
    catch(const rf::Error& error){response+=",\"textureError\":\""+quotedError(error.what())+'"';}
    response+='}';
    if(response.size()>32*1024*1024)return rf::abi::error("RESOURCE_EXHAUSTED","Texture-only response exceeds bounded JSON size");
    auto* result=static_cast<char*>(std::malloc(response.size()+1));if(!result)return nullptr;
    std::memcpy(result,response.c_str(),response.size()+1);return result;
} catch(const rf::cancellation::Cancelled&) {
    return rf::abi::error("CANCELLED","Native operation cancelled");
} catch(const std::bad_alloc&) {
    return rf::abi::error("RESOURCE_EXHAUSTED","Native allocation failed");
} catch(const std::invalid_argument& error) {
    return rf::abi::error("INVALID_INPUT",error.what());
} catch(const std::exception&) {
    return rf::abi::error("NATIVE_FAILURE","Accepted-surface texture boundary failed");
} catch(...) {
    return rf::abi::error("UNKNOWN_NATIVE_FAILURE","Unknown native boundary failure");
}
