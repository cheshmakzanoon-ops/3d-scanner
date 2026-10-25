// Synthetic known-truth fixture. No private reconstruction observations are embedded.
#include "realityforge/pnp.hpp"
#include "realityforge/cancellation.hpp"
namespace rf { CameraPose originProbeRegisterCamera(std::span<const Observation>,double,std::uint32_t=20261009); }
// White-box DLT probe without exporting test hooks or replacing library API symbols.
#define registerCamera originProbeRegisterCamera
#define cameraJson originProbeCameraJson
#define rf_register_camera_json origin_probe_register_camera_json
#ifndef RF_PNP_IMPLEMENTATION
#define RF_PNP_IMPLEMENTATION "../src/pnp.cpp"
#endif
#include RF_PNP_IMPLEMENTATION
#undef registerCamera
#undef cameraJson
#undef rf_register_camera_json
#include <cassert>
#include <iostream>

namespace {
const rf::Mat3 truth{std::cos(.12),0,std::sin(.12),0,1,0,-std::sin(.12),0,std::cos(.12)};
const rf::Vec3 translation{.4,-.1,.2};
std::vector<rf::Observation> synthetic(bool plane=false,bool outliers=false) {
    std::mt19937 gen(20261025);std::uniform_real_distribution<double> u(-1,1);
    std::vector<rf::Observation> rows;
    for(int i=0;i<45;++i){
        rf::Vec3 p{1.4*u(gen),u(gen),plane?5.:5.25+1.75*u(gen)};
        auto q=rf::multiply(truth,p);q.x+=translation.x;q.y+=translation.y;q.z+=translation.z;
        rf::Vec2 uv{q.x/q.z+std::sin(i*1.7)*.00025,q.y/q.z+std::cos(i*1.3)*.00025};
        if(outliers&&i%3==0)uv={.6*u(gen),.6*u(gen)};
        rows.push_back({p,uv});
    }
    return rows;
}
std::vector<rf::Observation> transform(std::vector<rf::Observation> rows,double s,rf::Vec3 c){
    for(auto& row:rows)row.world={s*row.world.x+c.x,s*row.world.y+c.y,s*row.world.z+c.z};return rows;
}
rf::Vec3 canonical(const rf::CameraPose& pose,double s,rf::Vec3 c){
    auto d=rf::multiply(pose.rotation,c);return {(pose.translation.x+d.x)/s,
        (pose.translation.y+d.y)/s,(pose.translation.z+d.z)/s};
}
double distance(rf::Vec3 a,rf::Vec3 b){return std::hypot(a.x-b.x,a.y-b.y,a.z-b.z);}
void samePose(const rf::CameraPose& expected,const rf::CameraPose& actual,double s,rf::Vec3 c,double tolerance){
    double r=0;for(int i=0;i<9;++i)r+=std::pow(expected.rotation[i]-actual.rotation[i],2);
    const double t=distance(expected.translation,canonical(actual,s,c));
    std::cerr<<"scale="<<s<<" origin="<<c.x<<","<<c.y<<","<<c.z
             <<" rotationDifference="<<std::sqrt(r)<<" canonicalTranslationDifference="<<t<<"\n";
    assert(std::sqrt(r)<tolerance&&t<tolerance);
}
void validate(const std::vector<rf::Observation>& rows,const rf::CameraPose& pose,bool outliers){
    assert(pose.inputCount==rows.size()&&pose.inliers.size()>=(outliers?30:45));
    assert(pose.hypothesesTested>=61&&pose.hypothesesTested<=450);
    auto ortho=rf::multiply(pose.rotation,rf::transpose(pose.rotation));
    for(int i=0;i<9;++i)assert(std::abs(ortho[i]-(i%4==0?1.:0.))<1e-10);
    assert(std::abs(rf::determinant(pose.rotation)-1)<1e-10);
    for(auto id:pose.inliers){
        assert(id<rows.size());if(outliers)assert(id%3!=0);
        auto p=rf::multiply(pose.rotation,rows[id].world);p.x+=pose.translation.x;
        p.y+=pose.translation.y;p.z+=pose.translation.z;
        assert(p.z>1e-8&&std::hypot(p.x/p.z-rows[id].image.x,p.y/p.z-rows[id].image.y)<.003);
    }
}
std::string abi(const std::vector<rf::Observation>& rows){
    std::vector<double> flat;for(auto o:rows)flat.insert(flat.end(),{o.world.x,o.world.y,o.world.z,o.image.x,o.image.y});
    char* json=rf_register_camera_json(flat.data(),rows.size(),.003);assert(json);
    std::string text(json);rf_free(json);return text;
}
}
int main(int argc,char** argv){
    auto rows=synthetic();const std::vector<std::size_t> sample{0,1,2,3,4,5,6,7};
    const auto probe=rf::dlt(rows,sample);
    // Isolate the normalization/projection contract before RANSAC or pose refinement.
    if(argc<2||std::string(argv[1])!="--public-only")
    for(double scale:{.1,1.,10.})for(rf::Vec3 shift: {rf::Vec3{},rf::Vec3{11,-7,23}}){
        auto changed=transform(rows,scale,shift);auto candidate=rf::dlt(changed,sample);
        samePose(probe,candidate,scale,shift,1e-7);
    }
    for(bool plane:{false,true})for(bool outliers:{false,true}){
        auto base=synthetic(plane,outliers);const auto reference=rf::registerCamera(base,.003);
        validate(base,reference,outliers);assert(reference.usedPlanarInitialization==plane);
        assert(distance(reference.translation,translation)<.02);
        for(int i=0;i<9;++i)assert(std::abs(reference.rotation[i]-truth[i])<.005);
        for(double scale:{.1,1.,10.})for(rf::Vec3 shift:{rf::Vec3{},rf::Vec3{11,-7,23}}){
            auto changed=transform(base,scale,shift);auto json=abi(changed);
            if(json.find("error")!=std::string::npos)std::cerr<<json<<"\n";
            assert(json.find("error")==std::string::npos);
            auto pose=rf::registerCamera(changed,.003);
            validate(changed,pose,outliers);samePose(reference,pose,scale,shift,1e-6);
            assert(pose.inliers==reference.inliers&&pose.hypothesesTested==reference.hypothesesTested);
            assert(json==rf::cameraJson(pose));
            std::vector<rf::Observation> duplicate;for(auto row:changed){duplicate.push_back(row);duplicate.push_back(row);}
            auto mapped=rf::registerCamera(duplicate,.003);assert(mapped.inputCount==90);
            for(std::size_t i=0;i<pose.inliers.size();++i)assert(mapped.inliers[i]==2*pose.inliers[i]);
            assert(mapped.rotation==pose.rotation&&mapped.translation.x==pose.translation.x&&
                mapped.translation.y==pose.translation.y&&mapped.translation.z==pose.translation.z);
        }
    }
    // Explicit negative controls; unchanged admission and cancellation remain exercised.
    for(bool plane:{false,true}){
        auto bad=synthetic(plane);std::mt19937 gen(19);std::uniform_real_distribution<double> u(-.8,.8);
        for(auto& row:bad)row.image={u(gen),u(gen)};
        assert(abi(bad).find("error")!=std::string::npos);
    }
    auto line=rows;for(std::size_t i=0;i<line.size();++i)line[i].world={double(i)*.1,0,4};
    assert(abi(line).find("error")!=std::string::npos);
    auto tooMany=rows;tooMany.resize(5001,rows[0]);assert(abi(tooMany).find("error")!=std::string::npos);
    std::atomic_bool stop{true};{rf::cancellation::Scope scope(&stop);
        bool cancelled=false;try{rf::registerCamera(rows,.003);}catch(const rf::cancellation::Cancelled&){cancelled=true;}
        assert(cancelled&&abi(rows).find("Reconstruction cancelled")!=std::string::npos);}
    std::cout<<"PnP centered-translation origin/scale equivariance, truth, public C ABI, provenance and controls passed\n";
}
