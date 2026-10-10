#include "realityforge/surface.hpp"
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
    int passed=0;
    auto test=[&](const char* name,auto fn){try{fn();++passed;std::cout<<"PASS "<<name<<'\n';}catch(const std::exception& e){std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';throw;}};
    test("confirmed depth samples produce genuine indexed triangles",[]{
        const auto images=fixture();
        rf::DenseSettings ds;ds.nearDepth=3.1;ds.farDepth=7;ds.depthPlanes=64;ds.pixelStride=2;
        const auto dense=rf::reconstructDense(images,ds);
        rf::SurfaceSettings ms;ms.pixelStride=2;
        auto mesh=rf::fuseDepthSurfaces(images,dense,ms);
        std::cout<<"confirmed="<<mesh.inputSamples<<" vertices="<<mesh.vertices.size()<<" faces="<<mesh.triangles.size()<<" fused="<<mesh.fusedObservations<<"\n";
        require(mesh.inputSamples>100,"No reciprocal depth samples");
        require(mesh.vertices.size()>80,"No fused vertices");
        require(mesh.triangles.size()>60,"Expected observed faces from textured plane");
        for(auto& v:mesh.vertices){
            require(std::abs(v.xyz.z-5.)<1.0,"Mesh contains unsupported distant depth");
            require(std::abs(std::hypot(v.normal.x,v.normal.y,v.normal.z)-1.)<.01,"Invalid mesh normal");
        }
        for(auto& t:mesh.triangles){
            require(t.a<mesh.vertices.size() && t.b<mesh.vertices.size() && t.c<mesh.vertices.size(),"Invalid index");
            require(t.a!=t.b&&t.b!=t.c&&t.c!=t.a,"Degenerate face");
        }
        require(rf::surfaceJson(mesh).find("\"triangleCount\":")!=std::string::npos,"Missing triangle data");
        rf::writeSurfaceGlb(mesh,"rf-test-surface.glb");
        rf::writeSurfacePly(mesh,"rf-test-surface.ply");
    });
    test("textureless inputs produce zero triangle faces",[]{
        const auto images=fixture(3,false);
        const auto dense=rf::reconstructDense(images);
        require(dense.verifiedSamples.empty(),"Uniform image yielded reciprocal points");
        auto mesh=rf::fuseDepthSurfaces(images,dense);
        require(mesh.triangles.empty(),"Unobserved geometry was filled");
        expectError([&]{rf::writeSurfaceGlb(mesh,"rf-invalid.glb");});
    });
    test("occluded input retains only confirmed geometry",[]{
        const auto images=fixture(3,true,true);
        rf::DenseSettings ds;ds.nearDepth=3.1;ds.farDepth=7.;ds.depthPlanes=64;ds.pixelStride=2;
        const auto dense=rf::reconstructDense(images,ds);
        rf::SurfaceSettings s;s.pixelStride=2;
        auto mesh=rf::fuseDepthSurfaces(images,dense,s);
        require(mesh.triangles.size()>20,"Occlusion erased all verified mesh");
    });
    test("depth-step barrier prevents triangles across measured discontinuity",[]{
        const auto views=fixture(2);
        rf::DenseResult dense;dense.maps.resize(views.size());
        for(auto& map:dense.maps){map.width=104;map.height=76;map.depth.assign(104*76,0);map.confidence.assign(104*76,0);}
        for(int y=3;y<73;y+=3)for(int x=3;x<101;x+=3){
            // An abrupt unobserved depth step is measured in both cameras.
            const float d=x<52?5.f:8.f;
            for(std::size_t cam=0;cam<views.size();++cam){
                const auto& v=views[cam];
                auto& m=dense.maps[cam];m.depth[y*104+x]=d;m.confidence[y*104+x]=.95f;
                const rf::Vec3 world{(x-v.cx)*d/v.fx+cam*.55,(y-v.cy)*d/v.fy,d};
                dense.verifiedSamples.push_back({world,{150,140,120},.95f,2,
                   static_cast<std::uint8_t>(cam),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
            }
        }
        rf::SurfaceSettings settings;settings.pixelStride=3;
        auto mesh=rf::fuseDepthSurfaces(views,dense,settings);
        require(mesh.triangles.size()>50,"Synthetic verified patches should form faces");
        for(auto t:mesh.triangles){
            const auto& a=mesh.vertices[t.a].xyz;
            const auto& b=mesh.vertices[t.b].xyz;
            const auto& c=mesh.vertices[t.c].xyz;
            const auto mn=std::min({a.z,b.z,c.z}),mx=std::max({a.z,b.z,c.z});
            require(mx-mn<1.,"Triangle bridged depth discontinuity");
        }
    });
    test("reject malformed source depth maps and unverified samples",[]{
        auto images=fixture(2);
        rf::DenseResult r;r.maps.resize(2);
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
        for(auto& m:r.maps){m.width=104;m.height=76;m.depth.assign(104*76,0);m.confidence.assign(104*76,0);}
        r.verifiedSamples.push_back({{0,0,5},{1,2,3},.9f,1,0,3,3});
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
        r.verifiedSamples[0].supportingViews=2;r.verifiedSamples[0].camera=100;
        expectError([&]{rf::fuseDepthSurfaces(images,r);});
    });
    test("invalid C ABI produces JSON error",[]{
        char* result=rf_surface_json(nullptr,104,76,nullptr,2,3,7,32,3,nullptr,nullptr);
        require(result && std::string(result).find("error")!=std::string::npos,"Missing ABI error");rf_free(result);
    });
    test("deterministic fusion",[]{
        const auto images=fixture(2);
        rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7.0;s.depthPlanes=48;s.pixelStride=3;
        auto d=rf::reconstructDense(images,s);
        auto m1=rf::fuseDepthSurfaces(images,d),m2=rf::fuseDepthSurfaces(images,d);
        require(rf::surfaceJson(m1)==rf::surfaceJson(m2),"Fusion is nondeterministic");
    });
    std::cout<<passed<<" surface meshing cases passed\n";
}
