#include "realityforge/c_abi_support.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <fstream>
#include <iomanip>
#include <limits>
#include <locale>
#include <map>
#include <numeric>
#include <sstream>
#include <string_view>
#include <unordered_map>

namespace rf {
namespace {
Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 times(Vec3 a,double n){return {a.x*n,a.y*n,a.z*n};}
double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
double norm(Vec3 a){return std::sqrt(dot(a,a));}
bool finite(Vec3 a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
Vec3 cameraCenter(const ViewPose& v){return times(multiply(transpose(v.rotation),v.translation),-1);}
Vec3 toCamera(const ViewPose& v,Vec3 x){return add(multiply(v.rotation,x),v.translation);}
void appendU32(std::vector<std::uint8_t>& out,std::uint32_t val){
    for(int k=0;k<4;++k)out.push_back(static_cast<std::uint8_t>((val>>(8*k))&255));
}
void appendFloat(std::vector<std::uint8_t>& out,float val){
    std::uint32_t bits=0;std::memcpy(&bits,&val,4);appendU32(out,bits);
}
void appendBig(std::vector<std::uint8_t>& out,std::uint32_t val){
    for(int k=3;k>=0;--k)out.push_back(static_cast<std::uint8_t>((val>>(8*k))&255));
}
std::uint32_t crc32(std::span<const std::uint8_t> bytes){
    std::uint32_t crc=0xffffffffu;
    for(auto v:bytes){crc^=v;for(int k=0;k<8;++k)crc=(crc>>1) ^ (crc&1?0xedb88320u:0u);}
    return ~crc;
}
void pngChunk(std::vector<std::uint8_t>& file,const char* name,std::span<const std::uint8_t> bytes){
    if(bytes.size()>std::numeric_limits<std::uint32_t>::max())throw Error("Oversized PNG chunk");
    appendBig(file,static_cast<std::uint32_t>(bytes.size()));
    const std::size_t offset=file.size();
    for(int k=0;k<4;++k)file.push_back(static_cast<std::uint8_t>(name[k]));
    file.insert(file.end(),bytes.begin(),bytes.end());
    appendBig(file,crc32(std::span<const std::uint8_t>(file).subspan(offset,file.size()-offset)));
}
std::vector<std::uint8_t> png(const TexturedSurface& atlas){
    if(atlas.atlasWidth<1||atlas.atlasHeight<1||atlas.atlasWidth>4096||atlas.atlasHeight>4096||
       atlas.atlasRgba.size()!=static_cast<std::size_t>(atlas.atlasWidth)*atlas.atlasHeight*4)
        throw Error("Invalid photographic atlas");
    // PNG RGBA8, uncompressed RFC1950/DEFLATE blocks: bounded, dependency-free,
    // deterministic and lossless. A compressing PNG implementation is a future optimization.
    const auto width=static_cast<std::size_t>(atlas.atlasWidth);
    const auto height=static_cast<std::size_t>(atlas.atlasHeight);
    std::vector<std::uint8_t> raw;
    raw.reserve(height*(1+width*4));
    for(std::size_t y=0;y<height;++y){
        cancellation::checkpoint();
        raw.push_back(0); // PNG filter 0
        const auto b=atlas.atlasRgba.begin()+static_cast<std::ptrdiff_t>(y*width*4);
        raw.insert(raw.end(),b,b+static_cast<std::ptrdiff_t>(width*4));
    }
    std::vector<std::uint8_t> zlib{0x78,0x01};
    std::uint32_t adlerA=1,adlerB=0;
    for(std::size_t i=0;i<raw.size();++i){
        if((i & 65535u)==0) cancellation::checkpoint();
        const auto v=raw[i];adlerA=(adlerA+v)%65521u;adlerB=(adlerB+adlerA)%65521u;}
    for(std::size_t p=0;p<raw.size();){
        cancellation::checkpoint();
        const auto n=std::min<std::size_t>(65535,raw.size()-p);
        const bool final=p+n==raw.size();
        zlib.push_back(final?1u:0u);
        zlib.push_back(static_cast<std::uint8_t>(n&255));zlib.push_back(static_cast<std::uint8_t>((n>>8)&255));
        auto neg=static_cast<std::uint16_t>(~static_cast<std::uint16_t>(n));
        zlib.push_back(static_cast<std::uint8_t>(neg&255));zlib.push_back(static_cast<std::uint8_t>((neg>>8)&255));
        zlib.insert(zlib.end(),raw.begin()+static_cast<std::ptrdiff_t>(p),raw.begin()+static_cast<std::ptrdiff_t>(p+n));
        p+=n;
    }
    appendBig(zlib,(adlerB<<16)|adlerA);
    std::vector<std::uint8_t> file{137,80,78,71,13,10,26,10};
    std::vector<std::uint8_t> header;
    appendBig(header,static_cast<std::uint32_t>(atlas.atlasWidth));
    appendBig(header,static_cast<std::uint32_t>(atlas.atlasHeight));
    header.insert(header.end(),{8,6,0,0,0}); // 8bit RGBA, no interlace
    pngChunk(file,"IHDR",header);
    pngChunk(file,"IDAT",zlib);
    pngChunk(file,"IEND",{});
    return file;
}
struct Projected {double u{},v{},z{};};
bool project(const TextureSource& source,Vec3 xyz,Projected& out){
    const auto c=toCamera(source.pose,xyz);
    if(!finite(c)||c.z<=.01)return false;
    out={c.x/c.z*source.fx+source.cx,c.y/c.z*source.fy+source.cy,c.z};
    return out.u>=1&&out.v>=1&&out.u<=source.width-2&&out.v<=source.height-2&&
           std::isfinite(out.u)&&std::isfinite(out.v);
}
bool visiblePixel(const TextureSource& v,const Projected& pixel){
    const int x=static_cast<int>(std::round(pixel.u)),y=static_cast<int>(std::round(pixel.v));
    if(x<0||y<0||x>=v.width||y>=v.height)return false;
    return v.rgba[(static_cast<std::size_t>(y)*v.width+x)*4+3]>=250;
}
bool depthAgreement(const DenseView& depthCamera,const DenseDepthMap& map,
                    const TextureSource& textureCamera, const Projected& projection,
                    double allowedFraction,int radius){
    const auto sx=(projection.u+0.5)*depthCamera.width/textureCamera.width-.5;
    const auto sy=(projection.v+0.5)*depthCamera.height/textureCamera.height-.5;
    const int centerX=static_cast<int>(std::round(sx)),centerY=static_cast<int>(std::round(sy));
    for(int dy=-radius;dy<=radius;++dy)for(int dx=-radius;dx<=radius;++dx){
        int x=centerX+dx,y=centerY+dy;
        if(x<0||y<0||x>=map.width||y>=map.height)continue;
        const auto d=map.depth[static_cast<std::size_t>(y)*map.width+x];
        if(d<=0||!std::isfinite(d))continue;

        if(std::abs(d-projection.z)/std::max(static_cast<double>(d),projection.z)<=allowedFraction)return true;
    }
    return false; // never assume visibility from absent evidence
}
struct Candidate {
    int camera=-1;
    std::array<Projected,3> uv{};
    double score=-std::numeric_limits<double>::infinity();
    double facing{};
};
bool sampleLinear(const TextureSource& image,const Projected& uv,std::array<double,3>& color) {
    const int x=static_cast<int>(std::floor(uv.u)),y=static_cast<int>(std::floor(uv.v));
    if (x<0 || y<0 || x+1>=image.width || y+1>=image.height) return false;
    color={0,0,0};
    const double fx=uv.u-x,fy=uv.v-y;
    for (int j=0;j<2;++j) for (int i=0;i<2;++i) {
        const auto pixel=(static_cast<std::size_t>(y+j)*image.width+x+i)*4;
        if (image.rgba[pixel+3]<250) return false;
        const double w=(i?fx:1-fx)*(j?fy:1-fy);
        for (int c=0;c<3;++c) color[c]+=w*srgbToLinear(image.rgba[pixel+c]/255.);
    }
    return true;
}
ExposureBalance measuredBalance(const SurfaceMesh& mesh,std::span<const DenseView> views,
    std::span<const DenseDepthMap> maps,std::span<const TextureSource> photos,double tolerance) {
    std::vector<ExposureSample> samples;
    const auto stride=std::max<std::size_t>(1,(mesh.vertices.size()+1999)/2000);
    for (std::size_t a=0;a<photos.size();++a) for (std::size_t b=a+1;b<photos.size();++b) {
        std::vector<ExposureSample> pair;
        double minX=photos[a].width,maxX=0,minY=photos[a].height,maxY=0;
        for (std::size_t i=0;i<mesh.vertices.size();i+=stride) {
            const auto& v=mesh.vertices[i];
            if (v.confidence<.10) continue;
            Projected pa{},pb{};
            if (!project(photos[a],v.xyz,pa) || !project(photos[b],v.xyz,pb) ||
                !depthAgreement(views[a],maps[a],photos[a],pa,tolerance,3) ||
                !depthAgreement(views[b],maps[b],photos[b],pb,tolerance,3)) continue;
            ExposureSample sample;sample.first=a;sample.second=b;
            if (!sampleLinear(photos[a],pa,sample.a) || !sampleLinear(photos[b],pb,sample.b)) continue;
            pair.push_back(sample);
            minX=std::min(minX,pa.u);maxX=std::max(maxX,pa.u);
            minY=std::min(minY,pa.v);maxY=std::max(maxY,pa.v);
        }
        // A tiny repeated patch is not sufficient to calibrate an entire camera.
        if (pair.size()>=32 && maxX-minX>photos[a].width*.1 && maxY-minY>photos[a].height*.1)
            samples.insert(samples.end(),pair.begin(),pair.end());
    }
    return balanceExposure(photos.size(),samples);
}
std::size_t seamCount(const SurfaceMesh& mesh,const std::vector<int>& labels) {
    std::map<std::pair<std::uint32_t,std::uint32_t>,int> edges;
    std::size_t switches=0;
    for (std::size_t i=0;i<mesh.triangles.size();++i) {
        if (labels[i]<0) continue;
        const auto& t=mesh.triangles[i];
        for (auto [a,b]:{std::pair{t.a,t.b},std::pair{t.b,t.c},std::pair{t.c,t.a}}) {
            const auto key=std::minmax(a,b);
            const auto [it,inserted]=edges.emplace(std::pair{key.first,key.second},labels[i]);
            if (!inserted && it->second!=labels[i]) ++switches;
        }
    }
    return switches;
}
void checkMesh(const SurfaceMesh& mesh){
    if(mesh.triangles.empty()||mesh.vertices.empty()||mesh.vertices.size()>40000||mesh.triangles.size()>80000)
        throw Error("M09 requires a bounded nonempty observed triangle mesh");
    for(const auto& v:mesh.vertices)if(!finite(v.xyz)||!finite(v.normal))throw Error("Invalid texture geometry");
    for(const auto& t:mesh.triangles)if(t.a>=mesh.vertices.size()||t.b>=mesh.vertices.size()||t.c>=mesh.vertices.size()||
       t.a==t.b||t.a==t.c||t.b==t.c)throw Error("Invalid texture triangle indexes");
}
}

TexturedSurface bakePhotographicAtlas(const SurfaceMesh& mesh,
    std::span<const DenseView> depthViews,std::span<const DenseDepthMap> depthMaps,
    std::span<const TextureSource> sources,const TextureSettings& s){
    checkMesh(mesh);
    if(sources.size()<2||sources.size()>MAX_DENSE_VIEWS||sources.size()!=depthViews.size()||sources.size()!=depthMaps.size()||
       s.gutter<2||s.gutter>16||!std::isfinite(s.minimumFacing)||s.minimumFacing<0||s.minimumFacing>.8||
       !std::isfinite(s.depthTolerance)||s.depthTolerance<.01||s.depthTolerance>.20||
       s.maxUvVertices<24||s.maxUvVertices>240000||
       !std::isfinite(s.seamPenalty)||s.seamPenalty<0||s.seamPenalty>.5)throw Error("Invalid M09 texture options");
    const int w=sources.front().width,h=sources.front().height;
    if(w<32||h<32||w>1024||h>1024||static_cast<std::int64_t>(w)*h>1024*1024)
        throw Error("Photographic source exceeds bounded texture resolution");
    for(std::size_t i=0;i<sources.size();++i){
        const auto& v=sources[i];const auto& d=depthViews[i];const auto& map=depthMaps[i];
        if(v.width!=w||v.height!=h||v.rgba.size()!=static_cast<std::size_t>(w)*h*4||
           d.width<32||d.height<32||map.width!=d.width||map.height!=d.height||
           map.depth.size()!=static_cast<std::size_t>(d.width)*d.height||
           map.confidence.size()!=map.depth.size())throw Error("Mismatched depth/photograph sizes");
        if(v.fx<=0||v.fy<=0||!std::isfinite(v.fx)||!std::isfinite(v.fy)||!std::isfinite(v.cx)||!std::isfinite(v.cy))throw Error("Invalid photographic camera intrinsics");
        const double sx=static_cast<double>(w)/d.width,sy=static_cast<double>(h)/d.height;
        if(std::abs(v.fx-sx*d.fx)>std::max(1e-3,sx*d.fx*.02)||
           std::abs(v.fy-sy*d.fy)>std::max(1e-3,sy*d.fy*.02)||
           std::abs(v.cx-(d.cx+.5)*sx+.5)>1.5||
           std::abs(v.cy-(d.cy+.5)*sy+.5)>1.5)throw Error("Texture intrinsics differ from depth camera");
        for(int k=0;k<9;++k)if(!std::isfinite(v.pose.rotation[k])||std::abs(v.pose.rotation[k]-d.pose.rotation[k])>1e-6)
            throw Error("Texture extrinsics differ from M08 stereo");
        for(double diff:{v.pose.translation.x-d.pose.translation.x,
                         v.pose.translation.y-d.pose.translation.y,
                         v.pose.translation.z-d.pose.translation.z})
            if(!std::isfinite(diff)||std::abs(diff)>1e-6)throw Error("Texture extrinsics differ from M08 stereo");
    }
    for (const auto& t:mesh.triangles) if (t.sourceCamera>=sources.size())
        throw Error("Invalid texture source-camera provenance");
    TexturedSurface result;
    result.exposure.gains.assign(sources.size(),{1,1,1});
    if (s.balanceColors) result.exposure=measuredBalance(mesh,depthViews,depthMaps,sources,s.depthTolerance);
    result.inputTriangles=mesh.triangles.size();
    result.sourceTriangleCounts.assign(sources.size(),0);
    const int columns=sources.size()<=4?2:3;
    const int rows=static_cast<int>((sources.size()+columns-1)/columns);
    const int pitchX=w+2*s.gutter,pitchY=h+2*s.gutter;
    result.atlasWidth=columns*pitchX;result.atlasHeight=rows*pitchY;
    if(result.atlasWidth>4096||result.atlasHeight>4096||static_cast<std::size_t>(result.atlasWidth)*result.atlasHeight>4500000)throw Error("Photographic atlas exceeds memory budget");
    result.atlasRgba.assign(static_cast<std::size_t>(result.atlasWidth)*result.atlasHeight*4,0);
    // Copy photograph pixels with measured linear-sRGB gains; preserve validity alpha.
    // Originals remain unchanged. Edge gutters are
    // independently clamped within each photo tile to prevent cross-photo bleeding.
    for(std::size_t cam=0;cam<sources.size();++cam){
        cancellation::checkpoint();
        const auto& source=sources[cam];const int startX=static_cast<int>(cam%columns)*pitchX;
        const int startY=static_cast<int>(cam/columns)*pitchY;
        for(int y=-s.gutter;y<h+s.gutter;++y){
          cancellation::checkpoint();
          for(int x=-s.gutter;x<w+s.gutter;++x){
            const int sx=std::clamp(x,0,w-1),sy=std::clamp(y,0,h-1);
            const auto from=(static_cast<std::size_t>(sy)*w+sx)*4;
            const auto to=(static_cast<std::size_t>(startY+y+s.gutter)*result.atlasWidth+
                           startX+x+s.gutter)*4;
            std::copy_n(source.rgba.data()+from,4,result.atlasRgba.data()+to);
            for (int k=0;k<3;++k) {
                const double gain=result.exposure.gains[cam][k];
                if (std::abs(gain-1)>1e-12) result.atlasRgba[to+k]=static_cast<std::uint8_t>(
                    std::round(255*linearToSrgb(srgbToLinear(source.rgba[from+k]/255.)*gain)));
            }
          }
        }
    }
    std::map<std::pair<std::uint32_t,int>,std::uint32_t> vertexMaps;
    std::vector<int> faceCameras(mesh.triangles.size(),-1);
    std::vector<double> scores;
    std::vector<std::array<Candidate,MAX_DENSE_VIEWS>> choices(mesh.triangles.size());
    std::vector<Vec3> faceNormals(mesh.triangles.size());
    for(std::size_t face=0;face<mesh.triangles.size();++face){
        if((face & 63u)==0) cancellation::checkpoint();
        const auto& tri=mesh.triangles[face];
        const std::array<Vec3,3> vertices{mesh.vertices[tri.a].xyz,mesh.vertices[tri.b].xyz,mesh.vertices[tri.c].xyz};
        const auto centre=times(add(add(vertices[0],vertices[1]),vertices[2]),1./3);
        const auto rawNormal=cross(sub(vertices[1],vertices[0]),sub(vertices[2],vertices[0]));
        const double length=norm(rawNormal);
        if(length<1e-12) continue;
        const auto normal=times(rawNormal,1./length); faceNormals[face]=normal;
        Candidate selected;
        for(std::size_t camera=0;camera<sources.size();++camera){
            const auto& view=sources[camera];
            const auto ray=sub(cameraCenter(view.pose),centre);
            const double distance=norm(ray);
            if(distance<1e-8)continue;
            const double facing=dot(normal,times(ray,1./distance));
            // Source normal is oriented out toward original M08 camera; other
            // cameras behind the face cannot contribute valid appearance.
            if(facing<s.minimumFacing)continue;
            std::array<Projected,3> projected{};
            bool valid=true;
            for(int i=0;i<3;++i){
                if(!project(view,vertices[i],projected[i])||!visiblePixel(view,projected[i])){valid=false;break;}
            }
            if(!valid)continue;
            Projected centerPixel{};
            if(!project(view,centre,centerPixel)||!visiblePixel(view,centerPixel))continue;
            // The depth camera which CREATED this face already supplies four
            // reciprocal-verified corners in M08; no additional centroid probe
            // is needed. Other cameras must pass an independent z-buffer check.
            if(camera!=tri.sourceCamera &&
               !depthAgreement(depthViews[camera],depthMaps[camera],view,centerPixel,s.depthTolerance,4))continue;
            // View-ranked sharpness: projected texel density and incidence,
            // plus a small provenance bias to discourage gratuitous UV seams.
            const double pixelArea=std::abs((projected[1].u-projected[0].u)*(projected[2].v-projected[0].v)-
                (projected[1].v-projected[0].v)*(projected[2].u-projected[0].u))*.5;
            if(pixelArea<.01)continue;
            const double quality=facing*(1+std::log1p(pixelArea)*.08)+
                (camera==tri.sourceCamera?.20:0.);
            choices[face][camera]={static_cast<int>(camera),projected,quality,facing};
            if(quality>selected.score) selected=choices[face][camera];
        }
        faceCameras[face]=selected.camera;
    }
    result.viewSwitchEdgesBefore=seamCount(mesh,faceCameras);
    const auto initialLabels=faceCameras;
    // ICM reduces a bounded pairwise label energy. Only already-visible source
    // candidates are permitted; coverage/depth/mask tests are never relaxed.
    std::map<std::pair<std::uint32_t,std::uint32_t>,std::vector<std::size_t>> edges;
    for (std::size_t face=0;face<mesh.triangles.size();++face) {
        if (faceCameras[face]<0) continue;
        const auto& t=mesh.triangles[face];
        for (auto [a,b]:{std::pair{t.a,t.b},std::pair{t.b,t.c},std::pair{t.c,t.a}}) {
            const auto key=std::minmax(a,b);
            edges[{key.first,key.second}].push_back(face);
        }
    }
    std::vector<std::vector<std::size_t>> neighbors(mesh.triangles.size());
    for (const auto& [edge,faces]:edges) {
        (void)edge;
        if (faces.size()!=2 || dot(faceNormals[faces[0]],faceNormals[faces[1]])<.8) continue;
        neighbors[faces[0]].push_back(faces[1]);neighbors[faces[1]].push_back(faces[0]);
    }
    for (int pass=0;pass<4 && s.seamPenalty>0;++pass) {
        bool changed=false;
        for (std::size_t face=0;face<choices.size();++face) {
            const int old=faceCameras[face]; if (old<0) continue;
            auto energy=[&](int camera) {
                double e=-choices[face][camera].score;
                for (auto n:neighbors[face]) if (faceCameras[n]!=camera) e+=s.seamPenalty;
                return e;
            };
            int best=old;double bestCost=energy(old);
            for (std::size_t camera=0;camera<sources.size();++camera) {
                if (choices[face][camera].camera<0) continue;
                const double cost=energy(static_cast<int>(camera));
                if (cost<bestCost-1e-10) {best=static_cast<int>(camera);bestCost=cost;}
            }
            if (best!=old) {faceCameras[face]=best;changed=true;}
        }
        if (!changed) break;
    }
    // Nonmanifold overlap edges were intentionally not smoothed. Never publish
    // a larger total seam count due to such unmatched adjacency constraints.
    if (seamCount(mesh,faceCameras)>result.viewSwitchEdgesBefore) faceCameras=initialLabels;
    for (std::size_t face=0;face<mesh.triangles.size();++face) {
        if (faceCameras[face]<0) {++result.skippedTriangles;continue;}
        const auto& tri=mesh.triangles[face];
        const std::array<std::uint32_t,3> ids{tri.a,tri.b,tri.c};
        const auto& selected=choices[face][faceCameras[face]];
        const auto cam=selected.camera;
        if (cam!=initialLabels[face]) ++result.reassignedFaces;
        const int x0=(cam%columns)*pitchX+s.gutter,y0=(cam/columns)*pitchY+s.gutter;
        std::array<std::uint32_t,3> finalIds{};
        bool good=true;
        for(int i=0;i<3;++i){
            const auto key=std::pair{ids[i],cam};
            auto it=vertexMaps.find(key);
            if(it!=vertexMaps.end())finalIds[i]=it->second;
            else {
                if(result.vertices.size()>=s.maxUvVertices){good=false;break;}
                TextureVertex tex;
                tex.surfaceVertex=ids[i];
                // glTF 2.0 uses upper-left (0,0); PNG row zero needs no V flip.
                tex.u=static_cast<float>((x0+selected.uv[i].u+.5)/result.atlasWidth);
                tex.v=static_cast<float>((y0+selected.uv[i].v+.5)/result.atlasHeight);
                if(tex.u<0||tex.u>1||tex.v<0||tex.v>1){good=false;break;}
                finalIds[i]=static_cast<std::uint32_t>(result.vertices.size());
                result.vertices.push_back(tex);vertexMaps.emplace(key,finalIds[i]);
            }
        }
        if(!good){++result.skippedTriangles;continue;}
        result.indices.insert(result.indices.end(),finalIds.begin(),finalIds.end());
        ++result.sourceTriangleCounts[cam];faceCameras[face]=cam;
        scores.push_back(selected.facing);
    }
    result.viewSwitchEdges=seamCount(mesh,faceCameras);
    result.faceCoverage=static_cast<double>(result.indices.size()/3)/mesh.triangles.size();
    if(!scores.empty()){
        std::nth_element(scores.begin(),scores.begin()+static_cast<std::ptrdiff_t>(scores.size()/2),scores.end());
        result.medianFacing=scores[scores.size()/2];
    }
    if(result.indices.size()<3*8||result.faceCoverage<.08)throw Error("Insufficient visible photographic UV coverage");
    return result;
}
std::string texturedSurfaceJson(const TexturedSurface& t){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(10);
    o<<"{\"schema\":10,\"representation\":\"camera_ranked_photographic_atlas\",\"textured\":true,\"watertight\":false,\"scale\":\"arbitrary\","
     <<"\"atlasWidth\":"<<t.atlasWidth<<",\"atlasHeight\":"<<t.atlasHeight
     <<",\"atlasFormat\":\"image/png\",\"uvVertexCount\":"<<t.vertices.size()
     <<",\"triangleCount\":"<<t.indices.size()/3<<",\"inputTriangles\":"<<t.inputTriangles
     <<",\"skippedTriangles\":"<<t.skippedTriangles
     <<",\"viewSwitchEdges\":"<<t.viewSwitchEdges
     <<",\"viewSwitchEdgesBefore\":"<<t.viewSwitchEdgesBefore
     <<",\"reassignedFaces\":"<<t.reassignedFaces
     <<",\"exposurePairs\":"<<t.exposure.supportedPairs
     <<",\"exposureSamples\":"<<t.exposure.acceptedSamples
     <<",\"overlapMedianLogErrorBefore\":"<<t.exposure.medianLogErrorBefore
     <<",\"overlapMedianLogErrorAfter\":"<<t.exposure.medianLogErrorAfter
     <<",\"uvOrigin\":\"upper_left\",\"faceCoverage\":"<<t.faceCoverage
     <<",\"medianFacing\":"<<t.medianFacing<<",\"cameraTriangleCounts\":[";
    for(std::size_t i=0;i<t.sourceTriangleCounts.size();++i){if(i)o<<',';o<<t.sourceTriangleCounts[i];}
    o<<"],\"cameraColorGains\":[";
    for(std::size_t i=0;i<t.exposure.gains.size();++i){if(i)o<<',';
        const auto& g=t.exposure.gains[i];o<<'['<<g[0]<<','<<g[1]<<','<<g[2]<<']';}
    o<<"]}";return o.str();
}
void writeTexturePng(const TexturedSurface& t,const std::string& path){
    auto bytes=png(t);std::ofstream file(path,std::ios::binary);
    if(!file)throw Error("Cannot create PNG atlas");
    file.write(reinterpret_cast<const char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    file.flush();if(!file)throw Error("PNG atlas write failed");
}
void writeTexturedGlb(const SurfaceMesh& m,const TexturedSurface& t,const std::string& path){
    checkMesh(m);
    if(t.vertices.empty()||t.indices.empty()||t.indices.size()%3||
       t.vertices.size()>240000||t.indices.size()>240000)throw Error("Invalid indexed photographic UV mesh");
    const auto tex=png(t);
    const std::size_t n=t.vertices.size(),indexCount=t.indices.size();
    const std::uint32_t posBytes=static_cast<std::uint32_t>(n*12),normBytes=posBytes,
                        uvBytes=static_cast<std::uint32_t>(n*8),indexBytes=static_cast<std::uint32_t>(indexCount*4);
    std::vector<std::uint8_t> buffer;buffer.reserve(static_cast<std::size_t>(posBytes)+normBytes+uvBytes+indexBytes+tex.size()+4);
    std::array<float,3> lo{INFINITY,INFINITY,INFINITY},hi{-INFINITY,-INFINITY,-INFINITY};
    for(const auto& tv:t.vertices){
        if(tv.surfaceVertex>=m.vertices.size())throw Error("Out-of-range textured vertex index");
        const auto p=m.vertices[tv.surfaceVertex].xyz;
        const std::array<float,3> xyz{static_cast<float>(p.x),static_cast<float>(-p.y),static_cast<float>(-p.z)};
        for(int i=0;i<3;++i){lo[i]=std::min(lo[i],xyz[i]);hi[i]=std::max(hi[i],xyz[i]);appendFloat(buffer,xyz[i]);}
    }
    for(const auto& tv:t.vertices){const auto nrm=m.vertices[tv.surfaceVertex].normal;
        for(double d:{nrm.x,-nrm.y,-nrm.z})appendFloat(buffer,static_cast<float>(d));}
    for(const auto& tv:t.vertices){if(!std::isfinite(tv.u)||!std::isfinite(tv.v)||tv.u<0||tv.u>1||tv.v<0||tv.v>1)
            throw Error("Invalid UV coordinate");
        appendFloat(buffer,tv.u);appendFloat(buffer,tv.v);}
    for(const auto i:t.indices){if(i>=t.vertices.size())throw Error("Invalid textured index");appendU32(buffer,i);}
    const auto textureOffset=buffer.size();
    buffer.insert(buffer.end(),tex.begin(),tex.end());
    const auto textureLength=tex.size();
    while(buffer.size()%4)buffer.push_back(0);
    std::ostringstream json;json.imbue(std::locale::classic());json<<std::setprecision(9);
    json<<"{\"asset\":{\"version\":\"2.0\",\"generator\":\"RealityForge M10\"},"
        <<"\"extras\":{\"scale\":\"arbitrary\",\"watertight\":false,\"photographicUV\":true,\"cameraRanked\":true},"
        <<"\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\"Observed textured open surface\"}],"
        <<"\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1,\"TEXCOORD_0\":2},\"indices\":3,\"mode\":4,\"material\":0}]}],"
        <<"\"materials\":[{\"doubleSided\":true,\"alphaMode\":\"MASK\",\"alphaCutoff\":0.5,"
        <<"\"pbrMetallicRoughness\":{\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0,\"roughnessFactor\":1}}],"
        <<"\"textures\":[{\"sampler\":0,\"source\":0}],\"samplers\":[{\"magFilter\":9729,\"minFilter\":9729,\"wrapS\":33071,\"wrapT\":33071}],"
        <<"\"images\":[{\"bufferView\":4,\"mimeType\":\"image/png\"}],"
        <<"\"buffers\":[{\"byteLength\":"<<buffer.size()<<"}],\"bufferViews\":["
        <<"{\"buffer\":0,\"byteOffset\":0,\"byteLength\":"<<posBytes<<",\"target\":34962},"
        <<"{\"buffer\":0,\"byteOffset\":"<<posBytes<<",\"byteLength\":"<<normBytes<<",\"target\":34962},"
        <<"{\"buffer\":0,\"byteOffset\":"<<posBytes+normBytes<<",\"byteLength\":"<<uvBytes<<",\"target\":34962},"
        <<"{\"buffer\":0,\"byteOffset\":"<<posBytes+normBytes+uvBytes<<",\"byteLength\":"<<indexBytes<<",\"target\":34963},"
        <<"{\"buffer\":0,\"byteOffset\":"<<textureOffset<<",\"byteLength\":"<<textureLength<<"}],\"accessors\":["
        <<"{\"bufferView\":0,\"componentType\":5126,\"count\":"<<n<<",\"type\":\"VEC3\",\"min\":["<<lo[0]<<','<<lo[1]<<','<<lo[2]<<"],\"max\":["<<hi[0]<<','<<hi[1]<<','<<hi[2]<<"]},"
        <<"{\"bufferView\":1,\"componentType\":5126,\"count\":"<<n<<",\"type\":\"VEC3\"},"
        <<"{\"bufferView\":2,\"componentType\":5126,\"count\":"<<n<<",\"type\":\"VEC2\"},"
        <<"{\"bufferView\":3,\"componentType\":5125,\"count\":"<<indexCount<<",\"type\":\"SCALAR\"}]}";
    auto js=json.str();while(js.size()%4)js.push_back(' ');
    const auto fileSize=12+8+js.size()+8+buffer.size();
    if(fileSize>std::numeric_limits<std::uint32_t>::max())throw Error("GLB exceeds 4GB");
    std::ofstream out(path,std::ios::binary);if(!out)throw Error("Cannot create textured GLB");
    auto writeU32=[&](std::uint32_t val){for(int k=0;k<4;++k)out.put(static_cast<char>((val>>(k*8))&255));};
    writeU32(0x46546c67);writeU32(2);writeU32(static_cast<std::uint32_t>(fileSize));
    writeU32(static_cast<std::uint32_t>(js.size()));writeU32(0x4e4f534a);out.write(js.data(),static_cast<std::streamsize>(js.size()));
    writeU32(static_cast<std::uint32_t>(buffer.size()));writeU32(0x004e4942);
    out.write(reinterpret_cast<const char*>(buffer.data()),static_cast<std::streamsize>(buffer.size()));
    out.flush();if(!out)throw Error("Textured GLB write failed");
}
} // namespace rf

extern "C" char* rf_textured_surface_json(
    const std::uint8_t* depth_rgba,int width,int height,
    const std::uint8_t* texture_rgba,int texture_width,int texture_height,
    const double* camera_rows,std::size_t camera_count,
    double near_depth,double far_depth,int depth_planes,int pixel_stride,
    const char* mesh_glb_path,const char* mesh_ply_path,
    const char* textured_glb_path,const char* texture_png_path)noexcept try {
    std::string response;
    auto appendError=[&](std::string_view message){
        std::string s;for(char c:message){if(c=='"'||c=='\\')s+='\\';if(static_cast<unsigned char>(c)>=32)s+=c;}return s;};
    try{
        if(!depth_rgba||!camera_rows||camera_count<2||camera_count>rf::MAX_DENSE_VIEWS||width<32||height<32||width>640||height>640||
           static_cast<std::int64_t>(width)*height>320000||
           !texture_rgba||texture_width<32||texture_height<32||texture_width>1024||texture_height>1024||
           static_cast<std::int64_t>(texture_width)*texture_height>1024*1024)
            throw rf::Error("Invalid M09 input geometry or image buffers");
        std::vector<rf::DenseView> views;views.reserve(camera_count);
        std::vector<rf::TextureSource> sources;sources.reserve(camera_count);
        const auto pixels=static_cast<std::size_t>(width)*height*4;
        const auto texPixels=static_cast<std::size_t>(texture_width)*texture_height*4;
        for(std::size_t i=0;i<camera_count;++i){
            rf::DenseView v;v.width=width;v.height=height;
            for(int k=0;k<9;++k)v.pose.rotation[k]=camera_rows[i*16+k];
            v.pose.translation={camera_rows[i*16+9],camera_rows[i*16+10],camera_rows[i*16+11]};
            v.fx=camera_rows[i*16+12];v.fy=camera_rows[i*16+13];v.cx=camera_rows[i*16+14];v.cy=camera_rows[i*16+15];
            v.rgba.assign(depth_rgba+i*pixels,depth_rgba+(i+1)*pixels);
            rf::TextureSource t;t.width=texture_width;t.height=texture_height;t.pose=v.pose;
            const double scaleX=static_cast<double>(texture_width)/width,scaleY=static_cast<double>(texture_height)/height;
            t.fx=v.fx*scaleX;t.fy=v.fy*scaleY;
            t.cx=(v.cx+.5)*scaleX-.5;t.cy=(v.cy+.5)*scaleY-.5;
            t.rgba.assign(texture_rgba+i*texPixels,texture_rgba+(i+1)*texPixels);
            views.push_back(std::move(v));sources.push_back(std::move(t));
        }
        rf::DenseSettings ds;ds.nearDepth=near_depth;ds.farDepth=far_depth;
        ds.depthPlanes=depth_planes;ds.pixelStride=pixel_stride;
        auto dense=rf::reconstructDense(views,ds);
        rf::cancellation::checkpoint();
        response="{\"schema\":10,\"dense\":"+rf::denseJson(dense);
        try{
            rf::SurfaceSettings s;s.pixelStride=pixel_stride;
            auto mesh=rf::fuseDepthSurfaces(views,dense,s);
            if(mesh.triangles.size()<8)throw rf::Error("Too few verified triangles for photographic atlas");
            if(mesh_glb_path&&*mesh_glb_path)rf::writeSurfaceGlb(mesh,mesh_glb_path);
            if(mesh_ply_path&&*mesh_ply_path)rf::writeSurfacePly(mesh,mesh_ply_path);
            response+=",\"mesh\":"+rf::surfaceJson(mesh);
            bool checkpointWritten=false;
            const bool checkpointAvailable=rf::surface_checkpoint::store(views,dense,mesh,s,&checkpointWritten);
            response+=",\"surfaceCheckpointAvailable\":"+std::string(checkpointAvailable?"true":"false")+
                ",\"surfaceCheckpointWritten\":"+std::string(checkpointWritten?"true":"false");
            try{
                auto baked=rf::bakePhotographicAtlas(mesh,views,dense.maps,sources);
                if(textured_glb_path&&*textured_glb_path)rf::writeTexturedGlb(mesh,baked,textured_glb_path);
                if(texture_png_path&&*texture_png_path)rf::writeTexturePng(baked,texture_png_path);
                response+=",\"texture\":"+rf::texturedSurfaceJson(baked);
            }catch(const rf::cancellation::Cancelled&){throw;}
            catch(const std::bad_alloc&){throw;}
    catch(const std::exception& e){response+=",\"textureError\":\""+appendError(e.what())+'"';}
        }catch(const rf::cancellation::Cancelled&){throw;}
        catch(const std::bad_alloc&){throw;}
    catch(const std::exception& e){response+=",\"meshError\":\""+appendError(e.what())+'"';}
        response+='}';
    }catch(const std::bad_alloc&){throw;}
    catch(const std::exception& e){response="{\"error\":\""+appendError(e.what())+"\"}";}
    auto* ret=static_cast<char*>(std::malloc(response.size()+1));if(!ret)return nullptr;
    std::memcpy(ret,response.c_str(),response.size()+1);return ret;
} catch (const std::bad_alloc&) {
    return rf::abi::error("RESOURCE_EXHAUSTED", "Native allocation failed");
} catch (const std::exception&) {
    return rf::abi::error("NATIVE_FAILURE", "Native boundary failure");
} catch (...) {
    return rf::abi::error("UNKNOWN_NATIVE_FAILURE", "Unknown native boundary failure");
}
