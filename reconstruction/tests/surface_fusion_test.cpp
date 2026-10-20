#include "realityforge/surface_fusion.hpp"
#include "realityforge/mesh_topology.hpp"
#include <algorithm>
#include <atomic>
#include <iostream>
#include <stdexcept>
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
}
int main(){
    bool overflow=false;try{(void)rf::surface_fusion::checkedVoxel({1e300,0,0},.01);}catch(const rf::Error&){overflow=true;}check(overflow,"voxel integer overflow accepted");
    auto [views,dense]=plane(false);const auto mesh=rf::fuseDepthSurfaces(views,dense);check(mesh.fusedObservations>20,"overlapping supported plane did not fuse");check(!mesh.observations.empty(),"source provenance lost");
    auto duplicate=dense;duplicate.verifiedSamples.push_back(duplicate.verifiedSamples.front());bool duplicateRejected=false;try{(void)rf::fuseDepthSurfaces(views,duplicate);}catch(const rf::Error&){duplicateRejected=true;}check(duplicateRejected,"duplicate source identity accepted");
    auto reversed=dense;std::reverse(reversed.verifiedSamples.begin(),reversed.verifiedSamples.end());const auto reordered=rf::fuseDepthSurfaces(views,reversed);check(rf::surfaceJson(mesh)==rf::surfaceJson(reordered),"sample order changed fused geometry");
    auto [thinViews,thinDense]=plane(true);const auto thin=rf::fuseDepthSurfaces(thinViews,thinDense);check(thin.fusedObservations==0,"opposite thin sides were merged");check(thin.triangles.size()>100,"thin observations disappeared");
    bool malformed=false;thinViews[0].pose.rotation[0]=2;try{(void)rf::fuseDepthSurfaces(thinViews,thinDense);}catch(const rf::Error&){malformed=true;}check(malformed,"improper source pose accepted");
    malformed=false;dense.verifiedSamples[0].xyz.x=1e300;try{(void)rf::fuseDepthSurfaces(views,dense);}catch(const rf::Error&){malformed=true;}check(malformed,"extreme source position accepted");
    std::atomic_bool stopped=true;rf::cancellation::Scope scope(&stopped);bool cancelled=false;try{(void)rf::fuseDepthSurfaces(views,plane(false).second);}catch(const rf::cancellation::Cancelled&){cancelled=true;}check(cancelled,"surface cancellation ignored");
    auto* result=rf_surface_json(nullptr,32,32,nullptr,2,2,9,32,3,nullptr,nullptr);check(result!=nullptr,"no-throw C ABI failed to serialize error");rf_free(result);
    std::cout<<"checked voxels, supported/hostile fusion, provenance, permutation, opposing sheets and C ABI passed\n";
}
