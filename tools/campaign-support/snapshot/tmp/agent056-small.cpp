#include "realityforge/mesh_cleanup.hpp"
#include <iostream>
int main(){rf::SurfaceMesh m;for(auto xyz:{rf::Vec3{0,0,0},rf::Vec3{1e-200,0,0},rf::Vec3{0,1e-200,0}}){rf::SurfaceVertex v;v.xyz=xyz;m.vertices.push_back(v);}m.triangles={{0,1,2,0}};std::cout<<rf::planMeshCleanup(m).removedFaces.size()<<'\n';}
