#pragma once
#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <cmath>
#include <limits>
#include <map>
#include <numeric>
#include <set>
namespace rf {
struct UVPoint {double u{},v{};};
struct UVChart {
    std::size_t sourceFace{},chartId{}; int x{},y{},width{},height{},padding{};
    std::array<UVPoint,3> local{},uv{};
    double worldArea{},worldWidth{},worldHeight{},maximumStretch{};
};
struct UVChartGroup {
    std::vector<std::size_t> faces;
    int x{},y{},width{},height{};
    double worldWidth{},worldHeight{};
};
struct UVAtlasSettings {
    int edge=2048,padding=3;std::size_t maxFaces=80000,maxChartFaces=64;
    double requestedTexelsPerUnit=128.,maximumProjectionStretch=0.;
};
struct UVAtlasLayout {
    int width{},height{};std::vector<UVChart> charts;std::vector<UVChartGroup> groups;
    double texelsPerUnit{},rectangleUtilization{},surfaceUtilization{},maximumStretch{};
};
namespace uv_detail {
inline Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline double norm(Vec3 a){return std::hypot(a.x,a.y,a.z);}
inline Vec3 unit(Vec3 a){const double n=norm(a);if(!std::isfinite(n)||n<=0)throw Error("Degenerate UV axis");return {a.x/n,a.y/n,a.z/n};}
inline std::array<std::uint32_t,3> ids(const SurfaceTriangle& t){return {t.a,t.b,t.c};}
// Positive-area overlap test; shared edges/vertices do not count as overlap.
inline bool overlap(const std::array<UVPoint,3>& a,const std::array<UVPoint,3>& b,double epsilon){
    for(const auto& triangle:{a,b})for(int e=0;e<3;++e){
        const double nx=triangle[(e+1)%3].v-triangle[e].v,ny=triangle[e].u-triangle[(e+1)%3].u;
        const double length=std::hypot(nx,ny);if(length==0)continue;
        double alo=INFINITY,ahi=-INFINITY,blo=INFINITY,bhi=-INFINITY;
        for(const auto& p:a){const double v=(p.u*nx+p.v*ny)/length;alo=std::min(alo,v);ahi=std::max(ahi,v);}
        for(const auto& p:b){const double v=(p.u*nx+p.v*ny)/length;blo=std::min(blo,v);bhi=std::max(bhi,v);}
        if(std::min(ahi,bhi)-std::max(alo,blo)<=epsilon)return false;
    }
    return true;
}
}
// Bounded manifold region growth merges consistently wound observed faces.
// Every proposed union is checked for positive-area self overlap. Curved/sharp,
// disconnected and nonmanifold regions fall back to separate isometric charts.
// Optional maximumProjectionStretch permits curved charts only within checked
// per-edge inverse-projection stretch (plus1e-8 numerical tolerance). Default0
// keeps exact coplanarity; actual geometry is never displaced.
inline UVAtlasLayout layoutSurfaceCharts(const SurfaceMesh& mesh,const UVAtlasSettings& s={}) {
    using namespace uv_detail;
    if((s.edge!=2048&&s.edge!=4096)||s.padding<2||s.padding>16||s.maxFaces>80000||s.maxChartFaces<1||s.maxChartFaces>256||
       mesh.triangles.empty()||mesh.triangles.size()>s.maxFaces||!std::isfinite(s.maximumProjectionStretch)||s.maximumProjectionStretch<0||s.maximumProjectionStretch>.05||!std::isfinite(s.requestedTexelsPerUnit)||s.requestedTexelsPerUnit<=0||s.requestedTexelsPerUnit>100000)
        throw Error("Invalid UV chart budget");
    UVAtlasLayout r;r.width=r.height=s.edge;r.charts.resize(mesh.triangles.size());
    struct Edge {std::size_t first{},second{},count{};};
    std::map<std::pair<std::uint32_t,std::uint32_t>,Edge> edges;
    std::vector<Vec3> normals(mesh.triangles.size());std::vector<std::array<UVPoint,3>> provisional(mesh.triangles.size());
    for(std::size_t face=0;face<mesh.triangles.size();++face){
        if((face&255u)==0)cancellation::checkpoint();
        const auto id=ids(mesh.triangles[face]);
        for(auto i:id)if(i>=mesh.vertices.size())throw Error("Invalid UV face");
        const auto a=mesh.vertices[id[0]].xyz,b=mesh.vertices[id[1]].xyz,c=mesh.vertices[id[2]].xyz;
        const auto raw=cross(sub(b,a),sub(c,a));normals[face]=unit(raw);
        auto& ch=r.charts[face];ch.sourceFace=face;ch.worldArea=norm(raw)*.5;ch.padding=s.padding;
        for(int k=0;k<3;++k){const auto key=std::minmax(id[k],id[(k+1)%3]);auto& edge=edges[{key.first,key.second}];if(edge.count==0)edge.first=face;else if(edge.count==1)edge.second=face;++edge.count;}
    }
    std::vector<std::vector<std::size_t>> adjacency(mesh.triangles.size());
    for(const auto& [key,edge]:edges){(void)key;if(edge.count==2){adjacency[edge.first].push_back(edge.second);adjacency[edge.second].push_back(edge.first);}}
    for(auto& neighbors:adjacency)std::sort(neighbors.begin(),neighbors.end());
    std::vector<bool> assigned(mesh.triangles.size());
    for(std::size_t seed=0;seed<mesh.triangles.size();++seed){
        if(assigned[seed])continue;
        cancellation::checkpoint();
        UVChartGroup group;const auto seedIds=ids(mesh.triangles[seed]);const auto origin=mesh.vertices[seedIds[0]].xyz;
        const auto axis=unit(sub(mesh.vertices[seedIds[1]].xyz,origin)),tangent=cross(normals[seed],axis);
        const double scale=std::sqrt(r.charts[seed].worldArea),epsilon=std::max(1e-12,scale*1e-9);
        auto coordinates=[&](std::size_t face){std::array<UVPoint,3> out{};const auto faceIds=ids(mesh.triangles[face]);for(int k=0;k<3;++k){const auto v=sub(mesh.vertices[faceIds[k]].xyz,origin);out[k]={dot(v,axis),dot(v,tangent)};}return out;};
        group.faces.push_back(seed);assigned[seed]=true;provisional[seed]=coordinates(seed);
        for(std::size_t next=0;next<group.faces.size()&&group.faces.size()<s.maxChartFaces;++next){
            for(auto candidate:adjacency[group.faces[next]]){
                if(assigned[candidate]||group.faces.size()>=s.maxChartFaces||dot(normals[seed],normals[candidate])<1/(1+s.maximumProjectionStretch)-1e-10)continue;
                bool coplanar=true;for(auto id:ids(mesh.triangles[candidate]))coplanar=coplanar&&std::abs(dot(sub(mesh.vertices[id].xyz,origin),normals[seed]))<=epsilon;
                if(!coplanar&&s.maximumProjectionStretch==0)continue;
                const auto proposed=coordinates(candidate);bool overlaps=false;
                const auto candidateIds=ids(mesh.triangles[candidate]);bool bounded=true;
                for(int k=0;k<3;++k){const double worldLength=norm(sub(mesh.vertices[candidateIds[k]].xyz,mesh.vertices[candidateIds[(k+1)%3]].xyz));const double uvLength=std::hypot(proposed[k].u-proposed[(k+1)%3].u,proposed[k].v-proposed[(k+1)%3].v);bounded=bounded&&uvLength>0&&worldLength/uvLength<=1+s.maximumProjectionStretch+1e-9;}
                if(!bounded)continue;
                for(auto existing:group.faces)if(overlap(proposed,provisional[existing],epsilon)){overlaps=true;break;}
                if(overlaps)continue;
                assigned[candidate]=true;provisional[candidate]=proposed;group.faces.push_back(candidate);
            }
        }
        // Align the final common isometric basis to a boundary edge, avoiding
        // the diamond bounding boxes produced by using a quad's diagonal.
        std::set<std::size_t> members(group.faces.begin(),group.faces.end());
        std::pair<std::uint32_t,std::uint32_t> longest{seedIds[0],seedIds[1]};double maximum=0;
        for(auto face:group.faces){const auto faceIds=ids(mesh.triangles[face]);for(int k=0;k<3;++k){const auto key=std::minmax(faceIds[k],faceIds[(k+1)%3]);const auto& edge=edges.at({key.first,key.second});if(edge.count==2&&members.contains(edge.first)&&members.contains(edge.second))continue;const double length=norm(sub(mesh.vertices[key.second].xyz,mesh.vertices[key.first].xyz));if(length>maximum){maximum=length;longest={key.first,key.second};}}}
        const auto base=mesh.vertices[longest.first].xyz,edge=sub(mesh.vertices[longest.second].xyz,base);
        const auto normal=normals[seed];const double normalPart=dot(edge,normal);
        const auto finalAxis=unit(Vec3{edge.x-normal.x*normalPart,edge.y-normal.y*normalPart,edge.z-normal.z*normalPart}),finalTangent=cross(normal,finalAxis);
        double minX=INFINITY,minY=INFINITY,maxX=-INFINITY,maxY=-INFINITY;
        for(auto face:group.faces){auto& chart=r.charts[face];chart.chartId=r.groups.size();const auto faceIds=ids(mesh.triangles[face]);for(int k=0;k<3;++k){const auto v=sub(mesh.vertices[faceIds[k]].xyz,base);chart.local[k]={dot(v,finalAxis),dot(v,finalTangent)};minX=std::min(minX,chart.local[k].u);maxX=std::max(maxX,chart.local[k].u);minY=std::min(minY,chart.local[k].v);maxY=std::max(maxY,chart.local[k].v);}}
        group.worldWidth=maxX-minX;group.worldHeight=maxY-minY;
        if(!std::isfinite(group.worldWidth)||!std::isfinite(group.worldHeight)||group.worldWidth<=0||group.worldHeight<=0)throw Error("Invalid UV chart extent");
        for(auto face:group.faces){auto& chart=r.charts[face];chart.worldWidth=group.worldWidth;chart.worldHeight=group.worldHeight;for(auto& p:chart.local){p.u-=minX;p.v-=minY;}
            const auto faceIds=ids(mesh.triangles[face]);
            for(int k=0;k<3;++k){const double worldLength=norm(sub(mesh.vertices[faceIds[k]].xyz,mesh.vertices[faceIds[(k+1)%3]].xyz));const double uvLength=std::hypot(chart.local[k].u-chart.local[(k+1)%3].u,chart.local[k].v-chart.local[(k+1)%3].v);if(uvLength<=0||worldLength/uvLength>1+s.maximumProjectionStretch+1e-8)throw Error("UV projection exceeds declared stretch");chart.maximumStretch=std::max(chart.maximumStretch,std::max(0.,worldLength/uvLength-1));}
            r.maximumStretch=std::max(r.maximumStretch,chart.maximumStretch);
        }
        r.groups.push_back(std::move(group));
    }
    std::vector<std::size_t> order(r.groups.size());std::iota(order.begin(),order.end(),0);
    std::stable_sort(order.begin(),order.end(),[&](auto a,auto b){return r.groups[a].worldHeight>r.groups[b].worldHeight;});
    auto pack=[&](double density,bool commit){
        int x=0,y=0,row=0;
        for(auto i:order){const auto& g=r.groups[i];const double fw=std::ceil(g.worldWidth*density)+1+2*s.padding,fh=std::ceil(g.worldHeight*density)+1+2*s.padding;if(fw>s.edge||fh>s.edge)return false;const int w=static_cast<int>(fw),h=static_cast<int>(fh);if(x+w>s.edge){x=0;y+=row;row=0;}if(y+h>s.edge)return false;if(commit){auto& out=r.groups[i];out.x=x;out.y=y;out.width=w;out.height=h;}x+=w;row=std::max(row,h);}return true;
    };
    if(!pack(0,false))throw Error("Chart gutters exceed atlas budget");
    double lo=0,hi=s.requestedTexelsPerUnit;if(pack(hi,false))lo=hi;else for(int pass=0;pass<48;++pass){cancellation::checkpoint();const double mid=(lo+hi)*.5;if(pack(mid,false))lo=mid;else hi=mid;}
    r.texelsPerUnit=lo;if(lo<=0||!pack(lo,true))throw Error("No supported atlas density");
    double rectangleArea=0,surfaceArea=0;
    for(const auto& g:r.groups)rectangleArea+=static_cast<double>(g.width)*g.height;
    for(auto& ch:r.charts){const auto& g=r.groups[ch.chartId];ch.x=g.x;ch.y=g.y;ch.width=g.width;ch.height=g.height;for(int k=0;k<3;++k)ch.uv[k]={(g.x+s.padding+.5+ch.local[k].u*lo)/s.edge,(g.y+s.padding+.5+ch.local[k].v*lo)/s.edge};surfaceArea+=std::abs((ch.local[1].u-ch.local[0].u)*(ch.local[2].v-ch.local[0].v)-(ch.local[1].v-ch.local[0].v)*(ch.local[2].u-ch.local[0].u))*.5*lo*lo;}
    r.rectangleUtilization=rectangleArea/(static_cast<double>(s.edge)*s.edge);r.surfaceUtilization=surfaceArea/(static_cast<double>(s.edge)*s.edge);return r;
}
inline UVPoint chartTexel(const UVChart& chart,const std::array<double,3>& barycentric){
    double total=0;UVPoint p;for(int i=0;i<3;++i){if(!std::isfinite(barycentric[i])||barycentric[i]<0||barycentric[i]>1)throw Error("Invalid UV barycentric coordinate");total+=barycentric[i];p.u+=chart.uv[i].u*barycentric[i];p.v+=chart.uv[i].v*barycentric[i];}if(std::abs(total-1)>1e-8)throw Error("Invalid barycentric sum");return p;
}
}
