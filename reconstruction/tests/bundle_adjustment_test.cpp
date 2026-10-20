#include "realityforge/bundle_adjustment.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <iomanip>
#include <sstream>
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
std::string stableSource(std::size_t i){std::ostringstream s;s<<std::hex<<std::setw(64)<<std::setfill('0')<<i;return s.str();}
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
        for(const auto& p:out.points)require(p.supportingCameraIndices.size()==p.supportingViews,"Retained support metadata was fabricated or lost");
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
    test("minimal gauge releases second anchor while preserving baseline and first pose",[]{
        auto s=scene();s.initial[1].rotation=yaw(.073);s.initial[1].translation.y+=.025;
        rf::BundleSettings cfg{.025,25};cfg.gauge=rf::BundleGauge::PoseAndBaseline;
        const auto out=rf::bundleAdjust(s.initial,s.seeds,s.observations,cfg);
        require(out.robustCostAfter<out.robustCostBefore*.1,"Minimal gauge did not converge");
        require(out.cameras[0].rotation==s.initial[0].rotation,"First pose gauge changed");
        const auto centre=[](const auto& p){auto c=rf::multiply(rf::transpose(p.rotation),p.translation);return rf::Vec3{-c.x,-c.y,-c.z};};
        require(std::abs(dist(centre(out.cameras[1]),centre(out.cameras[0]))-dist(centre(s.initial[1]),centre(s.initial[0])))<1e-10,"Scale gauge drifted");
        require(std::abs(out.cameras[1].rotation[2]-s.truth[1].rotation[2])<std::abs(s.initial[1].rotation[2]-s.truth[1].rotation[2]),"Second anchor rotation did not improve");
        require(out.medianCameraResiduals.size()==s.truth.size(),"Per-camera evidence missing");
        require(out.estimatedWorkingBytes<=cfg.maxWorkingBytes,"Working set exceeded admission");
    });
    test("camera Jacobian agrees with independent finite differences",[]{
        const rf::Vec3 q{.3,-.2,4.};const auto j=rf::bundleCameraJacobian(q);const double h=1e-6;
        for(int k=0;k<6;++k){
            auto displaced=[&](double d){rf::Vec3 a=q;if(k<3){
                const double c=std::cos(d),s=std::sin(d);
                if(k==0)a={q.x,c*q.y-s*q.z,s*q.y+c*q.z};
                if(k==1)a={c*q.x+s*q.z,q.y,-s*q.x+c*q.z};
                if(k==2)a={c*q.x-s*q.y,s*q.x+c*q.y,q.z};
            }else{if(k==3)a.x+=d;if(k==4)a.y+=d;if(k==5)a.z+=d;}return rf::Vec2{a.x/a.z,a.y/a.z};};
            const auto a=displaced(h),b=displaced(-h);
            require(std::abs((a.x-b.x)/(2*h)-j[k])<1e-8,"X derivative mismatch");
            require(std::abs((a.y-b.y)/(2*h)-j[6+k])<1e-8,"Y derivative mismatch");
        }
    });
    test("large graph and budget rejection are explicit before allocations",[]{
        auto s=scene();s.initial.resize(80,s.initial[2]);
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations);});
        s=scene();rf::BundleSettings cfg;cfg.maxWorkingBytes=1;
        rejects([&]{rf::bundleAdjust(s.initial,s.seeds,s.observations,cfg);});
        char* e=rf_bundle_adjust_json(nullptr,250,nullptr,16,nullptr,32,.003,2);
        require(e!=nullptr,"Invalid C request did not return error");rf_free(e);
    });
    test("intrinsic groups recover known Brown calibration and preserve withheld projections",[]{
        rf::IntrinsicModel truth;truth.width=1920;truth.height=1080;truth.lensCropIdentity="synthetic-diverse-camera";truth.parameters={1300,1260,950,530,-.09,.02,.001,-.001,.002};
        auto initial=truth;initial.measured=false;initial.parameters[0]-=35;initial.parameters[1]+=30;initial.parameters[2]+=4;initial.parameters[3]-=3;initial.parameters[4]+=.025;
        for(int k=0;k<5;++k)initial.refinable[k]=true;
        rf::IntrinsicPrior prior;prior.centre=initial.parameters;prior.sigma={200,200,50,50,.5,.5,.05,.05,.5};prior.lower={500,500,0,0,-1,-1,-.1,-.1,-1};prior.upper={3000,3000,1920,1080,1,1,.1,.1,1};
        std::vector<rf::IntrinsicMeasurement> observations;
        for(int i=0;i<220;++i){const rf::Vec3 q{(i%19-9)*.22,(i%13-6)*.17,3.+(i%11)*.19};
            observations.push_back({q,rf::projectIntrinsic(truth,{q.x/q.z,q.y/q.z}).pixel});}
        const auto fit=rf::refineIntrinsicGroup(initial,prior,{6,observations.size(),.7,1.8},observations,20);
        require(fit.acceptedSteps>0&&fit.normalizedCostAfter<fit.normalizedCostBefore*.01,"Intrinsic fit did not improve");
        require(std::abs(fit.model.parameters[0]-truth.parameters[0])<.1,"Known focal length not recovered");
        const auto a=rf::projectIntrinsic(truth,{.43,-.21}).pixel,b=rf::projectIntrinsic(fit.model,{.43,-.21}).pixel;
        require(std::hypot(a.x-b.x,a.y-b.y)<.03,"Withheld projection overfit");
        const auto weak=rf::refineIntrinsicGroup(initial,prior,{6,observations.size(),.7,1.01},observations);
        require(weak.model.parameters==initial.parameters,"Planar low-information calibration moved");
    });
    test("versioned pixel bundle consumes fixed intrinsic groups without changing calibration",[]{
        auto s=scene();rf::BundleProblem problem;problem.cameras=s.initial;problem.seeds=s.seeds;
        rf::IntrinsicModel camera;camera.width=1920;camera.height=1080;camera.lensCropIdentity="measured-calibration";camera.parameters={1300,1270,950,530,0,0,0,0,0};
        problem.intrinsicGroups.push_back({camera,{},{}});problem.cameraIntrinsicGroups.assign(s.initial.size(),0);
        for(const auto& o:s.observations)problem.observations.push_back({o.track,o.camera,rf::projectIntrinsic(camera,o.image).pixel,o.color});
        const auto result=rf::bundleAdjustProblem(problem,{.025,18});
        require(result.intrinsics[0].parameters==camera.parameters,"Measured profile changed");
        require(result.geometry.robustCostAfter<result.geometry.robustCostBefore,"Pixel bundle geometry did not improve");
        problem.schema=999;rejects([&]{rf::bundleAdjustProblem(problem);});
        problem.schema=1;
        problem.seeds.push_back({9999,{0,0,-3},{100,120,140}});
        problem.observations.push_back({9999,0,{camera.parameters[2],camera.parameters[3]},{100,120,140}});
        problem.observations.push_back({9999,1,{camera.parameters[2],camera.parameters[3]},{100,120,140}});
        rejects([&]{rf::bundleAdjustProblem(problem,{.025,0});});
    });
    test("verified circular revisit corrects withheld geometry without publishing failed loops",[]{
        rf::BundleProblem p;rf::IntrinsicModel lens;lens.width=1200;lens.height=900;lens.lensCropIdentity="synthetic-circular-fixed";lens.parameters={900,910,599.5,449.5,0,0,0,0,0};
        p.intrinsicGroups.push_back({lens,{},{}});std::vector<rf::ViewPose> truth;
        for(int i=0;i<8;++i){const double angle=i*6.1/7,co=std::cos(angle),si=std::sin(angle);
            truth.push_back({{-co,0,si,0,1,0,-si,0,-co},{0,0,4}});}
        p.cameras=truth;p.cameras.back().translation.x+=.035;p.cameras.back().translation.y-=.022;p.cameraIntrinsicGroups.assign(8,0);
        rf::LoopProposal proposal;proposal.sourcePhotoId="circle-start";proposal.revisitPhotoId="circle-revisit";proposal.componentId=proposal.revisitComponentId="object-A";proposal.revisitCamera=7;proposal.currentPose=p.cameras[7];
        for(std::size_t i=0;i<100;++i){const rf::Vec3 xyz{(int(i%10)-4)*.11,(int(i/10)-4)*.12,(int(i%7)-3)*.07};p.seeds.push_back({i,xyz,{120,100,90}});
            for(std::size_t c=0;c<8;++c){const auto ray=project(truth[c],xyz);p.observations.push_back({i,c,rf::projectIntrinsic(lens,ray).pixel,{120,100,90}});}
            p.stableTrackMapping.emplace_back(i,stableSource(i));
            proposal.landmarks.push_back({i,stableSource(i),xyz,project(truth[7],xyz),7});}
        const auto verified=rf::verifyLoopClosure(proposal);require(verified.accepted,"Circular revisit verification failed");p.loops.push_back(verified.constraint);
        rf::BundleSettings settings{.025,0};const auto out=rf::bundleAdjustProblem(p,settings);
        require(out.acceptedLoopCorrections==1,"Verified improving closure not consumed");
        const rf::Vec3 withheld{.125,-.17,.09};const auto expected=project(truth[7],withheld),before=project(p.cameras[7],withheld),after=project(out.geometry.cameras[7],withheld);
        require(std::hypot(after.x-expected.x,after.y-expected.y)<std::hypot(before.x-expected.x,before.y-expected.y)*.01,"Withheld circular drift was not improved");
        require(out.geometry.cameras[0].rotation==truth[0].rotation,"Loop altered world gauge");
        const auto sourceBefore=out.stableTrackMapping;p.stableTrackMapping[0].second=stableSource(99999);
        require(out.stableTrackMapping==sourceBefore,"Returned source mapping aliases caller metadata");
        rejects([&]{rf::bundleAdjustProblem(p,settings);});
        p.stableTrackMapping[0].second=stableSource(0);p.loops[0].schema=999;rejects([&]{rf::bundleAdjustProblem(p,settings);});
    });
    test("30-camera bounded exact window remains valid and larger global graphs reject explicitly",[]{
        std::vector<rf::ViewPose> cameras;std::vector<rf::BundleSeed> points;std::vector<rf::TrackObservation> rows;
        for(int c=0;c<30;++c)cameras.push_back({rf::identity(),{-c*.1,0,0}});
        for(std::size_t i=0;i<80;++i){const rf::Vec3 p{(int(i%10)-4)*.1,(int(i/10)-3)*.09,4.+i%7*.1};points.push_back({i,p,{100,120,140}});
            for(std::size_t c=0;c<cameras.size();++c)rows.push_back({i,c,project(cameras[c],p),{100,120,140}});}
        rf::BundleSettings cfg{.003,0};cfg.gauge=rf::BundleGauge::PoseAndBaseline;
        const auto out=rf::bundleAdjust(cameras,points,rows,cfg);require(out.points.size()==80&&out.robustCostAfter<1e-20,"30-camera exact window failed");
        require(out.estimatedWorkingBytes<cfg.maxWorkingBytes,"30-camera admission exceeded bound");
        cameras.resize(250,cameras.back());rejects([&]{rf::bundleAdjust(cameras,points,rows,cfg);});
    });
    std::cout<<passed<<" bundle adjustment cases passed\n";
}
