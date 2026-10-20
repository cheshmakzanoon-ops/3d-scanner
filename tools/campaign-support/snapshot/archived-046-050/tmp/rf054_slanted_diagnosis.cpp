#include "realityforge/surface_evidence.hpp"
#include <iostream>
#include <iomanip>
#include <stdexcept>
int main(){
 rf::DenseView view;view.width=view.height=65;view.fx=view.fy=1000;view.cx=view.cy=33;view.pose.rotation={1,0,0,0,1,0,0,0,1};
 rf::DenseDepthMap map{65,65,std::vector<float>(65*65,0),std::vector<float>(65*65,0)};
 const double pi=std::acos(-1.),a=75*pi/180,nx=std::sin(a),nz=std::cos(a),z0=50;
 std::vector<rf::NormalObservation> observations;double maximumJump=0,maximumDistance=0;int admittedByOldRadius=0;
 const double oldRadius=3*z0*3/1000;
 for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){int x=33+3*dx,y=33+3*dy;const float z=static_cast<float>(z0*nz/(nx*(x-33)/1000.+nz));map.depth[y*65+x]=z;map.confidence[y*65+x]=.95f;maximumJump=std::max(maximumJump,std::abs(z-z0)/z0);rf::Vec3 p{(x-33)*z/1000.,(y-33)*z/1000.,z};observations.push_back({p,{0,0,0},static_cast<std::uint64_t>(y*65+x)});double d=std::hypot(p.x,p.y,p.z-z0);maximumDistance=std::max(maximumDistance,d);if(d<=oldRadius)++admittedByOldRadius;}
 const auto current=rf::estimateDepthNeighborhoodNormal(view,map,33,33,3,.08);
 rf::SurfaceEvidenceSettings settings;settings.maxObservations=settings.maxNeighbors=9;settings.radius=maximumDistance*(1+1e-9);
 const auto bounded=rf::estimateObservedNormal(observations,{0,0,z0},settings);
 const double agreement=std::abs(bounded.direction.x*nx+bounded.direction.z*nz);
 std::cout<<std::setprecision(12)<<"all_observations="<<observations.size()<<" max_relative_jump="<<maximumJump<<" old_radius="<<oldRadius<<" old_retained="<<admittedByOldRadius<<" old_validity="<<static_cast<int>(current.validity)<<" actual_enclosing_radius="<<settings.radius<<" full_validity="<<static_cast<int>(bounded.validity)<<" full_support="<<bounded.supportingObservationIds.size()<<" conditioning="<<bounded.uncertainty.conditioning<<" plane_residual="<<bounded.uncertainty.planeResidual<<" normal_abs_dot_truth="<<agreement<<'\n';
 if(current.validity!=rf::NormalValidity::SparseSupport||bounded.validity!=rf::NormalValidity::Supported||maximumJump>=.08||bounded.uncertainty.conditioning<=.02||agreement<.999999)throw std::runtime_error("Counterexample did not isolate radius filtering");
}
