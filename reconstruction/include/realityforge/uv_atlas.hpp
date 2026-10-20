#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <numeric>
namespace rf {
struct UVPoint {double u{},v{};};
struct UVChart {
    std::size_t sourceFace{}; int x{},y{},width{},height{},padding{};
    std::array<UVPoint,3> local{},uv{};
    double worldArea{},worldWidth{},worldHeight{};
};
struct UVAtlasSettings {int edge=2048,padding=3;std::size_t maxFaces=80000;double requestedTexelsPerUnit=128.;};
struct UVAtlasLayout {
    int width{},height{}; std::vector<UVChart> charts;
    double texelsPerUnit{},rectangleUtilization{},surfaceUtilization{};
};
// Every observed triangle is an isometric chart. This intentionally trades
// chart count for zero parameterization stretch and preservation of every gap,
// component and sharp boundary. Pixel centers and upper-left V follow glTF.
inline UVAtlasLayout layoutSurfaceCharts(const SurfaceMesh& mesh,const UVAtlasSettings& s={}) {
    if((s.edge!=2048&&s.edge!=4096)||s.padding<2||s.padding>16||s.maxFaces>80000||
       mesh.triangles.empty()||mesh.triangles.size()>s.maxFaces||!std::isfinite(s.requestedTexelsPerUnit)||s.requestedTexelsPerUnit<=0||s.requestedTexelsPerUnit>100000)
        throw Error("Invalid UV chart budget");
    UVAtlasLayout r;r.width=r.height=s.edge;r.charts.reserve(mesh.triangles.size());
    for(std::size_t face=0;face<mesh.triangles.size();++face){
        if((face&255u)==0)cancellation::checkpoint();
        const auto& t=mesh.triangles[face];
        if(t.a>=mesh.vertices.size()||t.b>=mesh.vertices.size()||t.c>=mesh.vertices.size())throw Error("Invalid UV face");
        const auto a=mesh.vertices[t.a].xyz,b=mesh.vertices[t.b].xyz,c=mesh.vertices[t.c].xyz;
        const Vec3 u{b.x-a.x,b.y-a.y,b.z-a.z},v{c.x-a.x,c.y-a.y,c.z-a.z};
        const double length=std::hypot(u.x,u.y,u.z), along=(u.x*v.x+u.y*v.y+u.z*v.z)/length;
        const Vec3 cross{u.y*v.z-u.z*v.y,u.z*v.x-u.x*v.z,u.x*v.y-u.y*v.x};
        const double altitude=std::hypot(cross.x,cross.y,cross.z)/length;
        if(!std::isfinite(length)||!std::isfinite(along)||!std::isfinite(altitude)||length<=0||altitude<=0)throw Error("Degenerate UV geometry");
        const double minX=std::min(0.,along),maxX=std::max(length,along);
        UVChart ch;ch.sourceFace=face;ch.padding=s.padding;ch.worldWidth=maxX-minX;ch.worldHeight=altitude;ch.worldArea=length*altitude*.5;
        ch.local={UVPoint{-minX,0},UVPoint{length-minX,0},UVPoint{along-minX,altitude}};
        r.charts.push_back(ch);
    }
    auto pack=[&](double scale,bool commit){
        std::vector<std::size_t> order(r.charts.size());std::iota(order.begin(),order.end(),0);
        std::stable_sort(order.begin(),order.end(),[&](auto a,auto b){return r.charts[a].worldHeight>r.charts[b].worldHeight;});
        int x=0,y=0,row=0;
        for(auto i:order){
            const auto& ch=r.charts[i];const double fw=std::ceil(ch.worldWidth*scale)+1+2*s.padding,fh=std::ceil(ch.worldHeight*scale)+1+2*s.padding;
            if(fw>s.edge||fh>s.edge)return false;
            const int w=static_cast<int>(fw),h=static_cast<int>(fh);
            if(x+w>s.edge){x=0;y+=row;row=0;}if(y+h>s.edge)return false;
            if(commit){auto& out=r.charts[i];out.x=x;out.y=y;out.width=w;out.height=h;}
            x+=w;row=std::max(row,h);
        }
        return true;
    };
    if(!pack(0,false))throw Error("Chart gutters exceed atlas budget");
    double lo=0,hi=s.requestedTexelsPerUnit;
    if(pack(hi,false))lo=hi;else for(int pass=0;pass<48;++pass){cancellation::checkpoint();const double mid=(lo+hi)*.5;if(pack(mid,false))lo=mid;else hi=mid;}
    r.texelsPerUnit=lo;if(lo<=0)throw Error("No supported atlas density");
    if(!pack(lo,true))throw Error("Unstable UV packing");
    double rect=0,area=0;
    for(auto& ch:r.charts){
        for(int k=0;k<3;++k)ch.uv[k]={(ch.x+s.padding+.5+ch.local[k].u*lo)/s.edge,(ch.y+s.padding+.5+ch.local[k].v*lo)/s.edge};
        rect+=static_cast<double>(ch.width)*ch.height;area+=ch.worldArea*lo*lo;
    }
    r.rectangleUtilization=rect/(static_cast<double>(s.edge)*s.edge);r.surfaceUtilization=area/(static_cast<double>(s.edge)*s.edge);return r;
}
inline UVPoint chartTexel(const UVChart& chart,const std::array<double,3>& barycentric){
    double total=0;UVPoint p;
    for(int i=0;i<3;++i){if(!std::isfinite(barycentric[i])||barycentric[i]<0||barycentric[i]>1)throw Error("Invalid UV barycentric coordinate");total+=barycentric[i];p.u+=chart.uv[i].u*barycentric[i];p.v+=chart.uv[i].v*barycentric[i];}
    if(std::abs(total-1)>1e-8)throw Error("Invalid barycentric sum");
    return p;
}
}
