#include "realityforge/texture.hpp"
#include "realityforge/uv_atlas.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
int main(int argc,char** argv){
 if(argc!=2)throw std::runtime_error("output path required");
 std::vector<rf::DenseView> views(2);std::vector<rf::DenseDepthMap> maps(2);std::vector<rf::TextureSource> photos(2);
 for(int camera=0;camera<2;++camera){
  auto& v=views[camera];v.width=96;v.height=80;v.fx=v.fy=40;v.cx=47.5;v.cy=39.5;v.pose={rf::identity(),{0,0,0}};v.rgba.resize(96*80*4);
  for(int y=0;y<80;++y)for(int x=0;x<96;++x){auto i=(y*96+x)*4;v.rgba[i]=x<48?220:20;v.rgba[i+1]=x<48?20:220;v.rgba[i+2]=20;v.rgba[i+3]=(x>=35&&x<=36&&y>=38&&y<=40)?0:255;}
  auto& d=maps[camera];d.width=96;d.height=80;d.depth.assign(96*80,5);d.confidence.assign(96*80,1);
  auto& p=photos[camera];p.width=v.width;p.height=v.height;p.fx=v.fx;p.fy=v.fy;p.cx=v.cx;p.cy=v.cy;p.pose=v.pose;p.rgba=v.rgba;
 }
 rf::SurfaceMesh mesh;
 for(int patch=0;patch<2;++patch){double x=patch?1:-2;std::uint32_t start=mesh.vertices.size();for(auto p:{rf::Vec3{x,-1,5},rf::Vec3{x+1,-1,5},rf::Vec3{x,1,5},rf::Vec3{x+1,1,5}}){rf::SurfaceVertex v;v.xyz=p;v.normal={0,0,-1};v.confidence=1;mesh.vertices.push_back(v);}mesh.triangles.push_back({start,start+2,start+1,0});mesh.triangles.push_back({start+1,start+2,start+3,0});}
 rf::TextureSettings settings;settings.compactCharts=true;settings.chartTexelsPerUnit=8;settings.balanceColors=false;settings.maximumUvStretch=0;
 const auto baked=rf::bakePhotographicAtlas(mesh,views,maps,photos,settings);rf::writeTexturePng(baked,std::string(argv[1])+".png");
 std::ofstream out(std::string(argv[1])+".json");out<<"{\"width\":"<<baked.atlasWidth<<",\"height\":"<<baked.atlasHeight<<",\"density\":"<<baked.texelsPerUnit<<",\"uv\":[";
 bool first=true;for(auto v:baked.vertices){if(!first)out<<',';first=false;out<<'['<<v.u<<','<<v.v<<']';}out<<"],\"layouts\":[";
 first=true;for(int edge:{2048,4096}){rf::UVAtlasSettings s;s.edge=edge;s.requestedTexelsPerUnit=8;auto l=rf::layoutSurfaceCharts(mesh,s);if(!first)out<<',';first=false;out<<"{\"edge\":"<<edge<<",\"groups\":[";bool f=true;for(auto g:l.groups){if(!f)out<<',';f=false;out<<'['<<g.x<<','<<g.y<<','<<g.width<<','<<g.height<<']';}out<<"]}";}out<<"]}\n";
 std::cout<<"actual frozen bake "<<baked.atlasWidth<<'x'<<baked.atlasHeight<<" supported="<<baked.supportedTexels<<" unknown="<<baked.unsupportedTexels<<'\n';
}
