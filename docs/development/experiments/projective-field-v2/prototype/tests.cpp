#include "global_field.hpp"
#include <iostream>
#include <set>
using namespace rf;using namespace rf::experimental_projective;
trace_test::Snapshot fixture(bool slope=false,bool gap=false,double secondOffset=0){
 trace_test::Snapshot s;s.depthIdentity=std::string(64,'0');s.views.resize(2);s.dense.maps.resize(2);
 for(unsigned c=0;c<2;c++){
  auto& v=s.views[c];v.width=v.height=64;v.fx=v.fy=80;v.cx=v.cy=31.5;v.pose.rotation={1,0,0,0,1,0,0,0,1};v.rgba.resize(64*64*4,255);
  auto& m=s.dense.maps[c];m.width=m.height=64;m.depth.resize(4096);m.confidence.resize(4096);
  for(unsigned y=3;y<61;y+=3)for(unsigned x=3;x<61;x+=3){if(gap&&x>=27&&x<=36)continue;
   double rx=(double(x)-v.cx)/v.fx,ry=(double(y)-v.cy)/v.fy;double z=(2.13+(c?secondOffset:0))/(1-(slope?.2:0)*rx);
   // Float camera-z maps are authoritative; xyz matches precisely those depths.
   float fz=float(z);VerifiedDepthSample p;p.xyz={rx*fz,ry*fz,fz};p.camera=c;p.x=x;p.y=y;p.confidence=1;p.supportingViews=2;m.depth[y*64+x]=fz;m.confidence[y*64+x]=1;s.dense.verifiedSamples.push_back(p);
  }
 }
 return s;
}
void check(Builder& b){auto& w=*b.w;require(w.facesN>0,"test: expected faces");require(w.windingConflictEdges==0&&w.nonmanifoldEdges==0,"test: directed-edge winding/manifold contract");for(unsigned i=0;i<w.facesN;i++){auto& f=w.faces[i];for(auto v:f.vertex)require(v<w.verticesN&&b.witness(f.witness,w.vertices[v],w.pitch*.25),"test: whole face witness");}}
void rejected(const char* expected,auto fn){bool fail=false;try{fn();}catch(const Error& e){fail=std::string(e.what()).find(expected)!=std::string::npos;}require(fail,"test: expected specific fail-closed reason");}
int main(){try{
 for(bool slope:{false,true}){auto s=fixture(slope);Builder b(s);b.run();check(b);Builder c(s);c.run();require(b.w->verticesN==c.w->verticesN&&b.w->facesN==c.w->facesN,"test: deterministic counts");for(unsigned i=0;i<b.w->verticesN;i++){auto a=b.w->vertices[i],d=c.w->vertices[i];require(a.x==d.x&&a.y==d.y&&a.z==d.z,"test: deterministic vertices");}for(unsigned i=0;i<b.w->facesN;i++){auto a=b.w->faces[i],d=c.w->faces[i];require(a.witness==d.witness&&std::equal(a.vertex,a.vertex+3,d.vertex),"test: deterministic faces");}}
 {auto s=fixture(false,true);Builder b(s);b.run();check(b);for(unsigned i=0;i<b.w->facesN;i++){double lo=1e9,hi=-1e9;for(auto v:b.w->faces[i].vertex){lo=std::min(lo,b.w->vertices[v].x);hi=std::max(hi,b.w->vertices[v].x);}require(!(lo<0&&hi>0),"test: unknown gap bridge");}}
 {require(policy(-2,2)==VoteKind::Behind&&policy(-3,2)==VoteKind::Behind,"test: occluded no-vote branch");require(policy(2,2)==VoteKind::Near&&policy(0,2)==VoteKind::Near&&policy(-1,2)==VoteKind::Near,"test: actual near branch");require(policy(3,2)==VoteKind::Front,"test: free-space branch");
  Node freeOnly{};freeOnly.mask=1;freeOnly.distance[0]=1;require(!finalizeNode(freeOnly),"test: free-space cannot allocate surface node");Node mixed{};mixed.mask=3;mixed.nearMask=3;mixed.distance[0]=-.25f;mixed.distance[1]=.25f;require(finalizeNode(mixed)&&mixed.valid&&mixed.value==0,"test: legitimate mixed signs must average");Node occluded{};occluded.mask=1;occluded.nearMask=1;occluded.distance[0]=-.25f;occluded.distance[1]=-1;require(finalizeNode(occluded)&&occluded.value==-.25,"test: behind-camera excluded by mask");}
 {auto s=fixture();Builder b(s);b.prepare();double z=0;unsigned first=b.lookup(0,6,6,z);require(first<NP&&std::abs(z-double(float(2.13)))<1e-12,"test: canonical shared cell depth");double again=0;require(b.lookup(0,6,6,again)==first&&again==z,"test: canonical shared cell identity");double ignored=0;require(b.lookup(0,-1,6,ignored)==NP,"test: unknown projection");b.integrate();require(b.w->nodesN>0,"test: observed nodes");for(const auto& n:b.w->nodes)if(n.used){require(n.mask==3&&n.nearMask!=0,"test: once-per-camera mask");require(n.value==(double(n.distance[0])+n.distance[1])/2,"test: no repeated camera weighting");}}
 {auto s=fixture(true);s.views[0].pose.rotation[0]=1.001;for(auto& p:s.dense.verifiedSamples)if(p.camera==0)p.xyz.x/=1.001;Builder b(s);b.prepare();auto bounds=b.bandBounds(0);for(auto id:b.w->patches[0].source){const auto xyz=s.dense.verifiedSamples[id].xyz;require(xyz.x>=bounds[0].lo&&xyz.x<=bounds[0].hi&&xyz.y>=bounds[1].lo&&xyz.y<=bounds[1].hi&&xyz.z>=bounds[2].lo&&xyz.z<=bounds[2].hi,"test: tolerated nonorthonormal inverse allocation");}}
 {auto s=fixture(true);Builder b(s);b.prepare();double z=0;require(b.lookup(0,4.5,3,z)<NP,"test: observed interpolant edge");double a=s.dense.maps[0].depth[3*64+3],c=s.dense.maps[0].depth[3*64+6],expected=1/(.5/a+.5/c);require(std::abs(z-expected)<1e-12&&std::abs(z-(a+c)/2)>1e-8,"test: perspective-correct camera-z not linear z");}
 {auto s=fixture();s.views[1].pose.translation.z=-10;for(auto& p:s.dense.verifiedSamples)if(p.camera==1)p.xyz.z+=10;Builder b(s);b.prepare();b.integrate();require(b.w->counters.cameraUnknown>0&&b.w->counters.cameraBehind>0,"test: finite behind-camera UNKNOWN and occluded no-vote exercised");}
 {rejected("invalid_arithmetic: vote",[&]{(void)policy(std::numeric_limits<double>::quiet_NaN(),2);});}
 {auto s=fixture();for(auto& v:s.views)v.fx=v.fy=81;for(auto& p:s.dense.verifiedSamples){const auto& v=s.views[p.camera];p.xyz={(p.x-v.cx)*3/v.fx,(p.y-v.cy)*3/v.fy,3};s.dense.maps[p.camera].depth[p.y*64+p.x]=3;}bool explicitZero=false;try{Builder b(s);b.run();}catch(const Error& e){explicitZero=std::string(e.what()).find("unsupported_degeneracy: exact zero")!=std::string::npos;}require(explicitZero,"test: grid aligned zero must fail explicitly, not empty success");}
 {auto s=fixture();s.dense.maps[0].depth[3*64+3]=0;rejected("invalid: sample depth reprojection",[&]{Builder b(s);b.run();});}
 {auto s=fixture();s.views[0].fx=0;rejected("invalid: calibration",[&]{Builder b(s);b.run();});}
 {auto s=fixture();s.dense.verifiedSamples.push_back(s.dense.verifiedSamples[0]);rejected("invalid: duplicate sample",[&]{Builder b(s);b.run();});}
 {auto s=fixture();Builder b(s);b.w->operations=WORK;rejected("budget_exceeded: enumeration/projection",[&]{b.run();});}
 {auto s=fixture();Builder b(s);b.w->pitch=1;for(unsigned i=0;i<NN/2;i++)b.node({int(i),0,0},true);rejected("budget_exceeded: node table",[&]{b.node({int(NN),1,1},true);});}
 std::cout<<"projective synthetic contracts PASS; workspace sizeof="<<sizeof(Workspace)<<" plus 65536 stack/meta allowance\n";return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
