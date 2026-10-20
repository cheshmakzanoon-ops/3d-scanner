#include "realityforge/surface.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/surface_fusion.hpp"
#include "realityforge/mesh_topology.hpp"
#include "realityforge/mesh_cleanup.hpp"
#include "realityforge/depth_consistency.hpp"
#include "realityforge/progress.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <filesystem>
#include <chrono>
#include <atomic>
#include <cstdio>
#include <iomanip>
#include <limits>
#include <locale>
#include <numeric>
#include <sstream>
#include <unordered_map>
#include <unordered_set>
#include <tuple>

namespace rf {
namespace {
Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 mul(Vec3 a,double f){return {a.x*f,a.y*f,a.z*f};}
double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
double norm(Vec3 a){return std::sqrt(dot(a,a));}
bool finite(Vec3 a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
Vec3 cameraCenter(const DenseView& v){return mul(multiply(transpose(v.pose.rotation),v.pose.translation),-1);}
Vec3 cameraPoint(const DenseView& v,Vec3 w){return add(multiply(v.pose.rotation,w),v.pose.translation);}
struct Cell {std::int64_t x{},y{},z{};bool operator==(const Cell&) const = default;};
struct CellHash {
    std::size_t operator()(const Cell& c)const noexcept{
        auto h=std::hash<std::int64_t>{}(c.x);
        h^=std::hash<std::int64_t>{}(c.y)+0x9e3779b9+(h<<6)+(h>>2);
        h^=std::hash<std::int64_t>{}(c.z)+0x9e3779b9+(h<<6)+(h>>2);
        return h;
    }
};
Cell voxel(Vec3 p,double size){const auto v=surface_fusion::checkedVoxel(p,size);return {v[0],v[1],v[2]};}
struct Accum {
    Vec3 weightedPosition{},weightedNormal{};
    bool supportedNormal{};
    std::array<double,3> weightedColor{};
    double weight{};
    float maxConfidence{};
    std::uint8_t viewMask{};
};
struct FaceKey {std::uint32_t a{},b{},c{};bool operator==(const FaceKey&) const = default;};
struct FaceHash {std::size_t operator()(const FaceKey& f)const noexcept{
    auto h=std::hash<std::uint32_t>{}(f.a);
    h^=std::hash<std::uint32_t>{}(f.b)+0x9e3779b9+(h<<6)+(h>>2);
    h^=std::hash<std::uint32_t>{}(f.c)+0x9e3779b9+(h<<6)+(h>>2);
    return h;
}};
float median(std::vector<float>& v){
    if(v.empty())return 0;
    std::nth_element(v.begin(),v.begin()+static_cast<std::ptrdiff_t>(v.size()/2),v.end());return v[v.size()/2];
}
void u32(std::ostream& s,std::uint32_t v){for(int j=0;j<4;++j)s.put(static_cast<char>((v>>(j*8))&255));}
void f32(std::ostream& s,float f){std::uint32_t bits{};std::memcpy(&bits,&f,4);u32(s,bits);}
class StagedSurfaceFile {
public:
    std::string path;
    explicit StagedSurfaceFile(const std::string& destination):destination_(destination){
        static std::atomic_uint64_t nonce{0};
        for(int attempt=0;attempt<16;++attempt){
            path=destination+".partial."+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count())+"."+std::to_string(nonce.fetch_add(1));
            if(auto* file=std::fopen(path.c_str(),"wbx")){std::fclose(file);return;}
        }throw Error("Cannot reserve surface staging file");
    }
    ~StagedSurfaceFile(){if(!path.empty())std::remove(path.c_str());}
    void publish(){cancellation::checkpoint();std::error_code error;std::filesystem::rename(path,destination_,error);if(error)throw Error("Cannot atomically publish surface export");path.clear();}
private:std::string destination_;
};
void validateMesh(const SurfaceMesh& m){
    if(m.vertices.empty()||m.triangles.empty()||m.vertices.size()>40000||m.triangles.size()>80000)
        throw Error("No valid surface faces or mesh exceeds output bounds");
    for(auto& v:m.vertices)if(!finite(v.xyz)||!finite(v.normal)||norm(v.xyz)>1e6)
        throw Error("Non-finite surface geometry");
    for(auto& f:m.triangles)if(f.a>=m.vertices.size()||f.b>=m.vertices.size()||f.c>=m.vertices.size()||
                                    f.a==f.b||f.b==f.c||f.c==f.a)throw Error("Invalid surface face");
}
}
SurfaceMesh fuseDepthSurfaces(std::span<const DenseView> views,const DenseResult& dense,const SurfaceSettings& s){
    if(views.size()<2||views.size()>MAX_DENSE_VIEWS||dense.maps.size()!=views.size()||
       s.pixelStride<2||s.pixelStride>8||!std::isfinite(s.minimumConfidence)||
       s.minimumConfidence<0||s.minimumConfidence>1||
       !std::isfinite(s.maximumRelativeDepthJump)||s.maximumRelativeDepthJump<=0||s.maximumRelativeDepthJump>.25||
       !std::isfinite(s.maximumEdgePixelFactor)||s.maximumEdgePixelFactor<1.0||s.maximumEdgePixelFactor>8||
       s.maxVertices<10||s.maxVertices>40000||s.maxTriangles<10||s.maxTriangles>80000||
       s.maxVoxelOccupancy<1||s.maxVoxelOccupancy>512||s.maxFusionCandidates<1||s.maxFusionCandidates>4096)
        throw Error("Invalid surface settings or camera counts");
    const auto width=views.front().width,height=views.front().height;
    if(width<32||height<32||width>640||height>640||static_cast<std::size_t>(width)*height>320000)throw Error("Unsupported surface dimensions");
    if(dense.verifiedSamples.size()>MAX_DENSE_SAMPLES)throw Error("Surface sample budget exceeded");
    const std::size_t pixels=static_cast<std::size_t>(width)*height;
    for(const auto& v:views)if(v.width!=width||v.height!=height||
        !std::isfinite(v.fx)||!std::isfinite(v.fy)||v.fx<=0||v.fy<=0||
        !std::isfinite(v.cx)||!std::isfinite(v.cy))throw Error("Invalid surface camera intrinsics");
    for(const auto& v:views){
        if(!finite(v.pose.translation)||norm(v.pose.translation)>1e6)throw Error("Invalid surface camera translation");
        for(auto r:v.pose.rotation)if(!std::isfinite(r))throw Error("Invalid surface camera rotation");
        const auto orth=multiply(v.pose.rotation,transpose(v.pose.rotation));
        for(int i=0;i<9;++i)if(std::abs(orth[i]-(i%4==0?1.:0.))>.025)throw Error("Surface rotation not orthonormal");
        if(std::abs(determinant(v.pose.rotation)-1)>.025)throw Error("Surface pose has improper rotation");
    }
    for(const auto& map:dense.maps)if(map.width!=width||map.height!=height||
        map.depth.size()!=pixels||map.confidence.size()!=pixels)throw Error("Mismatched depth-map geometry");
    for(const auto& map:dense.maps)for(std::size_t i=0;i<pixels;++i){
        if((i&4095u)==0)cancellation::checkpoint();
        if(!std::isfinite(map.depth[i])||map.depth[i]<0||map.depth[i]>1e6||!std::isfinite(map.confidence[i])||map.confidence[i]<0||map.confidence[i]>1)throw Error("Invalid surface depth-map values");
    }
    SurfaceMesh mesh;mesh.inputSamples=dense.verifiedSamples.size();
    if(dense.verifiedSamples.empty())return mesh;
    std::vector<float> sampleSpacings;sampleSpacings.reserve(dense.verifiedSamples.size());
    std::unordered_set<std::size_t> sourcePixels;
    for(auto& p:dense.verifiedSamples){
        if(p.camera>=views.size())throw Error("Invalid dense sample camera index");
        const auto& v=views[p.camera];const auto c=cameraPoint(v,p.xyz);
        if(!finite(p.xyz)||norm(p.xyz)>1e6||!finite(c)||c.z<=.01||c.z>1e6||!std::isfinite(p.confidence)||
           p.confidence<0||p.confidence>1)throw Error("Invalid verified sample geometry");
        if(p.x>=width||p.y>=height||p.supportingViews<2||p.supportingViews>views.size())throw Error("Unverified surface sample");
        const auto pixel=static_cast<std::size_t>(p.y)*width+p.x;
        if(!sourcePixels.insert(static_cast<std::size_t>(p.camera)*pixels+pixel).second)throw Error("Repeated source pixel identity in surface observations");
        const double z=dense.maps[p.camera].depth[pixel];
        if(z<=0||std::abs(c.z-z)/z>.001||std::abs(v.fx*c.x/c.z+v.cx-p.x)>.25||std::abs(v.fy*c.y/c.z+v.cy-p.y)>.25)throw Error("Verified sample does not match source pixel/depth");
        sampleSpacings.push_back(static_cast<float>(c.z*s.pixelStride/std::min(v.fx,v.fy)));
    }
    // Median world-space pixel footprint bounds fusion distance and triangle edges.
    // Samples with two different source cameras may fuse; never fold two distinct
    // samples of the same image onto the same vertex (avoids triangle collapses).
    const float spacing=std::clamp(median(sampleSpacings),.00001f,1000.f);
    const double fusionRadius=std::max(.0001,static_cast<double>(spacing)*.60);
    const double cellSize=fusionRadius;
    std::unordered_map<Cell,std::vector<std::uint32_t>,CellHash> buckets;
    std::vector<Accum> accum;
    std::vector<std::vector<std::int32_t>> imageVertices(views.size(),std::vector<std::int32_t>(pixels,-1));
    accum.reserve(std::min<std::size_t>(dense.verifiedSamples.size(),s.maxVertices));
    std::vector<std::size_t> sampleOrder(dense.verifiedSamples.size());std::iota(sampleOrder.begin(),sampleOrder.end(),0);
    std::sort(sampleOrder.begin(),sampleOrder.end(),[&](auto a,auto b){const auto& p=dense.verifiedSamples[a];const auto& q=dense.verifiedSamples[b];return std::tie(p.xyz.x,p.xyz.y,p.xyz.z,p.camera,p.y,p.x)<std::tie(q.xyz.x,q.xyz.y,q.xyz.z,q.camera,q.y,q.x);});
    progress::begin(progress::Stage::Surface,0,0,sampleOrder.size());
    std::size_t processed=0;
    for(auto sourceId:sampleOrder){
        const auto& sample=dense.verifiedSamples[sourceId];
        progress::advance(processed++);
        const auto observedNormal=estimateDepthNeighborhoodNormal(views[sample.camera],dense.maps[sample.camera],sample.x,sample.y,s.pixelStride,s.maximumRelativeDepthJump);
        const bool normalSupported=observedNormal.validity==NormalValidity::Supported;
        if(sample.confidence<s.minimumConfidence)continue;
        const std::size_t pixel=static_cast<std::size_t>(sample.y)*width+sample.x;
        if(imageVertices[sample.camera][pixel]>=0)continue;
        const auto key=voxel(sample.xyz,cellSize);
        std::int64_t best=-1;double minDist=fusionRadius;std::size_t candidateVisits=0;
        for(int dz=-1;dz<=1;++dz)for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){
            auto it=buckets.find({key.x+dx,key.y+dy,key.z+dz});
            if(it==buckets.end())continue;
            for(auto index:it->second){
                if(++candidateVisits>s.maxFusionCandidates)throw Error("Surface fusion spatial candidate budget exceeded");
                const auto& a=accum[index];
                if(a.viewMask & (1u<<sample.camera))continue;
                if(!normalSupported||!a.supportedNormal||!surface_fusion::compatibleNormal(a.weightedNormal,observedNormal.direction))continue;
                auto avg=mul(a.weightedPosition,1./a.weight);
                double d=norm(sub(avg,sample.xyz));
                const auto projected=cameraPoint(views[sample.camera],avg);
                if(projected.z<=0)continue;
                const auto visibility=consistency::sampleDepth(dense.maps[sample.camera],views[sample.camera].fx*projected.x/projected.z+views[sample.camera].cx,views[sample.camera].fy*projected.y/projected.z+views[sample.camera].cy,s.pixelStride,3,s.maximumRelativeDepthJump);
                if(consistency::verify(visibility,projected.z,0,s.maximumRelativeDepthJump,1).classification!=consistency::Visibility::Supported)continue;
                if(d<minDist){best=index;minDist=d;}
            }
        }
        const double w=std::max(.005f,sample.confidence)*sample.supportingViews;
        std::uint32_t assigned{};
        if(best>=0){
            assigned=static_cast<std::uint32_t>(best);
            auto& a=accum[assigned];a.weightedPosition=add(a.weightedPosition,mul(sample.xyz,w));
            for(int k=0;k<3;++k)a.weightedColor[k]+=w*sample.color[k];
            a.weightedNormal=add(a.weightedNormal,mul(observedNormal.direction,w));
            a.weight+=w;a.viewMask|=static_cast<std::uint8_t>(1u<<sample.camera);
            a.maxConfidence=std::max(a.maxConfidence,sample.confidence);
            ++mesh.fusedObservations;
        } else {
            if(accum.size()>=s.maxVertices)continue;
            assigned=static_cast<std::uint32_t>(accum.size());
            if(buckets[key].size()>=s.maxVoxelOccupancy)throw Error("Surface voxel occupancy budget exceeded");
            Accum a;a.weightedPosition=mul(sample.xyz,w);a.weight=w;
            a.supportedNormal=normalSupported;if(normalSupported)a.weightedNormal=mul(observedNormal.direction,w);
            for(int k=0;k<3;++k)a.weightedColor[k]=w*sample.color[k];
            a.maxConfidence=sample.confidence;a.viewMask=static_cast<std::uint8_t>(1u<<sample.camera);
            accum.push_back(a);
            buckets[key].push_back(assigned);
        }
        imageVertices[sample.camera][pixel]=static_cast<std::int32_t>(assigned);
        mesh.observations.push_back({assigned,sample.camera,sample.x,sample.y,sourceId});
        ++mesh.acceptedSamples;
    }
    mesh.vertices.reserve(accum.size());
    for(auto& a:accum){
        SurfaceVertex v;v.xyz=mul(a.weightedPosition,1./a.weight);
        for(int k=0;k<3;++k)v.color[k]=static_cast<std::uint8_t>(std::clamp(std::round(a.weightedColor[k]/a.weight),0.,255.));
        v.confidence=a.maxConfidence;
        v.supportingViews=static_cast<std::uint8_t>(__builtin_popcount(static_cast<unsigned int>(a.viewMask)));
        mesh.vertices.push_back(v);
    }
    std::unordered_set<FaceKey,FaceHash> faces;
    faces.reserve(s.maxTriangles);
    const int step=s.pixelStride;
    auto validTriangle=[&](std::size_t cam,std::array<std::int32_t,3> ids,std::array<float,3> depths){
        if(ids[0]<0||ids[1]<0||ids[2]<0||ids[0]==ids[1]||ids[1]==ids[2]||ids[0]==ids[2])return false;
        const auto& view=views[cam];
        const double maxZ=std::max({depths[0],depths[1],depths[2]});
        const double minZ=std::min({depths[0],depths[1],depths[2]});
        if(minZ<=0||(maxZ-minZ)/maxZ>s.maximumRelativeDepthJump)return false;
        const auto& A=mesh.vertices[static_cast<std::size_t>(ids[0])].xyz;
        const auto& B=mesh.vertices[static_cast<std::size_t>(ids[1])].xyz;
        const auto& C=mesh.vertices[static_cast<std::size_t>(ids[2])].xyz;
        const double footprint=maxZ*step/std::min(view.fx,view.fy);
        const double limit=footprint*s.maximumEdgePixelFactor;
        if(norm(sub(A,B))>limit||norm(sub(A,C))>limit||norm(sub(B,C))>limit)return false;
        const Vec3 normal=cross(sub(B,A),sub(C,A));
        if(norm(normal)<footprint*footprint*.015)return false;
        std::array<std::uint32_t,3> sorted{static_cast<std::uint32_t>(ids[0]),static_cast<std::uint32_t>(ids[1]),static_cast<std::uint32_t>(ids[2])};
        std::sort(sorted.begin(),sorted.end());
        if(!faces.insert({sorted[0],sorted[1],sorted[2]}).second)return false;
        auto tri=SurfaceTriangle{static_cast<std::uint32_t>(ids[0]),static_cast<std::uint32_t>(ids[1]),static_cast<std::uint32_t>(ids[2]),static_cast<std::uint8_t>(cam)};
        const Vec3 centroid=mul(add(add(A,B),C),1./3);
        if(dot(normal,sub(cameraCenter(view),centroid))<0)std::swap(tri.b,tri.c);
        mesh.triangles.push_back(tri);
        return true;
    };
    for(std::size_t cam=0;cam<views.size();++cam){
        cancellation::checkpoint();
        const auto& grid=imageVertices[cam]; const auto& depth=dense.maps[cam].depth;
        // M07 samples patch centres at r+1; its default radius is 2.
        // We explicitly couple to that CPU reference, not arbitrary resampling.
        constexpr int start=3;
        for(int y=start;y+step<height-3 && mesh.triangles.size()<s.maxTriangles;y+=step){
            cancellation::checkpoint();
            for(int x=start;x+step<width-3 && mesh.triangles.size()<s.maxTriangles;x+=step){
                const std::size_t i=static_cast<std::size_t>(y)*width+x;
                std::array<std::size_t,4> pix{i,i+step,i+static_cast<std::size_t>(step)*width,i+static_cast<std::size_t>(step)*width+step};
                std::array<std::int32_t,4> ids{grid[pix[0]],grid[pix[1]],grid[pix[2]],grid[pix[3]]};
                if(std::any_of(ids.begin(),ids.end(),[](auto id){return id<0;})){
                    ++mesh.rejectedCells;continue;
                }
                const std::array<float,4> d{depth[pix[0]],depth[pix[1]],depth[pix[2]],depth[pix[3]]};
                const auto& v=mesh.vertices;
                const auto dist=[&](int a,int b){return norm(sub(v[static_cast<std::size_t>(ids[a])].xyz,
                                                                  v[static_cast<std::size_t>(ids[b])].xyz));};
                const bool diag03=dist(0,3)<dist(1,2);
                const auto face=[&](int a,int b,int c){
                    if(mesh.triangles.size()>=s.maxTriangles)return;
                    if(!validTriangle(cam,{ids[a],ids[b],ids[c]},{d[a],d[b],d[c]}))++mesh.rejectedTriangles;
                };
                if(diag03){face(0,1,3);face(0,3,2);}else{face(0,1,2);face(1,3,2);}
            }
        }
    }
    progress::advance(sampleOrder.size());
    {
        const auto initialTopology=analyzeMeshTopology(mesh);
        auto cleaned=applyMeshCleanup(mesh,planMeshCleanup(mesh,initialTopology));
        mesh=std::move(cleaned.mesh);
        mesh.topology.removedExactDefects=cleaned.plan.removedFaces.size();
    } // release first diagnostic/copy before normal compaction and final report
    // Area-weighted normals from accepted geometry; no normal guesses in holes.
    for(auto& t:mesh.triangles){
        const auto n=cross(sub(mesh.vertices[t.b].xyz,mesh.vertices[t.a].xyz),
                           sub(mesh.vertices[t.c].xyz,mesh.vertices[t.a].xyz));
        for(auto index:{t.a,t.b,t.c})mesh.vertices[index].normal=add(mesh.vertices[index].normal,n);
    }
    for(auto& v:mesh.vertices){
        const auto length=norm(v.normal);
        if(length>1e-15)v.normal=mul(v.normal,1./length);
    }
    // Remove unused vertices and remap indexed triangles for glTF/PLY portability.
    std::vector<std::int32_t> remap(mesh.vertices.size(),-1);
    std::vector<SurfaceVertex> compact;compact.reserve(mesh.vertices.size());
    for(auto& tri:mesh.triangles)for(auto* index:{&tri.a,&tri.b,&tri.c}){
        const auto old=*index;
        if(remap[old]<0){remap[old]=static_cast<std::int32_t>(compact.size());compact.push_back(mesh.vertices[old]);}
        *index=static_cast<std::uint32_t>(remap[old]);
    }
    for(auto& observation:mesh.observations){const auto mapped=remap[observation.vertex];observation.vertex=mapped<0?std::numeric_limits<std::uint32_t>::max():static_cast<std::uint32_t>(mapped);}
    mesh.observations.erase(std::remove_if(mesh.observations.begin(),mesh.observations.end(),[](const auto& o){return o.vertex==std::numeric_limits<std::uint32_t>::max();}),mesh.observations.end());
    mesh.vertices=std::move(compact);
    const auto report=analyzeMeshTopology(mesh);
    mesh.topology.boundaryEdges=report.boundaryEdges;mesh.topology.components=report.components;mesh.topology.nonmanifoldEdges=report.nonmanifoldEdges;mesh.topology.intersections=report.intersections.size();mesh.topology.ambiguousPairs=report.ambiguousPairs.size();mesh.topology.intersectionBudgetExceeded=report.intersectionBudgetExceeded;
    std::vector<float> scores;scores.reserve(mesh.vertices.size());
    for(auto& v:mesh.vertices)scores.push_back(v.confidence);
    mesh.medianConfidence=median(scores);
    return mesh;
}
std::string surfaceJson(const SurfaceMesh& m){
    std::ostringstream s;s.imbue(std::locale::classic());s<<std::setprecision(10);
    s<<"{\"schema\":8,\"representation\":\"observed_open_triangle_mesh\",\"scale\":\"arbitrary\","
     <<"\"textured\":false,\"watertight\":false,\"material\":\"vertex_colors\",\"vertexCount\":"<<m.vertices.size()
     <<",\"triangleCount\":"<<m.triangles.size()<<",\"inputSamples\":"<<m.inputSamples
     <<",\"acceptedSamples\":"<<m.acceptedSamples<<",\"fusedObservations\":"<<m.fusedObservations
     <<",\"rejectedCells\":"<<m.rejectedCells<<",\"rejectedTriangles\":"<<m.rejectedTriangles
     <<",\"medianConfidence\":"<<m.medianConfidence<<",\"fusionVersion\":2,\"boundaryEdges\":"<<m.topology.boundaryEdges<<",\"nonmanifoldEdges\":"<<m.topology.nonmanifoldEdges<<",\"intersections\":"<<m.topology.intersections<<",\"topologyIncomplete\":"<<(m.topology.intersectionBudgetExceeded?"true":"false")<<",\"vertices\":[";
    for(std::size_t i=0;i<m.vertices.size();++i){
        const auto& v=m.vertices[i];if(i)s<<',';
        s<<'['<<v.xyz.x<<','<<v.xyz.y<<','<<v.xyz.z<<','<<int(v.color[0])<<','<<int(v.color[1])<<','<<int(v.color[2])
         <<','<<v.normal.x<<','<<v.normal.y<<','<<v.normal.z<<','<<v.confidence<<','<<int(v.supportingViews)<<']';
    }
    s<<"],\"triangles\":[";
    for(std::size_t i=0;i<m.triangles.size();++i){if(i)s<<',';auto t=m.triangles[i];s<<'['<<t.a<<','<<t.b<<','<<t.c<<']';}
    s<<"]}";return s.str();
}
void writeSurfacePly(const SurfaceMesh& m,const std::string& filename){
    validateMesh(m);StagedSurfaceFile stage(filename);std::ofstream o(stage.path);if(!o)throw Error("Cannot create surface PLY");
    o.imbue(std::locale::classic());o<<std::setprecision(9);
    o<<"ply\nformat ascii 1.0\ncomment RealityForge observed open surface; arbitrary scale; no UV textures\n"
     <<"element vertex "<<m.vertices.size()<<"\nproperty float x\nproperty float y\nproperty float z\n"
     <<"property float nx\nproperty float ny\nproperty float nz\n"
     <<"property uchar red\nproperty uchar green\nproperty uchar blue\n"
     <<"element face "<<m.triangles.size()<<"\nproperty list uchar int vertex_indices\nend_header\n";
    for(auto& v:m.vertices)o<<v.xyz.x<<' '<<v.xyz.y<<' '<<v.xyz.z<<' '
        <<v.normal.x<<' '<<v.normal.y<<' '<<v.normal.z<<' '
        <<int(v.color[0])<<' '<<int(v.color[1])<<' '<<int(v.color[2])<<'\n';
    for(auto& tri:m.triangles)o<<"3 "<<tri.a<<' '<<tri.b<<' '<<tri.c<<'\n';
    o.flush();if(!o)throw Error("Surface PLY write failed");o.close();if(!o)throw Error("Surface PLY close failed");stage.publish();
}
void writeSurfaceGlb(const SurfaceMesh& m,const std::string& filename){
    validateMesh(m);
    const auto vertexCount=static_cast<std::uint32_t>(m.vertices.size());
    const auto indexCount=static_cast<std::uint32_t>(m.triangles.size()*3);
    const auto posBytes=vertexCount*12,normalBytes=vertexCount*12,colorBytes=vertexCount*4,indexBytes=indexCount*4;
    const auto binBytes=posBytes+normalBytes+colorBytes+indexBytes;
    std::array<float,3>lo{INFINITY,INFINITY,INFINITY},hi{-INFINITY,-INFINITY,-INFINITY};
    for(auto& v:m.vertices){const std::array<float,3>x{static_cast<float>(v.xyz.x),static_cast<float>(-v.xyz.y),static_cast<float>(-v.xyz.z)};
        for(int i=0;i<3;++i){lo[i]=std::min(lo[i],x[i]);hi[i]=std::max(hi[i],x[i]);}}
    std::ostringstream j;j.imbue(std::locale::classic());j<<std::setprecision(9);
    j<<"{\"asset\":{\"version\":\"2.0\",\"generator\":\"RealityForge M08\"},"
     <<"\"extras\":{\"scale\":\"arbitrary\",\"watertight\":false,\"photographicUV\":false},"
     <<"\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\"Observed open surface\"}],"
     <<"\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"COLOR_0\":2},\"indices\":3,\"mode\":4,\"material\":0}]}],"
     <<"\"materials\":[{\"doubleSided\":true,\"pbrMetallicRoughness\":{\"metallicFactor\":0,\"roughnessFactor\":1}}],"
     <<"\"buffers\":[{\"byteLength\":"<<binBytes<<"}],\"bufferViews\":["
     <<"{\"buffer\":0,\"byteOffset\":0,\"byteLength\":"<<posBytes<<",\"target\":34962},"
     <<"{\"buffer\":0,\"byteOffset\":"<<posBytes<<",\"byteLength\":"<<normalBytes<<",\"target\":34962},"
     <<"{\"buffer\":0,\"byteOffset\":"<<posBytes+normalBytes<<",\"byteLength\":"<<colorBytes<<",\"target\":34962},"
     <<"{\"buffer\":0,\"byteOffset\":"<<posBytes+normalBytes+colorBytes<<",\"byteLength\":"<<indexBytes<<",\"target\":34963}],"
     <<"\"accessors\":["
     <<"{\"bufferView\":0,\"componentType\":5126,\"count\":"<<vertexCount<<",\"type\":\"VEC3\",\"min\":["
     <<lo[0]<<','<<lo[1]<<','<<lo[2]<<"],\"max\":["<<hi[0]<<','<<hi[1]<<','<<hi[2]<<"]},"
     <<"{\"bufferView\":1,\"componentType\":5126,\"count\":"<<vertexCount<<",\"type\":\"VEC3\"},"
     <<"{\"bufferView\":2,\"componentType\":5121,\"normalized\":true,\"count\":"<<vertexCount<<",\"type\":\"VEC4\"},"
     <<"{\"bufferView\":3,\"componentType\":5125,\"count\":"<<indexCount<<",\"type\":\"SCALAR\"}]}";
    auto json=j.str();while(json.size()%4)json+=' ';
    StagedSurfaceFile stage(filename);std::ofstream o(stage.path,std::ios::binary);if(!o)throw Error("Cannot create GLB mesh");
    u32(o,0x46546C67);u32(o,2);u32(o,static_cast<std::uint32_t>(12+8+json.size()+8+binBytes));
    u32(o,static_cast<std::uint32_t>(json.size()));u32(o,0x4E4F534A);o.write(json.data(),static_cast<std::streamsize>(json.size()));
    u32(o,binBytes);u32(o,0x004E4942);
    for(auto& v:m.vertices){f32(o,static_cast<float>(v.xyz.x));f32(o,static_cast<float>(-v.xyz.y));f32(o,static_cast<float>(-v.xyz.z));}
    for(auto& v:m.vertices){f32(o,static_cast<float>(v.normal.x));f32(o,static_cast<float>(-v.normal.y));f32(o,static_cast<float>(-v.normal.z));}
    for(auto& v:m.vertices){for(auto c:v.color)o.put(static_cast<char>(c));o.put(static_cast<char>(255));}
    for(auto& t:m.triangles){u32(o,t.a);u32(o,t.b);u32(o,t.c);}
    o.flush();if(!o)throw Error("GLB mesh write failed");o.close();if(!o)throw Error("GLB mesh close failed");stage.publish();
}
} // namespace rf

extern "C" char* rf_surface_json(const std::uint8_t* rgba,int width,int height,
    const double* camera_rows,std::size_t camera_count,
    double near_depth,double far_depth,int depth_planes,int pixel_stride,
    const char* glb_path,const char* ply_path) noexcept {
    try {
    std::string response;
    try{
        if(!rgba||!camera_rows||camera_count<2||camera_count>rf::MAX_DENSE_VIEWS||width<32||height<32||width>640||height>640||
           static_cast<std::int64_t>(width)*height>320000)throw rf::Error("Invalid surface input buffers");
        std::vector<rf::DenseView> views;views.reserve(camera_count);
        const std::size_t bytes=static_cast<std::size_t>(width)*height*4;
        for(std::size_t i=0;i<camera_count;++i){
            rf::DenseView v;v.width=width;v.height=height;
            for(int k=0;k<9;++k)v.pose.rotation[k]=camera_rows[i*16+k];
            v.pose.translation={camera_rows[i*16+9],camera_rows[i*16+10],camera_rows[i*16+11]};
            v.fx=camera_rows[i*16+12];v.fy=camera_rows[i*16+13];v.cx=camera_rows[i*16+14];v.cy=camera_rows[i*16+15];
            v.rgba.assign(rgba+i*bytes,rgba+(i+1)*bytes);
            views.push_back(std::move(v));
        }
        rf::DenseSettings denseSettings;
        denseSettings.nearDepth=near_depth;denseSettings.farDepth=far_depth;
        denseSettings.depthPlanes=depth_planes;denseSettings.pixelStride=pixel_stride;
        auto dense=rf::reconstructDense(views,denseSettings);
        response="{\"schema\":8,\"dense\":"+rf::denseJson(dense);
        try{
            rf::SurfaceSettings settings;settings.pixelStride=pixel_stride;
            auto mesh=rf::fuseDepthSurfaces(views,dense,settings);
            if(mesh.triangles.size()<8)throw rf::Error("Fewer than eight verified surface faces");
            if(glb_path&&*glb_path)rf::writeSurfaceGlb(mesh,glb_path);
            if(ply_path&&*ply_path)rf::writeSurfacePly(mesh,ply_path);
            response+=",\"mesh\":"+rf::surfaceJson(mesh);
        }catch(const rf::cancellation::Cancelled&){throw;}
        catch(const std::exception& e){
            response+=",\"meshError\":\"";
            for(char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(static_cast<unsigned char>(c)>=32)response+=c;}
            response+='"';
        }
        response+='}';
    }catch(const std::exception& e){
        response="{\"error\":\"";
        for(char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(static_cast<unsigned char>(c)>=32)response+=c;}
        response+="\"}";
    }
    auto* output=static_cast<char*>(std::malloc(response.size()+1));if(!output)return nullptr;
    std::memcpy(output,response.c_str(),response.size()+1);
    return output;
    }catch(...){
        constexpr char failure[]=R"({"error":"Native surface failure"})";
        auto* result=static_cast<char*>(std::malloc(sizeof(failure)));if(result)std::memcpy(result,failure,sizeof(failure));return result;
    }
}
