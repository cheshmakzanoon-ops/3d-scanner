#include "realityforge/bundle_adjustment.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool yes,const char* message){if(!yes)throw std::runtime_error(message);}
rf::Mat3 yaw(double t){return {std::cos(t),0,std::sin(t),0,1,0,-std::sin(t),0,std::cos(t)};}
rf::Vec2 project(const rf::ViewPose& c,rf::Vec3 p){
    auto q=rf::multiply(c.rotation,p);q.x+=c.translation.x;q.y+=c.translation.y;q.z+=c.translation.z;
    return {q.x/q.z,q.y/q.z};
}
double dist(rf::Vec3 a,rf::Vec3 b){return std::sqrt((a.x-b.x)*(a.x-b.x)+(a.y-b.y)*(a.y-b.y)+(a.z-b.z)*(a.z-b.z));}
void rejects(const auto& f){try{f();}catch(const rf::Error&){return;}throw std::runtime_error("Expected explicit rejection");}
struct Scene {
    std::vector<rf::ViewPose> truth,initial;
    std::vector<rf::BundleSeed> seeds;
    std::vector<rf::TrackObservation> observations;
};
Scene scene(bool outliers=false){
    Scene s;
    s.truth={ {rf::identity(),{0,0,0}}, {yaw(.06),{-1.,.01,.02}},
              {yaw(.13),{-1.85,.07,.05}}, {yaw(.2),{-2.65,-.04,.15}} };
    s.initial=s.truth;
    s.initial[2].translation.x+=.045;s.initial[2].translation.y-=.03;
    s.initial[3].translation.y+=.037;s.initial[3].rotation=yaw(.216);
    std::mt19937 rng(20261010);
    std::uniform_real_distribution<double> u(-.85,.85),z(3.4,7.6);
    std::normal_distribution<double> imageNoise(0.,.00022),pointNoise(0.,.017);
    for(std::size_t i=0;i<250;++i){
        rf::Vec3 p{u(rng),u(rng),z(rng)};
        const rf::Vec3 initial{p.x+pointNoise(rng),p.y+pointNoise(rng),p.z+pointNoise(rng)};
        s.seeds.push_back({i,initial,{130,170,190}});
        for(std::size_t j=0;j<s.truth.size();++j){
            if((i<60&&j==0)||(i>=60&&i<100&&j>=2)||(i>=100&&i<160&&j==1))continue;
            auto uv=project(s.truth[j],p);uv.x+=imageNoise(rng);uv.y+=imageNoise(rng);
            if(outliers && (i%11==0) && (j==3)){uv.x+=.08;uv.y-=.1;}
            s.observations.push_back({i,j,uv,{130,170,190}});
        }
    }
    return s;
}
void nativeBridgeRejects(const double* camera,const double* point,const double* obs,std::size_t nPoints,std::size_t nObs){
    char* raw=rf_bundle_adjust_json(camera,3,point,nPoints,obs,nObs,.025,5);
    require(raw!=nullptr,"C API returned null rather than error JSON");
    std::string message(raw);rf_free(raw);
    require(message.find("error")!=std::string::npos,"C API failed to reject malformed buffer");
}
}
int main(){
    int passed=0;
    const auto test=[&](const char* title,auto&& fn){
        try{fn();std::cout<<"PASS "<<title<<'\n';++passed;}
        catch(const std::exception& e){std::cerr<<"FAIL "<<title<<": "<<e.what()<<'\n';throw;}
    };
    test("global Schur LM reduces reprojection error and improves independently noisy poses",[]{
        auto s=scene();
        auto out=rf::bundleAdjust(s.initial,s.seeds,s.observations,{.025,18});
        require(out.points.size()>220,"Most point tracks lost");
        require(out.usableObservations>400,"Not enough valid observations used");
        require(out.robustCostAfter<out.robustCostBefore*.30,"Coupled global cost not reduced substantially");
        require(out.medianErrorAfter<out.medianErrorBefore*.35,"Median reprojection error did not decrease");
        require(out.acceptedSteps>0,"No useful optimization step accepted");
        require(out.cameras[0].rotation==s.initial[0].rotation,"Anchor 0 rotation was moved");
        require(out.cameras[0].translation.x==s.initial[0].translation.x,"Anchor 0 translation was moved");
        require(out.cameras[1].rotation==s.initial[1].rotation,"Anchor 1 rotation was moved");
        require(out.cameras[1].translation.x==s.initial[1].translation.x,"Anchor 1 translation was moved");
        require(dist(out.cameras[2].translation,s.truth[2].translation)<dist(s.initial[2].translation,s.truth[2].translation),"Third camera got farther from ground truth");
        require(dist(out.cameras[3].translation,s.truth[3].translation)<dist(s.initial[3].translation,s.truth[3].translation),"Fourth camera got farther from ground truth");
        const auto json=rf::bundleJson(out);
        require(json.find("coupled_huber_schur_lm")!=std::string::npos,"Missing solver identity");
        std::cout<<"M06 initial median "<<out.medianErrorBefore<<" final "<<out.medianErrorAfter
                 <<" initial robust cost "<<out.robustCostBefore<<" final "<<out.robustCostAfter<<'\n';
    });
    test("outlying feature matches are excluded from coupled BA",[]{
        auto s=scene(true);
        const auto out=rf::bundleAdjust(s.initial,s.seeds,s.observations,{.025,18});
        require(out.rejectedObservations>10,"Corrupted feature observations were not excluded");
        require(out.robustCostAfter<out.robustCostBefore,"Robust BA increased loss");
        require(out.points.size()>200,"Robust BA rejected too many good points");
    });
    test("already perfect geometry is not made worse",[]{
        auto s=scene();s.initial=s.truth;s.seeds.clear();
        for(std::size_t i=0;i<250;++i){
            // A separate exact-data scene with known points.
            const rf::Vec3 p{static_cast<double>(i%11)*.11-.5,static_cast<double>(i/11)*.037-.4,4.+i%13*.12};
            s.seeds.push_back({i,p,{200,100,50}});
        }
        s.observations.clear();
        for(const auto& p:s.seeds)for(std::size_t c=0;c<s.truth.size();++c)
            s.observations.push_back({p.track,c,project(s.truth[c],p.xyz),p.color});
        const auto out=rf::bundleAdjust(s.initial,s.seeds,s.observations,{.003,12});
        require(out.robustCostAfter<=out.robustCostBefore+1e-13,"Perfect geometry degraded");
        require(out.points.size()==250,"Clean points disappeared");
    });
    test("singular gauge and conflicting observations rejected",[]{
        auto s=scene();s.initial[1]=s.initial[0];
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations);});
        s=scene();s.observations.push_back(s.observations[0]);
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations);});
    });
    test("NaN, unreasonable camera and wrong settings rejected",[]{
        auto s=scene();s.seeds[0].xyz.x=std::numeric_limits<double>::quiet_NaN();
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations);});
        s=scene();s.initial[3].rotation[0]=5.0;
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations);});
        s=scene();
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations,{.0,3});});
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations,{.003,30});});
    });
    test("C interface rejects nonintegral track indices",[]{
        const double cams[36]={1,0,0,0,1,0,0,0,1,0,0,0,
                               1,0,0,0,1,0,0,0,1,-1,0,0,
                               1,0,0,0,1,0,0,0,1,-2,0,0};
        double pts[16*7]{};double observations[32*7]{};
        for(int i=0;i<16;++i){pts[i*7]=i;pts[i*7+3]=4;}
        for(int i=0;i<32;++i){observations[i*7]=i/2;observations[i*7+1]=i%2;}
        observations[7]=.5;
        nativeBridgeRejects(cams,pts,observations,16,32);
        observations[7]=0.;pts[0]=.75;
        nativeBridgeRejects(cams,pts,observations,16,32);
    });
    std::cout<<passed<<" bundle adjustment cases passed\n";
}
