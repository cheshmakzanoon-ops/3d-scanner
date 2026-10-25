#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <filesystem>
#include <fstream>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool okay,const char* message){if(!okay)throw std::runtime_error(message);}
std::vector<rf::DenseView> fixture(){
    std::vector<rf::DenseView> views;
    constexpr int w=72,h=56;
    for(int cam=0;cam<3;++cam){
        rf::DenseView v;v.width=w;v.height=h;
        v.fx=v.fy=93.;v.cx=(w-1)/2.;v.cy=(h-1)/2.;
        v.pose={rf::identity(),{-cam*.4,0.,0.}};
        v.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            auto world=(x-v.cx)/v.fx*5.+cam*.4;
            auto wave=std::sin(world*15.+y*.12)*38.+std::cos(world*29.-y*.21)*43.+128.;
            auto val=static_cast<std::uint8_t>(std::clamp(wave,0.,255.));
            const auto offset=(y*w+x)*4;
            v.rgba[offset]=val;v.rgba[offset+1]=val;v.rgba[offset+2]=val;v.rgba[offset+3]=255;
        }
        views.push_back(std::move(v));
    }
    return views;
}
struct Temp {
    std::filesystem::path dir;
    Temp(){
        dir=std::filesystem::temp_directory_path()/("rf-depth-"+
            std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
        std::filesystem::create_directories(dir);
    }
    ~Temp(){std::error_code error;std::filesystem::remove_all(dir,error);}
};
constexpr auto HASH="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
}
int main(){
    int passes=0;
    const auto test=[&](const char* label,auto&& task){task();++passes;std::cout<<"PASS "<<label<<'\n';};
    test("complete reference map is saved with a strict content checksum",[]{
        Temp t;rf::DenseDepthMap map;map.width=64;map.height=48;
        map.depth.assign(64*48,0.f);map.confidence.assign(64*48,0.f);
        map.depth[200]=4.5f;map.confidence[200]=.8f;
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(rf::depth_checkpoint::store(2,map),"failed atomic depth publication");}
        rf::DenseDepthMap recovered;recovered.width=64;recovered.height=48;
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(rf::depth_checkpoint::load(2,recovered),"refused valid persisted checkpoint");}
        require(recovered.depth==map.depth&&recovered.confidence==map.confidence,"checkpoint altered float bits");
        const auto file=t.dir/"ref-2.bin";
        require(std::filesystem::file_size(file)==96+64*48*8,"unexpected bounded binary structure");
        require(!std::filesystem::exists(t.dir/"ref-2.bin.partial"),"orphan partial file");
    });
    test("older solver reference maps are rejected without changing destination",[]{
        Temp t;rf::DenseDepthMap map{64,48,std::vector<float>(64*48,5.f),std::vector<float>(64*48,.8f)};
        rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
        require(rf::depth_checkpoint::store(0,map),"cannot prepare current checkpoint");
        rf::DenseDepthMap recovered{64,48,std::vector<float>(64*48,7.f),std::vector<float>(64*48,.2f)};
        const auto savedDepth=recovered.depth,savedConfidence=recovered.confidence;
        const auto file=t.dir/"ref-0.bin";
        for(char version:{1,2}){
            require(rf::depth_checkpoint::store(0,map),"cannot restore current checkpoint");
            {std::fstream f(file,std::ios::in|std::ios::out|std::ios::binary);
             f.seekg(8);require(f.get()==3,"current reference format version missing");
             f.seekp(8);const std::array<char,4> oldVersion{version,0,0,0};f.write(oldVersion.data(),oldVersion.size());}
            require(!rf::depth_checkpoint::load(0,recovered),"accepted older solver cache under same identity");
            require(recovered.depth==savedDepth&&recovered.confidence==savedConfidence,"cache miss changed destination buffers");
        }
        require(rf::depth_checkpoint::store(0,map),"cannot replace stale reference with current result");
        require(rf::depth_checkpoint::load(0,recovered),"current-version roundtrip failed");
        require(recovered.depth==map.depth&&recovered.confidence==map.confidence,"current checkpoint changed float bits");
    });
    test("identity change never reuses stale geometry",[]{
        Temp t;rf::DenseDepthMap map{64,48,std::vector<float>(64*48),std::vector<float>(64*48)};
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(rf::depth_checkpoint::store(0,map),"cannot prepare checkpoint");}
        std::string changed(HASH);changed[0]='1';
        {rf::depth_checkpoint::Scope scope(t.dir.string(),changed);
         require(!rf::depth_checkpoint::load(0,map),"accepted wrong full scan SHA identity");}
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         map.width=65;require(!rf::depth_checkpoint::load(0,map),"accepted changed image dimensions");}
    });
    test("wrong-size, truncated and corrupted files are cache misses",[]{
        Temp t;rf::DenseDepthMap map{64,48,std::vector<float>(64*48),std::vector<float>(64*48)};
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(rf::depth_checkpoint::store(0,map),"store failed");}
        const auto file=t.dir/"ref-0.bin";
        {std::fstream f(file,std::ios::in|std::ios::out|std::ios::binary);
         f.seekp(111);const char corruption='X';f.write(&corruption,1);}
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(!rf::depth_checkpoint::load(0,map),"accepted corrupted data");}
        {std::ofstream f(file,std::ios::trunc|std::ios::binary);f.put('x');}
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(!rf::depth_checkpoint::load(0,map),"accepted truncated data");}
    });
    test("completed depth maps replay exactly without repeating photometric search",[]{
        Temp t;auto images=fixture();rf::DenseSettings s;s.nearDepth=3.2;s.farDepth=7.2;
        s.depthPlanes=36;s.pixelStride=3;
        rf::DenseResult initial;
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);initial=rf::reconstructDense(images,s);}
        require(initial.writtenDepthMaps==3&&initial.resumedDepthMaps==0,"initial maps missing");
        require(initial.photometricDiagnostics.evaluatedSamples==initial.sampledPixels,
                "fresh map evaluation coverage missing");
        rf::DenseResult replay;
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);replay=rf::reconstructDense(images,s);}
        require(replay.resumedDepthMaps==3&&replay.writtenDepthMaps==0,"resume did not reuse all cameras");
        require(replay.photometricDiagnostics.evaluatedSamples==0,
                "cached maps invented fresh rejection counters");
        require(replay.lossDiagnostics.available&&replay.lossDiagnostics.viewCount==3,
                "Re-executed reciprocal diagnostic scope unavailable");
        for(std::size_t i=0;i<3;++i){const auto& d=replay.lossDiagnostics.views[i];
            require(d.evaluated==0&&d.photometricAccepted==0&&d.invalidReferencePatch==0&&d.validLowVariance==0,
                    "Cached photometric work fabricated originating split counts");
            require(std::all_of(d.validReferenceMeanHistogram.begin(),d.validReferenceMeanHistogram.end(),[](auto n){return n==0;})&&
                    std::all_of(d.validLowVarianceMeanHistogram.begin(),d.validLowVarianceMeanHistogram.end(),[](auto n){return n==0;}),
                    "Cached photometric work invented patch-mean evidence");
            require(d.sampled==initial.lossDiagnostics.views[i].sampled&&
                    d.reciprocalAccepted==initial.lossDiagnostics.views[i].reciprocalAccepted&&
                    d.sourcePairs==initial.lossDiagnostics.views[i].sourcePairs&&
                    d.confirmationHistogram==initial.lossDiagnostics.views[i].confirmationHistogram,
                    "Replayed depth did not preserve re-executed reciprocal evidence");
        }
        const auto diagnostics=rf::denseJson(replay);
        require(diagnostics.find("\"coverage\":\"UNAVAILABLE\"")!=std::string::npos&&
                diagnostics.find("\"rejectionCounts\":null")!=std::string::npos&&
                diagnostics.find("\"unknownSamples\":"+std::to_string(replay.sampledPixels))!=std::string::npos,
                "cached map diagnostics must be explicitly unknown");
        require(diagnostics.find("\"photometric\":{\"coverage\":\"UNAVAILABLE\"")!=std::string::npos&&
                diagnostics.find("\"reciprocal\":{\"coverage\":\"COMPLETE\"")!=std::string::npos,
                "Cached photometric and re-executed reciprocal coverage were conflated");
        require(diagnostics.find("\"validReferenceMeanHistogram\":null,\"validLowVarianceMeanHistogram\":null")!=std::string::npos,
                "Cached patch-mean histogram serialized unknown work as measured zero bins");
        require(replay.maps.size()==initial.maps.size(),"camera count differs");
        for(std::size_t i=0;i<replay.maps.size();++i){
            require(replay.maps[i].depth==initial.maps[i].depth,"depth changed after resume");
            require(replay.maps[i].confidence==initial.maps[i].confidence,"confidence changed after resume");
        }
        require(replay.points.size()==initial.points.size()&&
                replay.consistentPixels==initial.consistentPixels&&
                replay.photometricPixels==initial.photometricPixels&&
                replay.sampledPixels==initial.sampledPixels,"resume changed real reconstruction counts");
        std::filesystem::remove(t.dir/"ref-1.bin");
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);replay=rf::reconstructDense(images,s);}
        require(replay.resumedDepthMaps==2&&replay.writtenDepthMaps==1,"missing reference not recomputed");
    });
    test("cancelled operations cannot replace accepted reference checkpoints",[]{
        Temp t;rf::DenseDepthMap map{64,48,std::vector<float>(64*48),std::vector<float>(64*48)};
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);
         require(rf::depth_checkpoint::store(0,map),"store failed");}
        const auto acceptedSize=std::filesystem::file_size(t.dir/"ref-0.bin");
        std::atomic_bool cancelled=true;
        bool stopped=false;
        {rf::depth_checkpoint::Scope depth(t.dir.string(),HASH);
         rf::cancellation::Scope scope(&cancelled);
         try{(void)rf::depth_checkpoint::store(1,map);}catch(const rf::cancellation::Cancelled&){stopped=true;}}
        require(stopped,"cancel flag not observed by checkpoint publication");
        require(!std::filesystem::exists(t.dir/"ref-1.bin"),"cancelled partial became accepted");
        require(std::filesystem::file_size(t.dir/"ref-0.bin")==acceptedSize,"cancel erased a completed view");
    });
    test("byte admission and orphan cleanup preserve accepted work and originals",[]{
        Temp t;rf::DenseDepthMap map{64,48,std::vector<float>(64*48),std::vector<float>(64*48)};
        {std::ofstream f(t.dir/"ref-0.bin.partial-orphan");f<<"interrupted";}
        {std::ofstream f(t.dir/"original.jpg");f<<"original";}
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH,96+64*48*8);
         require(rf::depth_checkpoint::store(0,map),"bounded valid store failed");
         require(rf::depth_checkpoint::store(1,map),"bounded eviction store failed");}
        require(!std::filesystem::exists(t.dir/"ref-0.bin.partial-orphan"),"orphan not recovered");
        require(!std::filesystem::exists(t.dir/"ref-0.bin"),"byte eviction did not remove oldest reference");
        require(std::filesystem::exists(t.dir/"original.jpg"),"cache eviction touched original");
        {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH,32);
         require(!rf::depth_checkpoint::store(2,map),"oversized cache payload accepted");}
        require(std::filesystem::exists(t.dir/"ref-1.bin"),"failed admission erased accepted work");
    });
    test("invalid directory or non-SHA identities are rejected",[]{
        bool caught=false;
        try{rf::depth_checkpoint::Scope invalid("/tmp","../wrong");}catch(const std::invalid_argument&){caught=true;}
        require(caught,"unsafe checkpoint path accepted");
    });
    std::cout<<passes<<" depth checkpoint cases passed\n";
}
