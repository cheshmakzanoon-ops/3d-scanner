#include "realityforge/depth_consistency.hpp"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <iostream>
#include <limits>
int main(){
 rf::DenseDepthMap m{8,8,std::vector<float>(64,0),std::vector<float>(64,0)};
 for(int y=1;y<8;y+=2)for(int x=1;x<8;x+=2){m.depth[y*8+x]=5./(1.+.002*x+.004*y);m.confidence[y*8+x]=.8;}
 auto a=rf::consistency::sampleDepth(m,2,2,2,1);assert(a.classification==rf::consistency::Visibility::Supported&&std::abs(a.depth-5./1.012)<1e-6);
 m.depth[3*8+3]=3;assert(rf::consistency::sampleDepth(m,2,2,2,1).classification==rf::consistency::Visibility::Discontinuity);
 m.depth[3*8+3]=0;assert(rf::consistency::sampleDepth(m,2,2,2,1).classification==rf::consistency::Visibility::Unknown);
 assert(rf::consistency::sampleDepth(m,-1,2,2,1).classification==rf::consistency::Visibility::Outside);
 // A declared sparse grid can extend beyond the image. Neighbor arithmetic
 // must not overflow even when an unused neighbor exceeds signed-int range.
 rf::DenseDepthMap tiny{3,3,std::vector<float>(9,4),std::vector<float>(9,.8f)};
 const auto hugeStep=std::numeric_limits<int>::max();
 for(int step:{1,2,8,hugeStep}){
  const auto exact=rf::consistency::sampleDepth(tiny,1,1,step,1);
  assert(exact.classification==rf::consistency::Visibility::Supported);
  assert(exact.depth==4&&std::abs(exact.confidence-.8)<1e-6);
 }
 assert(rf::consistency::sampleDepth(tiny,1.5,1.5,hugeStep,1).classification==rf::consistency::Visibility::Outside);
 for(int step:{0,-1,std::numeric_limits<int>::min()})
  assert(rf::consistency::sampleDepth(tiny,1,1,step,1).classification==rf::consistency::Visibility::Unknown);
 assert(rf::consistency::sampleDepth(tiny,1,1,1,hugeStep).classification==rf::consistency::Visibility::Outside);
 using V=rf::consistency::Visibility;rf::consistency::Sample s{V::Supported,3,.8,2,2};
 assert(rf::consistency::verify(s,5,0,.05,1).classification==V::Occluded);
 assert(rf::consistency::verify(s,2,0,.05,1).classification==V::Inconsistent);
 assert(rf::consistency::verify(s,3,.5,.05,1).classification==V::Supported);
 assert(rf::consistency::verify(s,3,2,.05,1).classification==V::Inconsistent);
 // Independently generated calibrated plane intersections, not expected
 // normals copied into the estimator. Different cameras observe one world
 // plane; camera-coordinate normals differ until transformed into world.
 rf::DenseView camera;camera.width=51;camera.height=51;camera.fx=100;camera.fy=100;camera.cx=25;camera.cy=25;camera.pose.rotation={1,0,0,0,1,0,0,0,1};
 const auto plane=[&](rf::Vec3 normal,double offset){rf::DenseDepthMap d{51,51,std::vector<float>(51*51,0),std::vector<float>(51*51,0)};
  for(int y=1;y<51;y+=2)for(int x=1;x<51;x+=2){const double ray=normal.x*(x-25)/100.+normal.y*(y-25)/100.+normal.z;d.depth[y*51+x]=offset/ray;d.confidence[y*51+x]=.9;}
  return d;};
 // Fractional-grid sampling must reproduce a calibrated plane, not a
 // bilinear z approximation. Test both slope signs, asymmetric fractions,
 // exact-grid samples, and arbitrary SfM units. Float inputs set the tolerance.
 for(double sceneScale:{1e-30,1e-6,1.,1e6,1e30})for(double slope:{-.9,.9}){
  const rf::Vec3 n{slope,.7,1};const auto d=plane(n,4*sceneScale);
  for(double x:{19.,19.2,19.7,20.5})for(double y:{23.,23.3,24.6}){
   const auto sampled=rf::consistency::sampleDepth(d,x,y,2,1);
   const double expected=4*sceneScale/(n.x*(x-25)/100.+n.y*(y-25)/100.+n.z);
   assert(sampled.classification==V::Supported);
   assert(std::abs(sampled.depth/expected-1)<8e-8);
   assert(std::abs(sampled.confidence-.9)<1e-6);
  }
 }
 const rf::Vec3 worldNormal{.25,-.15,1};auto tilted=plane(worldNormal,4);
 auto normal=rf::consistency::estimateNormal(camera,tilted,25,25,2,1);assert(normal.valid);
 const double scale=std::hypot(worldNormal.x,worldNormal.y,worldNormal.z);
 assert(std::abs(normal.cameraNormal.x-worldNormal.x/scale)<1e-5&&std::abs(normal.cameraNormal.y-worldNormal.y/scale)<1e-5);
 // The same observed plane must retain its normal when arbitrary SfM scale
 // changes. An absolute cross-product area cutoff used to reject small units.
 for(double sceneScale:{1e-30,1e-20,1e-6,1e-3,1.,1e6,1e20,1e30}){
  const auto scaledMap=plane(worldNormal,4*sceneScale);
  const auto scaledNormal=rf::consistency::estimateNormal(camera,scaledMap,25,25,2,1);
  assert(scaledNormal.valid);
  const double dot=scaledNormal.cameraNormal.x*normal.cameraNormal.x+
      scaledNormal.cameraNormal.y*normal.cameraNormal.y+scaledNormal.cameraNormal.z*normal.cameraNormal.z;
  assert(dot>1-1e-9);
  assert(std::abs(scaledNormal.confidence-normal.confidence)<1e-8);
 }
 auto source=camera;const double angle=.35;source.pose.rotation={std::cos(angle),0,std::sin(angle),0,1,0,-std::sin(angle),0,std::cos(angle)};
 auto sourceMap=plane({std::cos(angle)*worldNormal.x+std::sin(angle)*worldNormal.z,worldNormal.y,-std::sin(angle)*worldNormal.x+std::cos(angle)*worldNormal.z},4);
 auto sourceNormal=rf::consistency::estimateNormal(source,sourceMap,25,25,2,1);assert(sourceNormal.valid);
 auto agreement=rf::consistency::verifyNormals(normal,camera.pose,sourceNormal,source.pose);assert(agreement.classification==V::Supported&&agreement.angleDegrees<.001);
 // Equal center depth alone cannot prove agreement: independent slanted
 // neighborhoods produce incompatible normals and must reject support.
 auto incompatible=plane({-.8,0,1},4);auto opposing=rf::consistency::estimateNormal(camera,incompatible,25,25,2,1);assert(opposing.valid);
 assert(rf::consistency::verifyNormals(normal,camera.pose,opposing,camera.pose).classification==V::Inconsistent);
 auto curved=plane({0,0,1},4);for(int y=1;y<51;y+=2)for(int x=1;x<51;x+=2)curved.depth[y*51+x]=4+.00005*((x-25)*(x-25)+(y-25)*(y-25));
 auto curveNormal=rf::consistency::estimateNormal(camera,curved,25,25,2,1);assert(curveNormal.valid&&curveNormal.cameraNormal.z>.999);
 tilted.depth[25*51+27]=2;assert(!rf::consistency::estimateNormal(camera,tilted,25,25,2,1).valid);
 tilted.depth[25*51+27]=0;assert(!rf::consistency::estimateNormal(camera,tilted,25,25,2,1).valid);
 assert(!rf::consistency::estimateNormal(camera,sourceMap,1,1,2,1).valid);
 assert(rf::consistency::verifyNormals({},camera.pose,normal,camera.pose).classification==V::Unknown);
 // Missing or non-finite observed evidence stays unknown after normalization.
 for(float bad:{0.f,-1.f,std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity()}){
  for(int index:{25*51+25,25*51+27}){
   auto invalidMap=plane(worldNormal,4);invalidMap.depth[index]=bad;
   assert(!rf::consistency::estimateNormal(camera,invalidMap,25,25,2,1).valid);
  }
 }
 // Degenerate angular footprints are still rejected in dimensionless units.
 auto collapsed=camera;collapsed.fx=1e200;collapsed.fy=1e200;
 const auto flat=plane({0,0,1},4);
 assert(!rf::consistency::estimateNormal(collapsed,flat,25,25,2,1).valid);
 auto overflow=camera;overflow.fx=1e-300;overflow.fy=1e-300;
 assert(!rf::consistency::estimateNormal(overflow,flat,25,25,2,1).valid);
 // Normal settings participate in dense input identity, preventing stale reuse.
 static_assert(rf::consistency::NORMAL_SETTINGS_VERSION==3);
 auto invalid=camera;invalid.fx=0;assert(!rf::consistency::estimateNormal(invalid,sourceMap,25,25,2,1).valid);
 auto enormous=normal;enormous.cameraNormal={1e300,1e300,1e300};assert(rf::consistency::verifyNormals(enormous,camera.pose,normal,camera.pose).classification==V::Unknown);
 auto invalidPose=camera.pose;invalidPose.rotation[0]=2;assert(rf::consistency::verifyNormals(normal,invalidPose,normal,camera.pose).classification==V::Unknown);
 std::cout<<"continuous interpolation, thin-edge unknown, occlusion and reverse projection checks passed\n";
}
