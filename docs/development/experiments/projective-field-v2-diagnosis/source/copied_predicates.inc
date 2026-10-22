// Exact original lookup/witness arithmetic, only independent metering.
 unsigned lookup(unsigned camera,double u,double v,double& depth){
  tick();require(std::isfinite(u)&&std::isfinite(v),"invalid_arithmetic: projected pixel");const auto& view=in.views[camera];if(u<3||v<3||u>=view.width-3||v>=view.height-3)return NP;
  int step=in.settings.pixelStride;double gx=(u-3)/step,gy=(v-3)/step;int ix=int(std::floor(gx)),iy=int(std::floor(gy));unsigned best=NP;
  auto cellKey=[&](const Patch& patch){int x=INT_MAX,y=INT_MAX;for(auto id:patch.source){x=std::min(x,int(in.dense.verifiedSamples[id].x));y=std::min(y,int(in.dense.verifiedSamples[id].y));}return std::tuple(unsigned(patch.camera),y,x);};
  for(int dy=0;dy>=(gy==iy?-1:0);dy--)for(int dx=0;dx>=(gx==ix?-1:0);dx--){int x=3+(ix+dx)*step,y=3+(iy+dy)*step;if(x<3||y<3||x+step>=view.width-3||y+step>=view.height-3)continue;auto target=std::tuple(camera,y,x);
   auto* first=std::lower_bound(w->patches,w->patches+w->patchesN,target,[&](const Patch& p,auto key){return cellKey(p)<key;});
   for(auto* p=first;p!=w->patches+w->patchesN&&cellKey(*p)==target;++p){tick();double px[3],py[3],z[3];for(unsigned j=0;j<3;j++){const auto& sample=in.dense.verifiedSamples[p->source[j]];px[j]=sample.x;py[j]=sample.y;z[j]=in.dense.maps[camera].depth[std::size_t(sample.y)*view.width+sample.x];}
    double determinant=(py[1]-py[2])*(px[0]-px[2])+(px[2]-px[1])*(py[0]-py[2]);require(std::isfinite(determinant)&&determinant!=0,"invalid_arithmetic: canonical triangle");
    double l0=((py[1]-py[2])*(u-px[2])+(px[2]-px[1])*(v-py[2]))/determinant,l1=((py[2]-py[0])*(u-px[2])+(px[0]-px[2])*(v-py[2]))/determinant,l2=1-l0-l1;
    require(std::isfinite(l0)&&std::isfinite(l1)&&std::isfinite(l2),"invalid_arithmetic: barycentric");if(l0<0||l1<0||l2<0||l0>1||l1>1||l2>1)continue;
    double denominator=l0/z[0]+l1/z[1]+l2/z[2];require(std::isfinite(denominator)&&denominator>0,"invalid_arithmetic: reciprocal depth");double candidate=1/denominator;require(std::isfinite(candidate)&&candidate>0,"invalid_arithmetic: interpolated depth");unsigned pi=unsigned(p-w->patches);if(pi<best){best=pi;depth=candidate;}
   }
  }return best;
 }
 bool witness(unsigned pi,Vec3 p,double slab){
  witnessTick();const auto& patch=w->patches[pi];const auto& view=in.views[patch.camera];Vec3 q=cam(view,p);if(q.z<=.01)return false;
  if(std::abs(dot(patch.normal,p)-patch.plane)>slab)return false;
  Vec3 rays[3];for(int k=0;k<3;k++)rays[k]=cam(view,in.dense.verifiedSamples[patch.source[k]].xyz);
  for(int k=0;k<3;k++){Vec3 n=cross(rays[k],rays[(k+1)%3]);double sign=dot(n,rays[(k+2)%3]);if(!std::isfinite(sign)||sign==0)return false;if(dot(n,q)*(sign>0?1:-1)<0)return false;}
  return true;
 }
