#include "realityforge/surface_evidence.hpp"
#include <iostream>
#include <stdexcept>
#include <source_location>
namespace {
void check(bool c,std::source_location location=std::source_location::current()){if(!c)throw std::runtime_error("surface evidence regression at line "+std::to_string(location.line()));}
void near(double actual,double expected,double tolerance,std::source_location location=std::source_location::current()){if(!std::isfinite(actual)||std::abs(actual-expected)>tolerance)throw std::runtime_error("weighted evidence numerical mismatch at line "+std::to_string(location.line()));}
template<class F> void rejects(F action){bool rejected=false;try{action();}catch(const rf::Error&){rejected=true;}check(rejected);}
rf::DenseView confidenceView(){rf::DenseView v;v.width=v.height=64;v.fx=v.fy=200;v.cx=v.cy=30;v.pose.rotation=rf::identity();return v;}
rf::DenseDepthMap confidenceMap(double scale,double outlier){
    rf::DenseDepthMap map;map.width=map.height=64;map.depth.assign(4096,0);map.confidence.assign(4096,1);
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx)map.depth[(30+dy*3)*64+30+dx*3]=static_cast<float>(5*scale);
    map.depth[33*64+33]=static_cast<float>((5+outlier)*scale);return map;
}
std::vector<rf::NormalObservation> confidencePoints(const rf::DenseDepthMap& map){
    std::vector<rf::NormalObservation> points;
    for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){const int x=30+dx*3,y=30+dy*3;const auto id=static_cast<std::size_t>(y)*64+x;const double z=map.depth[id];points.push_back({{(x-30)*z/200,(y-30)*z/200,z},{0,0,0},id,map.confidence[id]});}
    return points;
}
void confidenceRegressions(){
    const auto view=confidenceView();
    // Fixed independent truth z=5. One corner is displaced by 1% or 2%, both
    // strictly below the unchanged 8% depth-jump gate, with reliability 1/8.
    // No photographs or measured benchmark were used to choose this fixture.
    for(double scale:{.01,1.,100.})for(double outlier:{.05,.1}){
        auto map=confidenceMap(scale,outlier);
        const auto uniform=rf::estimateDepthNeighborhoodNormal(view,map,30,30,3);
        map.confidence[33*64+33]=.125f;
        const auto weighted=rf::estimateDepthNeighborhoodNormal(view,map,30,30,3);
        if(weighted.validity!=rf::NormalValidity::Supported||weighted.supportingObservationIds.size()!=9)throw std::runtime_error("confidence fixture scale="+std::to_string(scale)+" outlier="+std::to_string(outlier)+" validity="+std::to_string(static_cast<int>(weighted.validity))+" retained="+std::to_string(weighted.supportingObservationIds.size())+" Neff="+std::to_string(weighted.uncertainty.effectiveSupport));
        check(!weighted.uncertainty.calibrated&&weighted.direction.z<-.997);
        near(weighted.uncertainty.effectiveSupport,8.235867446393762,1e-12);
        if(outlier==.05){
            check(uniform.validity==rf::NormalValidity::Supported&&weighted.direction.z<uniform.direction.z);
            near(std::acos(-weighted.direction.z)*180/3.14159265358979323846,1.877991188425489,.0001);
        }else{
            // Pre-change production fit rejects this same complete window.
            check(uniform.validity==rf::NormalValidity::IllConditioned);
            near(std::acos(-weighted.direction.z)*180/3.14159265358979323846,3.9257796850403786,.0001);
            near(weighted.uncertainty.conditioning,.6818248991440159,1e-6);
            near(weighted.uncertainty.planeResidual/scale,.01178364896490858,1e-7);
        }
    }
    auto map=confidenceMap(1,.05);
    auto points=confidencePoints(map);
    const auto uniform=rf::estimateObservedNormal(points,{0,0,5});
    // Explicitly equal reliability has exactly the old unit-weight arithmetic,
    // even for tiny common scales whose unnormalized squares would underflow.
    for(double common:{1.,.125,1e-300}){
        auto equal=points;for(auto& p:equal)p.weight=common;
        const auto n=rf::estimateObservedNormal(equal,{0,0,5});
        check(n.direction.x==uniform.direction.x&&n.direction.y==uniform.direction.y&&n.direction.z==uniform.direction.z);
        check(n.uncertainty.planeResidual==uniform.uncertainty.planeResidual&&n.uncertainty.conditioning==uniform.uncertainty.conditioning&&n.uncertainty.estimatedPositionSigma==uniform.uncertainty.estimatedPositionSigma);
        check(n.uncertainty.effectiveSupport==9&&n.supportingObservationIds==uniform.supportingObservationIds);
    }
    map=confidenceMap(1,.1);map.confidence[33*64+33]=.125f;points=confidencePoints(map);
    const auto weighted=rf::estimateObservedNormal(points,{0,0,5});
    for(double common:{.125,1e-300}){
        auto scaled=points;for(auto& p:scaled)p.weight*=common;
        const auto rescaled=rf::estimateObservedNormal(scaled,{0,0,5});
        check(weighted.validity==rescaled.validity);
        near(weighted.direction.x,rescaled.direction.x,1e-14);
        near(weighted.direction.y,rescaled.direction.y,1e-14);
        near(weighted.direction.z,rescaled.direction.z,1e-14);
        near(weighted.uncertainty.planeResidual,rescaled.uncertainty.planeResidual,1e-14);
        near(weighted.uncertainty.conditioning,rescaled.uncertainty.conditioning,1e-14);
        near(weighted.uncertainty.effectiveSupport,rescaled.uncertainty.effectiveSupport,1e-12);
    }
    auto permuted=points;std::reverse(permuted.begin(),permuted.end());
    const auto reordered=rf::estimateObservedNormal(permuted,{0,0,5});
    check(weighted.direction.z==reordered.direction.z&&weighted.supportingObservationIds==reordered.supportingObservationIds);
    // A rigid world transform rotates the fitted normal and preserves evidence.
    const double angle=.61,c=std::cos(angle),s=std::sin(angle);
    const std::array<double,9> rotation{c,0,s,0,1,0,-s,0,c};const rf::Vec3 shift{.4,-.2,.7};
    const auto transform=[&](rf::Vec3 p){p=rf::multiply(rotation,p);return rf::Vec3{p.x+shift.x,p.y+shift.y,p.z+shift.z};};
    auto transformed=points;for(auto& p:transformed){p.position=transform(p.position);p.cameraCenter=transform(p.cameraCenter);}
    const auto rigid=rf::estimateObservedNormal(transformed,transform({0,0,5}));const auto expected=rf::multiply(rotation,weighted.direction);
    near(rigid.direction.x,expected.x,1e-12);near(rigid.direction.y,expected.y,1e-12);near(rigid.direction.z,expected.z,1e-12);
    near(rigid.uncertainty.conditioning,weighted.uncertainty.conditioning,1e-12);
    near(rigid.uncertainty.planeResidual,weighted.uncertainty.planeResidual,1e-12);
    auto posed=view;posed.pose.rotation=rf::transpose(rotation);posed.pose.translation=rf::multiply(posed.pose.rotation,rf::Vec3{-shift.x,-shift.y,-shift.z});
    const auto adapted=rf::estimateDepthNeighborhoodNormal(posed,map,30,30,3);
    near(adapted.direction.x,expected.x,1e-12);near(adapted.direction.z,expected.z,1e-12);
    // Unequal reliable weights do not invent tilt on an exact plane.
    auto exact=confidenceMap(1,0);exact.confidence[33*64+33]=.125f;
    const auto exactNormal=rf::estimateDepthNeighborhoodNormal(view,exact,30,30,3);
    check(exactNormal.validity==rf::NormalValidity::Supported&&exactNormal.direction.z<-.999999999&&exactNormal.supportingObservationIds.size()==9);
    // All nine positions remain present, but three dominant reliabilities cannot
    // satisfy the existing five-neighbor requirement through tiny extra weights.
    auto weak=points;for(std::size_t i=0;i<weak.size();++i)weak[i].weight=i<3?1.:1./128;
    const auto insufficient=rf::estimateObservedNormal(weak,{0,0,5});
    check(insufficient.validity==rf::NormalValidity::SparseSupport&&insufficient.uncertainty.effectiveSupport<5);
    auto weakMap=confidenceMap(1,0);for(std::size_t i=0;i<weak.size();++i)weakMap.confidence[weak[i].observationId]=static_cast<float>(weak[i].weight);
    check(rf::estimateDepthNeighborhoodNormal(view,weakMap,30,30,3).validity==rf::NormalValidity::SparseSupport);
    rf::SurfaceEvidenceSettings calibrated;calibrated.calibratedNoise=true;
    check(!rf::estimateObservedNormal(points,{0,0,5},calibrated).uncertainty.calibrated);
    for(double invalid:{0.,-1.,1.01,std::numeric_limits<double>::infinity(),std::numeric_limits<double>::quiet_NaN()}){
        auto malformed=points;malformed.front().weight=invalid;
        rejects([&]{(void)rf::estimateObservedNormal(malformed,{0,0,5});});
    }
    map.confidence[27*64+27]=0;
    check(rf::estimateDepthNeighborhoodNormal(view,map,30,30,3).validity==rf::NormalValidity::SparseSupport);
    map.depth[33*64+33]=6;
    check(rf::estimateDepthNeighborhoodNormal(view,map,30,30,3).validity==rf::NormalValidity::Discontinuity);
    map=confidenceMap(1,.1);map.confidence[33*64+33]=.125f;
    for(float invalid:{-1.f,1.01f,std::numeric_limits<float>::infinity(),std::numeric_limits<float>::quiet_NaN()}){
        map.confidence[27*64+27]=invalid;
        rejects([&]{(void)rf::estimateDepthNeighborhoodNormal(view,map,30,30,3);});
    }
    map.confidence.clear();rejects([&]{(void)rf::estimateDepthNeighborhoodNormal(view,map,30,30,3);});
    rf::SurfaceEvidenceSettings bounded;bounded.maxObservations=8;
    rejects([&]{(void)rf::estimateObservedNormal(points,{0,0,5},bounded);});
    bounded.maxObservations=9;bounded.maxNeighbors=257;
    rejects([&]{(void)rf::estimateObservedNormal(points,{0,0,5},bounded);});
    std::atomic_bool cancelled{true};
    {rf::cancellation::Scope scope(&cancelled);
        bool observed=false;try{(void)rf::estimateObservedNormal(points,{0,0,5});}catch(const rf::cancellation::Cancelled&){observed=true;}check(observed);
        observed=false;try{(void)rf::estimateDepthNeighborhoodNormal(view,exact,30,30,3);}catch(const rf::cancellation::Cancelled&){observed=true;}check(observed);
    }
    check(rf::estimateDepthNeighborhoodNormal(view,exact,30,30,3).validity==rf::NormalValidity::Supported);
}
}
int main(){
    confidenceRegressions();
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
    layers.front().weight=.125;check(rf::estimateObservedNormal(layers,{0,0,2}).validity==rf::NormalValidity::Discontinuity);
    auto duplicate=plane;duplicate[1].observationId=duplicate[0].observationId;bool dup=false;try{(void)rf::estimateObservedNormal(duplicate,{0,0,2});}catch(const rf::Error&){dup=true;}check(dup);
    auto huge=plane;huge[0].position.x=1e200;bool overflow=false;try{(void)rf::estimateObservedNormal(huge,{0,0,2});}catch(const rf::Error&){overflow=true;}check(overflow);
    check(rf::estimateDepthNeighborhoodNormal(view,map,2147483647,4,1).validity==rf::NormalValidity::SparseSupport);
    view.pose.rotation[0]=2;bool pose=false;try{(void)rf::estimateDepthNeighborhoodNormal(view,map,4,4,1);}catch(const rf::Error&){pose=true;}check(pose);
    plane[0].position.z=INFINITY;bool rejected=false;try{(void)rf::estimateObservedNormal(plane,{0,0,2});}catch(const rf::Error&){rejected=true;}check(rejected);
    std::cout<<"analytic normals, bounded reliability PCA, effective support, transforms, cancellation and discontinuity cases passed\n";
}
