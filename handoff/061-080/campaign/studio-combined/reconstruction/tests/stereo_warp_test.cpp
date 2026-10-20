#include "realityforge/stereo_warp.hpp"
#include <cmath>
#include <iostream>
#include <limits>
#include <random>
#include <stdexcept>

namespace {
void check(bool value,const char* why){if(!value)throw std::runtime_error(why);}
rf::Mat3 rotation(double a,double b){
    const rf::Mat3 ry{std::cos(a),0,std::sin(a),0,1,0,-std::sin(a),0,std::cos(a)};
    const rf::Mat3 rx{1,0,0,0,std::cos(b),-std::sin(b),0,std::sin(b),std::cos(b)};
    return rf::multiply(rx,ry);
}
rf::DenseView view(double a,double b,rf::Vec3 t,double fx,double fy,double cx,double cy){
    rf::DenseView v;v.width=640;v.height=480;v.fx=fx;v.fy=fy;v.cx=cx;v.cy=cy;
    v.pose={rotation(a,b),t};return v;
}
}
int main(){
    std::mt19937 random(130013);
    std::uniform_real_distribution<double> angle(-.4,.4),position(-2,2),pixel(4,450),depth(.1,30);
    double largest=0;
    for(int i=0;i<10000;++i){
        auto a=view(angle(random),angle(random),{position(random),position(random),position(random)},
                    701.3,639.8,320.2,237.7);
        auto b=view(angle(random),angle(random),{position(random),position(random),position(random)},
                    535.7,811.1,300.1,256.8);
        const double x=pixel(random),y=pixel(random),z=depth(random);
        // Independent route: calibrated ray -> world point -> second camera -> pixel.
        rf::Vec3 p{(x-a.cx)/a.fx*z,(y-a.cy)/a.fy*z,z};
        p={p.x-a.pose.translation.x,p.y-a.pose.translation.y,p.z-a.pose.translation.z};
        auto world=rf::multiply(rf::transpose(a.pose.rotation),p);
        auto q=rf::multiply(b.pose.rotation,world);
        q={q.x+b.pose.translation.x,q.y+b.pose.translation.y,q.z+b.pose.translation.z};
        const auto h=rf::detail::StereoWarp::between(a,b).homogeneous(x,y,1/z);
        check(std::abs(h.z*z-q.z)<1e-10,"precomputed warp changes camera depth");
        check((h.z>0)==(q.z>0),"warp changes cheirality");
        if(std::abs(q.z)>.1){
            const double du=std::abs(h.x/h.z-(b.fx*q.x/q.z+b.cx));
            const double dv=std::abs(h.y/h.z-(b.fy*q.y/q.z+b.cy));
            largest=std::max(largest,std::max(du,dv));
            check(du<1e-7&&dv<1e-7,"warp disagrees with independent projection");
        }
    }
    const auto a=view(.2,-.1,{1,2,3},701.3,639.8,320.2,237.7);
    auto h=rf::detail::StereoWarp::between(a,a).homogeneous(120.25,80.75,.2);
    check(std::abs(h.x/h.z-120.25)<1e-9&&std::abs(h.y/h.z-80.75)<1e-9,"identity warp failed");
    auto b=a;b.rgba.resize(640*480*4,255);auto c=b;c.pose.translation.x-=1;
    const std::vector<rf::DenseView> views{b,c};
    for(int field=0;field<3;++field){
        rf::DenseSettings s;const double nan=std::numeric_limits<double>::quiet_NaN();
        if(field==0)s.minimumNcc=nan;
        if(field==1)s.minUniqueness=nan;
        if(field==2)s.depthConsistencyFraction=nan;
        bool rejected=false;try{rf::reconstructDense(views,s);}catch(const rf::Error&){rejected=true;}
        check(rejected,"NaN quality gate was accepted");
    }
    std::cout<<"PASS 10000 independent camera projections, identity and NaN gates; max pixel error "<<largest<<'\n';
}
