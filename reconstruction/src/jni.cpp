#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
#include "realityforge/multiview.hpp"
#include "realityforge/bundle_adjustment.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/surface_checkpoint.hpp"
#include "realityforge/jni_support.hpp"
#include "realityforge/progress.hpp"
#include "realityforge/dense_selection.hpp"
#include "bundle_problem_bridge.hpp"
#include <optional>
#include <cmath>
#include <algorithm>
#include <array>
#include <jni.h>
#include <vector>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <unordered_map>
#include <stdexcept>
namespace {
void illegal(JNIEnv* env,const char* message){rf::jni::raise(env,"java/lang/IllegalArgumentException",message);}
std::string jString(JNIEnv* env,jstring value){return rf::jni::string(env,value);}
struct Operation {
    std::shared_ptr<std::atomic_bool> flag=std::make_shared<std::atomic_bool>(false);
    std::shared_ptr<rf::progress::State> progress=std::make_shared<rf::progress::State>();
    std::atomic_bool busy{false};
};
std::mutex operationMutex;
std::unordered_map<std::uint64_t,std::shared_ptr<Operation>> operations;
std::atomic_size_t activeOperations{0};
std::uint64_t nextOperation=1;
std::shared_ptr<Operation> operationFor(jlong token){
    std::lock_guard lock(operationMutex);
    const auto found=operations.find(static_cast<std::uint64_t>(token));
    if(token<=0||found==operations.end())throw std::invalid_argument("Unknown or finished reconstruction operation");
    return found->second;
}
class OperationLease {
public:
    std::shared_ptr<Operation> state;
    explicit OperationLease(jlong token){
        std::lock_guard lock(operationMutex);
        const auto found=operations.find(static_cast<std::uint64_t>(token));
        if(token<=0||found==operations.end())throw std::invalid_argument("Unknown or finished reconstruction operation");
        state=found->second;
        if(activeOperations.load()>=16||state->busy.exchange(true))throw std::logic_error("Native operation is already executing or capacity exceeded");
        activeOperations.fetch_add(1);
    }
    ~OperationLease()noexcept{state->busy.store(false);activeOperations.fetch_sub(1);}
    OperationLease(const OperationLease&)=delete;
};
template<class F>jstring withOperation(JNIEnv* env,jlong token,F&& work){
    OperationLease lease(token);
    rf::cancellation::Scope cancellation(lease.state->flag);
    rf::progress::Scope progress(lease.state->progress);
    rf::progress::begin(rf::progress::Stage::Sparse);
    rf::cancellation::checkpoint();
    auto result=work();
    if(!env->ExceptionCheck())rf::cancellation::checkpoint();
    return result;
}
}
// Resource-bounded, per-scan native cancellation. Only the owning Kotlin
// session can publish or close its token; cancelling one operation never
// affects another in this process. Handles are deliberately not recycled.
extern "C" JNIEXPORT jlong JNICALL
Java_com_realityforge_scanner_engine_NativeCore_beginReconstruction(JNIEnv* env,jobject){
    return rf::jni::boundary<jlong>(env,0,[&]() -> jlong {
    std::lock_guard lock(operationMutex);
    if(operations.size()>=16 || nextOperation>static_cast<std::uint64_t>(INT64_MAX)){
        illegal(env,"Too many simultaneous native reconstructions");return 0;
    }
    const auto id=nextOperation++;
    operations.emplace(id,std::make_shared<Operation>());
    return static_cast<jlong>(id);

    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_realityforge_scanner_engine_NativeCore_cancelReconstruction(JNIEnv* env,jobject,jlong token){
    return rf::jni::boundary<jboolean>(env,JNI_FALSE,[&]() -> jboolean {
    std::lock_guard lock(operationMutex);
    auto it=operations.find(static_cast<std::uint64_t>(token));
    if(token<=0||it==operations.end())return JNI_FALSE;
    it->second->flag->store(true,std::memory_order_relaxed);
    return JNI_TRUE;

    });
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_realityforge_scanner_engine_NativeCore_finishReconstruction(JNIEnv* env,jobject,jlong token){
    return rf::jni::boundary<jboolean>(env,JNI_FALSE,[&]() -> jboolean {
    std::lock_guard lock(operationMutex);
    if(token<=0)return JNI_FALSE;
    const auto it=operations.find(static_cast<std::uint64_t>(token));
    if(it==operations.end())return JNI_FALSE;
    it->second->flag->store(true,std::memory_order_relaxed);operations.erase(it);return JNI_TRUE;

    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstruct(JNIEnv* env,jobject,jdoubleArray rows,jdouble threshold) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!rows){illegal(env,"Missing matches");return nullptr;}auto length=env->GetArrayLength(rows);
    if(length%7||length<140||length>140000){illegal(env,"Expected 20–20000 seven-element matches");return nullptr;}
    std::vector<double> data(static_cast<std::size_t>(length));rf::jni::doubles(env,rows,0,length,data.data());if(env->ExceptionCheck())return nullptr;
    if(!std::isfinite(threshold)||threshold<=0||threshold>1){illegal(env,"Invalid normalized threshold");return nullptr;}
    auto* json=rf_reconstruct_json(data.data(),data.size()/7,threshold,nullptr,nullptr);
    if(!json){throw std::bad_alloc();}
    return rf::jni::response(env,json);

    });
}
// All camera poses use the anchor pair's shared, arbitrary SfM coordinate system.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_registerCamera(JNIEnv* env,jobject,jdoubleArray rows,jdouble threshold) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!rows){illegal(env,"Missing 3D-2D tracks");return nullptr;}
    auto length=env->GetArrayLength(rows);
    if(length%5||length<80||length>25000){illegal(env,"Expected 16–5000 five-element tracks");return nullptr;}
    std::vector<double> data(static_cast<std::size_t>(length));
    rf::jni::doubles(env,rows,0,length,data.data());if(env->ExceptionCheck())return nullptr;
    if(!std::isfinite(threshold)||threshold<=0||threshold>1){illegal(env,"Invalid normalized threshold");return nullptr;}
    auto* json=rf_register_camera_json(data.data(),data.size()/5,threshold);
    if(!json)throw std::bad_alloc();
    return rf::jni::response(env,json);

    });
}
// Gauge-fixed sparse multi-view triangulation and robust camera/point refinement.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructMultiview(
    JNIEnv* env,jobject,jdoubleArray cameraRows,jdoubleArray observationRows,jdouble threshold,jint iterations) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!cameraRows||!observationRows){illegal(env,"Missing multi-view geometry buffers");return nullptr;}
    const auto cameraLength=env->GetArrayLength(cameraRows);
    const auto observationLength=env->GetArrayLength(observationRows);
    if(cameraLength<24||cameraLength>144||cameraLength%12!=0||
       observationLength<14||observationLength>700000||observationLength%7!=0){
        illegal(env,"Invalid packed multi-view camera/observation sizes");return nullptr;
    }
    std::vector<double> cameras(static_cast<std::size_t>(cameraLength));
    std::vector<double> observations(static_cast<std::size_t>(observationLength));
    rf::jni::doubles(env,cameraRows,0,cameraLength,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,observationRows,0,observationLength,observations.data());
    if(env->ExceptionCheck())return nullptr;
    auto* json=rf_multiview_json(cameras.data(),cameras.size()/12,
        observations.data(),observations.size()/7,threshold,iterations);
    if(!json)throw std::bad_alloc();
    return rf::jni::response(env,json);

    });
}
// M06 joint, gauge-fixed coupled BA; packed buffers are validated before native calls.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_bundleAdjust(
    JNIEnv* env,jobject,jdoubleArray cameraRows,jdoubleArray pointRows,
    jdoubleArray trackRows,jdouble threshold,jint iterations) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!cameraRows||!pointRows||!trackRows){illegal(env,"Missing BA buffers");return nullptr;}
    const auto camCount=env->GetArrayLength(cameraRows);
    const auto pointCount=env->GetArrayLength(pointRows);
    const auto observationCount=env->GetArrayLength(trackRows);
    if(camCount<36||camCount>144||camCount%12||
       pointCount<112||pointCount>140000||pointCount%7||
       observationCount<224||observationCount>700000||observationCount%7) {
        illegal(env,"Invalid packed joint BA buffer sizes");return nullptr;
    }
    std::vector<double> cams(static_cast<std::size_t>(camCount));
    std::vector<double> points(static_cast<std::size_t>(pointCount));
    std::vector<double> observations(static_cast<std::size_t>(observationCount));
    rf::jni::doubles(env,cameraRows,0,camCount,cams.data());
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,pointRows,0,pointCount,points.data());
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,trackRows,0,observationCount,observations.data());
    if(env->ExceptionCheck())return nullptr;
    char* result=rf_bundle_adjust_json(cams.data(),cams.size()/12,
        points.data(),points.size()/7,observations.data(),observations.size()/7,
        threshold,iterations);
    if(!result)throw std::bad_alloc();
    return rf::jni::response(env,result);

    });
}
// CPU-only rectified dense stereo. Never substitutes a mesh for point geometry.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructDense(
    JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height,
    jdoubleArray cameraRows, jdouble nearDepth, jdouble farDepth,
    jint depthPlanes, jint pixelStride) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!rgba || !cameraRows || width<32 || height<32 || width>640 || height>640 ||
       static_cast<long long>(width)*height>320000) {
        illegal(env, "Invalid bounded dense stereo images");return nullptr;
    }
    auto n=env->GetArrayLength(cameraRows);
    if(n<32 || n>128 || n%16!=0 ||
       static_cast<long long>(width)*height*4*(n/16)!=env->GetArrayLength(rgba)) {
        illegal(env, "Dense rectified image and camera shapes mismatch");return nullptr;
    }
    std::vector<std::uint8_t> colors(static_cast<std::size_t>(env->GetArrayLength(rgba)));
    std::vector<double> cameras(static_cast<std::size_t>(n));
    env->GetByteArrayRegion(rgba,0,env->GetArrayLength(rgba),reinterpret_cast<jbyte*>(colors.data()));
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,cameraRows,0,n,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    char* json=rf_dense_json(colors.data(),width,height,cameras.data(),static_cast<std::size_t>(n/16),
         nearDepth,farDepth,depthPlanes,pixelStride);
    if(!json)throw std::bad_alloc();
    return rf::jni::response(env,json);

    });
}
// Reuses the same bounded rectified image and camera contract as M07's dense engine.
// Paths refer only to app-private .partial exports, never user-controlled filesystem paths.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurface(
    JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height,
    jdoubleArray cameraRows, jdouble nearDepth, jdouble farDepth,
    jint depthPlanes, jint pixelStride, jstring glbPath, jstring plyPath) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!rgba||!cameraRows||!glbPath||!plyPath||width<32||height<32||width>640||height>640||
       static_cast<long long>(width)*height>320000) {
        illegal(env,"Invalid bounded M08 surface input");return nullptr;
    }
    const auto count=env->GetArrayLength(cameraRows);
    if(count<32||count>128||count%16!=0||
       static_cast<long long>(width)*height*4*(count/16)!=env->GetArrayLength(rgba)) {
        illegal(env,"Surface image and camera shapes mismatch");return nullptr;
    }
    std::vector<std::uint8_t> pixels(static_cast<std::size_t>(env->GetArrayLength(rgba)));
    std::vector<double> cameras(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(rgba,0,env->GetArrayLength(rgba),reinterpret_cast<jbyte*>(pixels.data()));
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,cameraRows,0,count,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    const auto glb=jString(env,glbPath),ply=jString(env,plyPath);
    auto* json=rf_surface_json(pixels.data(),width,height,cameras.data(),static_cast<std::size_t>(count/16),
        nearDepth,farDepth,depthPlanes,pixelStride,glb.c_str(),ply.c_str());
    if(!json)throw std::bad_alloc();
    return rf::jni::response(env,json);

    });
}
// Native M09: one calibrated depth pass, M08 mesh, view-ranked photo atlas,
// embedded GLB PNG, and a separate PNG. All paths are app-private partial files.
namespace {
jstring texturedSurface(
    JNIEnv* env, jobject, jbyteArray depthRgba, jint width, jint height,
    jbyteArray textureRgba, jint texWidth, jint texHeight, jdoubleArray cameraRows,
    jdouble nearDepth, jdouble farDepth, jint depthPlanes, jint pixelStride,
    jstring meshGlbPath, jstring meshPlyPath, jstring texturedGlbPath, jstring atlasPngPath,bool compact) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    if(!depthRgba||!textureRgba||!cameraRows||!meshGlbPath||!meshPlyPath||!texturedGlbPath||!atlasPngPath||
       width<32||height<32||width>640||height>640||
       texWidth<32||texHeight<32||texWidth>1024||texHeight>1024||
       static_cast<long long>(width)*height>320000||
       static_cast<long long>(texWidth)*texHeight>1048576) {
        illegal(env,"Invalid bounded M09 photo/mesh input");return nullptr;
    }
    const auto count=env->GetArrayLength(cameraRows);
    if(count<32||count>128||count%16!=0||
       static_cast<long long>(width)*height*4*(count/16)!=env->GetArrayLength(depthRgba)||
       static_cast<long long>(texWidth)*texHeight*4*(count/16)!=env->GetArrayLength(textureRgba)) {
        illegal(env,"M09 image buffers do not match camera row count");return nullptr;
    }
    std::vector<std::uint8_t> low(static_cast<std::size_t>(env->GetArrayLength(depthRgba)));
    std::vector<std::uint8_t> high(static_cast<std::size_t>(env->GetArrayLength(textureRgba)));
    std::vector<double> camera(static_cast<std::size_t>(count));
    env->GetByteArrayRegion(depthRgba,0,env->GetArrayLength(depthRgba),reinterpret_cast<jbyte*>(low.data()));
    if(env->ExceptionCheck())return nullptr;
    env->GetByteArrayRegion(textureRgba,0,env->GetArrayLength(textureRgba),reinterpret_cast<jbyte*>(high.data()));
    if(env->ExceptionCheck())return nullptr;
    rf::jni::doubles(env,cameraRows,0,count,camera.data());
    if(env->ExceptionCheck())return nullptr;
    const std::array<jstring,4> jpaths{meshGlbPath,meshPlyPath,texturedGlbPath,atlasPngPath};
    std::array<std::string,4> paths{};
    for(int i=0;i<4;++i){
        paths[i]=jString(env,jpaths[i]);
    }
    auto* json=(compact?rf_textured_surface_compact_json:rf_textured_surface_json)(low.data(),width,height,
        high.data(),texWidth,texHeight,camera.data(),static_cast<std::size_t>(count/16),
        nearDepth,farDepth,depthPlanes,pixelStride,
        paths[0].c_str(),paths[1].c_str(),paths[2].c_str(),paths[3].c_str());
    if(!json)throw std::bad_alloc();
    return rf::jni::response(env,json);

    });
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurface(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,jbyteArray texture,jint tw,jint th,
    jdoubleArray cameras,jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return texturedSurface(env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,meshGlb,meshPly,texturedGlb,png,false);});
}

// Existing JNI entry points retain their exact legacy ABI. These adapters
// install a thread-local scope only during the current native execution.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCancellable(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jdoubleArray cameras,jdouble near,jdouble far,jint planes,jint stride,
    jstring glb,jstring ply,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    return withOperation(env,token,[&]{return Java_com_realityforge_scanner_engine_NativeCore_reconstructSurface(
        env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);});

    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCancellable(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jbyteArray texture,jint tw,jint th,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    return withOperation(env,token,[&]{return Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurface(
        env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,
        meshGlb,meshPly,texturedGlb,png);});

    });
}
// M18: one scoped native depth-cache context per JNI thread. Both wrappers
// retain the M17 cancellation lease and legacy C ABI used in host tests.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jdoubleArray cameras,jdouble near,jdouble far,jint planes,jint stride,
    jstring glb,jstring ply,jlong token,jstring directory,jstring identity){
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    return withOperation(env,token,[&]{
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,identity));
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructSurface(
            env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);});

    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jbyteArray texture,jint tw,jint th,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,
    jlong token,jstring directory,jstring identity){
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
    return withOperation(env,token,[&]{
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,identity));
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurface(
            env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,
            meshGlb,meshPly,texturedGlb,png);});

    });
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_realityforge_scanner_engine_NativeCore_quality(JNIEnv* env,jobject,jbyteArray gray,jint width,jint height) {
    return rf::jni::boundary<jdoubleArray>(env,nullptr,[&]() -> jdoubleArray {
    if(!gray||width<3||height<3||width>8192||height>8192||static_cast<long long>(width)*height>16777216||static_cast<long long>(width)*height!=env->GetArrayLength(gray)){illegal(env,"Invalid image dimensions");return nullptr;}
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(width)*height);env->GetByteArrayRegion(gray,0,env->GetArrayLength(gray),reinterpret_cast<jbyte*>(bytes.data()));if(env->ExceptionCheck())return nullptr;
    auto q=rf::quality(bytes,width,height,width);double values[]{q.mean,q.standardDeviation,q.laplacianVariance,q.darkFraction,q.brightFraction};auto result=env->NewDoubleArray(5);if(result)env->SetDoubleArrayRegion(result,0,5,values);return result;

    });
}
namespace {
void exportPointData(JNIEnv* env,jobject,jdoubleArray rows,jstring destination,jboolean glb,jboolean dense) {
    rf::jni::boundaryVoid(env,[&]{
    if(!rows||!destination){illegal(env,"Missing export data");return;}auto count=env->GetArrayLength(rows);if(count%6||count==0||count>120000){illegal(env,"Invalid point buffer");return;}
    std::vector<double>data(static_cast<std::size_t>(count));rf::jni::doubles(env,rows,0,count,data.data());if(env->ExceptionCheck())return;
    const auto path=jString(env,destination);
    rf::Result result;result.representation=dense==JNI_TRUE?rf::PointRepresentation::DensePoints:rf::PointRepresentation::SparsePoints;for(int i=0;i<count;i+=6){rf::Point p{{data[i],data[i+1],data[i+2]},{},0,0,0};for(int c=0;c<3;++c){double x=data[i+3+c];if(!std::isfinite(x)||x<0||x>255)throw rf::Error("Invalid color");p.color[c]=static_cast<std::uint8_t>(x);}result.points.push_back(p);}if(glb)rf::writeGlb(result,path);else rf::writePly(result,path);

    });
}
}
extern "C" JNIEXPORT void JNICALL
Java_com_realityforge_scanner_engine_NativeCore_exportPoints(JNIEnv* env,jobject receiver,jdoubleArray rows,jstring path,jboolean glb){
    rf::jni::boundaryVoid(env,[&]{exportPointData(env,receiver,rows,path,glb,JNI_FALSE);});
}
extern "C" JNIEXPORT void JNICALL
Java_com_realityforge_scanner_engine_NativeCore_exportPointsTyped(JNIEnv* env,jobject receiver,jdoubleArray rows,jstring path,jboolean glb,jboolean dense){
    rf::jni::boundaryVoid(env,[&]{exportPointData(env,receiver,rows,path,glb,dense);});
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructCancellable(
    JNIEnv* env,jobject receiver,jdoubleArray rows,jdouble threshold,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        return Java_com_realityforge_scanner_engine_NativeCore_reconstruct(env,receiver,rows,threshold);
    });});
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_registerCameraCancellable(
    JNIEnv* env,jobject receiver,jdoubleArray rows,jdouble threshold,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        return Java_com_realityforge_scanner_engine_NativeCore_registerCamera(env,receiver,rows,threshold);
    });});
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructMultiviewCancellable(
    JNIEnv* env,jobject receiver,jdoubleArray cameras,jdoubleArray tracks,jdouble threshold,jint iterations,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructMultiview(env,receiver,cameras,tracks,threshold,iterations);
    });});
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_bundleAdjustCancellable(
    JNIEnv* env,jobject receiver,jdoubleArray cameras,jdoubleArray points,jdoubleArray tracks,jdouble threshold,jint iterations,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        rf::progress::begin(rf::progress::Stage::Bundle);
        return Java_com_realityforge_scanner_engine_NativeCore_bundleAdjust(env,receiver,cameras,points,tracks,threshold,iterations);
    });});
}
extern "C" JNIEXPORT jlongArray JNICALL
Java_com_realityforge_scanner_engine_NativeCore_pollNativeProgress(JNIEnv* env,jobject,jlong token){
    return rf::jni::boundary<jlongArray>(env,nullptr,[&]{
        const auto state=operationFor(token);const auto snapshot=state->progress->snapshot();
        const auto checked=[](std::uint64_t value)->jlong{if(value>static_cast<std::uint64_t>(INT64_MAX))throw std::overflow_error("Native progress count exceeds bridge representation");return static_cast<jlong>(value);};
        const std::array<jlong,7> values{1,static_cast<jlong>(snapshot.stage),checked(snapshot.reference),checked(snapshot.tile),checked(snapshot.completed),snapshot.total?checked(*snapshot.total):-1,checked(snapshot.sequence)};
        auto array=env->NewLongArray(7);if(array)env->SetLongArrayRegion(array,0,7,values.data());return array;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructDenseRefinedCancellable(
    JNIEnv* env,jobject,jbyteArray rgba,jint width,jint height,jdoubleArray cameraRows,
    jdouble near,jdouble far,jint planes,jint stride,jdoubleArray priorRows,
    jbyteArray coarseRgba,jint coarseWidth,jint coarseHeight,jdoubleArray coarseCameraRows,
    jstring inputIdentity,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]() -> jstring {
        if(!rgba||!cameraRows||!priorRows||!coarseRgba||!coarseCameraRows||!inputIdentity||
           width<32||height<32||width>640||height>640||static_cast<std::int64_t>(width)*height>320000)
            throw std::invalid_argument("Invalid bounded refined dense buffers");
        const auto count=env->GetArrayLength(cameraRows),priorSize=env->GetArrayLength(priorRows);
        if(count<32||count>128||count%16||priorSize%6||priorSize>120000||
           static_cast<std::int64_t>(width)*height*4*(count/16)!=env->GetArrayLength(rgba))
            throw std::invalid_argument("Refined dense image/camera/prior shape mismatch");
        const bool coarse=coarseWidth!=0||coarseHeight!=0;
        const auto coarseBytes=env->GetArrayLength(coarseRgba),coarseCount=env->GetArrayLength(coarseCameraRows);
        if(coarse){
            if(coarseWidth<32||coarseHeight<32||coarseWidth>=width||coarseHeight>=height||
               static_cast<std::int64_t>(coarseWidth)*coarseHeight>320000||coarseCount!=count||
               static_cast<std::int64_t>(coarseWidth)*coarseHeight*4*(count/16)!=coarseBytes)
                throw std::invalid_argument("Invalid admitted coarse pyramid buffers");
        }else if(coarseBytes||coarseCount)throw std::invalid_argument("Absent coarse pyramid must use empty buffers and zero dimensions");
        if(!coarse&&!priorSize)throw std::invalid_argument("Refined execution needs real priors or coarse photographs");
        const auto identity=jString(env,inputIdentity);
        if(identity.size()!=64||!std::all_of(identity.begin(),identity.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');}))
            throw std::invalid_argument("Refinement input identity must be canonical lowercase SHA256");
        std::vector<double> cameras(static_cast<std::size_t>(count)),priors(static_cast<std::size_t>(priorSize)),coarseCameras(static_cast<std::size_t>(coarseCount));
        rf::jni::doubles(env,cameraRows,0,count,cameras.data());
        if(priorSize)rf::jni::doubles(env,priorRows,0,priorSize,priors.data());
        if(coarseCount)rf::jni::doubles(env,coarseCameraRows,0,coarseCount,coarseCameras.data());
        rf::cancellation::checkpoint();
        std::vector<std::uint8_t> pixels(static_cast<std::size_t>(env->GetArrayLength(rgba))),coarsePixels(static_cast<std::size_t>(coarseBytes));
        env->GetByteArrayRegion(rgba,0,env->GetArrayLength(rgba),reinterpret_cast<jbyte*>(pixels.data()));
        if(env->ExceptionCheck())return nullptr;
        if(coarseBytes)env->GetByteArrayRegion(coarseRgba,0,coarseBytes,reinterpret_cast<jbyte*>(coarsePixels.data()));
        if(env->ExceptionCheck())return nullptr;
        rf::cancellation::checkpoint();
        return rf::jni::response(env,rf_dense_refined_json(pixels.data(),width,height,cameras.data(),static_cast<std::size_t>(count/16),
            near,far,planes,stride,priorSize?priors.data():nullptr,static_cast<std::size_t>(priorSize/6),
            coarse?coarsePixels.data():nullptr,coarseWidth,coarseHeight,coarse?coarseCameras.data():nullptr,identity.c_str()));
    });});
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCompactCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jbyteArray texture,jint tw,jint th,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,
    jlong token,jstring directory,jstring identity){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,identity));
        rf::progress::begin(rf::progress::Stage::Texture);
        return texturedSurface(env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,meshGlb,meshPly,texturedGlb,png,true);
    });});
}

namespace {
struct PreparedRefinement {
    std::vector<rf::DenseSparseDepthPrior> priors;
    std::vector<rf::DenseView> coarse;
    std::string identity;
};
PreparedRefinement prepareRefinement(JNIEnv* env,jint width,jint height,jdoubleArray fullCameras,
    jdoubleArray priorRows,jbyteArray coarseRgba,jint coarseWidth,jint coarseHeight,
    jdoubleArray coarseCameraRows,jstring inputIdentity,bool allowPlanOnly=false){
    if(!fullCameras||!priorRows||!coarseRgba||!coarseCameraRows||!inputIdentity||width<32||height<32||width>640||height>640||static_cast<std::int64_t>(width)*height>320000)
        throw std::invalid_argument("Invalid whole-pipeline refinement input");
    const auto cameraSize=env->GetArrayLength(fullCameras),priorSize=env->GetArrayLength(priorRows);
    if(cameraSize<32||cameraSize>128||cameraSize%16||priorSize%6||priorSize>120000)
        throw std::invalid_argument("Invalid refinement camera/prior shape");
    const auto cameraCount=static_cast<std::size_t>(cameraSize/16);
    const auto coarseBytes=env->GetArrayLength(coarseRgba),coarseCameraSize=env->GetArrayLength(coarseCameraRows);
    const bool coarse=coarseWidth!=0||coarseHeight!=0;
    if(coarse){
        if(coarseWidth<32||coarseHeight<32||coarseWidth>=width||coarseHeight>=height||
           static_cast<std::int64_t>(coarseWidth)*coarseHeight>320000||coarseCameraSize!=cameraSize||
           static_cast<std::int64_t>(coarseWidth)*coarseHeight*4*static_cast<std::int64_t>(cameraCount)!=coarseBytes)
            throw std::invalid_argument("Coarse refinement shape does not match ordered full views");
    }else if(coarseBytes||coarseCameraSize)throw std::invalid_argument("Absent coarse pyramid has nonempty buffers");
    if(!coarse&&!priorSize&&!allowPlanOnly)throw std::invalid_argument("Refinement requires actual coarse photographs or sparse priors");
    PreparedRefinement out;out.identity=jString(env,inputIdentity);
    if(out.identity.size()!=64||!std::all_of(out.identity.begin(),out.identity.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');}))throw std::invalid_argument("Refinement identity must be canonical lowercase SHA256");
    std::vector<double> priors(static_cast<std::size_t>(priorSize)),cameras(static_cast<std::size_t>(coarseCameraSize));
    if(priorSize)rf::jni::doubles(env,priorRows,0,priorSize,priors.data());
    if(coarseCameraSize)rf::jni::doubles(env,coarseCameraRows,0,coarseCameraSize,cameras.data());
    out.priors.reserve(priors.size()/6);
    for(std::size_t i=0;i<priors.size();i+=6){
        if(priors[i]<0||priors[i]>=cameraCount||priors[i]!=std::floor(priors[i])||priors[i+1]<0||priors[i+1]>=width||priors[i+2]<0||priors[i+2]>=height||priors[i+3]<=0||priors[i+4]<0||priors[i+4]>.25||(priors[i+5]!=0&&priors[i+5]!=1))throw std::invalid_argument("Invalid admitted sparse depth prior");
        out.priors.push_back({static_cast<std::uint32_t>(priors[i]),priors[i+1],priors[i+2],priors[i+3],priors[i+4],priors[i+5]==1});
    }
    if(coarse){
        out.coarse.reserve(cameraCount);const auto bytes=static_cast<std::size_t>(coarseWidth)*coarseHeight*4;
        for(std::size_t i=0;i<cameraCount;++i){
            rf::cancellation::checkpoint();rf::DenseView view;view.width=coarseWidth;view.height=coarseHeight;const auto* row=cameras.data()+i*16;
            for(int k=0;k<9;++k){view.pose.rotation[k]=row[k];}
            view.pose.translation={row[9],row[10],row[11]};view.fx=row[12];view.fy=row[13];view.cx=row[14];view.cy=row[15];view.rgba.resize(bytes);
            env->GetByteArrayRegion(coarseRgba,static_cast<jsize>(i*bytes),static_cast<jsize>(bytes),reinterpret_cast<jbyte*>(view.rgba.data()));
            if(env->ExceptionCheck()){throw rf::jni::PendingJavaException();}
            out.coarse.push_back(std::move(view));
        }
    }return out;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceRefinedCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint width,jint height,
    jbyteArray photos,jint photoWidth,jint photoHeight,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,
    jdoubleArray priorRows,jbyteArray coarseRgba,jint coarseWidth,jint coarseHeight,
    jdoubleArray coarseCameras,jstring refinementIdentity,jintArray sourceAdjacency,jboolean compactAtlas,
    jlong token,jstring directory,jstring checkpointIdentity){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        if(!cameras||!sourceAdjacency)throw std::invalid_argument("Missing admitted dense source plan");
        const auto cameraSize=env->GetArrayLength(cameras),adjacencySize=env->GetArrayLength(sourceAdjacency);
        if(cameraSize<32||cameraSize>128||cameraSize%16||adjacencySize>32)throw std::invalid_argument("Dense source plan exceeds bounded camera shape");
        std::vector<double> fullCameras(static_cast<std::size_t>(cameraSize));rf::jni::doubles(env,cameras,0,cameraSize,fullCameras.data());
        std::vector<jint> encoded(static_cast<std::size_t>(adjacencySize));if(adjacencySize)env->GetIntArrayRegion(sourceAdjacency,0,adjacencySize,encoded.data());
        if(env->ExceptionCheck())throw rf::jni::PendingJavaException();
        std::vector<std::vector<std::size_t>> adjacency;
        if(adjacencySize){
            const auto count=static_cast<std::size_t>(cameraSize/16);adjacency.resize(count);std::size_t cursor=0;
            for(std::size_t reference=0;reference<count;++reference){
                if(cursor>=encoded.size()){throw std::invalid_argument("Truncated dense source adjacency");}
                const int degree=encoded[cursor++];
                if(degree<0||degree>3||cursor+static_cast<std::size_t>(degree)>encoded.size())throw std::invalid_argument("Invalid dense source degree");
                for(int k=0;k<degree;++k){const int source=encoded[cursor++];if(source<0||source>=static_cast<int>(count)||source==static_cast<int>(reference)||std::find(adjacency[reference].begin(),adjacency[reference].end(),static_cast<std::size_t>(source))!=adjacency[reference].end())throw std::invalid_argument("Invalid or duplicate dense source identity");adjacency[reference].push_back(static_cast<std::size_t>(source));}
            }if(cursor!=encoded.size())throw std::invalid_argument("Appended dense source adjacency");
        }
        auto prepared=prepareRefinement(env,width,height,cameras,priorRows,coarseRgba,coarseWidth,coarseHeight,coarseCameras,refinementIdentity,!adjacency.empty());
        const rf::DenseRefinementInputs inputs{prepared.priors,prepared.coarse,prepared.identity};
        rf::DenseRefinementScope refinement(prepared.priors.empty()&&prepared.coarse.empty()?nullptr:&inputs);
        std::optional<rf::detail::DenseSourcePlanScope> sourcePlan;if(!adjacency.empty())sourcePlan.emplace(adjacency,fullCameras);
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,checkpointIdentity));
        return texturedSurface(env,receiver,rgba,width,height,photos,photoWidth,photoHeight,cameras,near,far,planes,stride,meshGlb,meshPly,texturedGlb,png,compactAtlas==JNI_TRUE);
    });});
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_bundleAdjustProblemCancellable(
    JNIEnv* env,jobject,jdoubleArray cameraRows,jdoubleArray seedRows,jdoubleArray observationRows,
    jdoubleArray intrinsicRows,jobjectArray groupIds,jobjectArray photoIds,jobjectArray sourceTrackIds,
    jboolean measured,jboolean refineFocal,jdouble threshold,jint iterations,jlong token){
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        if(!cameraRows||!seedRows||!observationRows||!intrinsicRows||!groupIds||!photoIds||!sourceTrackIds)
            throw std::invalid_argument("Missing versioned bundle problem buffers");
        const auto cameras=env->GetArrayLength(cameraRows),seeds=env->GetArrayLength(seedRows),observations=env->GetArrayLength(observationRows),models=env->GetArrayLength(intrinsicRows);
        const auto groups=env->GetArrayLength(groupIds),photos=env->GetArrayLength(photoIds),tracks=env->GetArrayLength(sourceTrackIds);
        if(cameras<36||cameras>384||cameras%12||seeds<112||seeds>140000||seeds%7||observations<224||observations>700000||observations%7||models!=(cameras/12)*11||groups!=cameras/12||photos!=cameras/12||tracks<16||tracks>20000)
            throw std::invalid_argument("Versioned bundle problem exceeds admitted array bounds");
        std::vector<double> cameraData(static_cast<std::size_t>(cameras)),seedData(static_cast<std::size_t>(seeds)),pixelData(static_cast<std::size_t>(observations)),modelData(static_cast<std::size_t>(models));
        rf::jni::doubles(env,cameraRows,0,cameras,cameraData.data());rf::jni::doubles(env,seedRows,0,seeds,seedData.data());
        rf::jni::doubles(env,observationRows,0,observations,pixelData.data());rf::jni::doubles(env,intrinsicRows,0,models,modelData.data());
        const auto strings=[&](jobjectArray input,jsize count,jsize maxChars){
            std::vector<std::string> values;values.reserve(static_cast<std::size_t>(count));
            for(jsize i=0;i<count;++i){
                if((i%64)==0)rf::cancellation::checkpoint();
                auto value=static_cast<jstring>(env->GetObjectArrayElement(input,i));
                if(env->ExceptionCheck())throw rf::jni::PendingJavaException();
                struct LocalReference {JNIEnv* env;jstring value;~LocalReference()noexcept{if(value)env->DeleteLocalRef(value);}} local{env,value};
                if(!value||env->GetStringLength(value)>maxChars)throw std::invalid_argument("Bundle identity string exceeds admitted bounds");
                values.push_back(jString(env,value));
            }return values;
        };
        const auto groupData=strings(groupIds,groups,1024),photoData=strings(photoIds,photos,256),trackData=strings(sourceTrackIds,tracks,64);
        rf::progress::begin(rf::progress::Stage::Bundle);
        const auto json=rf::jni::bundleProblemJson(cameraData,seedData,pixelData,modelData,groupData,photoData,trackData,measured==JNI_TRUE,refineFocal==JNI_TRUE,threshold,iterations);
        rf::cancellation::checkpoint();return env->NewStringUTF(json.c_str());
    });});
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_textureAcceptedSurfaceCheckpointed(
    JNIEnv* env,jobject,jbyteArray depthRgba,jint width,jint height,
    jbyteArray photoRgba,jint photoWidth,jint photoHeight,jdoubleArray cameraRows,
    jstring meshCheckpointPath,jstring texturedGlbPath,jstring atlasPngPath,
    jlong token,jstring checkpointDirectory,jstring checkpointIdentity) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]() -> jstring {
    rf::progress::begin(rf::progress::Stage::Texture);
    if(!depthRgba||!photoRgba||!cameraRows||!meshCheckpointPath||!texturedGlbPath||!atlasPngPath||
        !checkpointDirectory||!checkpointIdentity||width<32||height<32||width>640||height>640||
        static_cast<long long>(width)*height>320000||photoWidth<32||photoHeight<32||photoWidth>1024||photoHeight>1024||
        static_cast<long long>(photoWidth)*photoHeight>1048576) {
        illegal(env,"Invalid bounded texture-only checkpoint input");return nullptr;
    }
    const auto count=env->GetArrayLength(cameraRows);
    if(count<32||count>128||count%16!=0||
        static_cast<long long>(width)*height*4*(count/16)!=env->GetArrayLength(depthRgba)||
        static_cast<long long>(photoWidth)*photoHeight*4*(count/16)!=env->GetArrayLength(photoRgba)) {
        illegal(env,"Texture-only buffers do not match camera count");return nullptr;
    }
    const auto mesh=rf::jni::string(env,meshCheckpointPath),glb=rf::jni::string(env,texturedGlbPath);
    const auto png=rf::jni::string(env,atlasPngPath),directory=rf::jni::string(env,checkpointDirectory);
    const auto identity=rf::jni::string(env,checkpointIdentity);
    if(mesh.size()>1200||glb.size()>1200||png.size()>1200||directory.size()>1000||identity.size()!=64) {
        illegal(env,"Texture-only checkpoint path exceeds bounds");return nullptr;
    }
    std::vector<double> cameras(static_cast<std::size_t>(count));
    env->GetDoubleArrayRegion(cameraRows,0,count,cameras.data());if(env->ExceptionCheck())return nullptr;
    for(const auto value:cameras)if(!std::isfinite(value)){illegal(env,"Non-finite texture-only camera geometry");return nullptr;}
    rf::cancellation::checkpoint();
    std::vector<std::uint8_t> depth(static_cast<std::size_t>(env->GetArrayLength(depthRgba)));
    env->GetByteArrayRegion(depthRgba,0,env->GetArrayLength(depthRgba),reinterpret_cast<jbyte*>(depth.data()));
    if(env->ExceptionCheck())return nullptr;
    rf::cancellation::checkpoint();
    std::vector<std::uint8_t> photographs(static_cast<std::size_t>(env->GetArrayLength(photoRgba)));
    env->GetByteArrayRegion(photoRgba,0,env->GetArrayLength(photoRgba),reinterpret_cast<jbyte*>(photographs.data()));
    if(env->ExceptionCheck())return nullptr;
    rf::cancellation::checkpoint();
    return rf::jni::response(env,rf_texture_accepted_surface_json(depth.data(),width,height,
        photographs.data(),photoWidth,photoHeight,cameras.data(),static_cast<std::size_t>(count/16),
        mesh.c_str(),glb.c_str(),png.c_str(),directory.c_str(),identity.c_str()));
    });});
}

// Retain the explicit integer representation ABI used by the audited pipeline.
extern "C" JNIEXPORT void JNICALL
Java_com_realityforge_scanner_engine_NativeCore_exportPointCloud(
    JNIEnv* env,jobject receiver,jdoubleArray rows,jstring destination,jboolean glb,jint representation){
    rf::jni::boundaryVoid(env,[&]{
        if(representation!=0&&representation!=1)throw std::invalid_argument("Unknown point representation");
        exportPointData(env,receiver,rows,destination,glb,representation==1?JNI_TRUE:JNI_FALSE);
    });
}

// Additive route; legacy reconstruct remains normalized-only E for compatibility.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructCalibrated(
    JNIEnv* env,jobject,jdoubleArray rows,jdoubleArray observed,jdoubleArray domains,jdouble threshold) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]() -> jstring {
        if(!rows||!observed||!domains){illegal(env,"Missing calibrated initializer arrays");return nullptr;}
        const auto length=env->GetArrayLength(rows);
        if(length%7||length<140||length>140000||env->GetArrayLength(observed)!=(length/7)*4||env->GetArrayLength(domains)!=22){illegal(env,"Invalid calibrated initializer array shape");return nullptr;}
        if(!std::isfinite(threshold)||threshold<=0||threshold>.05){illegal(env,"Invalid normalized threshold");return nullptr;}
        std::vector<double> data(static_cast<std::size_t>(length)),pixels(static_cast<std::size_t>(length/7)*4);
        std::array<double,22> calibration{};
        rf::jni::doubles(env,rows,0,length,data.data());if(env->ExceptionCheck())return nullptr;
        rf::jni::doubles(env,observed,0,static_cast<jsize>(pixels.size()),pixels.data());if(env->ExceptionCheck())return nullptr;
        rf::jni::doubles(env,domains,0,22,calibration.data());if(env->ExceptionCheck())return nullptr;
        auto* json=rf_reconstruct_calibrated_json(data.data(),data.size()/7,pixels.data(),pixels.size(),calibration.data(),calibration.size(),threshold);
        if(!json)throw std::bad_alloc();return rf::jni::response(env,json);
    });
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructCalibratedCancellable(
    JNIEnv* env,jobject receiver,jdoubleArray rows,jdoubleArray observed,jdoubleArray domains,jdouble threshold,jlong token) {
    return rf::jni::boundary<jstring>(env,nullptr,[&]{return withOperation(env,token,[&]{
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructCalibrated(env,receiver,rows,observed,domains,threshold);
    });});
}
