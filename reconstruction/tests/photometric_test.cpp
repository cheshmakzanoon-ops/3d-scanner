#include "realityforge/photometric.hpp"
#include "realityforge/texture.hpp"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>

namespace {
void check(bool test,const char* message) { if(!test) throw std::runtime_error(message); }
struct Scene {rf::SurfaceMesh mesh; std::vector<rf::DenseView> views; std::vector<rf::DenseDepthMap> maps; std::vector<rf::TextureSource> photos;};
Scene plane() {
    Scene scene;
    for (int camera=0;camera<2;++camera) {
        rf::DenseView view;view.width=104;view.height=76;view.fx=view.fy=116;view.cx=51.5;view.cy=37.5;
        view.pose={rf::identity(),{-.55*camera,0,0}};
        view.rgba.resize(104*76*4,255);
        rf::TextureSource photo{104,76,116,116,51.5,37.5,view.pose,view.rgba};
        for(int y=0;y<76;++y)for(int x=0;x<104;++x)for(int k=0;k<3;++k) {
            double wx=(x-51.5)*5./116+.55*camera,wy=(y-37.5)*5./116;
            const double radiance=.20+.015*wx+.01*wy+.01*k;
            photo.rgba[(y*104+x)*4+k]=static_cast<std::uint8_t>(std::round(255*rf::linearToSrgb(radiance*(camera?1.4:1.))));
        }
        scene.views.push_back(view);scene.photos.push_back(photo);
        scene.maps.push_back({104,76,std::vector<float>(104*76,5),std::vector<float>(104*76,1)});
    }
    for(int y=0;y<7;++y)for(int x=0;x<9;++x) {
        rf::SurfaceVertex v;v.xyz={-.8+x*.2,-.6+y*.2,5};v.normal={0,0,-1};v.confidence=1;v.supportingViews=2;
        scene.mesh.vertices.push_back(v);
    }
    for(int y=0;y<6;++y)for(int x=0;x<8;++x) {
        auto a=static_cast<std::uint32_t>(y*9+x),b=a+1,c=a+9,d=c+1;
        auto camera=static_cast<std::uint8_t>((x+y)%2);
        scene.mesh.triangles.push_back({a,c,b,camera});scene.mesh.triangles.push_back({b,c,d,camera});
    }
    return scene;
}
}
int main() {
    int count=0;
    auto run=[&](const char* label,auto test) {test();++count;std::cout<<"PASS "<<label<<'\n';};
    run("linear-sRGB byte roundtrip",[]{
        for (int value=0;value<=255;++value)
            check(std::abs(rf::linearToSrgb(rf::srgbToLinear(value/255.))*255-value)<1e-9,"sRGB roundtrip");
    });
    run("robust RGB exposure recovery with outliers",[]{
        std::vector<rf::ExposureSample> samples;
        for(int i=0;i<100;++i) {
            double x=.10+.004*i;
            samples.push_back({0,1,{x,x,x},{x*1.4,x*.8,x*1.1}});
            if(i<15) samples.back().b={.08,.16,.3};
        }
        auto r=rf::balanceExposure(2,samples);
        check(r.supportedPairs==1&&r.acceptedSamples==100,"missing measured samples");
        check(std::abs(r.gains[1][0]-1/1.4)<1e-8,"red correction");
        check(std::abs(r.gains[1][1]-1/.8)<1e-8,"green correction");
        check(r.medianLogErrorAfter<r.medianLogErrorBefore*.05,"no robust improvement");
        check(r.gains[0]==std::array<double,3>{1,1,1},"exposure gauge moved");
    });
    run("disconnected and under-supported views stay identity",[]{
        std::vector<rf::ExposureSample> samples;
        for(int i=0;i<40;++i) samples.push_back({2,3,{.2,.2,.2},{.3,.3,.3}});
        for(int i=0;i<15;++i) samples.push_back({0,1,{.2,.2,.2},{.3,.3,.3}});
        auto r=rf::balanceExposure(4,samples);
        for(auto g:r.gains) check(g==std::array<double,3>{1,1,1},"unsupported gain introduced");
    });
    run("gain limits and invalid samples",[]{
        std::vector<rf::ExposureSample> samples(40,{0,1,{.02,.02,.02},{.8,.8,.8}});
        auto r=rf::balanceExposure(2,samples);
        check(r.gains[1][0]==.5,"gain must be bounded");
        samples[0].a[0]=std::numeric_limits<double>::quiet_NaN();bool rejected=false;
        try{rf::balanceExposure(2,samples);}catch(const rf::Error&){rejected=true;}
        check(rejected,"NaN not rejected");
    });
    auto scene=plane();
    run("fronto-parallel mesh retains every visible triangle",[&]{
        auto t=rf::bakePhotographicAtlas(scene.mesh,scene.views,scene.maps,scene.photos);
        check(t.indices.size()==scene.mesh.triangles.size()*3,"complete cross product required");
        check(t.medianFacing>.9&&t.faceCoverage==1,"incorrect face normal rejected valid surface");
        check(t.exposure.acceptedSamples>=32,"no image-derived exposure evidence");
        check(std::abs(t.exposure.gains[1][0]-1/1.4)<.03,"image-space gain mismatch");
        const auto before=scene.photos;
        auto again=rf::bakePhotographicAtlas(scene.mesh,scene.views,scene.maps,scene.photos);
        check(t.indices==again.indices&&t.atlasRgba==again.atlasRgba,"nondeterministic bake");
        for(std::size_t i=0;i<before.size();++i)check(before[i].rgba==scene.photos[i].rgba,"original modified");
    });
    run("glTF UV origin matches asymmetric top-down pixels",[&]{
        rf::TextureSettings settings;settings.balanceColors=false;settings.seamPenalty=0;
        auto t=rf::bakePhotographicAtlas(scene.mesh,scene.views,scene.maps,scene.photos,settings);
        for (auto uv:t.vertices) {
            const auto p=scene.mesh.vertices[uv.surfaceVertex].xyz;
            const double atlasX=uv.u*t.atlasWidth-.5,atlasY=uv.v*t.atlasHeight-.5;
            const int cam=static_cast<int>(atlasX/110);
            const double expectedY=p.y/p.z*116+37.5+3;
            const double expectedX=(p.x-.55*cam)/p.z*116+51.5+3+cam*110;
            check(std::abs(atlasY-expectedY)<1e-4,"UV V is flipped");
            check(std::abs(atlasX-expectedX)<1e-4,"UV U mismatch");
        }
    });
    run("view coherence reduces seams without dropping faces",[&]{
        rf::TextureSettings baseline;baseline.seamPenalty=0;
        auto before=rf::bakePhotographicAtlas(scene.mesh,scene.views,scene.maps,scene.photos,baseline);
        auto after=rf::bakePhotographicAtlas(scene.mesh,scene.views,scene.maps,scene.photos);
        check(before.indices.size()==after.indices.size(),"smoother removed geometry");
        check(after.viewSwitchEdges<before.viewSwitchEdges,"no seam reduction");
        check(after.reassignedFaces>0,"no visible labels changed");
        std::cout<<"seam edges "<<before.viewSwitchEdges<<" -> "<<after.viewSwitchEdges<<'\n';
    });
    run("mask and backface exclusions survive smoothing",[&]{
        auto modified=scene;
        for(std::size_t p=3;p<modified.photos[1].rgba.size();p+=4) modified.photos[1].rgba[p]=0;
        auto t=rf::bakePhotographicAtlas(modified.mesh,modified.views,modified.maps,modified.photos);
        check(t.sourceTriangleCounts[1]==0,"masked camera chosen by smoothing");
        for(auto& tri:modified.mesh.triangles)std::swap(tri.b,tri.c);
        bool rejected=false;
        try{rf::bakePhotographicAtlas(modified.mesh,modified.views,modified.maps,modified.photos);}catch(const rf::Error&){rejected=true;}
        check(rejected,"backfaces textured");
    });
    std::cout<<count<<" photometric/UV regression cases passed\n";
}
