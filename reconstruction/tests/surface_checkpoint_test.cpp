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
#if defined(__linux__) || defined(__ANDROID__)
#include <cerrno>
#include <fcntl.h>
#include <unistd.h>
#endif

namespace {
constexpr char HASH[]="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
std::vector<std::uint8_t> read(const std::filesystem::path& path) {
    std::ifstream stream(path,std::ios::binary);require(static_cast<bool>(stream),"missing fixture file");
    return {std::istreambuf_iterator<char>(stream),std::istreambuf_iterator<char>()};
}
void write(const std::filesystem::path& path,std::span<const std::uint8_t> bytes) {
    std::ofstream stream(path,std::ios::binary|std::ios::trunc);stream.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    require(static_cast<bool>(stream),"fixture write failed");
}
void checksum(std::vector<std::uint8_t>& bytes) {
    rf::checkpoint::Sha256 digest;digest.update(std::span(bytes.data(),bytes.size()-32));const auto hash=digest.finish();
    std::copy(hash.begin(),hash.end(),bytes.end()-32);
}
void u32(std::vector<std::uint8_t>& bytes,std::size_t offset,std::uint32_t value) {
    for(int i=0;i<4;++i)bytes.at(offset+static_cast<std::size_t>(i))=static_cast<std::uint8_t>(value>>(8*i));
}
std::uint32_t u32(std::span<const std::uint8_t> bytes,std::size_t offset) {
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
std::vector<rf::DenseView> photographs() {
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
std::string text(char* value){require(value!=nullptr,"native result allocation failed");std::string out(value);rf_free(value);return out;}
}

int main() {
    int passed=0;
    const auto test=[&](const char* name,auto&& body){body();++passed;std::cout<<"PASS "<<name<<'\n';};
    test("checkpoint SHA-256 matches published digest vectors and streaming boundaries",[] {
        rf::checkpoint::Sha256 empty;
        require(rf::checkpoint::hex(empty.finish())=="e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855","empty SHA-256 differs");
        rf::checkpoint::Sha256 abc;abc.update("a");abc.update("bc");
        require(rf::checkpoint::hex(abc.finish())=="ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad","abc SHA-256 differs");
        rf::checkpoint::Sha256 million;const std::array<std::uint8_t,1000> block=[] {std::array<std::uint8_t,1000> out{};out.fill('a');return out;}();
        for(int i=0;i<1000;++i)million.update(block);
        require(rf::checkpoint::hex(million.finish())=="cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0","million-byte SHA-256 differs");
    });
    Temp temporary;const auto views=photographs();const Packed packed(views);
    const auto checkpoint=temporary.directory/rf::surface_checkpoint::FILENAME;
    rf::DenseSettings denseSettings;denseSettings.nearDepth=3.2;denseSettings.farDepth=7.2;denseSettings.depthPlanes=56;denseSettings.pixelStride=3;
    rf::SurfaceSettings meshSettings;meshSettings.pixelStride=3;meshSettings.maxVoxelOccupancy=32;meshSettings.maxFusionCandidates=256;
    rf::DenseResult dense;rf::SurfaceMesh mesh;
    {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);
        dense=rf::reconstructDense(views,denseSettings);mesh=rf::fuseDepthSurfaces(views,dense,meshSettings);
    }
    require(dense.points.size()>60&&mesh.triangles.size()>100,"synthetic fixture has insufficient real observed geometry");
    // Explicit synthetic planar-normal evidence exercises nonzero v2 fields.
    dense.verifiedSamples.front().worldNormal={0,0,-1};
    dense.verifiedSamples.front().normalConfidence=.75f;
    dense.verifiedSamples.front().normalSupportingViews=2;
    test("store diagnostics are bounded and reset for each call",[&] {
        using namespace rf::surface_checkpoint;
        StoreDiagnostic diagnostic{StoreStage::Write,123,456};bool created=true;
        require(!store(views,dense,mesh,meshSettings,&created,&diagnostic)&&!created,
                "unscoped checkpoint unexpectedly succeeded");
        require(diagnosticJson(diagnostic)=="{\"stage\":\"no_scope\",\"errno\":0,\"component\":-1}",
                "unscoped diagnostic leaked stale evidence");
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);
        auto incomplete=dense;incomplete.writtenDepthMaps=0;incomplete.resumedDepthMaps=0;
        require(!store(views,incomplete,mesh,meshSettings,&created,&diagnostic)&&!created,
                "incomplete depth publication accepted");
        require(diagnostic.stage==StoreStage::DepthMaps&&diagnostic.errorNumber==0&&diagnostic.component==-1,
                "depth publication diagnostic incorrect");
    });
    test("accepted full mesh and actual dense evidence round-trip bit-for-bit",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);
        rf::surface_checkpoint::StoreDiagnostic diagnostic{rf::surface_checkpoint::StoreStage::Write,123,456};
        require(rf::surface_checkpoint::store(views,dense,mesh,meshSettings,nullptr,&diagnostic),"valid observed mesh checkpoint was not written");
        require(rf::surface_checkpoint::diagnosticJson(diagnostic)=="{\"stage\":\"none\",\"errno\":0,\"component\":-1}",
                "successful store retained stale diagnostic");
        rf::surface_checkpoint::AcceptedSurface recovered;
        require(rf::surface_checkpoint::load(checkpoint.string(),views,recovered),"valid observed mesh checkpoint was rejected");
        require(rf::surfaceJson(mesh)==rf::surfaceJson(recovered.mesh),"accepted positions/normals/colors/confidence changed");
        require(recovered.settings.maxVoxelOccupancy==meshSettings.maxVoxelOccupancy&&
                recovered.settings.maxFusionCandidates==meshSettings.maxFusionCandidates,"fusion settings lost in checkpoint");
        require(recovered.mesh.observations.size()==mesh.observations.size(),"surface observation provenance lost");
        require(recovered.mesh.topology.components==mesh.topology.components&&
                recovered.mesh.topology.ambiguousPairs==mesh.topology.ambiguousPairs&&
                recovered.mesh.topology.removedExactDefects==mesh.topology.removedExactDefects,
                "unserialized topology diagnostics lost");
        for(std::size_t i=0;i<mesh.observations.size();++i){
            const auto& a=mesh.observations[i];const auto& b=recovered.mesh.observations[i];
            require(a.vertex==b.vertex&&a.camera==b.camera&&a.x==b.x&&a.y==b.y&&a.sourceSampleId==b.sourceSampleId,
                    "surface observation provenance changed");
        }
        for(std::size_t i=0;i<dense.verifiedSamples.size();++i){
            const auto& a=dense.verifiedSamples[i];const auto& b=recovered.dense.verifiedSamples[i];
            require(a.worldNormal.x==b.worldNormal.x&&a.worldNormal.y==b.worldNormal.y&&a.worldNormal.z==b.worldNormal.z&&
                    a.normalConfidence==b.normalConfidence&&a.normalSupportingViews==b.normalSupportingViews,
                    "verified normal evidence changed");
        }

        auto replay=dense;replay.resumedDepthMaps=views.size();replay.writtenDepthMaps=0;replay.peakLuminanceBytes=0;
        require(rf::denseJson(replay)==rf::denseJson(recovered.dense),"accepted dense points and measured counts changed");
        bool written=true;
        require(rf::surface_checkpoint::store(views,recovered.dense,recovered.mesh,recovered.settings,&written),"replayed evidence no longer has identical canonical bytes");
        require(!written,"existing accepted checkpoint was reported as a new write");
        require(std::filesystem::file_size(checkpoint)<rf::surface_checkpoint::MAX_FILE_BYTES,"checkpoint exceeded byte bound");
    });
    const auto acceptedBytes=read(checkpoint);
    test("new evidence cannot exceed admitted cache memory or overwrite accepted bytes",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);
        auto oversized=dense;auto largeMesh=mesh;
        oversized.points.resize(50000,dense.points.front());
        oversized.verifiedSamples.resize(rf::MAX_DENSE_SAMPLES,dense.verifiedSamples.front());
        largeMesh.observations.resize(rf::MAX_DENSE_SAMPLES);
        require(!rf::surface_checkpoint::store(views,oversized,largeMesh,meshSettings),"over-budget optional cache was allocated");
        require(read(checkpoint)==acceptedBytes,"over-budget cache replaced accepted bytes");
    });

    test("texture-only C ABI changes atlas while accepted mesh and depth hashes stay equal",[&] {
        std::vector<std::vector<std::uint8_t>> originalDepth;
        for(std::size_t i=0;i<views.size();++i)originalDepth.push_back(read(temporary.directory/("ref-"+std::to_string(i)+".bin")));
        const auto call=[&](std::span<const std::uint8_t> photos,int photoWidth,int photoHeight,const std::string& name) {
            const auto glb=temporary.project/(name+".glb.partial"),png=temporary.project/(name+".png.partial");
            const auto result=text(rf_texture_accepted_surface_json(packed.rgba.data(),104,76,photos.data(),photoWidth,photoHeight,
                packed.cameras.data(),views.size(),checkpoint.c_str(),glb.c_str(),png.c_str(),temporary.directory.c_str(),HASH));
            require(result.find("\"geometryReused\":true")!=std::string::npos,"texture call did not report actual reuse");
            require(result.find("\"texture\":{")!=std::string::npos&&result.find("\"error\"")==std::string::npos,"texture replay did not produce an atlas");
            require(result.find("\"writtenDepthMaps\":0")!=std::string::npos,"texture replay wrote depth checkpoints");
            require(result.find("\"surfaceCheckpointAvailable\":true")!=std::string::npos&&result.find("\"surfaceCheckpointWritten\":false")!=std::string::npos,"texture replay fabricated a surface-checkpoint write");
            require(result.find("\"peakLuminanceBytes\":0")!=std::string::npos,"texture replay reported depth luminance work");
            require(result.find("\"mesh\":"+rf::surfaceJson(mesh))!=std::string::npos,"texture replay changed accepted mesh evidence");
            return read(png);
        };
        const auto first=call(packed.rgba,104,76,"first");
        auto differentPhotographs=packed.rgba;
        for(std::size_t i=0;i<differentPhotographs.size();i+=4)std::swap(differentPhotographs[i],differentPhotographs[i+2]);
        const auto second=call(differentPhotographs,104,76,"second");require(first!=second,"changed photographic colors did not change actual atlas bytes");
        std::vector<std::uint8_t> largerPhotographs(views.size()*208*152*4);
        for(std::size_t camera=0;camera<views.size();++camera)for(int y=0;y<152;++y)for(int x=0;x<208;++x)
            std::copy_n(packed.rgba.begin()+static_cast<std::ptrdiff_t>((camera*104*76+(y/2)*104+x/2)*4),4,
                largerPhotographs.begin()+static_cast<std::ptrdiff_t>((camera*208*152+y*208+x)*4));
        const auto larger=call(largerPhotographs,208,152,"larger-atlas");
        const auto pngWidth=[](std::span<const std::uint8_t> bytes){return (std::uint32_t(bytes[16])<<24)|
            (std::uint32_t(bytes[17])<<16)|(std::uint32_t(bytes[18])<<8)|bytes[19];};
        require(pngWidth(larger)>pngWidth(first),"texture-only resolution setting did not change actual PNG dimensions");
        require(read(checkpoint)==acceptedBytes,"texture replay changed accepted mesh bytes");
        for(std::size_t i=0;i<views.size();++i)require(read(temporary.directory/("ref-"+std::to_string(i)+".bin"))==originalDepth[i],"texture replay modified depth bytes");
    });
    test("combined production solver publishes complete accepted binary before texturing",[&] {
        Temp other;rf::depth_checkpoint::Scope scope(other.directory.string(),HASH);
        const auto output=text(rf_textured_surface_json(packed.rgba.data(),104,76,packed.rgba.data(),104,76,
            packed.cameras.data(),views.size(),3.2,7.2,56,3,nullptr,nullptr,nullptr,nullptr));
        require(output.find("\"surfaceCheckpointWritten\":true")!=std::string::npos,"combined production path did not persist accepted mesh");
        rf::surface_checkpoint::AcceptedSurface reopened;
        require(rf::surface_checkpoint::load((other.directory/rf::surface_checkpoint::FILENAME).string(),views,reopened),"combined writer produced unreopenable geometry");
        require(rf::surfaceJson(reopened.mesh)==rf::surfaceJson(mesh),"combined checkpoint changed real native surface");
    });
    test("optical geometry pixels algorithm and depth identity changes never reuse stale evidence",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);rf::surface_checkpoint::AcceptedSurface unused;
        auto changed=views;changed[1].pose.translation.x+=.01;
        require(!rf::surface_checkpoint::load(checkpoint.string(),changed,unused),"changed camera pose reused geometry");
        changed=views;changed[0].fx+=.01;
        require(!rf::surface_checkpoint::load(checkpoint.string(),changed,unused),"changed intrinsics reused geometry");
        changed=views;changed[0].rgba[0]^=1;
        require(!rf::surface_checkpoint::load(checkpoint.string(),changed,unused),"changed rectified original reused geometry");
        auto settings=meshSettings;settings.maximumEdgePixelFactor+=.1;
        require(rf::surface_checkpoint::algorithmIdentity(settings)!=rf::surface_checkpoint::algorithmIdentity(meshSettings),"surface setting omitted from dependency hash");
        std::string wrong(HASH);wrong[0]='0';
        rf::depth_checkpoint::Scope changedScope(temporary.directory.string(),wrong);
        require(!rf::surface_checkpoint::load(checkpoint.string(),views,unused),"wrong depth identity reused mesh");
    });
    test("truncation oversized counts nonfinite geometry and invalid provenance fail before publication",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);rf::surface_checkpoint::AcceptedSurface unchanged;
        unchanged.mesh.triangles.push_back({1,2,3,0});
        const auto rejects=[&](std::vector<std::uint8_t> bad) {
            write(checkpoint,bad);
            require(!rf::surface_checkpoint::load(checkpoint.string(),views,unchanged),"corrupt accepted geometry was loaded");
            require(unchanged.mesh.triangles.size()==1&&unchanged.mesh.triangles[0].a==1,"failed load modified caller state");
        };
        auto bad=acceptedBytes;bad.resize(bad.size()-1);rejects(bad);
        bad=acceptedBytes;bad.push_back(0);rejects(bad);
        bad=acceptedBytes;bad[12]^=1;rejects(bad);
        bad=acceptedBytes;u32(bad,28,std::numeric_limits<std::uint32_t>::max());checksum(bad);rejects(bad);
        bad=acceptedBytes;std::size_t faceEnd=316;
        for(std::size_t i=0;i<views.size();++i)faceEnd+=4+u32(bad,faceEnd)*4+8;
        faceEnd+=static_cast<std::size_t>(u32(bad,36))*32+static_cast<std::size_t>(u32(bad,40))*66+
                 static_cast<std::size_t>(u32(bad,28))*56+static_cast<std::size_t>(u32(bad,32))*13;
        bad[faceEnd-1]=8;checksum(bad);rejects(bad);
        bad=acceptedBytes;u32(bad,8,1);checksum(bad);rejects(bad);
        bad=acceptedBytes;std::size_t vertexOffset=316;
        for(std::size_t i=0;i<views.size();++i)vertexOffset+=4+u32(bad,vertexOffset)*4+8;
        vertexOffset+=static_cast<std::size_t>(u32(bad,36))*32+static_cast<std::size_t>(u32(bad,40))*66;
        const auto nan=std::bit_cast<std::uint64_t>(std::numeric_limits<double>::quiet_NaN());
        for(int i=0;i<8;++i)bad[vertexOffset+24+static_cast<std::size_t>(i)]=static_cast<std::uint8_t>(nan>>(i*8));
        checksum(bad);rejects(bad);
        write(checkpoint,acceptedBytes);
    });
    test("even checksummed replacement depth is rejected by independent full map SHA",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);rf::surface_checkpoint::AcceptedSurface unused;
        auto changed=dense.maps[0];const auto nonzero=std::find_if(changed.depth.begin(),changed.depth.end(),[](float value){return value>0;});
        require(nonzero!=changed.depth.end(),"fixture has no measured depth");*nonzero+=.01f;
        require(rf::depth_checkpoint::store(0,changed),"cannot create valid modified depth fixture");
        require(!rf::surface_checkpoint::load(checkpoint.string(),views,unused),"modified depth CRC bypassed full accepted-map identity");
        require(rf::depth_checkpoint::store(0,dense.maps[0]),"cannot restore depth fixture");
        require(read(checkpoint)==acceptedBytes,"depth mismatch modified accepted mesh");
    });
#if (defined(__linux__) || defined(__ANDROID__)) && defined(O_PATH)
    test("accepted geometry persists and reopens beneath an execute-only private ancestor",[&] {
        Temp privateCache;
        struct RestorePermissions {
            std::filesystem::path path;
            std::filesystem::perms original;
            ~RestorePermissions(){std::error_code ec;std::filesystem::permissions(path,original,ec);}
        } restore{privateCache.project,std::filesystem::status(privateCache.project).permissions()};
        std::filesystem::permissions(privateCache.project,std::filesystem::perms::owner_exec);
        // Prove this process really lacks ancestor read access; privileged runs
        // cannot qualify the Android search-only-ancestor regression.
        errno=0;
        const int ancestor=::open(privateCache.project.c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC);
        const int openError=errno;
        if(ancestor>=0)::close(ancestor);
        require(ancestor<0&&openError==EACCES,"permission regression requires an unprivileged process with a genuinely unreadable ancestor");
        rf::depth_checkpoint::Scope scope(privateCache.directory.string(),HASH);
        for(std::size_t i=0;i<dense.maps.size();++i)
            require(rf::depth_checkpoint::store(i,dense.maps[i]),"depth maps failed beneath searchable ancestor");
        bool created=false;
        require(rf::surface_checkpoint::store(views,dense,mesh,meshSettings,&created)&&created,
                "accepted geometry failed beneath searchable ancestor");
        const auto saved=privateCache.directory/rf::surface_checkpoint::FILENAME;
        const auto bytes=read(saved);
        require(bytes==acceptedBytes,"search-only ancestor changed accepted checkpoint bytes");
        rf::surface_checkpoint::AcceptedSurface recovered;
        require(rf::surface_checkpoint::load(saved.string(),views,recovered),
                "accepted geometry could not reopen beneath searchable ancestor");
        require(rf::surfaceJson(recovered.mesh)==rf::surfaceJson(mesh),"search-only replay changed accepted mesh");
        created=true;
        require(rf::surface_checkpoint::store(views,recovered.dense,recovered.mesh,recovered.settings,&created)&&!created,
                "search-only replay replaced accepted bytes");
        require(read(saved)==bytes,"search-only replay modified accepted bytes");
    });
#endif
    test("symlink checkpoint directory ancestors and final directory fail closed",[&] {
        Temp linked;
        const auto alias=linked.project/"aliased-cache";
        std::filesystem::create_directory_symlink(temporary.directory.parent_path(),alias);
        {
            rf::depth_checkpoint::Scope scope((alias/HASH).string(),HASH);
            rf::surface_checkpoint::AcceptedSurface unused;
            rf::surface_checkpoint::StoreDiagnostic diagnostic;
            require(!rf::surface_checkpoint::store(views,dense,mesh,meshSettings,nullptr,&diagnostic),"symbolic cache ancestor accepted for writing");
            const auto relative=alias.relative_path();
            const int aliasIndex=static_cast<int>(std::distance(relative.begin(),relative.end()))-1;
            require(diagnostic.stage==rf::surface_checkpoint::StoreStage::ComponentOpen&&
                    diagnostic.errorNumber!=0&&diagnostic.component==aliasIndex,
                    "symlink traversal rejection lost syscall or component evidence");
            const auto evidence=rf::surface_checkpoint::diagnosticJson(diagnostic);
            require(evidence.find(alias.string())==std::string::npos&&evidence.size()<100,
                    "store diagnostic contains a path or is unbounded");
            require(!rf::surface_checkpoint::load((alias/HASH/rf::surface_checkpoint::FILENAME).string(),views,unused),
                    "symbolic cache ancestor accepted for reading");
        }
        std::filesystem::remove(linked.directory);
        std::filesystem::create_directory_symlink(temporary.directory,linked.directory);
        {
            rf::depth_checkpoint::Scope scope(linked.directory.string(),HASH);
            rf::surface_checkpoint::AcceptedSurface unused;
            require(!rf::surface_checkpoint::store(views,dense,mesh,meshSettings),"symbolic final cache directory accepted for writing");
            require(!rf::surface_checkpoint::load((linked.directory/rf::surface_checkpoint::FILENAME).string(),views,unused),
                    "symbolic final cache directory accepted for reading");
        }
        require(read(checkpoint)==acceptedBytes,"symbolic directory access changed accepted bytes");
    });
    test("symlink escape and preexisting unknown files preserve all referenced bytes",[&] {
        rf::depth_checkpoint::Scope scope(temporary.directory.string(),HASH);rf::surface_checkpoint::AcceptedSurface unused;
        require(!rf::surface_checkpoint::load((temporary.directory/".."/HASH/rf::surface_checkpoint::FILENAME).string(),views,unused),"noncanonical mesh path accepted");
        const auto original=temporary.project/"original-marker.bin";const std::vector<std::uint8_t> marker{7,8,9,1};write(original,marker);
        std::filesystem::remove(checkpoint);std::filesystem::create_symlink(original,checkpoint);
        require(!rf::surface_checkpoint::load(checkpoint.string(),views,unused),"symbolic mesh file accepted");
        require(!rf::surface_checkpoint::store(views,dense,mesh,meshSettings),"symbolic mesh file was overwritten");
        require(read(original)==marker,"mesh operation altered unrelated original bytes");
        std::filesystem::remove(checkpoint);write(checkpoint,acceptedBytes);
        const auto unknown=temporary.directory/"surface-v1.rfmesh.partial-foreign";write(unknown,marker);
        auto differentMesh=mesh;differentMesh.vertices[0].xyz.x+=.01;
        require(!rf::surface_checkpoint::store(views,dense,differentMesh,meshSettings),"different mesh overwrote accepted identity");
        require(read(checkpoint)==acceptedBytes&&read(unknown)==marker,"cache writing deleted bytes it did not own");
    });
    test("cancellation is typed and never writes texture or replaces accepted geometry",[&] {
        std::atomic_bool cancelled=true;rf::cancellation::Scope stopped(&cancelled);
        const auto glb=temporary.project/"cancelled.glb.partial",png=temporary.project/"cancelled.png.partial";
        const auto result=text(rf_texture_accepted_surface_json(packed.rgba.data(),104,76,packed.rgba.data(),104,76,
            packed.cameras.data(),views.size(),checkpoint.c_str(),glb.c_str(),png.c_str(),temporary.directory.c_str(),HASH));
        require(result.find("\"errorCode\":\"CANCELLED\"")!=std::string::npos,"cancellation became ordinary texture failure");
        require(!std::filesystem::exists(glb)&&!std::filesystem::exists(png),"cancelled texture was written");
        require(read(checkpoint)==acceptedBytes,"cancelled texture mutated accepted geometry");
    });
    test("unavailable atlas preserves truthful loaded dense and mesh without fake texture",[&] {
        auto missing=packed.rgba;for(std::size_t i=3;i<missing.size();i+=4)missing[i]=0;
        const auto glb=temporary.project/"missing.glb.partial",png=temporary.project/"missing.png.partial";
        const auto result=text(rf_texture_accepted_surface_json(packed.rgba.data(),104,76,missing.data(),104,76,
            packed.cameras.data(),views.size(),checkpoint.c_str(),glb.c_str(),png.c_str(),temporary.directory.c_str(),HASH));
        require(result.find("\"textureError\"")!=std::string::npos&&result.find("\"texture\":{")==std::string::npos,"missing photographic evidence became successful texture");
        require(result.find("\"mesh\":"+rf::surfaceJson(mesh))!=std::string::npos,"texture fallback lost accepted mesh");
        require(!std::filesystem::exists(glb)&&!std::filesystem::exists(png),"missing texture produced output files");
    });
    std::cout<<passed<<" accepted-surface checkpoint cases passed; synthetic measured vertices="<<mesh.vertices.size()
        <<", triangles="<<mesh.triangles.size()<<", checkpoint bytes="<<acceptedBytes.size()<<'\n';
}
