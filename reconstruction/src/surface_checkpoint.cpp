#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <atomic>
#include <bit>
#include <cerrno>
#include <cmath>
#include <cstring>
#include <filesystem>
#include <limits>
#include <utility>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace rf::surface_checkpoint {
namespace {
constexpr std::array<std::uint8_t,8> MAGIC{'R','F','M','S','H','0','0','2'};
constexpr std::size_t BLOCK=64*1024;
constexpr std::size_t MAX_POINTS=50000,MAX_VERTICES=40000,MAX_TRIANGLES=80000;
constexpr std::size_t POINT_BYTES=32,SAMPLE_BYTES=66,VERTEX_BYTES=56,TRIANGLE_BYTES=13;
constexpr std::size_t MIN_BYTES=316+32;
// Preserve the admitted 10 MiB geometry allocation cap despite richer evidence.
// Large valid reconstructions remain usable but skip this optional cache.
constexpr std::size_t MAX_GEOMETRY_BYTES=10*1024*1024;
constexpr std::size_t OBSERVATION_BYTES=17;
bool withinMemoryBudget(std::size_t points,std::size_t samples,std::size_t vertices,
                        std::size_t triangles,std::size_t observations){
    if(points>MAX_POINTS||samples>MAX_DENSE_SAMPLES||vertices>MAX_VERTICES||
       triangles>MAX_TRIANGLES||observations>MAX_DENSE_SAMPLES)return false;
    return points*sizeof(DensePoint)+samples*sizeof(VerifiedDepthSample)+
        vertices*sizeof(SurfaceVertex)+triangles*sizeof(SurfaceTriangle)+
        observations*sizeof(SurfaceObservationRef)<MAX_GEOMETRY_BYTES;
}
std::atomic<std::uint64_t> nextTemporary{1};

class FileDescriptor final {
public:
    explicit FileDescriptor(int value=-1):value_(value){}
    ~FileDescriptor(){if(value_>=0)::close(value_);}
    FileDescriptor(const FileDescriptor&)=delete;
    FileDescriptor& operator=(const FileDescriptor&)=delete;
    FileDescriptor(FileDescriptor&& other) noexcept:value_(std::exchange(other.value_,-1)){}
    FileDescriptor& operator=(FileDescriptor&& other) noexcept {
        if(this!=&other){if(value_>=0)::close(value_);value_=std::exchange(other.value_,-1);}return *this;
    }
    int get() const{return value_;}
    explicit operator bool() const{return value_>=0;}
private:int value_;
};

FileDescriptor directory(const depth_checkpoint::Scope& scope) {
    const std::filesystem::path path(scope.directory());
    if(scope.directory().find('\0')!=std::string::npos||!path.is_absolute()||path.lexically_normal()!=path||path.filename()!=scope.identity())
        return FileDescriptor{};
#if (defined(__linux__) || defined(__ANDROID__)) && defined(O_PATH)
    // Android app-data ancestors may be searchable but not readable. Traverse
    // with path-only FDs, retaining no-follow checks on every single component.
    constexpr int traversalAccess=O_PATH;
#else
    constexpr int traversalAccess=O_RDONLY;
#endif
    FileDescriptor current(::open("/",traversalAccess|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
    if(!current)return FileDescriptor{};
    for(const auto& component:path.relative_path()) {
        if(component.empty()||component=="."||component=="..")return FileDescriptor{};
        FileDescriptor next(::openat(current.get(),component.c_str(),traversalAccess|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
        if(!next)return FileDescriptor{};
        current=std::move(next);
    }
#if (defined(__linux__) || defined(__ANDROID__)) && defined(O_PATH)
    // O_PATH cannot fsync. Reopen the held final inode, never the original path
    // (which could have been replaced), and require read access for durability.
    struct stat held{},reopened{};
    if(::fstat(current.get(),&held)!=0||!S_ISDIR(held.st_mode))return FileDescriptor{};
    FileDescriptor readable(::openat(current.get(),".",O_RDONLY|O_DIRECTORY|O_NOFOLLOW|O_CLOEXEC));
    if(!readable||::fstat(readable.get(),&reopened)!=0||!S_ISDIR(reopened.st_mode)||
       held.st_dev!=reopened.st_dev||held.st_ino!=reopened.st_ino)return FileDescriptor{};
    return readable;
#else
    return current;
#endif
}

void hashBytes(checkpoint::Sha256& digest,std::span<const std::uint8_t> bytes) {
    for(std::size_t i=0;i<bytes.size();i+=BLOCK) {
        cancellation::checkpoint();digest.update(bytes.subspan(i,std::min(BLOCK,bytes.size()-i)));
    }
}
template<class T> void hashInteger(checkpoint::Sha256& digest,T value) {
    std::array<std::uint8_t,sizeof(T)> bytes{};
    for(std::size_t i=0;i<bytes.size();++i)bytes[i]=static_cast<std::uint8_t>(value>>(8*i));
    digest.update(bytes);
}
void hashReal(checkpoint::Sha256& digest,double value){hashInteger(digest,std::bit_cast<std::uint64_t>(value));}
bool finitePoint(Vec3 value) {
    return std::isfinite(value.x)&&std::isfinite(value.y)&&std::isfinite(value.z)&&
        std::abs(value.x)<=1e6&&std::abs(value.y)<=1e6&&std::abs(value.z)<=1e6;
}
void validSettings(const SurfaceSettings& settings) {
    if(settings.pixelStride<2||settings.pixelStride>8||!std::isfinite(settings.minimumConfidence)||
        settings.minimumConfidence<0||settings.minimumConfidence>1||
        !std::isfinite(settings.maximumRelativeDepthJump)||settings.maximumRelativeDepthJump<=0||
        settings.maximumRelativeDepthJump>.25||!std::isfinite(settings.maximumEdgePixelFactor)||
        settings.maximumEdgePixelFactor<1||settings.maximumEdgePixelFactor>8||
        settings.maxVertices<10||settings.maxVertices>MAX_VERTICES||
        settings.maxTriangles<10||settings.maxTriangles>MAX_TRIANGLES||settings.maxVoxelOccupancy<1||
        settings.maxVoxelOccupancy>512||settings.maxFusionCandidates<1||settings.maxFusionCandidates>4096)
        throw Error("Invalid accepted-surface settings");
}

void validGeometry(std::span<const DenseView> views,const DenseResult& dense,const SurfaceMesh& mesh,
    const SurfaceSettings& settings) {
    validSettings(settings);
    if(!withinMemoryBudget(dense.points.size(),dense.verifiedSamples.size(),mesh.vertices.size(),
                           mesh.triangles.size(),mesh.observations.size()))throw Error("Accepted geometry exceeds cache memory budget");
    if(views.size()<2||views.size()>MAX_DENSE_VIEWS||dense.points.empty()||dense.points.size()>MAX_POINTS||
        dense.verifiedSamples.empty()||dense.verifiedSamples.size()>MAX_DENSE_SAMPLES||
        mesh.vertices.empty()||mesh.vertices.size()>settings.maxVertices||mesh.triangles.size()<8||
        mesh.triangles.size()>settings.maxTriangles||dense.sourceViews.size()!=views.size()||
        dense.consistentPixelsPerView.size()!=views.size()||dense.pointBudget<10||dense.pointBudget>MAX_POINTS||
        dense.points.size()>dense.pointBudget||dense.sampledPixels>MAX_DENSE_SAMPLES||
        dense.photometricPixels>dense.sampledPixels||dense.consistentPixels>dense.photometricPixels||
        dense.points.size()>dense.consistentPixels||dense.verifiedSamples.size()>dense.consistentPixels||
        mesh.inputSamples!=dense.verifiedSamples.size()||mesh.acceptedSamples>mesh.inputSamples||
        mesh.fusedObservations>mesh.acceptedSamples||mesh.rejectedCells>MAX_DENSE_SAMPLES||
        mesh.rejectedTriangles>2*MAX_DENSE_SAMPLES||!std::isfinite(dense.medianConfidence)||
        dense.medianConfidence<0||dense.medianConfidence>1||!std::isfinite(mesh.medianConfidence)||
        mesh.medianConfidence<0||mesh.medianConfidence>1)
        throw Error("Invalid accepted-surface counts or evidence");
    std::size_t consistentTotal=0;
    for(std::size_t i=0;i<views.size();++i) {
        const auto& source=dense.sourceViews[i];
        if(source.empty()||source.size()>3||dense.consistentPixelsPerView[i]>dense.consistentPixels)
            throw Error("Invalid accepted depth source evidence");
        for(std::size_t j=0;j<source.size();++j)if(source[j]>=views.size()||source[j]==i||
            std::find(source.begin(),source.begin()+static_cast<std::ptrdiff_t>(j),source[j])!=source.begin()+static_cast<std::ptrdiff_t>(j))
            throw Error("Invalid accepted depth source identity");
        consistentTotal+=dense.consistentPixelsPerView[i];
    }
    if(consistentTotal!=dense.consistentPixels)throw Error("Accepted depth counts do not reconcile");
    for(std::size_t i=0;i<dense.points.size();++i) {
        if((i&1023)==0)cancellation::checkpoint();
        const auto& p=dense.points[i];
        if(!finitePoint(p.xyz)||!std::isfinite(p.confidence)||p.confidence<0||p.confidence>1||
            p.consistentViews<2||p.consistentViews>views.size())throw Error("Invalid accepted dense point");
    }
    for(std::size_t i=0;i<dense.verifiedSamples.size();++i) {
        if((i&1023)==0)cancellation::checkpoint();
        const auto& p=dense.verifiedSamples[i];
        if(!finitePoint(p.xyz)||!std::isfinite(p.confidence)||p.confidence<0||p.confidence>1||
            p.supportingViews<2||p.supportingViews>views.size()||p.camera>=views.size()||
            p.x>=views[p.camera].width||p.y>=views[p.camera].height||
            !finitePoint(p.worldNormal)||!std::isfinite(p.normalConfidence)||p.normalConfidence<0||
            p.normalConfidence>1||p.normalSupportingViews>views.size())throw Error("Invalid accepted depth sample");
    }
    for(const auto& observation:mesh.observations){
        if(observation.vertex>=mesh.vertices.size()||observation.camera>=views.size()||
           observation.sourceSampleId>=dense.verifiedSamples.size())throw Error("Invalid accepted observation provenance");
        const auto& sample=dense.verifiedSamples[observation.sourceSampleId];
        if(observation.camera!=sample.camera||observation.x!=sample.x||observation.y!=sample.y)
            throw Error("Accepted observation does not match source sample");
    }
    for(std::size_t i=0;i<mesh.vertices.size();++i) {
        if((i&1023)==0)cancellation::checkpoint();
        const auto& v=mesh.vertices[i];
        if(!finitePoint(v.xyz)||!finitePoint(v.normal)||!std::isfinite(v.confidence)||v.confidence<0||v.confidence>1||
            v.supportingViews<1||v.supportingViews>views.size()||
            v.normal.x*v.normal.x+v.normal.y*v.normal.y+v.normal.z*v.normal.z>1.01)
            throw Error("Invalid accepted surface vertex");
    }
    for(std::size_t i=0;i<mesh.triangles.size();++i) {
        if((i&1023)==0)cancellation::checkpoint();
        const auto& t=mesh.triangles[i];
        if(t.a>=mesh.vertices.size()||t.b>=mesh.vertices.size()||t.c>=mesh.vertices.size()||
            t.a==t.b||t.a==t.c||t.b==t.c||t.sourceCamera>=views.size())throw Error("Invalid accepted surface face");
    }
}

checkpoint::Digest mapIdentity(std::span<const DenseDepthMap> maps,std::span<const DenseView> views) {
    if(maps.size()!=views.size())throw Error("Missing accepted depth maps");
    checkpoint::Sha256 digest;digest.update("realityforge-accepted-depth-maps-v1");
    hashInteger(digest,static_cast<std::uint32_t>(maps.size()));
    for(std::size_t i=0;i<maps.size();++i) {
        const auto& map=maps[i];const auto count=static_cast<std::size_t>(views[i].width)*views[i].height;
        if(map.width!=views[i].width||map.height!=views[i].height||map.depth.size()!=count||map.confidence.size()!=count)
            throw Error("Mismatched accepted depth map dimensions");
        hashInteger(digest,static_cast<std::uint32_t>(map.width));hashInteger(digest,static_cast<std::uint32_t>(map.height));
        for(std::size_t j=0;j<count;++j) {
            if((j&8191)==0)cancellation::checkpoint();
            const auto d=map.depth[j],c=map.confidence[j];
            if(!std::isfinite(d)||!std::isfinite(c)||d<0||d>100000||c<0||c>1||(d==0&&c!=0))
                throw Error("Invalid accepted depth values");
            hashInteger(digest,std::bit_cast<std::uint32_t>(d));hashInteger(digest,std::bit_cast<std::uint32_t>(c));
        }
    }
    return digest.finish();
}

struct Writer {
    std::vector<std::uint8_t> bytes;
    template<class T> void integer(T value){for(std::size_t i=0;i<sizeof(T);++i)bytes.push_back(static_cast<std::uint8_t>(value>>(8*i)));}
    void real(double value){integer(std::bit_cast<std::uint64_t>(value));}
    void real(float value){integer(std::bit_cast<std::uint32_t>(value));}
    void vector(Vec3 value){real(value.x);real(value.y);real(value.z);}
    void data(std::span<const std::uint8_t> value){bytes.insert(bytes.end(),value.begin(),value.end());}
};
struct Reader {
    std::span<const std::uint8_t> bytes;std::size_t offset=0;
    std::span<const std::uint8_t> data(std::size_t count) {
        if(count>bytes.size()-offset)throw Error("Truncated accepted-surface checkpoint");
        auto out=bytes.subspan(offset,count);offset+=count;return out;
    }
    template<class T> T integer(){auto part=data(sizeof(T));T value=0;for(std::size_t i=0;i<sizeof(T);++i)value|=static_cast<T>(part[i])<<(8*i);return value;}
    double real(){return std::bit_cast<double>(integer<std::uint64_t>());}
    float single(){return std::bit_cast<float>(integer<std::uint32_t>());}
    Vec3 vector(){return {real(),real(),real()};}
    std::array<std::uint8_t,3> color(){auto b=data(3);return {b[0],b[1],b[2]};}
    checkpoint::Digest digest(){checkpoint::Digest out{};auto part=data(out.size());std::copy(part.begin(),part.end(),out.begin());return out;}
};

std::vector<std::uint8_t> encode(const depth_checkpoint::Scope& scope,std::span<const DenseView> views,
    const DenseResult& dense,const SurfaceMesh& mesh,const SurfaceSettings& settings) {
    validGeometry(views,dense,mesh,settings);
    Writer out;
    out.bytes.reserve(512+views.size()*24+mesh.vertices.size()*VERTEX_BYTES+mesh.triangles.size()*TRIANGLE_BYTES+
        dense.points.size()*POINT_BYTES+dense.verifiedSamples.size()*SAMPLE_BYTES);
    out.data(MAGIC);out.integer(std::uint32_t{2});out.integer(std::uint32_t{0});
    out.integer(static_cast<std::uint32_t>(views.front().width));out.integer(static_cast<std::uint32_t>(views.front().height));
    out.integer(static_cast<std::uint32_t>(views.size()));out.integer(static_cast<std::uint32_t>(mesh.vertices.size()));
    out.integer(static_cast<std::uint32_t>(mesh.triangles.size()));out.integer(static_cast<std::uint32_t>(dense.points.size()));
    out.integer(static_cast<std::uint32_t>(dense.verifiedSamples.size()));
    out.data(std::span(reinterpret_cast<const std::uint8_t*>(scope.identity().data()),scope.identity().size()));
    out.data(geometryIdentity(views));out.data(algorithmIdentity(settings));out.data(mapIdentity(dense.maps,views));
    out.real(settings.minimumConfidence);out.real(settings.maximumRelativeDepthJump);out.real(settings.maximumEdgePixelFactor);
    out.integer(static_cast<std::uint32_t>(settings.maxVertices));out.integer(static_cast<std::uint32_t>(settings.maxTriangles));
    out.integer(static_cast<std::uint32_t>(settings.pixelStride));
    out.integer(static_cast<std::uint64_t>(dense.sampledPixels));out.integer(static_cast<std::uint64_t>(dense.photometricPixels));
    out.integer(static_cast<std::uint64_t>(dense.consistentPixels));out.real(dense.medianConfidence);out.integer(static_cast<std::uint32_t>(dense.pointBudget));
    for(const auto n:{mesh.inputSamples,mesh.acceptedSamples,mesh.rejectedCells,mesh.rejectedTriangles,mesh.fusedObservations})
        out.integer(static_cast<std::uint64_t>(n));
    out.real(mesh.medianConfidence);
    for(std::size_t i=0;i<views.size();++i) {
        out.integer(static_cast<std::uint32_t>(dense.sourceViews[i].size()));
        for(const auto index:dense.sourceViews[i])out.integer(static_cast<std::uint32_t>(index));
        out.integer(static_cast<std::uint64_t>(dense.consistentPixelsPerView[i]));
    }
    std::size_t encoded=0;
    const auto step=[&]{if((encoded++&1023)==0)cancellation::checkpoint();};
    for(const auto& p:dense.points){step();out.vector(p.xyz);out.data(p.color);out.real(p.confidence);out.integer(p.consistentViews);}
    for(const auto& p:dense.verifiedSamples){step();out.vector(p.xyz);out.data(p.color);out.real(p.confidence);out.integer(p.supportingViews);out.integer(p.camera);out.integer(p.x);out.integer(p.y);out.vector(p.worldNormal);out.real(p.normalConfidence);out.integer(p.normalSupportingViews);}
    for(const auto& v:mesh.vertices){step();out.vector(v.xyz);out.vector(v.normal);out.data(v.color);out.real(v.confidence);out.integer(v.supportingViews);}
    for(const auto& t:mesh.triangles){step();out.integer(t.a);out.integer(t.b);out.integer(t.c);out.integer(t.sourceCamera);}
    out.integer(static_cast<std::uint32_t>(mesh.observations.size()));
    for(const auto& observation:mesh.observations){step();out.integer(observation.vertex);out.integer(observation.camera);out.integer(observation.x);out.integer(observation.y);out.integer(static_cast<std::uint64_t>(observation.sourceSampleId));}
    for(const auto count:{mesh.topology.boundaryEdges,mesh.topology.components,mesh.topology.nonmanifoldEdges,
                         mesh.topology.intersections,mesh.topology.ambiguousPairs,mesh.topology.removedExactDefects})out.integer(static_cast<std::uint64_t>(count));
    out.integer(static_cast<std::uint8_t>(mesh.topology.intersectionBudgetExceeded));
    out.integer(static_cast<std::uint32_t>(settings.maxVoxelOccupancy));out.integer(static_cast<std::uint32_t>(settings.maxFusionCandidates));
    out.integer(static_cast<std::uint64_t>(dense.completedTiles));
    out.integer(static_cast<std::uint64_t>(dense.totalTiles));
    out.integer(static_cast<std::uint64_t>(dense.admittedWorkingSetBytes));
    out.integer(static_cast<std::uint64_t>(dense.resumedDepthTiles));
    out.integer(static_cast<std::uint64_t>(dense.recomputedDepthTiles));
    out.integer(static_cast<std::uint64_t>(dense.writtenDepthTiles));
    out.integer(static_cast<std::uint64_t>(dense.rejectedDepthTiles));
    out.integer(static_cast<std::uint64_t>(dense.coarseDepthMaps));
    out.integer(static_cast<std::uint64_t>(dense.propagatedHypotheses));
    out.integer(static_cast<std::uint64_t>(dense.sparsePriorTiles));
    out.integer(static_cast<std::uint64_t>(dense.normalHypothesesEvaluated));
    out.integer(static_cast<std::uint64_t>(dense.normalRefinedPixels));
    out.integer(static_cast<std::uint64_t>(dense.normalSupportPairs));
    out.integer(static_cast<std::uint64_t>(dense.normalRejectedPairs));
    out.integer(static_cast<std::uint64_t>(dense.normalUnknownPairs));
    const auto total=out.bytes.size()+32;
    if(total>MAX_FILE_BYTES)throw Error("Accepted-surface checkpoint exceeds byte bound");
    for(int i=0;i<4;++i)out.bytes[12+static_cast<std::size_t>(i)]=static_cast<std::uint8_t>(total>>(8*i));
    checkpoint::Sha256 digest;hashBytes(digest,out.bytes);out.data(digest.finish());return std::move(out.bytes);
}

bool readExactly(int descriptor,std::span<std::uint8_t> bytes) {
    std::size_t offset=0;
    while(offset<bytes.size()) {
        cancellation::checkpoint();
        const auto count=::read(descriptor,bytes.data()+offset,std::min(BLOCK,bytes.size()-offset));
        if(count<0&&errno==EINTR)continue;
        if(count<=0)return false;
        offset+=static_cast<std::size_t>(count);
    }
    return true;
}
bool matchesExisting(int directoryFd,std::span<const std::uint8_t> bytes) {
    FileDescriptor file(::openat(directoryFd,FILENAME,O_RDONLY|O_NOFOLLOW|O_CLOEXEC));
    struct stat status{};
    if(!file||::fstat(file.get(),&status)!=0||!S_ISREG(status.st_mode)||status.st_nlink!=1||
        status.st_size<0||static_cast<std::uint64_t>(status.st_size)!=bytes.size())return false;
    std::array<std::uint8_t,BLOCK> block{};
    for(std::size_t offset=0;offset<bytes.size();offset+=block.size()) {
        const auto count=std::min(block.size(),bytes.size()-offset);
        if(!readExactly(file.get(),std::span(block.data(),count))||!std::equal(block.begin(),block.begin()+static_cast<std::ptrdiff_t>(count),bytes.begin()+static_cast<std::ptrdiff_t>(offset)))return false;
    }
    return true;
}
} // namespace

checkpoint::Digest geometryIdentity(std::span<const DenseView> views) {
    if(views.size()<2||views.size()>MAX_DENSE_VIEWS)throw Error("Invalid accepted camera count");
    const auto width=views.front().width,height=views.front().height;
    if(width<32||height<32||width>640||height>640||static_cast<std::int64_t>(width)*height>320000)
        throw Error("Invalid accepted rectified dimensions");
    checkpoint::Sha256 digest;digest.update("realityforge-accepted-surface-input-v1");
    hashInteger(digest,static_cast<std::uint32_t>(width));hashInteger(digest,static_cast<std::uint32_t>(height));
    hashInteger(digest,static_cast<std::uint32_t>(views.size()));
    for(const auto& v:views) {
        if(v.width!=width||v.height!=height||v.rgba.size()!=static_cast<std::size_t>(width)*height*4||
            !std::isfinite(v.fx)||!std::isfinite(v.fy)||v.fx<=10||v.fy<=10||!std::isfinite(v.cx)||!std::isfinite(v.cy)||
            v.cx<0||v.cx>=width||v.cy<0||v.cy>=height||!finitePoint(v.pose.translation))throw Error("Invalid accepted camera geometry");
        for(const auto value:v.pose.rotation){if(!std::isfinite(value))throw Error("Invalid accepted camera rotation");hashReal(digest,value);}
        const auto orthogonal=multiply(v.pose.rotation,transpose(v.pose.rotation));
        for(std::size_t k=0;k<9;++k)if(std::abs(orthogonal[k]-(k%4==0?1.:0.))>.025)throw Error("Non-rigid accepted camera rotation");
        if(std::abs(determinant(v.pose.rotation)-1)>.025)throw Error("Invalid accepted camera orientation");
        for(const auto value:{v.pose.translation.x,v.pose.translation.y,v.pose.translation.z,v.fx,v.fy,v.cx,v.cy})hashReal(digest,value);
        for(std::size_t i=3;i<v.rgba.size();i+=4)if(v.rgba[i]!=0&&v.rgba[i]!=255)throw Error("Invalid rectified validity mask");
        hashBytes(digest,v.rgba);
    }
    return digest.finish();
}

checkpoint::Digest algorithmIdentity(const SurfaceSettings& settings) {
    validSettings(settings);
    checkpoint::Sha256 digest;digest.update("realityforge-observed-open-surface-fusion-v6-checkpoint-v2");
    hashInteger(digest,std::bit_cast<std::uint32_t>(settings.minimumConfidence));
    hashReal(digest,settings.maximumRelativeDepthJump);hashReal(digest,settings.maximumEdgePixelFactor);
    hashInteger(digest,static_cast<std::uint32_t>(settings.maxVertices));hashInteger(digest,static_cast<std::uint32_t>(settings.maxTriangles));
    hashInteger(digest,static_cast<std::uint32_t>(settings.pixelStride));
    hashInteger(digest,static_cast<std::uint32_t>(settings.maxVoxelOccupancy));
    hashInteger(digest,static_cast<std::uint32_t>(settings.maxFusionCandidates));return digest.finish();
}

bool store(std::span<const DenseView> views,const DenseResult& dense,const SurfaceMesh& mesh,const SurfaceSettings& settings,bool* created) {
    if(created)*created=false;
    const auto* scope=depth_checkpoint::current();if(!scope)return false;
    cancellation::checkpoint();
    if(dense.resumedDepthMaps+dense.writtenDepthMaps!=views.size())return false;
    auto dir=directory(*scope);if(!dir)return false;
    if(!withinMemoryBudget(dense.points.size(),dense.verifiedSamples.size(),mesh.vertices.size(),mesh.triangles.size(),mesh.observations.size()))return false;
    const auto bytes=encode(*scope,views,dense,mesh,settings);
    struct stat existing{};
    if(::fstatat(dir.get(),FILENAME,&existing,AT_SYMLINK_NOFOLLOW)==0)return matchesExisting(dir.get(),bytes);
    if(errno!=ENOENT)return false;
    const auto temporary=std::string(FILENAME)+".partial-"+std::to_string(::getpid())+"-"+
        std::to_string(nextTemporary.fetch_add(1,std::memory_order_relaxed));
    FileDescriptor file(::openat(dir.get(),temporary.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600));
    if(!file)return false;
    struct Cleanup {int dir;const std::string& name;bool owns=true;~Cleanup(){if(owns)::unlinkat(dir,name.c_str(),0);}} cleanup{dir.get(),temporary};
    std::size_t offset=0;
    while(offset<bytes.size()) {
        cancellation::checkpoint();
        const auto count=::write(file.get(),bytes.data()+offset,std::min(BLOCK,bytes.size()-offset));
        if(count<0&&errno==EINTR)continue;
        if(count<=0)return false;
        offset+=static_cast<std::size_t>(count);
    }
    if(::fsync(file.get())!=0)return false;
    cancellation::checkpoint();
    // linkat atomically creates the final name without replacing any existing accepted bytes.
    if(::linkat(dir.get(),temporary.c_str(),dir.get(),FILENAME,0)!=0)
        return errno==EEXIST&&matchesExisting(dir.get(),bytes);
    if(::unlinkat(dir.get(),temporary.c_str(),0)!=0)return false;
    cleanup.owns=false;
    if(::fsync(dir.get())!=0)return false;
    if(created)*created=true;
    return true;
}

bool load(const std::string& checkpointPath,std::span<const DenseView> views,AcceptedSurface& output) {
    const auto* scope=depth_checkpoint::current();if(!scope)return false;
    cancellation::checkpoint();
    if(checkpointPath.size()>1200||std::filesystem::path(checkpointPath)!=std::filesystem::path(scope->directory())/FILENAME)
        return false;
    auto dir=directory(*scope);if(!dir)return false;
    AcceptedSurface candidate;checkpoint::Digest expectedMaps{};
    try {
        // Serialized bytes are released before depth maps are loaded and texture allocations begin.
        {
            FileDescriptor file(::openat(dir.get(),FILENAME,O_RDONLY|O_NOFOLLOW|O_CLOEXEC));struct stat status{};
            if(!file||::fstat(file.get(),&status)!=0||!S_ISREG(status.st_mode)||status.st_nlink!=1||
                status.st_size<static_cast<off_t>(MIN_BYTES)||status.st_size>static_cast<off_t>(MAX_FILE_BYTES))return false;
            std::vector<std::uint8_t> bytes(static_cast<std::size_t>(status.st_size));
            if(!readExactly(file.get(),bytes))return false;
            checkpoint::Sha256 checksum;hashBytes(checksum,std::span(bytes.data(),bytes.size()-32));
            const auto actual=checksum.finish();
            if(!std::equal(actual.begin(),actual.end(),bytes.end()-32))return false;
            Reader in{std::span(bytes.data(),bytes.size()-32)};
            if(!std::equal(MAGIC.begin(),MAGIC.end(),in.data(8).begin())||in.integer<std::uint32_t>()!=2||in.integer<std::uint32_t>()!=bytes.size())return false;
            const auto width=in.integer<std::uint32_t>(),height=in.integer<std::uint32_t>(),cameras=in.integer<std::uint32_t>();
            const auto vertices=in.integer<std::uint32_t>(),triangles=in.integer<std::uint32_t>();
            const auto points=in.integer<std::uint32_t>(),samples=in.integer<std::uint32_t>();
            if(cameras!=views.size()||cameras<2||cameras>MAX_DENSE_VIEWS||width!=static_cast<std::uint32_t>(views.front().width)||
                height!=static_cast<std::uint32_t>(views.front().height)||vertices==0||vertices>MAX_VERTICES||triangles<8||triangles>MAX_TRIANGLES||
                points==0||points>MAX_POINTS||samples==0||samples>MAX_DENSE_SAMPLES)return false;
            const auto identity=in.data(64);
            if(!std::equal(identity.begin(),identity.end(),scope->identity().begin()))return false;
            const auto expectedGeometry=in.digest(),expectedAlgorithm=in.digest();expectedMaps=in.digest();
            if(expectedGeometry!=geometryIdentity(views))return false;
            auto& settings=candidate.settings;settings.minimumConfidence=in.single();settings.maximumRelativeDepthJump=in.real();settings.maximumEdgePixelFactor=in.real();
            settings.maxVertices=in.integer<std::uint32_t>();settings.maxTriangles=in.integer<std::uint32_t>();settings.pixelStride=static_cast<int>(in.integer<std::uint32_t>());
            auto& dense=candidate.dense;auto& mesh=candidate.mesh;
            dense.sampledPixels=in.integer<std::uint64_t>();dense.photometricPixels=in.integer<std::uint64_t>();dense.consistentPixels=in.integer<std::uint64_t>();
            dense.medianConfidence=in.real();dense.pointBudget=in.integer<std::uint32_t>();
            mesh.inputSamples=in.integer<std::uint64_t>();mesh.acceptedSamples=in.integer<std::uint64_t>();mesh.rejectedCells=in.integer<std::uint64_t>();
            mesh.rejectedTriangles=in.integer<std::uint64_t>();mesh.fusedObservations=in.integer<std::uint64_t>();mesh.medianConfidence=in.single();
            for(std::size_t i=0;i<cameras;++i) {
                const auto count=in.integer<std::uint32_t>();if(count<1||count>3)return false;
                std::vector<std::size_t> source;source.reserve(count);
                for(std::size_t j=0;j<count;++j)source.push_back(in.integer<std::uint32_t>());
                dense.sourceViews.push_back(std::move(source));dense.consistentPixelsPerView.push_back(in.integer<std::uint64_t>());
            }
            const std::size_t required=vertices*VERTEX_BYTES+triangles*TRIANGLE_BYTES+points*POINT_BYTES+samples*SAMPLE_BYTES;
            if(required>in.bytes.size()-in.offset||!withinMemoryBudget(points,samples,vertices,triangles,0))return false;
            dense.points.reserve(points);dense.verifiedSamples.reserve(samples);mesh.vertices.reserve(vertices);mesh.triangles.reserve(triangles);
            for(std::size_t i=0;i<points;++i) {
                if((i&1023)==0)cancellation::checkpoint();
                DensePoint p;p.xyz=in.vector();p.color=in.color();p.confidence=in.single();p.consistentViews=in.integer<std::uint8_t>();dense.points.push_back(p);
            }
            for(std::size_t i=0;i<samples;++i) {
                if((i&1023)==0)cancellation::checkpoint();
                VerifiedDepthSample p;p.xyz=in.vector();p.color=in.color();p.confidence=in.single();p.supportingViews=in.integer<std::uint8_t>();
                p.camera=in.integer<std::uint8_t>();p.x=in.integer<std::uint16_t>();p.y=in.integer<std::uint16_t>();p.worldNormal=in.vector();p.normalConfidence=in.single();p.normalSupportingViews=in.integer<std::uint8_t>();dense.verifiedSamples.push_back(p);
            }
            for(std::size_t i=0;i<vertices;++i) {
                if((i&1023)==0)cancellation::checkpoint();
                SurfaceVertex v;v.xyz=in.vector();v.normal=in.vector();v.color=in.color();v.confidence=in.single();v.supportingViews=in.integer<std::uint8_t>();mesh.vertices.push_back(v);
            }
            for(std::size_t i=0;i<triangles;++i) {
                if((i&1023)==0)cancellation::checkpoint();
                SurfaceTriangle t;t.a=in.integer<std::uint32_t>();t.b=in.integer<std::uint32_t>();t.c=in.integer<std::uint32_t>();t.sourceCamera=in.integer<std::uint8_t>();mesh.triangles.push_back(t);
            }
            const auto observationCount=in.integer<std::uint32_t>();
            if(observationCount>samples||!withinMemoryBudget(points,samples,vertices,triangles,observationCount)||
               static_cast<std::size_t>(observationCount)*OBSERVATION_BYTES>in.bytes.size()-in.offset)return false;
            mesh.observations.reserve(observationCount);
            for(std::size_t i=0;i<observationCount;++i){
                if((i&1023)==0)cancellation::checkpoint();
                SurfaceObservationRef observation;observation.vertex=in.integer<std::uint32_t>();observation.camera=in.integer<std::uint8_t>();
                observation.x=in.integer<std::uint16_t>();observation.y=in.integer<std::uint16_t>();observation.sourceSampleId=in.integer<std::uint64_t>();
                mesh.observations.push_back(observation);
            }
            mesh.topology.boundaryEdges=in.integer<std::uint64_t>();
            mesh.topology.components=in.integer<std::uint64_t>();
            mesh.topology.nonmanifoldEdges=in.integer<std::uint64_t>();
            mesh.topology.intersections=in.integer<std::uint64_t>();
            mesh.topology.ambiguousPairs=in.integer<std::uint64_t>();
            mesh.topology.removedExactDefects=in.integer<std::uint64_t>();
            const auto incomplete=in.integer<std::uint8_t>();if(incomplete>1)return false;mesh.topology.intersectionBudgetExceeded=incomplete!=0;
            settings.maxVoxelOccupancy=in.integer<std::uint32_t>();settings.maxFusionCandidates=in.integer<std::uint32_t>();
            dense.completedTiles=in.integer<std::uint64_t>();
            dense.totalTiles=in.integer<std::uint64_t>();
            dense.admittedWorkingSetBytes=in.integer<std::uint64_t>();
            dense.resumedDepthTiles=in.integer<std::uint64_t>();
            dense.recomputedDepthTiles=in.integer<std::uint64_t>();
            dense.writtenDepthTiles=in.integer<std::uint64_t>();
            dense.rejectedDepthTiles=in.integer<std::uint64_t>();
            dense.coarseDepthMaps=in.integer<std::uint64_t>();
            dense.propagatedHypotheses=in.integer<std::uint64_t>();
            dense.sparsePriorTiles=in.integer<std::uint64_t>();
            dense.normalHypothesesEvaluated=in.integer<std::uint64_t>();
            dense.normalRefinedPixels=in.integer<std::uint64_t>();
            dense.normalSupportPairs=in.integer<std::uint64_t>();
            dense.normalRejectedPairs=in.integer<std::uint64_t>();
            dense.normalUnknownPairs=in.integer<std::uint64_t>();
            if(in.offset!=in.bytes.size()||expectedAlgorithm!=algorithmIdentity(settings))return false;
            validGeometry(views,dense,mesh,settings);
        }
        for(std::size_t i=0;i<views.size();++i) {
            DenseDepthMap map;map.width=views[i].width;map.height=views[i].height;
            if(!depth_checkpoint::load(i,map))return false;
            candidate.dense.maps.push_back(std::move(map));
        }
        if(mapIdentity(candidate.dense.maps,views)!=expectedMaps)return false;
        // These are the actual execution counters for replay: no luminance, depth search or writes.
        candidate.dense.resumedDepthMaps=views.size();candidate.dense.writtenDepthMaps=0;candidate.dense.peakLuminanceBytes=0;
        cancellation::checkpoint();output=std::move(candidate);return true;
    } catch(const cancellation::Cancelled&){throw;}
    catch(const std::bad_alloc&){throw;}
    catch(const Error&){return false;}
    catch(const std::invalid_argument&){return false;}
}
} // namespace rf::surface_checkpoint
