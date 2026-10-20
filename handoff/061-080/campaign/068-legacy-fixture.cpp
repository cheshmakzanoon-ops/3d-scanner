#include "realityforge/core.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
int main(){
 rf::Result p;for(auto xyz:{rf::Vec3{-2,-1,3},rf::Vec3{4,-1,3},rf::Vec3{-2,5,3}}){rf::Point v;v.xyz=xyz;v.color={255,0,0};p.points.push_back(v);}rf::writeGlb(p,"/workspace/campaign-061-080/061-fixtures/legacy-sparse.glb");
 rf::SurfaceMesh mesh;for(const auto& v:p.points){rf::SurfaceVertex vertex;vertex.xyz=v.xyz;vertex.normal={0,0,1};vertex.color=v.color;vertex.confidence=1;vertex.supportingViews=2;mesh.vertices.push_back(vertex);}mesh.triangles.push_back({0,1,2,0});rf::writeSurfaceGlb(mesh,"/workspace/campaign-061-080/061-fixtures/legacy-surface.glb");
 rf::TexturedSurface t;t.atlasWidth=2;t.atlasHeight=2;t.atlasRgba={255,0,0,255,0,255,0,255,0,0,255,255,255,255,0,255};t.vertices={{0,0,0},{1,1,0},{2,0,1}};t.indices={0,1,2};rf::writeTexturedGlb(mesh,t,"/workspace/campaign-061-080/061-fixtures/legacy-texture.glb");
}
