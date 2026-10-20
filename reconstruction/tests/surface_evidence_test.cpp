#include "realityforge/surface_evidence.hpp"
#include <iostream>
#include <stdexcept>
namespace {void check(bool c){if(!c)throw std::runtime_error("surface evidence regression");}}
int main(){
    std::vector<rf::NormalObservation> plane;
    for(int y=-2;y<=2;++y)for(int x=-2;x<=2;++x)plane.push_back({{x*.1,y*.1,2},{0,0,0},plane.size()});
    auto n=rf::estimateObservedNormal(plane,{0,0,2});check(n.validity==rf::NormalValidity::Supported&&n.direction.z<-.999999);check(n.uncertainty.planeResidual<1e-10&&!n.uncertainty.calibrated);
    auto noisy=plane;for(std::size_t i=0;i<noisy.size();++i)noisy[i].position.z+=(static_cast<int>(i%3)-1)*.01;
    auto q=rf::estimateObservedNormal(noisy,{0,0,2});check(q.validity==rf::NormalValidity::Supported&&q.uncertainty.planeResidual>n.uncertainty.planeResidual);
    std::vector<rf::NormalObservation> line;for(int i=0;i<9;++i)line.push_back({{i*.01,0,2},{0,0,0},line.size()});check(rf::estimateObservedNormal(line,{0,0,2}).validity==rf::NormalValidity::IllConditioned);
    auto diverse=plane;for(std::size_t i=0;i<diverse.size();++i)diverse[i].cameraCenter.x=i%2?1:-1;
    const auto parallax=rf::estimateObservedNormal(diverse,{0,0,2});check(parallax.uncertainty.maximumParallaxRadians>.5&&parallax.uncertainty.triangulationSigma.has_value()&&!n.uncertainty.triangulationSigma.has_value());
    auto opposite=plane;for(auto& o:opposite)o.cameraCenter.z=4;check(rf::estimateObservedNormal(opposite,{0,0,2}).direction.z>.999999);
    rf::DenseView view;view.width=view.height=9;view.fx=view.fy=100;view.cx=view.cy=4;view.pose.rotation=rf::identity();
    rf::DenseDepthMap map;map.width=map.height=9;map.depth.assign(81,2);map.confidence.assign(81,1);
    check(rf::estimateDepthNeighborhoodNormal(view,map,4,4,1).validity==rf::NormalValidity::Supported);
    map.depth[3*9+3]=3;check(rf::estimateDepthNeighborhoodNormal(view,map,4,4,1).validity==rf::NormalValidity::Discontinuity);
    check(rf::estimateDepthNeighborhoodNormal(view,map,0,0,1).validity==rf::NormalValidity::SparseSupport);
    // A complete calibrated 75-degree plane has all nine valid depths within
    // the unchanged eight-percent jump gate. Perspective distances exceed a
    // frontoparallel pixel-footprint radius; those are still observed neighbors.
    for(double scale:{.01,1.,100.}){
        rf::DenseView tilted;tilted.width=tilted.height=64;tilted.fx=tilted.fy=200;tilted.cx=tilted.cy=30;tilted.pose.rotation=rf::identity();
        rf::DenseDepthMap slanted;slanted.width=slanted.height=64;slanted.depth.assign(4096,0);slanted.confidence.assign(4096,1);
        const double radians=75*3.14159265358979323846/180.;
        for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
            const double depth=5*scale/(1+std::tan(radians)*dx*3/tilted.fx);
            if(std::abs(depth-5*scale)/(5*scale)>=.08)throw std::runtime_error("tilted fixture exceeded original jump gate");
            slanted.depth[(30+dy*3)*64+30+dx*3]=static_cast<float>(depth);
        }
        const auto normal=rf::estimateDepthNeighborhoodNormal(tilted,slanted,30,30,3);
        if(normal.validity!=rf::NormalValidity::Supported||normal.supportingObservationIds.size()!=9)throw std::runtime_error("validated slanted neighbors were truncated by a frontoparallel radius");
        check(normal.uncertainty.conditioning>=.02&&normal.direction.x*-std::sin(radians)+normal.direction.z*-std::cos(radians)>.99999);
        slanted.depth[27*64+27]=0;check(rf::estimateDepthNeighborhoodNormal(tilted,slanted,30,30,3).validity==rf::NormalValidity::Discontinuity);
        slanted.depth[27*64+27]=static_cast<float>(6*scale);check(rf::estimateDepthNeighborhoodNormal(tilted,slanted,30,30,3).validity==rf::NormalValidity::Discontinuity);
    }
    auto curved=plane;for(auto& p:curved)p.position.z+=.1*(p.position.x*p.position.x+p.position.y*p.position.y);auto curvature=rf::estimateObservedNormal(curved,{0,0,2});check(curvature.validity==rf::NormalValidity::Supported&&curvature.direction.z<-.999&&curvature.uncertainty.planeResidual>n.uncertainty.planeResidual);
    auto layers=plane;for(std::size_t i=0;i<layers.size();++i)layers[i].position.z+=i%2?.02:0;check(rf::estimateObservedNormal(layers,{0,0,2}).validity==rf::NormalValidity::Discontinuity);
    auto duplicate=plane;duplicate[1].observationId=duplicate[0].observationId;bool dup=false;try{(void)rf::estimateObservedNormal(duplicate,{0,0,2});}catch(const rf::Error&){dup=true;}check(dup);
    auto huge=plane;huge[0].position.x=1e200;bool overflow=false;try{(void)rf::estimateObservedNormal(huge,{0,0,2});}catch(const rf::Error&){overflow=true;}check(overflow);
    check(rf::estimateDepthNeighborhoodNormal(view,map,2147483647,4,1).validity==rf::NormalValidity::SparseSupport);
    view.pose.rotation[0]=2;bool pose=false;try{(void)rf::estimateDepthNeighborhoodNormal(view,map,4,4,1);}catch(const rf::Error&){pose=true;}check(pose);
    plane[0].position.z=INFINITY;bool rejected=false;try{(void)rf::estimateObservedNormal(plane,{0,0,2});}catch(const rf::Error&){rejected=true;}check(rejected);
    std::cout<<"analytic normals, noise ordering, degenerate support, facing and discontinuity cases passed\n";
}
