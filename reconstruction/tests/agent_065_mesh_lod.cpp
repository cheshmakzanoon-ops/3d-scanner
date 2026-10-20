#include "realityforge/mesh_lod.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <set>
#include <limits>
using namespace rf;using namespace rf::asset;
struct Fixture {
    std::vector<Vec3> positions;std::vector<std::uint32_t> indices;std::vector<Vec2> uv;
    SurfaceAsset view()const{PointAsset p;p.positions=positions;p.representation=Representation::ObservedSurface;
        p.provenance={"synthetic-grid","",std::string(64,'a'),"analytic-v1"};return {p,indices};}
};
Fixture grid(double offset=0){Fixture f;for(int y=0;y<5;++y)for(int x=0;x<5;++x){f.positions.push_back({x+offset,double(y),0});f.uv.push_back({x/4.0,y/4.0});}
    for(std::uint32_t y=0;y<4;++y)for(std::uint32_t x=0;x<4;++x){auto a=y*5+x;for(auto v:{a,a+1,a+5,a+1,a+6,a+5})f.indices.push_back(v);}
    return f;}
template<class F>void fails(F fn){bool failed=false;try{fn();}catch(const Error&){failed=true;}assert(failed);}
std::size_t components(const SurfaceAsset& a){std::vector<unsigned> groups(a.vertices.positions.size());for(unsigned i=0;i<groups.size();++i)groups[i]=i;
    auto root=[&](unsigned x){while(groups[x]!=x)x=groups[x];return x;};for(std::size_t i=0;i<a.indices.size();i+=3)for(int k=1;k<3;++k)groups[root(a.indices[i+k])]=root(a.indices[i]);
    std::set<unsigned> ids;for(auto i:a.indices)ids.insert(root(i));return ids.size();}
int main(){
    auto f=grid();const auto originals=f.positions;const auto triangles=f.indices;
    LodRecipe r;r.targetTriangles=24;r.maximumDisplacement=1.5;
    auto lod=simplifySurface(f.view(),r);assert(lod.geometry.indices.size()/3==24);assert(lod.report.collapsedEdges==4);
    assert(lod.report.maximumVertexDisplacement<=1.5);assert(components(lod.geometry.surface())==1);
    for(auto p:originals)if(p.x==0||p.x==4||p.y==0||p.y==4){bool found=false;for(auto q:lod.geometry.positions)found|=q.x==p.x&&q.y==p.y&&q.z==p.z;assert(found);}
    auto same=simplifySurface(f.view(),r);assert(lod.geometry.indices==same.geometry.indices);assert(f.indices==triangles);
    for(std::size_t i=0;i<originals.size();++i)assert(f.positions[i].x==originals[i].x&&f.positions[i].y==originals[i].y);
    assert(lod.geometry.provenance.parentId=="synthetic-grid");assert(lod.geometry.provenance.assetId.empty());
    auto g=grid(10);auto disconnected=f;disconnected.positions.insert(disconnected.positions.end(),g.positions.begin(),g.positions.end());
    for(auto i:g.indices)disconnected.indices.push_back(i+25);
    r.targetTriangles=48;
    auto two=simplifySurface(disconnected.view(),r);assert(components(two.geometry.surface())==2);
    r.targetTriangles=24;r.maximumUvDisplacement=.3;auto textured=simplifySurface(f.view(),r,f.uv);assert(textured.report.maximumUvDisplacement<=.3);assert(textured.geometry.uv.size()==textured.geometry.positions.size());
    r.maximumUvDisplacement=0;fails([&]{simplifySurface(f.view(),r,f.uv);});
    r.maximumDisplacement=.1;fails([&]{simplifySurface(f.view(),r);});
    r.maximumDisplacement=1.5;fails([&]{simplifySurface(f.view(),r,{},[]{return true;});});
    r.maximumWork=10;fails([&]{simplifySurface(f.view(),r);});r.maximumWork=20'000'000;
    auto malformed=f;malformed.positions[0].x=std::numeric_limits<double>::infinity();fails([&]{simplifySurface(malformed.view(),r);});
    auto tiny=f;tiny.indices={0,1,5};r.targetTriangles=1;auto unchanged=simplifySurface(tiny.view(),r);assert(unchanged.geometry.indices.size()==3);
    Fixture oct;oct.positions={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    oct.indices={0,2,4,2,1,4,1,3,4,3,0,4,2,0,5,1,2,5,3,1,5,0,3,5};
    r.targetTriangles=6;r.minimumNormalCosine=.1;r.maximumDisplacement=1.5;auto closed=simplifySurface(oct.view(),r);assert(closed.geometry.indices.size()==18);assert(components(closed.geometry.surface())==1);
    std::cout<<"agent065: deterministic open/closed/disconnected/UV/error/budget/cancellation checks passed\n";
}
