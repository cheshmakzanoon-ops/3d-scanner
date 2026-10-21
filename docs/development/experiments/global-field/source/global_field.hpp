#pragma once
// EXTERNAL, EXPERIMENTAL, NOT SurfaceMesh. No input sample or normal is modified.
#include "snapshot.hpp"
#include <memory>
#include <chrono>
#include <cstdio>
namespace rf::experimental_global {
using trace_test::require;
constexpr unsigned NP=100000, NN=131072, NC=65536, NE=131072, NV=40000, NF=80000;
constexpr std::uint64_t WORK=40000000; constexpr double SECONDS=60;
inline Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
inline Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
inline Vec3 mul(Vec3 a,double b){return {a.x*b,a.y*b,a.z*b};}
inline double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
inline Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
inline double len(Vec3 a){return std::sqrt(dot(a,a));}
inline Vec3 cam(const DenseView& v,Vec3 p){return add(multiply(v.pose.rotation,p),v.pose.translation);}
struct Key{int x{},y{},z{};bool operator==(const Key&)const=default;};
inline bool less(Key a,Key b){return std::tie(a.x,a.y,a.z)<std::tie(b.x,b.y,b.z);}
inline std::uint64_t hash(Key k){auto h=std::uint64_t(std::uint32_t(k.x))*0x9e3779b185ebca87ULL;h^=std::uint64_t(std::uint32_t(k.y))*0xc2b2ae3d27d4eb4fULL;return h^(std::uint64_t(std::uint32_t(k.z))*0x165667b19e3779f9ULL);}
struct Patch{std::uint32_t source[3]{};Vec3 normal{};double plane{};std::uint8_t camera{};};
struct Node{Key key{};std::uint32_t patch[8]{};float distance[8]{};double value{};std::uint8_t mask{};bool used{},conflict{},valid{};};
struct Cell{Key key{};bool used{};};
struct Edge{std::uint32_t a{},b{},vertex{};bool used{};};
struct Face{std::uint32_t vertex[3]{},witness{};};
struct AuditEdge{std::uint32_t a{},b{};int direction{};};
struct Workspace{
 Patch patches[NP]{};Node nodes[NN]{};Cell cells[NC]{};Edge edges[NE]{};
 Vec3 vertices[NV]{};Face faces[NF]{};AuditEdge auditEdges[3*NF]{};
 std::uint32_t sampleOrder[MAX_DENSE_SAMPLES]{},cellOrder[NC]{},remap[NV]{};double footprints[MAX_DENSE_SAMPLES]{};
 unsigned patchesN{},nodesN{},cellsN{},edgesN{},verticesN{},facesN{};
 double pitch{},tau{};std::uint64_t operations{},rejectedFaces{},conflictNodes{},boundaryEdges{},nonmanifoldEdges{},windingConflictEdges{};
};
// Fixed storage owns table slack, provenance, sort staging AND output. 64 KiB
// reserved for bounded stack/meta; decoded immutable input is accounted separately.
static_assert(sizeof(Workspace)+65536 <= 32u*1024u*1024u);
struct Builder {
 const trace_test::Snapshot& in;std::unique_ptr<Workspace> w;
 std::chrono::steady_clock::time_point begin=std::chrono::steady_clock::now();
 explicit Builder(const trace_test::Snapshot& s):in(s),w(std::make_unique<Workspace>()){}
 void tick(){require(++w->operations<=WORK,"budget_exceeded: enumeration/projection");if((w->operations&1023)==0)require(std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count()<=SECONDS,"budget_exceeded: deadline");}
 Vec3 xyz(Key k)const{return {k.x*w->pitch,k.y*w->pitch,k.z*w->pitch};}
 Key grid(Vec3 p)const{auto q=[&](double x){double v=std::floor(x/w->pitch);require(std::isfinite(v)&&v>-1000000000&&v<1000000000,"budget_exceeded: grid coordinate");return int(v);};return {q(p.x),q(p.y),q(p.z)};}
 unsigned node(Key key,bool create){auto i=unsigned(hash(key)%NN);for(unsigned n=0;n<NN;n++,i=(i+1)%NN){tick();auto& a=w->nodes[i];if(a.used){if(a.key==key)return i;}else {if(!create)return NN;require(w->nodesN<NN/2,"budget_exceeded: node table");a.used=true;a.key=key;++w->nodesN;return i;}}throw Error("budget_exceeded: node probes");}
 void cell(Key key){auto i=unsigned(hash(key)%NC);for(unsigned n=0;n<NC;n++,i=(i+1)%NC){tick();auto& a=w->cells[i];if(a.used){if(a.key==key)return;}else{require(w->cellsN<NC/2,"budget_exceeded: cell table");a.used=true;a.key=key;w->cellOrder[w->cellsN++]=i;return;}}throw Error("budget_exceeded: cell probes");}
 unsigned sample(unsigned camera,int x,int y)const{
  auto target=std::tuple(camera,y,x);auto* start=w->sampleOrder;auto* end=start+in.dense.verifiedSamples.size();
  auto* found=std::lower_bound(start,end,target,[&](unsigned i,auto key){const auto& p=in.dense.verifiedSamples[i];return std::tuple(unsigned(p.camera),int(p.y),int(p.x))<key;});
  if(found==end)return UINT32_MAX;const auto& p=in.dense.verifiedSamples[*found];return std::tuple(unsigned(p.camera),int(p.y),int(p.x))==target?*found:UINT32_MAX;
 }
 void admit(unsigned camera,unsigned a,unsigned b,unsigned c){
  const auto& s=in.settings;const auto& d=in.dense;const auto& v=in.views[camera];
  const Vec3 A=d.verifiedSamples[a].xyz,B=d.verifiedSamples[b].xyz,C=d.verifiedSamples[c].xyz;
  double lo=1e300,hi=0;for(unsigned i:{a,b,c}){const auto& sample=d.verifiedSamples[i];double z=d.maps[camera].depth[std::size_t(sample.y)*v.width+sample.x];lo=std::min(lo,z);hi=std::max(hi,z);}
  const double f=hi*s.pixelStride/std::min(v.fx,v.fy),limit=f*s.maximumEdgePixelFactor;
  if(lo<=0||(hi-lo)/hi>s.maximumRelativeDepthJump||len(sub(A,B))>limit||len(sub(A,C))>limit||len(sub(B,C))>limit)return;
  Vec3 n=cross(sub(B,A),sub(C,A));double length=len(n);if(length<f*f*.015)return;n=mul(n,1/length);
  Vec3 center=mul(multiply(transpose(v.pose.rotation),v.pose.translation),-1);if(dot(n,sub(center,A))<0)n=mul(n,-1);
  require(w->patchesN<NP,"budget_exceeded: patches");w->patches[w->patchesN++]={{a,b,c},n,dot(n,A),std::uint8_t(camera)};
 }
 void prepare(){
  require(in.views.size()>=2&&in.views.size()<=8&&in.dense.maps.size()==in.views.size(),"invalid: views/maps");
  require(in.settings.pixelStride>=2&&in.settings.pixelStride<=8&&in.settings.maxVertices==NV&&in.settings.maxTriangles==NF,"invalid: settings");
  require(in.settings.minimumConfidence==SurfaceSettings{}.minimumConfidence&&in.settings.maximumRelativeDepthJump==SurfaceSettings{}.maximumRelativeDepthJump&&in.settings.maximumEdgePixelFactor==SurfaceSettings{}.maximumEdgePixelFactor,"invalid: changed guards");
  require(in.dense.verifiedSamples.size()<=MAX_DENSE_SAMPLES,"budget_exceeded: samples");
  for(unsigned camera=0;camera<in.views.size();camera++){const auto& v=in.views[camera];const auto& map=in.dense.maps[camera];
   require(v.width>=32&&v.width<=640&&v.height>=32&&v.height<=640&&std::size_t(v.width)*v.height<=320000&&v.width==in.views[0].width&&v.height==in.views[0].height,"invalid: dimensions");
   require(std::isfinite(v.fx)&&std::isfinite(v.fy)&&v.fx>0&&v.fy>0&&std::isfinite(v.cx)&&std::isfinite(v.cy)&&std::isfinite(len(v.pose.translation))&&len(v.pose.translation)<=1e6,"invalid: calibration");
   for(double r:v.pose.rotation)require(std::isfinite(r),"invalid: rotation");auto orth=multiply(v.pose.rotation,transpose(v.pose.rotation));for(int i=0;i<9;i++)require(std::abs(orth[i]-(i%4==0?1.:0.))<=.025,"invalid: rotation");require(std::abs(determinant(v.pose.rotation)-1)<=.025,"invalid: rotation determinant");
   auto pixels=std::size_t(v.width)*v.height;require(map.width==v.width&&map.height==v.height&&map.depth.size()==pixels&&map.confidence.size()==pixels&&v.rgba.size()==4*pixels,"invalid: buffers");
   for(unsigned i=0;i<pixels;i++){tick();require(std::isfinite(map.depth[i])&&map.depth[i]>=0&&map.depth[i]<=1e6&&std::isfinite(map.confidence[i])&&map.confidence[i]>=0&&map.confidence[i]<=1,"invalid: map value");}
  }
  for(const auto& p:in.dense.verifiedSamples){tick();require(p.camera<in.views.size()&&std::isfinite(len(p.xyz))&&len(p.xyz)<=1e6&&std::isfinite(p.confidence)&&p.confidence>=0&&p.confidence<=1&&p.supportingViews>=2&&p.supportingViews<=in.views.size(),"invalid: sample");const auto& v=in.views[p.camera];require(p.x<v.width&&p.y<v.height,"invalid: sample pixel");Vec3 q=cam(v,p.xyz);double z=in.dense.maps[p.camera].depth[std::size_t(p.y)*v.width+p.x];require(std::isfinite(len(q))&&q.z>.01&&q.z<=1e6&&z>0&&std::abs(q.z-z)/z<=.001&&std::abs(v.fx*q.x/q.z+v.cx-p.x)<=.25&&std::abs(v.fy*q.y/q.z+v.cy-p.y)<=.25,"invalid: sample depth reprojection");}

  const auto& samples=in.dense.verifiedSamples;require(!samples.empty(),"no_observed_patches");
  for(unsigned i=0;i<samples.size();i++){tick();w->sampleOrder[i]=i;const auto& p=samples[i];const auto& v=in.views[p.camera];w->footprints[i]=cam(v,p.xyz).z*in.settings.pixelStride/std::min(v.fx,v.fy);}
  std::sort(w->sampleOrder,w->sampleOrder+samples.size(),[&](unsigned a,unsigned b){const auto& p=samples[a];const auto& q=samples[b];return std::tie(p.camera,p.y,p.x)<std::tie(q.camera,q.y,q.x);});
  for(unsigned i=1;i<samples.size();i++){const auto& p=samples[w->sampleOrder[i-1]];const auto& q=samples[w->sampleOrder[i]];require(std::tie(p.camera,p.y,p.x)!=std::tie(q.camera,q.y,q.x),"invalid: duplicate sample");}
  // Exact upper median, no fit to truth, coverage, or topology. No fallback/coarsening.
  std::sort(w->footprints,w->footprints+samples.size());w->pitch=w->footprints[samples.size()/2];require(std::isfinite(w->pitch)&&w->pitch>1e-9,"invalid: pitch");w->tau=2*w->pitch;
  const int step=in.settings.pixelStride;
  for(unsigned camera=0;camera<in.views.size();camera++)for(int y=3;y+step<in.views[camera].height-3;y+=step)for(int x=3;x+step<in.views[camera].width-3;x+=step){
   tick();unsigned ids[4]={sample(camera,x,y),sample(camera,x+step,y),sample(camera,x,y+step),sample(camera,x+step,y+step)};bool good=true;
   for(auto id:ids)if(id==UINT32_MAX||samples[id].confidence<in.settings.minimumConfidence)good=false;if(!good)continue;
   auto dist=[&](unsigned a,unsigned b){return len(sub(samples[ids[a]].xyz,samples[ids[b]].xyz));};
   if(dist(0,3)<dist(1,2)){admit(camera,ids[0],ids[1],ids[3]);admit(camera,ids[0],ids[3],ids[2]);}else{admit(camera,ids[0],ids[1],ids[2]);admit(camera,ids[1],ids[3],ids[2]);}
  }
  require(w->patchesN>0,"no_observed_patches");
 }
 // The source triangle's three camera-ray halfspaces are convex in world
 // coordinates. Whole-face inclusion follows from testing all three vertices,
 // not centroid sampling. Positive camera z and a signed plane slab are convex too.
 bool witness(unsigned pi,Vec3 p,double slab){
  tick();const auto& patch=w->patches[pi];const auto& view=in.views[patch.camera];Vec3 q=cam(view,p);if(q.z<=.01)return false;
  if(std::abs(dot(patch.normal,p)-patch.plane)>slab)return false;
  Vec3 rays[3];for(int k=0;k<3;k++)rays[k]=cam(view,in.dense.verifiedSamples[patch.source[k]].xyz);
  for(int k=0;k<3;k++){Vec3 n=cross(rays[k],rays[(k+1)%3]);double sign=dot(n,rays[(k+2)%3]);if(!std::isfinite(sign)||sign==0)return false;if(dot(n,q)*(sign>0?1:-1)<0)return false;}
  return true;
 }
 void integrate(){
  for(unsigned pi=0;pi<w->patchesN;pi++){
   const auto& p=w->patches[pi];Vec3 lo=in.dense.verifiedSamples[p.source[0]].xyz,hi=lo;
   for(auto id:p.source){const auto a=in.dense.verifiedSamples[id].xyz;lo={std::min(lo.x,a.x),std::min(lo.y,a.y),std::min(lo.z,a.z)};hi={std::max(hi.x,a.x),std::max(hi.y,a.y),std::max(hi.z,a.z)};}
   // Camera-ray slab can expand tangentially; explicitly enumerate the bounded
   // convex truncated pyramid's six corners, not a normal-offset AABB.
   const auto& v=in.views[p.camera];Vec3 center=mul(multiply(transpose(v.pose.rotation),v.pose.translation),-1);
   for(auto id:p.source){Vec3 a=in.dense.verifiedSamples[id].xyz;Vec3 ray=sub(a,center);double denom=dot(p.normal,ray);require(std::abs(denom)>1e-12,"invalid: grazing source patch");for(double side:{-w->tau,w->tau}){Vec3 b=add(a,mul(ray,side/denom));lo={std::min(lo.x,b.x),std::min(lo.y,b.y),std::min(lo.z,b.z)};hi={std::max(hi.x,b.x),std::max(hi.y,b.y),std::max(hi.z,b.z)};}}
   Key a=grid(lo),b=grid(hi);std::uint64_t count=1;for(auto d:{std::int64_t(b.x)-a.x+2,std::int64_t(b.y)-a.y+2,std::int64_t(b.z)-a.z+2}){require(d>0&&std::uint64_t(d)<=WORK/count,"budget_exceeded: patch enumeration");count*=d;}
   require(count<=WORK-w->operations,"budget_exceeded: patch enumeration");
   for(int z=a.z;z<=b.z+1;z++)for(int y=a.y;y<=b.y+1;y++)for(int x=a.x;x<=b.x+1;x++){
    tick();Key key{x,y,z};Vec3 pos=xyz(key);if(!witness(pi,pos,w->tau))continue;
    auto ni=node(key,true);auto& n=w->nodes[ni];double distance=dot(p.normal,pos)-p.plane;unsigned mask=1u<<p.camera;
    if(n.mask&mask){const auto& old=w->patches[n.patch[p.camera]];if(std::abs(double(n.distance[p.camera])-distance)>w->pitch*1e-5||dot(old.normal,p.normal)<.999)n.conflict=true;}
    else{n.mask|=mask;n.patch[p.camera]=pi;n.distance[p.camera]=float(distance);}
    for(int dz=-1;dz<=0;dz++)for(int dy=-1;dy<=0;dy++)for(int dx=-1;dx<=0;dx++)cell({x+dx,y+dy,z+dz});
   }
  }
  for(auto& n:w->nodes)if(n.used){tick();double sum=0;unsigned count=0;for(unsigned a=0;a<in.views.size();a++)if(n.mask&(1u<<a)){
   sum+=n.distance[a];++count;for(unsigned b=0;b<a;b++)if(n.mask&(1u<<b))if(dot(w->patches[n.patch[a]].normal,w->patches[n.patch[b]].normal)<.95||std::abs(double(n.distance[a])-n.distance[b])>w->pitch*.25)n.conflict=true;
  }n.valid=!n.conflict&&count>0;n.value=sum/std::max(1u,count);if(n.conflict)++w->conflictNodes;}
 }
 unsigned vertex(unsigned a,unsigned b){
  if(a>b)std::swap(a,b);unsigned h=unsigned((std::uint64_t(a)*0x9e3779b1+b)%NE);
  for(unsigned probes=0;probes<NE;probes++,h=(h+1)%NE){tick();auto& e=w->edges[h];if(e.used){if(e.a==a&&e.b==b)return e.vertex;}else{
   require(w->edgesN<NE/2&&w->verticesN<NV,"budget_exceeded: shared edges/vertices");const auto& A=w->nodes[a];const auto& B=w->nodes[b];double t=A.value/(A.value-B.value);require(std::isfinite(t)&&t>=0&&t<=1,"invalid: zero crossing");
   e={a,b,w->verticesN,true};++w->edgesN;w->vertices[w->verticesN++]=add(xyz(A.key),mul(sub(xyz(B.key),xyz(A.key)),t));return e.vertex;
  }}throw Error("budget_exceeded: edge probes");
 }
 void face(unsigned a,unsigned b,unsigned c,const unsigned* tetra){
  Vec3 A=w->vertices[a],B=w->vertices[b],C=w->vertices[c];Vec3 n=cross(sub(B,A),sub(C,A));if(len(n)<=w->pitch*w->pitch*1e-12){++w->rejectedFaces;return;}
  unsigned witnessId=NP;
  // Candidate provenance comes from this tetra's nodes; do not search unrelated patches.
  for(unsigned j=0;j<4;j++)for(unsigned camera=0;camera<in.views.size();camera++){const auto& node=w->nodes[tetra[j]];if(!(node.mask&(1u<<camera)))continue;unsigned pi=node.patch[camera];if(witness(pi,A,.25*w->pitch)&&witness(pi,B,.25*w->pitch)&&witness(pi,C,.25*w->pitch))witnessId=std::min(witnessId,pi);}
  if(witnessId==NP){++w->rejectedFaces;return;}
  const auto& n0=w->nodes[tetra[0]];Vec3 origin=xyz(n0.key),e1=sub(xyz(w->nodes[tetra[1]].key),origin),e2=sub(xyz(w->nodes[tetra[2]].key),origin),e3=sub(xyz(w->nodes[tetra[3]].key),origin);
  double det=dot(e1,cross(e2,e3));require(std::isfinite(det)&&det!=0,"invalid: tetra determinant");
  Vec3 gradient=mul(add(add(mul(cross(e2,e3),w->nodes[tetra[1]].value-n0.value),mul(cross(e3,e1),w->nodes[tetra[2]].value-n0.value)),mul(cross(e1,e2),w->nodes[tetra[3]].value-n0.value)),1/det);
  double direction=dot(n,gradient);require(std::isfinite(direction)&&direction!=0,"unsupported_degeneracy: face gradient orientation");
  if(direction<0)std::swap(b,c);require(w->facesN<NF,"budget_exceeded: faces");w->faces[w->facesN++]={{a,b,c},witnessId};
 }
 void extract(){
  std::sort(w->cellOrder,w->cellOrder+w->cellsN,[&](unsigned a,unsigned b){return less(w->cells[a].key,w->cells[b].key);});
  constexpr unsigned tet[6][4]={{0,1,3,7},{0,3,2,7},{0,2,6,7},{0,6,4,7},{0,4,5,7},{0,5,1,7}};
  for(unsigned ci=0;ci<w->cellsN;ci++){auto k=w->cells[w->cellOrder[ci]].key;unsigned corners[8];bool valid=true;
   for(unsigned i=0;i<8;i++){corners[i]=node({k.x+int(i&1),k.y+int((i>>1)&1),k.z+int((i>>2)&1)},false);if(corners[i]==NN||!w->nodes[corners[i]].valid)valid=false;}if(!valid)continue;
   for(const auto& t:tet){unsigned ids[4],pos[4],neg[4],p=0,n=0;bool zero=false;for(unsigned i=0;i<4;i++){ids[i]=corners[t[i]];auto value=w->nodes[ids[i]].value;if(value==0)zero=true;if(value>0)pos[p++]=ids[i];else neg[n++]=ids[i];}
    // Do not silently erase grid-aligned surfaces. Endpoint-key extraction
    // is not implemented in v1: reject the entire candidate explicitly.
    require(!zero,"unsupported_degeneracy: exact zero lattice endpoint");
    if(p==0||n==0)continue;
    if(p==1||n==1){unsigned single=p==1?pos[0]:neg[0];unsigned* other=p==1?neg:pos;unsigned a=vertex(single,other[0]),b=vertex(single,other[1]),c=vertex(single,other[2]);face(a,b,c,ids);}
    else{unsigned a=vertex(pos[0],neg[0]),b=vertex(pos[0],neg[1]),c=vertex(pos[1],neg[0]),d=vertex(pos[1],neg[1]);face(a,b,d,ids);face(a,d,c,ids);}
   }
  }
  tick();require(std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count()<=SECONDS,"budget_exceeded: deadline");
 }
 void compact(){for(unsigned i=0;i<w->facesN;i++)for(auto id:w->faces[i].vertex)w->remap[id]=1;unsigned next=0;for(unsigned i=0;i<w->verticesN;i++)if(w->remap[i]){w->remap[i]=next;w->vertices[next++]=w->vertices[i];}for(unsigned i=0;i<w->facesN;i++)for(auto& id:w->faces[i].vertex)id=w->remap[id];w->verticesN=next;}
 void audit(){unsigned size=0;for(unsigned i=0;i<w->facesN;i++)for(unsigned j=0;j<3;j++){auto a=w->faces[i].vertex[j],b=w->faces[i].vertex[(j+1)%3];w->auditEdges[size++]={std::min(a,b),std::max(a,b),a<b?1:-1};}
  std::sort(w->auditEdges,w->auditEdges+size,[](const AuditEdge& a,const AuditEdge& b){return std::tie(a.a,a.b)<std::tie(b.a,b.b);});
  for(unsigned i=0;i<size;){tick();unsigned end=i+1;int balance=w->auditEdges[i].direction;while(end<size&&w->auditEdges[end].a==w->auditEdges[i].a&&w->auditEdges[end].b==w->auditEdges[i].b)balance+=w->auditEdges[end++].direction;unsigned incident=end-i;if(incident==1)++w->boundaryEdges;if(incident>2)++w->nonmanifoldEdges;if(incident==2&&balance!=0)++w->windingConflictEdges;i=end;}
 }
 void run(){prepare();integrate();extract();compact();audit();require(std::chrono::duration<double>(std::chrono::steady_clock::now()-begin).count()<=SECONDS,"budget_exceeded: terminal deadline");require(w->facesN>0,"no_supported_surface: no success output");}
};
} // namespace rf::experimental_global
