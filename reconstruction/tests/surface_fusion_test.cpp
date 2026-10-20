#include "realityforge/surface_fusion.hpp"
#include "realityforge/mesh_topology.hpp"
#include <algorithm>
#include <atomic>
#include <iostream>
#include <stdexcept>
#include <set>
namespace {
void check(bool c,const char* message){if(!c)throw std::runtime_error(message);}
std::pair<std::vector<rf::DenseView>,rf::DenseResult> plane(bool opposite){
    std::vector<rf::DenseView> views(2);rf::DenseResult dense;dense.maps.resize(2);
    for(int camera=0;camera<2;++camera){
        auto& view=views[camera];view.width=view.height=32;view.fx=view.fy=100;view.cx=view.cy=15;view.pose.rotation=rf::identity();
        if(camera==1&&opposite){view.pose.rotation={-1,0,0,0,1,0,0,0,-1};view.pose.translation={0,0,10};}
        auto& map=dense.maps[camera];map.width=map.height=32;map.depth.assign(1024,0);map.confidence.assign(1024,0);
        for(int y=3;y<29;y+=3)for(int x=3;x<29;x+=3){
            const float z=camera==1&&opposite?4.99f:5.f;map.depth[y*32+x]=z;map.confidence[y*32+x]=.95f;
            const auto xyz=rf::multiply(rf::transpose(view.pose.rotation),rf::Vec3{(x-15)*z/100-view.pose.translation.x,(y-15)*z/100-view.pose.translation.y,z-view.pose.translation.z});
            dense.verifiedSamples.push_back({xyz,{150,140,130},.95f,2,static_cast<std::uint8_t>(camera),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
        }
    }return {views,dense};
}
std::pair<std::vector<rf::DenseView>,rf::DenseResult> depthPatches(bool includeNear){
    std::vector<rf::DenseView> views(2);rf::DenseResult dense;dense.maps.resize(2);
    for(int camera=0;camera<2;++camera){
        auto& view=views[camera];view.width=view.height=96;view.fx=view.fy=200;view.cx=view.cy=45;view.pose.rotation=rf::identity();view.pose.translation.x=-.2*camera;
        auto& map=dense.maps[camera];map.width=map.height=96;map.depth.assign(96*96,0);map.confidence.assign(96*96,0);
        for(int y=3;y<91;y+=3)for(int x=3;x<91;x+=3){
            if(x>57&&x<66)continue;
            const bool far=x>=66;if(!far&&!includeNear)continue;
            const double z=far?50:5;map.depth[y*96+x]=z;map.confidence[y*96+x]=.95f;
            const rf::Vec3 xyz{(x-view.cx)*z/view.fx-view.pose.translation.x,(y-view.cy)*z/view.fy,z};
            dense.verifiedSamples.push_back({xyz,{150,140,130},.95f,2,static_cast<std::uint8_t>(camera),static_cast<std::uint16_t>(x),static_cast<std::uint16_t>(y)});
        }
    }return {views,dense};
}
std::pair<std::vector<rf::DenseView>,rf::DenseResult> movingCentroid(){
    std::vector<rf::DenseView> views(8);rf::DenseResult dense;dense.maps.resize(8);double centroid=.1;
    for(int camera=0;camera<8;++camera){
        const double point=camera?centroid+.085:.1;
        auto& view=views[camera];view.width=view.height=32;view.fx=view.fy=100;view.cx=13;view.cy=15;view.pose.rotation=rf::identity();view.pose.translation.x=-(point-.1);
        auto& map=dense.maps[camera];map.width=map.height=32;map.depth.assign(1024,0);map.confidence.assign(1024,0);
        for(int y=0;y<32;y+=3)for(int x=0;x<32;x+=3){map.depth[y*32+x]=5;map.confidence[y*32+x]=.95f;}
        dense.verifiedSamples.push_back({{point,0,5},{150,140,130},.95f,2,static_cast<std::uint8_t>(camera),15,15});
        centroid=(centroid*camera+point)/(camera+1);
    }return {views,dense};
}
std::size_t farFusions(const rf::SurfaceMesh& mesh){
    std::set<std::uint32_t> seen;std::size_t count=0;
    for(const auto& source:mesh.observations)if(mesh.vertices[source.vertex].xyz.z>25&&!seen.insert(source.vertex).second)++count;
    return count;
}
}
int main(){
    check(std::abs(rf::surface_fusion::observedFusionRadius(50,200,3)-.45)<1e-12,"local calibrated footprint changed");
    check(rf::surface_fusion::observedFusionRadius(1e6,std::numeric_limits<double>::denorm_min(),8)==600,"maximum footprint clamp failed");
    check(rf::surface_fusion::observedFusionRadius(1e-100,std::numeric_limits<double>::max(),2)==.0001,"minimum footprint clamp failed");
    for(std::size_t level=0;level<rf::surface_fusion::radiusLevelCount;++level){
        const double cell=rf::surface_fusion::radiusCellSize(level);
        if(cell<=600){check(rf::surface_fusion::radiusLevel(cell)==level,"radius level boundary misclassified");if(level+1<rf::surface_fusion::radiusLevelCount)check(rf::surface_fusion::radiusLevel(std::nextafter(cell,INFINITY))==level+1,"radius level rounding lost a cell");}
    }
    bool badRadius=false;try{(void)rf::surface_fusion::radiusLevel(INFINITY);}catch(const rf::Error&){badRadius=true;}check(badRadius,"nonfinite spatial radius accepted");
    constexpr double radians=3.14159265358979323846/180.;
    for(double scale:{1e-300,.01,.4,1.,1e300,std::numeric_limits<double>::max()}){
        check(rf::surface_fusion::compatibleNormal({0,0,scale},{std::sin(20*radians),0,std::cos(20*radians)}),"positive normal scale changed20degree agreement");
        check(!rf::surface_fusion::compatibleNormal({0,0,scale},{std::sin(40*radians),0,std::cos(40*radians)}),"normal40degree disagreement was accepted");
        check(!rf::surface_fusion::compatibleNormal({0,0,scale},{0,0,-1}),"opposing weighted normal accepted");
    }
    check(rf::surface_fusion::compatibleNormal({0,0,std::numeric_limits<double>::denorm_min()},{0,0,1}),"positive subnormal direction rejected");
    check(!rf::surface_fusion::compatibleNormal({},{0,0,1}),"zero normal accepted");
    check(!rf::surface_fusion::compatibleNormal({0,0,std::numeric_limits<double>::infinity()},{0,0,1}),"infinite normal accepted");
    check(!rf::surface_fusion::compatibleNormal({0,0,std::numeric_limits<double>::quiet_NaN()},{0,0,1}),"NaN normal accepted");
    bool overflow=false;try{(void)rf::surface_fusion::checkedVoxel({1e300,0,0},.01);}catch(const rf::Error&){overflow=true;}check(overflow,"voxel integer overflow accepted");
    auto [views,dense]=plane(false);const auto mesh=rf::fuseDepthSurfaces(views,dense);check(mesh.fusedObservations>20,"overlapping supported plane did not fuse");check(!mesh.observations.empty(),"source provenance lost");check(rf::surfaceJson(mesh).find("\"fusionVersion\":4")!=std::string::npos,"local footprint algorithm version missing");
    // Confidence is an admitted observation weight, not a normal-vector length.
    // Identical supported planes must associate identically above the configured
    // minimum confidence; changing weights must not rotate or invalidate normals.
    auto lowerConfidence=dense;
    for(auto& map:lowerConfidence.maps)for(auto& confidence:map.confidence)if(confidence>0)confidence=.20f;
    for(auto& sample:lowerConfidence.verifiedSamples)sample.confidence=.20f;
    const auto lowMesh=rf::fuseDepthSurfaces(views,lowerConfidence);
    check(lowMesh.fusedObservations==mesh.fusedObservations,"admitted confidence weights invalidated identical supported normal directions");
    check(lowMesh.triangles.size()==mesh.triangles.size(),"confidence-only change altered exact plane topology");
    check(lowMesh.medianConfidence<=.20f,"fusion inflated admitted observation confidence");
    auto [farViews,farDepth]=depthPatches(false);const auto farOnly=rf::fuseDepthSurfaces(farViews,farDepth);
    auto [mixedViews,mixedDepth]=depthPatches(true);const auto mixed=rf::fuseDepthSurfaces(mixedViews,mixedDepth);
    check(farFusions(farOnly)>100,"calibrated far-plane observations did not associate");
    check(farFusions(mixed)==farFusions(farOnly),"unrelated near geometry changed far-plane association resolution");
    auto [movingViews,movingDepth]=movingCentroid();const auto moving=rf::fuseDepthSurfaces(movingViews,movingDepth);
    check(moving.fusedObservations==7,"moved multiview centroid became undiscoverable in a stale spatial bucket");
    rf::SurfaceSettings bounded;bounded.maxFusionCandidates=1;bool budgetRejected=false;
    try{(void)rf::fuseDepthSurfaces(mixedViews,mixedDepth,bounded);}catch(const rf::Error&){budgetRejected=true;}check(budgetRejected,"candidate work limit was bypassed");
    auto duplicate=dense;duplicate.verifiedSamples.push_back(duplicate.verifiedSamples.front());bool duplicateRejected=false;try{(void)rf::fuseDepthSurfaces(views,duplicate);}catch(const rf::Error&){duplicateRejected=true;}check(duplicateRejected,"duplicate source identity accepted");
    auto reversed=dense;std::reverse(reversed.verifiedSamples.begin(),reversed.verifiedSamples.end());const auto reordered=rf::fuseDepthSurfaces(views,reversed);check(rf::surfaceJson(mesh)==rf::surfaceJson(reordered),"sample order changed fused geometry");
    auto [thinViews,thinDense]=plane(true);const auto thin=rf::fuseDepthSurfaces(thinViews,thinDense);check(thin.fusedObservations==0,"opposite thin sides were merged");check(thin.triangles.size()>100,"thin observations disappeared");
    bool malformed=false;thinViews[0].pose.rotation[0]=2;try{(void)rf::fuseDepthSurfaces(thinViews,thinDense);}catch(const rf::Error&){malformed=true;}check(malformed,"improper source pose accepted");
    malformed=false;dense.verifiedSamples[0].xyz.x=1e300;try{(void)rf::fuseDepthSurfaces(views,dense);}catch(const rf::Error&){malformed=true;}check(malformed,"extreme source position accepted");
    std::atomic_bool stopped=true;rf::cancellation::Scope scope(&stopped);bool cancelled=false;try{(void)rf::fuseDepthSurfaces(views,plane(false).second);}catch(const rf::cancellation::Cancelled&){cancelled=true;}check(cancelled,"surface cancellation ignored");
    auto* result=rf_surface_json(nullptr,32,32,nullptr,2,2,9,32,3,nullptr,nullptr);check(result!=nullptr,"no-throw C ABI failed to serialize error");rf_free(result);
    std::cout<<"checked voxels, supported/hostile fusion, provenance, permutation, opposing sheets and C ABI passed\n";
}
