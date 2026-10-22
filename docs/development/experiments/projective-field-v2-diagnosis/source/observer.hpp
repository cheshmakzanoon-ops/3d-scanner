#pragma once
#include "serialize.hpp"
#include <bit>
#include <cstring>
namespace diag {
constexpr unsigned NK=262144;
struct Unique{Key key{};bool used{};};
struct Proposal{unsigned a{},b{},c{},witness{NP};unsigned status{};}; //0 area,1 witness rejected,2 accepted
struct Storage{
 Unique keys[NK]{};Edge edges[NE]{};Vec3 vertices[NV]{},originalVertices[NV]{};Face accepted[NF]{};Proposal proposals[NF]{};
 std::uint64_t visits{},unique{},eligible{},ineligible{},camera[4]{},nearCardinality[9]{},voteCardinality[9]{},mixed{},cornerHistogram[2][5]{},tetraZero[2]{},tetraOneSign[2]{},tetraCrossing[2]{};
 std::uint64_t operations{},patchTrials{},witnessPredicates{},areaRejected{},searchLimited{},unsupportedSinglePatch{},preGradientZero{};
 unsigned verticesN{},edgesN{},proposalsN{},acceptedN{},originalVerticesN{},uniqueN{};
};
static_assert(sizeof(Storage)+sizeof(Workspace)+65536<=64u*1024u*1024u);
inline bool same(double a,double b){return std::bit_cast<std::uint64_t>(a)==std::bit_cast<std::uint64_t>(b);}
inline bool same(Vec3 a,Vec3 b){return same(a.x,b.x)&&same(a.y,b.y)&&same(a.z,b.z);}
struct Observer{
 const Builder& b;const trace_test::Snapshot& in;const Workspace* w;std::unique_ptr<Storage> d;
 std::chrono::steady_clock::time_point start=std::chrono::steady_clock::now();
 explicit Observer(const Builder& a):b(a),in(a.in),w(a.w.get()),d(std::make_unique<Storage>()){}
 void tick(){require(++d->operations<=100000000,"budget_exceeded: observer operations");if((d->operations&1023)==0)require(std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<120,"budget_exceeded: observer deadline");}
 void witnessTick(){require(++d->witnessPredicates<=120000000,"budget_exceeded: observer witness predicates");if((d->witnessPredicates&1023)==0)require(std::chrono::duration<double>(std::chrono::steady_clock::now()-start).count()<120,"budget_exceeded: observer witness deadline");}
 Vec3 xyz(Key k)const{return b.xyz(k);}
 unsigned find(Key key){unsigned i=unsigned(hash(key)%NN);for(unsigned n=0;n<NN;n++,i=(i+1)%NN){tick();const auto& a=w->nodes[i];if(!a.used)return NN;if(a.key==key)return i;}throw Error("budget_exceeded: observer node probes");}
 bool first(Key key){unsigned i=unsigned(hash(key)%NK);for(unsigned n=0;n<NK;n++,i=(i+1)%NK){tick();auto& a=d->keys[i];if(a.used){if(a.key==key)return false;}else{require(d->uniqueN<NK/2,"budget_exceeded: unique keys");a={key,true};++d->uniqueN;return true;}}throw Error("budget_exceeded: unique probes");}
#include "copied_predicates.inc"
 void captureBeforeCompact(){d->originalVerticesN=w->verticesN;d->acceptedN=w->facesN;std::copy(w->vertices,w->vertices+w->verticesN,d->originalVertices);std::copy(w->faces,w->faces+w->facesN,d->accepted);}
 void uniqueEligibility(){
  for(unsigned pi=0;pi<w->patchesN;pi++){auto bounds=const_cast<Builder&>(b).bandBounds(pi);Key a=const_cast<Builder&>(b).boundGrid(bounds,false),end=const_cast<Builder&>(b).boundGrid(bounds,true);
   for(int z=a.z;z<=end.z+1;z++)for(int y=a.y;y<=end.y+1;y++)for(int x=a.x;x<=end.x+1;x++){
    require(++d->visits<=1000000,"budget_exceeded: diagnostic enumeration");Key key{x,y,z};if(!first(key))continue;++d->unique;
    Node candidate{};candidate.key=key;candidate.used=true;Vec3 position=xyz(key);require(std::isfinite(len(position)),"invalid_arithmetic: lattice position");bool negative=false,positive=false;
    for(unsigned camera=0;camera<in.views.size();camera++){
     const auto& view=in.views[camera];Vec3 q=cam(view,position);require(std::isfinite(q.x)&&std::isfinite(q.y)&&std::isfinite(q.z),"invalid_arithmetic: camera transform");if(q.z<=.01){++d->camera[0];continue;}
     double u=view.fx*(q.x/q.z)+view.cx,v=view.fy*(q.y/q.z)+view.cy,depth=0;unsigned patch=lookup(camera,u,v,depth);if(patch==NP){++d->camera[0];continue;}double residual=depth-q.z;VoteKind kind=policy(residual,w->tau);
     if(kind==VoteKind::Behind){++d->camera[1];continue;}double value=1;
     if(kind==VoteKind::Near){++d->camera[2];candidate.nearMask|=1u<<camera;value=residual/w->tau;negative|=value<0;positive|=value>0;}else ++d->camera[3];
     require(std::isfinite(value),"invalid_arithmetic: normalized vote");candidate.mask|=1u<<camera;candidate.patch[camera]=patch;candidate.distance[camera]=float(value);
    }
    ++d->nearCardinality[std::popcount(unsigned(candidate.nearMask))];++d->voteCardinality[std::popcount(unsigned(candidate.mask))];unsigned id=find(key);
    if(!finalizeNode(candidate)){++d->ineligible;require(id==NN,"parity: ineligible node stored");continue;}++d->eligible;if(negative&&positive)++d->mixed;
    require(id!=NN,"parity: eligible node missing");const auto& n=w->nodes[id];require(n.mask==candidate.mask&&n.nearMask==candidate.nearMask&&same(n.value,candidate.value),"parity: node scalar/masks");for(unsigned c=0;c<8;c++)require(n.patch[c]==candidate.patch[c]&&n.distance[c]==candidate.distance[c],"parity: node contribution");
   }
  }
  require(d->eligible==w->nodesN&&d->visits==w->counters.allocationVisits&&d->unique==d->eligible+d->ineligible,"parity: unique eligibility partition");
 }
 unsigned vertex(unsigned a,unsigned c){if(a>c)std::swap(a,c);unsigned h=unsigned((std::uint64_t(a)*0x9e3779b1+c)%NE);for(unsigned probes=0;probes<NE;probes++,h=(h+1)%NE){tick();auto& e=d->edges[h];if(e.used){if(e.a==a&&e.b==c)return e.vertex;}else{require(d->edgesN<NE/2&&d->verticesN<NV,"budget_exceeded: observer edges/vertices");const auto& A=w->nodes[a];const auto& B=w->nodes[c];double t=A.value/(A.value-B.value);require(std::isfinite(t)&&t>=0&&t<=1,"invalid: zero crossing");e={a,c,d->verticesN,true};++d->edgesN;d->vertices[d->verticesN++]=add(xyz(A.key),mul(sub(xyz(B.key),xyz(A.key)),t));return e.vertex;}}throw Error("budget_exceeded: observer edge probes");}
 bool supports(unsigned pi,unsigned a,unsigned c,unsigned e){return witness(pi,d->vertices[a],.25*w->pitch)&&witness(pi,d->vertices[c],.25*w->pitch)&&witness(pi,d->vertices[e],.25*w->pitch);}
 void face(unsigned a,unsigned c,unsigned e,const unsigned* tetra,unsigned& accepted){
  require(d->proposalsN<NF,"budget_exceeded: observer proposals");auto& f=d->proposals[d->proposalsN++];f={a,c,e,NP,0};Vec3 A=d->vertices[a],B=d->vertices[c],C=d->vertices[e];Vec3 normal=cross(sub(B,A),sub(C,A));if(len(normal)<=w->pitch*w->pitch*1e-12){++d->areaRejected;return;}
  unsigned witnessId=NP;for(unsigned j=0;j<4;j++)for(unsigned camera=0;camera<in.views.size();camera++){const auto& node=w->nodes[tetra[j]];if(!(node.mask&(1u<<camera)))continue;unsigned pi=node.patch[camera];if(supports(pi,a,c,e))witnessId=std::min(witnessId,pi);}
  // Orientation added to diagnostic pre-stage only; vertex links ignore it.
  const auto& n0=w->nodes[tetra[0]];Vec3 origin=xyz(n0.key),e1=sub(xyz(w->nodes[tetra[1]].key),origin),e2=sub(xyz(w->nodes[tetra[2]].key),origin),e3=sub(xyz(w->nodes[tetra[3]].key),origin);double det=dot(e1,cross(e2,e3));require(std::isfinite(det)&&det!=0,"invalid: tetra determinant");Vec3 gradient=mul(add(add(mul(cross(e2,e3),w->nodes[tetra[1]].value-n0.value),mul(cross(e3,e1),w->nodes[tetra[2]].value-n0.value)),mul(cross(e1,e2),w->nodes[tetra[3]].value-n0.value)),1/det);double direction=dot(normal,gradient);require(std::isfinite(direction),"invalid: observer gradient");if(direction==0)++d->preGradientZero;if(direction<0)std::swap(c,e);f.b=c;f.c=e;f.witness=witnessId;f.status=witnessId==NP?1:2;
  if(witnessId!=NP){require(direction!=0&&accepted<d->acceptedN,"parity: accepted direction/count");const auto& original=d->accepted[accepted++];require(original.witness==witnessId&&original.vertex[0]==a&&original.vertex[1]==c&&original.vertex[2]==e,"parity: face/witness identity");}
 }
 void tetraStages(){constexpr unsigned tet[6][4]={{0,1,3,7},{0,3,2,7},{0,2,6,7},{0,6,4,7},{0,4,5,7},{0,5,1,7}};unsigned accepted=0;
  for(unsigned ci=0;ci<w->cellsN;ci++){auto k=w->cells[w->cellOrder[ci]].key;unsigned corners[8];bool full=true;for(unsigned i=0;i<8;i++){corners[i]=find({k.x+int(i&1),k.y+int((i>>1)&1),k.z+int((i>>2)&1)});if(corners[i]==NN||!w->nodes[corners[i]].valid)full=false;}
   for(const auto& t:tet){unsigned known=0,ids[4],pos[4],neg[4],p=0,n=0;bool zero=false;for(unsigned i=0;i<4;i++){ids[i]=corners[t[i]];if(ids[i]==NN||!w->nodes[ids[i]].valid)continue;++known;double value=w->nodes[ids[i]].value;if(value==0)zero=true;if(value>0)pos[p++]=ids[i];else neg[n++]=ids[i];}++d->cornerHistogram[full][known];if(known!=4)continue;if(zero){++d->tetraZero[full];require(!full,"parity: original zero endpoint succeeded");continue;}if(!p||!n){++d->tetraOneSign[full];continue;}++d->tetraCrossing[full];if(!full)continue;
    if(p==1||n==1){unsigned single=p==1?pos[0]:neg[0];unsigned* other=p==1?neg:pos;unsigned a=vertex(single,other[0]),c=vertex(single,other[1]),e=vertex(single,other[2]);face(a,c,e,ids,accepted);}else{unsigned a=vertex(pos[0],neg[0]),c=vertex(pos[0],neg[1]),e=vertex(pos[1],neg[0]),f=vertex(pos[1],neg[1]);face(a,c,f,ids,accepted);face(a,f,e,ids,accepted);}
   }
  }
  require(accepted==d->acceptedN&&d->verticesN==d->originalVerticesN&&d->proposalsN==w->counters.faceCandidates&&d->areaRejected==w->counters.areaRejected,"parity: stage counts");for(unsigned i=0;i<d->verticesN;i++)require(same(d->vertices[i],d->originalVertices[i]),"parity: raw vertex arithmetic/index");require(d->tetraOneSign[1]==w->counters.allOneSignTetra&&d->tetraCrossing[1]==w->counters.crossingTetra,"parity: complete cube signs");
 }
 void exhaustive(){for(unsigned i=0;i<d->proposalsN;i++){auto& f=d->proposals[i];if(f.status!=1)continue;bool supported=false;for(unsigned pi=0;pi<w->patchesN;pi++){require(++d->patchTrials<=40000000,"budget_exceeded: exhaustive witness UNKNOWN_BUDGET");if(supports(pi,f.a,f.b,f.c)){supported=true;f.witness=pi;break;}}if(supported)++d->searchLimited;else ++d->unsupportedSinglePatch;}require(d->searchLimited+d->unsupportedSinglePatch==w->counters.witnessRejected,"parity: witness rejection partition");}
 void ply(const std::filesystem::path& path,bool post){Out out(path);unsigned count=post?d->acceptedN:d->proposalsN-unsigned(d->areaRejected);std::fprintf(out.f,"ply\nformat ascii 1.0\nelement vertex %u\nproperty double x\nproperty double y\nproperty double z\nelement face %u\nproperty list uchar uint vertex_indices\nend_header\n",d->verticesN,count);for(unsigned i=0;i<d->verticesN;i++){auto p=d->vertices[i];std::fprintf(out.f,"%.17g %.17g %.17g\n",p.x,p.y,p.z);}for(unsigned i=0;i<d->proposalsN;i++){auto f=d->proposals[i];if(f.status==0||(post&&f.status!=2))continue;std::fprintf(out.f,"3 %u %u %u\n",f.a,f.b,f.c);}out.close();}
 void witnessRows(const std::filesystem::path& path){Out out(path);std::fprintf(out.f,"[\n");bool first=true;for(unsigned i=0;i<d->proposalsN;i++){const auto& f=d->proposals[i];if(f.status!=1)continue;std::fprintf(out.f,"%s{\"proposalId\":%u,\"minimumGlobalWitnessPatch\":",first?"":",\n",i);if(f.witness==NP)std::fprintf(out.f,"null}");else std::fprintf(out.f,"%u}",f.witness);first=false;}std::fprintf(out.f,"\n]\n");out.close();}
 void report(const std::filesystem::path& path){Out out(path);std::fprintf(out.f,"{\"status\":\"DIAGNOSTIC_COMPLETE_REQUIRES_EXTERNAL_PARITY\",\"singlePatchSearch\":\"COMPLETE_EXACT_V2_PREDICATE_NOT_GEOMETRIC_TRUTH\",\"scratchBytes\":%zu,\"stackAllowanceBytes\":65536,\"allocationVisits\":%llu,\"uniqueProposedNodes\":%llu,\"uniqueEligibleNodes\":%llu,\"uniqueIneligibleNodes\":%llu,\"uniqueMixedNearSignNodes\":%llu,\"searchLimitedFaces\":%llu,\"noSinglePatchWitnessFaces\":%llu,\"patchTrials\":%llu,\"witnessPredicates\":%llu,\"observerOperations\":%llu,\"preGradientZero\":%llu,\"proposedFaces\":%u,\"postAreaFaces\":%llu,\"acceptedFaces\":%u",sizeof(Storage)+sizeof(Workspace),(unsigned long long)d->visits,(unsigned long long)d->unique,(unsigned long long)d->eligible,(unsigned long long)d->ineligible,(unsigned long long)d->mixed,(unsigned long long)d->searchLimited,(unsigned long long)d->unsupportedSinglePatch,(unsigned long long)d->patchTrials,(unsigned long long)d->witnessPredicates,(unsigned long long)d->operations,(unsigned long long)d->preGradientZero,d->proposalsN,(unsigned long long)(d->proposalsN-d->areaRejected),d->acceptedN);
  auto array=[&](const char* key,const std::uint64_t* a,unsigned n){std::fprintf(out.f,",\"%s\":[",key);for(unsigned i=0;i<n;i++)std::fprintf(out.f,"%s%llu",i?",":"",(unsigned long long)a[i]);std::fprintf(out.f,"]");};array("uniqueCameraUnknownBehindNearFront",d->camera,4);array("uniqueNearMaskCardinality",d->nearCardinality,9);array("uniqueVoteMaskCardinality",d->voteCardinality,9);array("incompleteCubeKnownTetraCornerHistogram",d->cornerHistogram[0],5);array("completeCubeKnownTetraCornerHistogram",d->cornerHistogram[1],5);array("zeroTetraIncompleteComplete",d->tetraZero,2);array("oneSignTetraIncompleteComplete",d->tetraOneSign,2);array("crossingTetraIncompleteComplete",d->tetraCrossing,2);std::fprintf(out.f,"}\n");out.close();}
};
}
