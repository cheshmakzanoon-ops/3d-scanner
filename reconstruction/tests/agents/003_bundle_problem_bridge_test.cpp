#include "../../src/bundle_problem_bridge.hpp"
#include <iostream>
#include <stdexcept>
#include <atomic>
#include <memory>
namespace {
void require(bool value,const char* reason){if(!value)throw std::runtime_error(reason);}
void rejects(auto block){bool rejected=false;try{block();}catch(const rf::Error&){rejected=true;}require(rejected,"Invalid typed problem accepted");}
std::string source(std::size_t i){std::string value(64,'0');const std::string digits="0123456789abcdef";value[62]=digits[(i/16)%16];value[63]=digits[i%16];return value;}
struct Fixture {
    std::vector<double> cameras,seeds,pixels,models;
    std::vector<std::string> groups,photos,tracks;
    Fixture(std::size_t count=5){
        rf::IntrinsicModel lens;lens.width=640;lens.height=480;lens.lensCropIdentity="estimated-same-camera";
        lens.parameters={500,510,319.5,239.5,-.04,.006,.001,-.0005,.0002};
        for(std::size_t c=0;c<count;++c){auto rotation=rf::identity();cameras.insert(cameras.end(),rotation.begin(),rotation.end());
            cameras.insert(cameras.end(),{-.12*static_cast<double>(c),0,0});
            models.insert(models.end(),{640,480});models.insert(models.end(),lens.parameters.begin(),lens.parameters.end());
            groups.push_back("estimated-same-camera");photos.push_back("photo-"+std::to_string(c));}
        for(std::size_t t=0;t<100;++t){tracks.push_back(source(t));const double x=(static_cast<int>(t%10)-4.5)*.17,y=(static_cast<int>(t/10)-4.5)*.13,z=3.+(t%7)*.22;
            seeds.insert(seeds.end(),{static_cast<double>(t),x,y,z,130,90,60});
            for(std::size_t c=0;c<count;++c){const auto p=rf::projectIntrinsic(lens,{(x-.12*c)/z,y/z}).pixel;
                pixels.insert(pixels.end(),{static_cast<double>(t),static_cast<double>(c),p.x,p.y,130,90,60});}}
        // Full036 mapping contains a source track that was not retained as a seed.
        tracks.push_back(source(100));pixels.insert(pixels.end(),{100,0,320,240,130,90,60});
    }
};
}
int main(){
    Fixture f;
    auto prepared=rf::jni::bundle_bridge::prepare(f.cameras,f.seeds,f.pixels,f.models,f.groups,f.photos,f.tracks,true,true,.003,2);
    require(prepared.problem.intrinsicGroups.size()==1,"Declared identical models not grouped");
    require(!prepared.problem.intrinsicGroups[0].model.refinable[0],"Measured profile was released");
    require(prepared.problem.intrinsicGroups[0].evidence.independentViews==5,"Actual group view evidence lost");
    require(prepared.problem.intrinsicGroups[0].evidence.observations==500,"Nonretained source polluted observability");
    require(prepared.loopAttempts>0&&prepared.loopVerified>0,"Actual nonadjacent PnP not executed");
    require(prepared.problem.stableTrackMapping.size()==100,"Full batch mapping not restricted to retained seeds");
    const auto json=rf::jni::bundleProblemJson(f.cameras,f.seeds,f.pixels,f.models,f.groups,f.photos,f.tracks,true,false,.003,2);
    require(json.find("\"bundleProblemSchema\":1")!=std::string::npos&&json.find("\"cameraIntrinsics\":[[640,480,500,510")!=std::string::npos,"Actual typed output metadata missing");
    const auto estimated=rf::jni::bundle_bridge::prepare(f.cameras,f.seeds,f.pixels,f.models,f.groups,f.photos,f.tracks,false,true,.003,2);
    const auto& group=estimated.problem.intrinsicGroups[0];require(group.model.refinable[0]&&group.model.refinable[1]&&!group.model.refinable[4],"Brown/focal policy escaped explicit scope");
    require(group.prior.sigma[0]==25&&group.prior.lower[0]==425&&group.prior.upper[0]==575,"Focal prior policy changed");
    auto bad=f;bad.models[11+2]=501;rejects([&]{rf::jni::bundle_bridge::prepare(bad.cameras,bad.seeds,bad.pixels,bad.models,bad.groups,bad.photos,bad.tracks,true,false,.003,2);});
    bad=f;bad.tracks[1]=bad.tracks[0];rejects([&]{rf::jni::bundle_bridge::prepare(bad.cameras,bad.seeds,bad.pixels,bad.models,bad.groups,bad.photos,bad.tracks,true,false,.003,2);});
    bad=f;bad.pixels.insert(bad.pixels.end(),bad.pixels.begin(),bad.pixels.begin()+7);rejects([&]{rf::jni::bundle_bridge::prepare(bad.cameras,bad.seeds,bad.pixels,bad.models,bad.groups,bad.photos,bad.tracks,true,false,.003,2);});
    bad=f;bad.photos[1]=bad.photos[0];rejects([&]{rf::jni::bundle_bridge::prepare(bad.cameras,bad.seeds,bad.pixels,bad.models,bad.groups,bad.photos,bad.tracks,true,false,.003,2);});
    auto cancelled=std::make_shared<std::atomic_bool>(true);bool stopped=false;
    {rf::cancellation::Scope scope(cancelled);try{rf::jni::bundleProblemJson(f.cameras,f.seeds,f.pixels,f.models,f.groups,f.photos,f.tracks,true,false,.003,2);}catch(const rf::cancellation::Cancelled&){stopped=true;}}
    require(stopped,"Typed problem ignored owned cancellation scope");
    std::cout<<"typed BundleProblem parser, actual PnP/solver, fixed model, evidence, subset mapping, invalid identity and cancellation passed\n";
}
