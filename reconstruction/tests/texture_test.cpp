#include "realityforge/texture.hpp"
#include "realityforge/cancellation.hpp"
#include <filesystem>
#if defined(__unix__) || defined(__ANDROID__)
#include <sys/resource.h>
#include <csignal>
#endif
#include <algorithm>
#include <array>
#include <cmath>
#include <fstream>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
void check(bool b,const char* m){if(!b)throw std::runtime_error(m);}
void rejected(auto f){bool fail=false;try{f();}catch(const rf::Error&){fail=true;}check(fail,"unsafe input accepted");}
std::vector<rf::DenseView> fixture(bool mask=false,bool texture=true){
    constexpr int w=104,h=76;
    std::mt19937 rand(188);std::uniform_int_distribution<int> color(0,255);
    std::vector<std::array<int,3>> src(256*256);
    for(auto& c:src)c={color(rand),color(rand),color(rand)};
    auto lookup=[&](double x,double y,int ch){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        auto ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto p=[&](int xx,int yy){return src[(iy+yy)*256+ix+xx][ch];};
        return (1-a)*(1-b)*p(0,0)+a*(1-b)*p(1,0)+(1-a)*b*p(0,1)+a*b*p(1,1);
    };
    std::vector<rf::DenseView> images;
    for(int c=0;c<3;++c){
        rf::DenseView im;im.width=w;im.height=h;im.fx=116;im.fy=116;im.cx=(w-1)/2.;im.cy=(h-1)/2.;
        im.pose={rf::identity(),{-c*.55,0,0}};im.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const double wx=(x-im.cx)*5.0/im.fx+c*.55,wy=(y-im.cy)*5./im.fy;
            const auto pixel=static_cast<std::size_t>(y*w+x)*4;
            for(int ch=0;ch<3;++ch)im.rgba[pixel+ch]=texture?static_cast<std::uint8_t>(std::round(lookup(wx,wy,ch))):128;
            im.rgba[pixel+3]=mask&&c==2&&x>10&&x<95&&y>10&&y<65?0:255;
        }
        images.push_back(std::move(im));
    }
    return images;
}
std::vector<rf::TextureSource> sources(std::span<const rf::DenseView> images,int upsample=2){
    std::vector<rf::TextureSource> out;
    for(auto& im:images){
        rf::TextureSource t;t.width=im.width*upsample;t.height=im.height*upsample;
        t.fx=im.fx*upsample;t.fy=im.fy*upsample;
        t.cx=(im.cx+.5)*upsample-.5;t.cy=(im.cy+.5)*upsample-.5;t.pose=im.pose;
        t.rgba.resize(static_cast<std::size_t>(t.width)*t.height*4);
        for(int y=0;y<t.height;++y)for(int x=0;x<t.width;++x){
            const auto old=((static_cast<std::size_t>(y)/upsample)*im.width+x/upsample)*4;
            const auto dest=(static_cast<std::size_t>(y)*t.width+x)*4;
            std::copy_n(im.rgba.begin()+static_cast<std::ptrdiff_t>(old),4,t.rgba.begin()+static_cast<std::ptrdiff_t>(dest));
        }
        out.push_back(std::move(t));
    }
    return out;
}
}
int main(){
    int done=0;
    auto test=[&](const char* label,auto task){
        try{task();++done;std::cout<<"PASS "<<label<<'\n';}
        catch(const std::exception& e){std::cerr<<"FAIL "<<label<<": "<<e.what()<<'\n';throw;}
    };
    auto images=fixture();rf::DenseSettings ds;ds.nearDepth=3.1;ds.farDepth=7;ds.depthPlanes=56;ds.pixelStride=3;
    auto dense=rf::reconstructDense(images,ds);
    rf::SurfaceSettings ss;ss.pixelStride=3;
    auto mesh=rf::fuseDepthSurfaces(images,dense,ss);
    auto photo=sources(images);
    test("photo atlas creates indexed UV mesh",[&]{
        const auto atlas=rf::bakePhotographicAtlas(mesh,images,dense.maps,photo);
        std::cout<<"triangles="<<atlas.indices.size()/3<<" coverage="<<atlas.faceCoverage<<" atlas="<<atlas.atlasWidth<<"x"<<atlas.atlasHeight<<'\n';
        check(atlas.indices.size()>300,"too few actual UV triangles");
        check(atlas.vertices.size()>100,"no UV vertices");
        check(atlas.atlasWidth==2*(photo[0].width+6),"atlas width wrong");
        check(atlas.sourceTriangleCounts.size()==3,"missing camera views");
        check(atlas.faceCoverage>.10,"too few supported photos");
        for(auto t:atlas.vertices){check(t.u>0&&t.u<1&&t.v>0&&t.v<1,"out of range UV");
            check(t.surfaceVertex<mesh.vertices.size(),"wrong geometry index");}
        auto j=rf::texturedSurfaceJson(atlas);
        check(j.find("\"textured\":true")!=std::string::npos,"missing texture metadata");
        rf::writeTexturedGlb(mesh,atlas,"rf-test-textured.glb");
        rf::writeTexturePng(atlas,"rf-test-atlas.png");
        std::ifstream in("rf-test-textured.glb",std::ios::binary);char magic[4]{};in.read(magic,4);
        check(std::string(magic,4)=="glTF","GLB magic wrong");
        std::ifstream png("rf-test-atlas.png",std::ios::binary);char sign[8]{};png.read(sign,8);
        check(static_cast<unsigned char>(sign[0])==137&&std::string(sign+1,3)=="PNG","PNG signature wrong");
    });
    test("masked images are rejected as texture choices",[&]{
        auto masked=photo;
        for(auto& v:masked)std::fill(v.rgba.begin()+3,v.rgba.end(),0); // all invalid, must reject
        rejected([&]{rf::bakePhotographicAtlas(mesh,images,dense.maps,masked);});
    });
    test("camera mismatch is rejected",[&]{
        auto wrong=photo;wrong[1].fx*=1.4;
        rejected([&]{rf::bakePhotographicAtlas(mesh,images,dense.maps,wrong);});
        wrong=photo;wrong[0].pose.translation.x=.33;
        rejected([&]{rf::bakePhotographicAtlas(mesh,images,dense.maps,wrong);});
    });
    test("occlusion mask does not invent textures",[&]{
        auto occluded=photo;
        for(int y=15;y<occluded[1].height-15;++y)for(int x=20;x<occluded[1].width-20;++x)
            occluded[1].rgba[(static_cast<std::size_t>(y)*occluded[1].width+x)*4+3]=0;
        auto baked=rf::bakePhotographicAtlas(mesh,images,dense.maps,occluded);
        check(baked.indices.size()>300,"occluded camera erased valid faces");
        check(baked.indices.size()<=mesh.triangles.size()*3,"invented faces");
    });
    test("atlas deterministic",[&]{
        const auto a=rf::bakePhotographicAtlas(mesh,images,dense.maps,photo);
        const auto b=rf::bakePhotographicAtlas(mesh,images,dense.maps,photo);
        check(rf::texturedSurfaceJson(a)==rf::texturedSurfaceJson(b),"nondeterministic chart count");
        check(a.indices==b.indices&&a.atlasRgba==b.atlasRgba,"nondeterministic UVs or pixels");
    });
    test("invalid C ABI safe JSON error",[&]{
        auto* s=rf_textured_surface_json(nullptr,104,76,nullptr,208,152,nullptr,3,3,7,56,3,
            nullptr,nullptr,nullptr,nullptr);
        check(s&&std::string(s).find("error")!=std::string::npos,"Missing ABI error");rf_free(s);
    });
    test("untextured stereo produces no faked UV mesh",[&]{
        auto plain=fixture(false,false);
        auto d=rf::reconstructDense(plain);
        check(d.verifiedSamples.empty(),"textureless scene produced depth");
    });
    test("compact charts bake actual verified pixels and retain unsupported geometry",[&]{
        rf::TextureSettings settings;settings.compactCharts=true;settings.depthSampleStride=3;settings.depthSampleOrigin=3;
        auto baked=rf::bakePhotographicAtlas(mesh,images,dense.maps,photo,settings);
        check(baked.compactCharts&&baked.supportedTexels>0,"compact path did not bake photographic support");
        check(baked.indices.size()==mesh.triangles.size()*3,"compact path hid unsupported geometry");
        check(baked.atlasWidth<=2048&&baked.atlasHeight<=2048,"2K budget exceeded");
        check(baked.texelsPerUnit>0&&baked.atlasUtilization>0&&baked.atlasUtilization<=1,"missing measured density");
        auto repeat=rf::bakePhotographicAtlas(mesh,images,dense.maps,photo,settings);
        check(repeat.atlasRgba==baked.atlasRgba&&repeat.indices==baked.indices,"nondeterministic compact bake");
        std::cout<<"compact atlas "<<baked.atlasWidth<<'x'<<baked.atlasHeight<<" support="<<baked.supportedTexels<<" unknown="<<baked.unsupportedTexels<<" utilization="<<baked.atlasUtilization<<" density="<<baked.texelsPerUnit<<'\n';
    });
#if defined(__unix__) || defined(__ANDROID__)
    test("PNG write failure and cancellation preserve previous publication",[&]{
        const std::string path="rf-test-png-publication.png",glbPath="rf-test-glb-publication.glb";
        {std::ofstream previous(path,std::ios::binary);previous<<"previous";}
        {std::ofstream previous(glbPath,std::ios::binary);previous<<"previous";}
        rf::TexturedSurface atlas;atlas.atlasWidth=127;atlas.atlasHeight=89;atlas.atlasRgba.resize(127*89*4);
        for(std::size_t i=0;i<atlas.atlasRgba.size();++i)atlas.atlasRgba[i]=static_cast<std::uint8_t>(i*37);
        const auto triangle=mesh.triangles.front();atlas.vertices={{triangle.a,.1f,.1f},{triangle.b,.2f,.1f},{triangle.c,.1f,.2f}};atlas.indices={0,1,2};
        struct rlimit oldLimit{};check(getrlimit(RLIMIT_FSIZE,&oldLimit)==0,"getrlimit failed");
        auto limited=oldLimit;limited.rlim_cur=64;const auto handler=std::signal(SIGXFSZ,SIG_IGN);
        check(setrlimit(RLIMIT_FSIZE,&limited)==0,"cannot inject bounded file failure");
        bool failed=false;try{rf::writeTexturePng(atlas,path);}catch(const std::exception&){failed=true;}
        bool glbFailed=false;try{rf::writeTexturedGlb(mesh,atlas,glbPath);}catch(const std::exception&){glbFailed=true;}
        const bool restored=setrlimit(RLIMIT_FSIZE,&oldLimit)==0;std::signal(SIGXFSZ,handler);
        check(restored&&failed&&glbFailed,"file-size limit was not propagated");
        check(!std::filesystem::exists(path+".partial")&&!std::filesystem::exists(glbPath+".partial"),"failed temporary remained");
        std::atomic_bool stop{true};bool cancelled=false;
        try{rf::cancellation::Scope scope(&stop);rf::writeTexturePng(atlas,path);}catch(const rf::cancellation::Cancelled&){cancelled=true;}
        check(cancelled&&!std::filesystem::exists(path+".partial"),"cancelled PNG was published");
        cancelled=false;try{rf::cancellation::Scope scope(&stop);rf::writeTexturedGlb(mesh,atlas,glbPath);}catch(const rf::cancellation::Cancelled&){cancelled=true;}
        check(cancelled&&!std::filesystem::exists(glbPath+".partial"),"cancelled GLB was published");
        std::ifstream previous(path,std::ios::binary);std::string content((std::istreambuf_iterator<char>(previous)),{});
        check(content=="previous","write failure overwrote prior PNG artifact");std::filesystem::remove(path);
        std::ifstream previousGlb(glbPath,std::ios::binary);std::string previousContent((std::istreambuf_iterator<char>(previousGlb)),{});
        check(previousContent=="previous","write failure overwrote prior GLB artifact");std::filesystem::remove(glbPath);
    });
#endif
    test("compact exposure samples require exact co-visible depth support",[&]{
        std::vector<rf::DenseView> views(2);
        std::vector<rf::DenseDepthMap> maps(2);
        for(std::size_t camera=0;camera<2;++camera){
            auto& view=views[camera];view.width=32;view.height=32;view.fx=24;view.fy=24;view.cx=15.5;view.cy=15.5;
            view.pose={rf::identity(),{0,0,0}};view.rgba.resize(32*32*4);
            for(std::size_t pixel=0;pixel<32*32;++pixel){
                for(std::size_t channel=0;channel<3;++channel)view.rgba[pixel*4+channel]=camera?160:190;
                view.rgba[pixel*4+3]=255;
            }
            auto& map=maps[camera];map.width=32;map.height=32;map.depth.assign(32*32,5);map.confidence.assign(32*32,1);
        }
        rf::SurfaceMesh plane;
        for(int y=0;y<8;++y)for(int x=0;x<8;++x){
            const int px=4+3*x,py=4+3*y;
            rf::SurfaceVertex vertex;vertex.xyz={(px-15.5)*5/24.,(py-15.5)*5/24.,5};vertex.normal={0,0,-1};vertex.confidence=.9;
            plane.vertices.push_back(vertex);maps[1].depth[py*32+px]=2.5;
        }
        for(std::uint32_t y=0;y<7;++y)for(std::uint32_t x=0;x<7;++x){const auto a=y*8+x;
            plane.triangles.push_back({a,a+8,a+1,0});plane.triangles.push_back({a+1,a+8,a+9,0});}
        rf::TextureSettings settings;settings.compactCharts=true;
        const auto baked=rf::bakePhotographicAtlas(plane,views,maps,sources(views,1),settings);
        check(baked.supportedTexels>0,"visible reference photograph was lost");
        check(baked.exposure.acceptedSamples==0&&baked.exposure.supportedPairs==0,
              "nearby background depth incorrectly authorized an occluded radiometric sample");
        check(baked.exposure.gains[1]==std::array<double,3>{1,1,1},"unsupported camera changed radiometry");
    });
    std::cout<<done<<" texture cases passed\n";
}
