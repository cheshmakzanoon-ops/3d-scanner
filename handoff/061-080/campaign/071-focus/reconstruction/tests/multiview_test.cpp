#include "realityforge/multiview.hpp"
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

void check(bool b,const char* what){if(!b)throw std::runtime_error(what);}
void rejects(auto&& f){try{f();}catch(const rf::Error&){return;}throw std::runtime_error("expected explicit rejection");}
rf::Mat3 yaw(double a){return {std::cos(a),0,std::sin(a),0,1,0,-std::sin(a),0,std::cos(a)};}
rf::Vec2 project(const rf::ViewPose& p,rf::Vec3 x){auto q=rf::multiply(p.rotation,x);q.x+=p.translation.x;q.y+=p.translation.y;q.z+=p.translation.z;return {q.x/q.z,q.y/q.z};}
int main(){
    int pass=0;
    auto test=[&](const char* name,auto&& f){try{f();++pass;std::cout<<"PASS "<<name<<'\n';}catch(const std::exception& e){std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';throw;}};
    rf::ViewPose a{rf::identity(),{0,0,0}},b{yaw(.07),{-1,0,.03}},c{yaw(.14),{-1.8,.08,.08}};
    test("triple-view new points + gauge and noise reduction",[&]{
        std::vector<rf::ViewPose> cams{a,b,c};
        cams[2].translation.x+=.04;cams[2].translation.y-=.028;
        std::mt19937 rng(404);std::uniform_real_distribution<double> coord(-1.,1.),depth(3.,7.);
        std::normal_distribution<double> noise(0,.0002);
        std::vector<rf::TrackObservation> obs;
        int n=320;
        for(int i=0;i<n;++i){
            rf::Vec3 p{coord(rng),coord(rng),depth(rng)};
            // Half of features are visible only in nonanchor pair B-C.
            for(int cam=(i>=160?1:0);cam<3;++cam){
                auto uv=project(std::array<rf::ViewPose,3>{a,b,c}[cam],p);uv.x+=noise(rng);uv.y+=noise(rng);
                obs.push_back({static_cast<std::size_t>(i),static_cast<std::size_t>(cam),uv,{120,180,205}});
            }
        }
        auto result=rf::reconstructMultiView(cams,obs,{.012,.8,20});
        check(result.tracks.size()>300,"new points not recovered");
        check(result.cameras.size()==3,"lost registered camera");
        check(result.cameras[0].rotation==cams[0].rotation&&result.cameras[0].translation.x==cams[0].translation.x,"anchor A gauge moved");
        check(result.cameras[1].rotation==cams[1].rotation&&result.cameras[1].translation.x==cams[1].translation.x,"anchor B gauge moved");
        std::cout << "third tx " << result.cameras[2].translation.x << " target " << c.translation.x << " before " << result.medianErrorBefore << " after " << result.medianErrorAfter << " count " << result.tracks.size() << "\n";
        check(std::abs(result.cameras[2].translation.x-c.translation.x)<.015,"new camera translation not refined");
        check(result.medianErrorAfter<result.medianErrorBefore,"global residual did not decrease");
        check(result.medianErrorAfter<.002,"final error too large");
        check(rf::multiViewJson(result).find("gauge_fixed_alternating_sparse")!=std::string::npos,"schema contract");
    });
    test("intermittent feature mismatches do not create false geometry",[&]{
        std::mt19937 rng(505);std::uniform_real_distribution<double> u(-1,1),z(3,7);
        std::vector<rf::TrackObservation> obs;
        for(int i=0;i<110;++i){rf::Vec3 p{u(rng),u(rng),z(rng)};
            for(int cam=0;cam<3;++cam){auto xy=project(std::array<rf::ViewPose,3>{a,b,c}[cam],p);
                if(i%10==0&&cam==2){xy.x+=.2;xy.y+=.25;}
                obs.push_back({static_cast<std::size_t>(i),static_cast<std::size_t>(cam),xy,{100,100,100}});}
        }
        auto result=rf::reconstructMultiView(std::array{a,b,c},obs,{.003,.8,4});
        check(result.tracks.size()>=99,"good tracks lost with outliers");
        check(result.medianErrorAfter<.002,"outliers damaged calibration");
    });
    test("no invented geometry from pure rotation",[&]{
        rf::ViewPose rotate{yaw(.25),{0,0,0}};
        std::vector<rf::TrackObservation> obs;
        for(int i=0;i<60;++i){rf::Vec3 p{static_cast<double>(i%10)*.1,static_cast<double>(i/10)*.1,4.};
            obs.push_back({static_cast<std::size_t>(i),0,project(a,p),{}});obs.push_back({static_cast<std::size_t>(i),1,project(rotate,p),{}});
        }
        rejects([&]{rf::reconstructMultiView(std::array{a,rotate},obs);});
    });
    test("invalid duplicate camera track rejected",[&]{
        std::vector<rf::TrackObservation> rows{{0,0,{0,0},{}},{0,0,{.1,.1},{}}};
        rejects([&]{rf::reconstructMultiView(std::array{a,b},rows);});
    });
    test("nonintegral ABI indexes and invalid color rejected",[&]{
        double cameras[24]={1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,0,0,0,1,-1,0,0};
        double obs[7]={.5,0,.1,.1,120,100,100};
        char* p=rf_multiview_json(cameras,2,obs,1,.003,4);
        check(p&&std::string(p).find("error")!=std::string::npos,"invalid track id accepted");rf_free(p);
        obs[0]=0;obs[4]=1000;
        p=rf_multiview_json(cameras,2,obs,1,.003,4);
        check(p&&std::string(p).find("error")!=std::string::npos,"invalid color accepted");rf_free(p);
    });
    std::cout<<pass<<" multiview native cases passed\n";return 0;
}
