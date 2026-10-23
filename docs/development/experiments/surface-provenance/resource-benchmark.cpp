#include "realityforge/mesh_cleanup.hpp"
#ifdef RF_PROVENANCE_V3
#include "realityforge/surface_provenance.hpp"
#endif
#include <sys/resource.h>
#include <chrono>
#include <iostream>
int main(){
 rf::SurfaceMesh mesh;
 for(unsigned y=0;y<200;++y)for(unsigned x=0;x<200;++x){rf::SurfaceVertex v;v.xyz={static_cast<double>(x),static_cast<double>(y),5};mesh.vertices.push_back(v);}
 for(unsigned y=0;y<199;++y)for(unsigned x=0;x<199;++x){const auto a=y*200+x;mesh.triangles.push_back({a,a+1,a+201,0});mesh.triangles.push_back({a,a+201,a+200,0});}
 for(unsigned i=0;i<100000;++i)mesh.observations.push_back({i%40000,static_cast<std::uint8_t>(i/40000),static_cast<std::uint16_t>(i%40000%640),static_cast<std::uint16_t>(i%40000/640),i});
 const auto begin=std::chrono::steady_clock::now();auto plan=rf::planMeshCleanup(mesh);
#ifdef RF_PROVENANCE_V3
 auto targets=rf::exactCleanupObservationTargets(mesh,plan);
#endif
 auto cleaned=rf::applyMeshCleanup(mesh,plan);std::vector<std::int32_t> remap(40000);for(int i=0;i<40000;++i)remap[i]=i;
#ifdef RF_PROVENANCE_V3
 auto ledger=rf::compactSurfaceObservations(mesh.observations,remap,targets);
#endif
 const auto end=std::chrono::steady_clock::now();rusage usage{};getrusage(RUSAGE_SELF,&usage);
 std::cout<<"vertices="<<mesh.vertices.size()<<" triangles="<<mesh.triangles.size()<<" observations="<<mesh.observations.size()<<" observation_size="<<sizeof(rf::SurfaceObservationRef)<<" elapsed_ms="<<std::chrono::duration<double,std::milli>(end-begin).count()<<" peak_rss_kib="<<usage.ru_maxrss<<'\n';
}
