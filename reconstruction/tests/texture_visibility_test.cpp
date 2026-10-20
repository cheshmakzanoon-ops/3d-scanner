#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_visibility.hpp"
#include <cassert>
int main(){
 rf::TextureSource p;p.width=p.height=32;p.fx=p.fy=20;p.cx=p.cy=15.5;p.pose={rf::identity(),{0,0,0}};p.rgba.assign(32*32*4,255);
 rf::DenseView v;v.width=v.height=32;rf::DenseDepthMap d{32,32,std::vector<float>(1024,5),std::vector<float>(1024,1)};
 rf::TextureProjection out;assert(rf::projectVisibleTexel(p,v,d,{0,0,5},.075,out));
 d.depth[16*32+16]=2;assert(!rf::projectVisibleTexel(p,v,d,{0,0,5},.075,out));
 d.depth[16*32+16]=0;assert(!rf::projectVisibleTexel(p,v,d,{0,0,5},.075,out));
 d.depth[16*32+16]=5;p.rgba[(15*32+15)*4+3]=0;assert(!rf::projectVisibleTexel(p,v,d,{0,0,5},.075,out));
}
