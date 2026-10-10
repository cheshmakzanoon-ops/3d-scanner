#include "realityforge/dense.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
void expectError(auto&& callback){bool failed=false;try{callback();}catch(const rf::Error&){failed=true;}require(failed,"invalid geometry accepted");}
// A textured physically planar surface, observed by cameras at different positions.
// Images are actually rendered from world coordinates, not copied and shifted arbitrarily.
std::vector<rf::DenseView> fixture(int count=3,bool texture=true,bool occlude=false){
    constexpr int w=104,h=76;
    std::mt19937 engine(504);std::uniform_int_distribution<int> rgb(0,255);
    std::vector<std::array<int,3>> surface(256*256);
    for(auto& p:surface)p={rgb(engine),rgb(engine),rgb(engine)};
    auto sample=[&](double x,double y,int channel){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        int ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto pix=[&](int dx,int dy){return surface[(iy+dy)*256+ix+dx][channel];};
        return (1-a)*(1-b)*pix(0,0)+a*(1-b)*pix(1,0)+(1-a)*b*pix(0,1)+a*b*pix(1,1);
    };
    std::vector<rf::DenseView> views;
    for(int cam=0;cam<count;++cam){
        rf::DenseView view;view.width=w;view.height=h;
        view.fx=116;view.fy=116;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-cam*.55,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            std::size_t idx=(y*w+x)*4;
            double wx=(x-view.cx)/view.fx*5.0+cam*.55;
            double wy=(y-view.cy)/view.fy*5.0;
            for(int c=0;c<3;++c)view.rgba[idx+c]=texture?static_cast<std::uint8_t>(std::round(sample(wx,wy,c))):128;
            view.rgba[idx+3]=occlude && cam==2 && x>20&&x<70&&y>15&&y<60?0:255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
}
int main(){
    int pass=0;
    auto test=[&](const char* name,auto&& fn){try{fn();std::cout<<"PASS "<<name<<'\n';++pass;}catch(const std::exception& e){std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';throw;}};
    test("plane sweep derives dense geometry from overlapping rendered images",[]{
        const auto images=fixture();
        rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7.0;s.depthPlanes=64;s.pixelStride=2;
        auto r=rf::reconstructDense(images,s);
        std::cout<<"found "<<r.points.size()<<" candidate="<<r.photometricPixels<<" reciprocal="<<r.consistentPixels<<" confidence="<<r.medianConfidence<<"\n";
        require(r.maps.size()==3,"lost image depth maps");
        require(r.points.size()>100,"insufficient reciprocal depth support");
        std::vector<double> depths;for(auto& p:r.points)depths.push_back(p.xyz.z);
        std::sort(depths.begin(),depths.end());double median=depths[depths.size()/2];
        std::cout<<"median depth="<<median<<" ground truth 5\n";
        require(std::abs(median-5.0)<.35,"depth too far from measured synthetic plane");
        require(rf::denseJson(r).find(R"("triangleCount":0)")!=std::string::npos,"incorrect output type");
    });
    test("uniform image contains no invented surface",[]{
        auto r=rf::reconstructDense(fixture(3,false));
        require(r.points.empty(),"textureless input generated fictitious geometry");
    });
    test("occluded view does not corrupt nonoccluded geometry",[]{
        auto r=rf::reconstructDense(fixture(3,true,true));
        require(r.points.size()>50,"occlusion unexpectedly erased reliable regions");
    });
    test("coincident camera centers explicitly rejected",[]{
        auto images=fixture(2);images[1].pose=images[0].pose;
        expectError([&]{rf::reconstructDense(images);});
    });
    test("invalid or mismatched buffers rejected",[]{
        auto images=fixture(2);images[1].rgba.pop_back();
        expectError([&]{rf::reconstructDense(images);});
        auto regular=fixture(2);
        rf::DenseSettings s;s.depthPlanes=256;
        expectError([&]{rf::reconstructDense(regular,s);});
    });
    test("C ABI invalid input returns explicit JSON error",[]{
        char* p=rf_dense_json(nullptr,104,76,nullptr,3,2,8,32,3);
        require(p && std::string(p).find("error")!=std::string::npos,"no ABI rejection");rf_free(p);
    });
    test("deterministic input produces stable point cloud",[]{
        auto images=fixture(2);
        auto a=rf::reconstructDense(images),b=rf::reconstructDense(images);
        require(rf::denseJson(a)==rf::denseJson(b),"nondeterministic stereo output");
    });
    std::cout<<pass<<" CPU dense MVS cases passed\n";
}
