#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
#include "realityforge/multiview.hpp"
#include "realityforge/bundle_adjustment.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/surface.hpp"
#include "realityforge/texture.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include <cmath>
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
void illegal(JNIEnv* env,const char* message){auto type=env->FindClass("java/lang/IllegalArgumentException");if(type)env->ThrowNew(type,message);}
// Opaque handles are leases on shared atomic flags. Holding a flag during JNI
// execution keeps it alive even if Android closes the handle during an error.
// Each flag is attached to exactly one executing reconstruction thread.
std::mutex operationMutex;
std::unordered_map<std::uint64_t,std::shared_ptr<std::atomic_bool>> operations;
std::uint64_t nextOperation=1;
std::shared_ptr<std::atomic_bool> operationFor(JNIEnv* env,jlong token){
    std::lock_guard lock(operationMutex);
    if(token<=0 || !operations.contains(static_cast<std::uint64_t>(token))){
        illegal(env,"Unknown or finished reconstruction operation");return {};
    }
    return operations.at(static_cast<std::uint64_t>(token));
}
}
// Resource-bounded, per-scan native cancellation. Only the owning Kotlin
// session can publish or close its token; cancelling one operation never
// affects another in this process. Handles are deliberately not recycled.
extern "C" JNIEXPORT jlong JNICALL
Java_com_realityforge_scanner_engine_NativeCore_beginReconstruction(JNIEnv* env,jobject){
    std::lock_guard lock(operationMutex);
    if(operations.size()>=16 || nextOperation>static_cast<std::uint64_t>(INT64_MAX)){
        illegal(env,"Too many simultaneous native reconstructions");return 0;
    }
    const auto id=nextOperation++;
    operations.emplace(id,std::make_shared<std::atomic_bool>(false));
    return static_cast<jlong>(id);
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_realityforge_scanner_engine_NativeCore_cancelReconstruction(JNIEnv*,jobject,jlong token){
    std::lock_guard lock(operationMutex);
    auto it=operations.find(static_cast<std::uint64_t>(token));
    if(token<=0||it==operations.end())return JNI_FALSE;
    it->second->store(true,std::memory_order_relaxed);
    return JNI_TRUE;
}
extern "C" JNIEXPORT jboolean JNICALL
Java_com_realityforge_scanner_engine_NativeCore_finishReconstruction(JNIEnv*,jobject,jlong token){
    std::lock_guard lock(operationMutex);
    if(token<=0)return JNI_FALSE;
    return operations.erase(static_cast<std::uint64_t>(token)) ? JNI_TRUE : JNI_FALSE;
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstruct(JNIEnv* env,jobject,jdoubleArray rows,jdouble threshold) {
    if(!rows){illegal(env,"Missing matches");return nullptr;}auto length=env->GetArrayLength(rows);
    if(length%7||length<140||length>140000){illegal(env,"Expected 20–20000 seven-element matches");return nullptr;}
    std::vector<double> data(static_cast<std::size_t>(length));env->GetDoubleArrayRegion(rows,0,length,data.data());if(env->ExceptionCheck())return nullptr;
    auto* json=rf_reconstruct_json(data.data(),data.size()/7,threshold,nullptr,nullptr);
    if(!json){illegal(env,"Native allocation failed");return nullptr;}auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// All camera poses use the anchor pair's shared, arbitrary SfM coordinate system.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_registerCamera(JNIEnv* env,jobject,jdoubleArray rows,jdouble threshold) {
    if(!rows){illegal(env,"Missing 3D-2D tracks");return nullptr;}
    auto length=env->GetArrayLength(rows);
    if(length%5||length<80||length>25000){illegal(env,"Expected 16–5000 five-element tracks");return nullptr;}
    std::vector<double> data(static_cast<std::size_t>(length));
    env->GetDoubleArrayRegion(rows,0,length,data.data());if(env->ExceptionCheck())return nullptr;
    auto* json=rf_register_camera_json(data.data(),data.size()/5,threshold);
    if(!json){illegal(env,"Native allocation failed");return nullptr;}
    auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// Gauge-fixed sparse multi-view triangulation and robust camera/point refinement.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructMultiview(
    JNIEnv* env,jobject,jdoubleArray cameraRows,jdoubleArray observationRows,jdouble threshold,jint iterations) {
    if(!cameraRows||!observationRows){illegal(env,"Missing multi-view geometry buffers");return nullptr;}
    const auto cameraLength=env->GetArrayLength(cameraRows);
    const auto observationLength=env->GetArrayLength(observationRows);
    if(cameraLength<24||cameraLength>144||cameraLength%12!=0||
       observationLength<14||observationLength>700000||observationLength%7!=0){
        illegal(env,"Invalid packed multi-view camera/observation sizes");return nullptr;
    }
    std::vector<double> cameras(static_cast<std::size_t>(cameraLength));
    std::vector<double> observations(static_cast<std::size_t>(observationLength));
    env->GetDoubleArrayRegion(cameraRows,0,cameraLength,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    env->GetDoubleArrayRegion(observationRows,0,observationLength,observations.data());
    if(env->ExceptionCheck())return nullptr;
    auto* json=rf_multiview_json(cameras.data(),cameras.size()/12,
        observations.data(),observations.size()/7,threshold,iterations);
    if(!json){illegal(env,"Native multi-view result allocation failed");return nullptr;}
    auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// M06 joint, gauge-fixed coupled BA; packed buffers are validated before native calls.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_bundleAdjust(
    JNIEnv* env,jobject,jdoubleArray cameraRows,jdoubleArray pointRows,
    jdoubleArray trackRows,jdouble threshold,jint iterations) {
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
    env->GetDoubleArrayRegion(cameraRows,0,camCount,cams.data());
    if(env->ExceptionCheck())return nullptr;
    env->GetDoubleArrayRegion(pointRows,0,pointCount,points.data());
    if(env->ExceptionCheck())return nullptr;
    env->GetDoubleArrayRegion(trackRows,0,observationCount,observations.data());
    if(env->ExceptionCheck())return nullptr;
    char* result=rf_bundle_adjust_json(cams.data(),cams.size()/12,
        points.data(),points.size()/7,observations.data(),observations.size()/7,
        threshold,iterations);
    if(!result){illegal(env,"BA result allocation failed");return nullptr;}
    jstring text=env->NewStringUTF(result);
    rf_free(result);
    return text;
}
// CPU-only rectified dense stereo. Never substitutes a mesh for point geometry.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructDense(
    JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height,
    jdoubleArray cameraRows, jdouble nearDepth, jdouble farDepth,
    jint depthPlanes, jint pixelStride) {
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
    env->GetDoubleArrayRegion(cameraRows,0,n,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    char* json=rf_dense_json(colors.data(),width,height,cameras.data(),static_cast<std::size_t>(n/16),
         nearDepth,farDepth,depthPlanes,pixelStride);
    if(!json){illegal(env,"CPU dense stereo memory allocation failed");return nullptr;}
    auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// Reuses the same bounded rectified image and camera contract as M07's dense engine.
// Paths refer only to app-private .partial exports, never user-controlled filesystem paths.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurface(
    JNIEnv* env, jobject, jbyteArray rgba, jint width, jint height,
    jdoubleArray cameraRows, jdouble nearDepth, jdouble farDepth,
    jint depthPlanes, jint pixelStride, jstring glbPath, jstring plyPath) {
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
    env->GetDoubleArrayRegion(cameraRows,0,count,cameras.data());
    if(env->ExceptionCheck())return nullptr;
    const char* rawGlb=env->GetStringUTFChars(glbPath,nullptr);
    if(!rawGlb)return nullptr;
    const char* rawPly=env->GetStringUTFChars(plyPath,nullptr);
    if(!rawPly){env->ReleaseStringUTFChars(glbPath,rawGlb);return nullptr;}
    // Own the strings before release; a native failure still produces explicit JSON.
    std::string glb(rawGlb),ply(rawPly);
    env->ReleaseStringUTFChars(glbPath,rawGlb);
    env->ReleaseStringUTFChars(plyPath,rawPly);
    auto* json=rf_surface_json(pixels.data(),width,height,cameras.data(),static_cast<std::size_t>(count/16),
        nearDepth,farDepth,depthPlanes,pixelStride,glb.c_str(),ply.c_str());
    if(!json){illegal(env,"Native surface response allocation failed");return nullptr;}
    auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// Native M09: one calibrated depth pass, M08 mesh, view-ranked photo atlas,
// embedded GLB PNG, and a separate PNG. All paths are app-private partial files.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurface(
    JNIEnv* env, jobject, jbyteArray depthRgba, jint width, jint height,
    jbyteArray textureRgba, jint texWidth, jint texHeight, jdoubleArray cameraRows,
    jdouble nearDepth, jdouble farDepth, jint depthPlanes, jint pixelStride,
    jstring meshGlbPath, jstring meshPlyPath, jstring texturedGlbPath, jstring atlasPngPath) {
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
    env->GetDoubleArrayRegion(cameraRows,0,count,camera.data());
    if(env->ExceptionCheck())return nullptr;
    const std::array<jstring,4> jpaths{meshGlbPath,meshPlyPath,texturedGlbPath,atlasPngPath};
    std::array<std::string,4> paths{};
    for(int i=0;i<4;++i){
        const char* text=env->GetStringUTFChars(jpaths[i],nullptr);
        if(!text)return nullptr;
        paths[i]=text;env->ReleaseStringUTFChars(jpaths[i],text);
    }
    auto* json=rf_textured_surface_json(low.data(),width,height,
        high.data(),texWidth,texHeight,camera.data(),static_cast<std::size_t>(count/16),
        nearDepth,farDepth,depthPlanes,pixelStride,
        paths[0].c_str(),paths[1].c_str(),paths[2].c_str(),paths[3].c_str());
    if(!json){illegal(env,"M09 native allocation failed");return nullptr;}
    auto result=env->NewStringUTF(json);rf_free(json);return result;
}
// Existing JNI entry points retain their exact legacy ABI. These adapters
// install a thread-local scope only during the current native execution.
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCancellable(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jdoubleArray cameras,jdouble near,jdouble far,jint planes,jint stride,
    jstring glb,jstring ply,jlong token){
    auto flag=operationFor(env,token);if(!flag)return nullptr;
    rf::cancellation::Scope scope(flag.get());
    return Java_com_realityforge_scanner_engine_NativeCore_reconstructSurface(
        env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCancellable(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jbyteArray texture,jint tw,jint th,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,jlong token){
    auto flag=operationFor(env,token);if(!flag)return nullptr;
    rf::cancellation::Scope scope(flag.get());
    return Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurface(
        env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,
        meshGlb,meshPly,texturedGlb,png);
}
// M18: one scoped native depth-cache context per JNI thread. Both wrappers
// retain the M17 cancellation lease and legacy C ABI used in host tests.
namespace {
std::string jString(JNIEnv* env,jstring input){
    if(!input)throw std::invalid_argument("Missing depth cache location or identity");
    const char* value=env->GetStringUTFChars(input,nullptr);
    if(!value)throw std::runtime_error("Cannot decode depth cache location");
    std::string out(value);env->ReleaseStringUTFChars(input,value);return out;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jdoubleArray cameras,jdouble near,jdouble far,jint planes,jint stride,
    jstring glb,jstring ply,jlong token,jstring directory,jstring identity){
    try{
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,identity));
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructSurfaceCancellable(
            env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply,token);
    }catch(const std::exception& e){illegal(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCheckpointed(
    JNIEnv* env,jobject receiver,jbyteArray rgba,jint w,jint h,
    jbyteArray texture,jint tw,jint th,jdoubleArray cameras,
    jdouble near,jdouble far,jint planes,jint stride,
    jstring meshGlb,jstring meshPly,jstring texturedGlb,jstring png,
    jlong token,jstring directory,jstring identity){
    try{
        rf::depth_checkpoint::Scope cache(jString(env,directory),jString(env,identity));
        return Java_com_realityforge_scanner_engine_NativeCore_reconstructTexturedSurfaceCancellable(
            env,receiver,rgba,w,h,texture,tw,th,cameras,near,far,planes,stride,
            meshGlb,meshPly,texturedGlb,png,token);
    }catch(const std::exception& e){illegal(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_realityforge_scanner_engine_NativeCore_quality(JNIEnv* env,jobject,jbyteArray gray,jint width,jint height) {
    if(!gray||width<3||height<3||static_cast<long long>(width)*height!=env->GetArrayLength(gray)){illegal(env,"Invalid image dimensions");return nullptr;}
    std::vector<std::uint8_t> bytes(static_cast<std::size_t>(width)*height);env->GetByteArrayRegion(gray,0,env->GetArrayLength(gray),reinterpret_cast<jbyte*>(bytes.data()));if(env->ExceptionCheck())return nullptr;
    try{auto q=rf::quality(bytes,width,height,width);double values[]{q.mean,q.standardDeviation,q.laplacianVariance,q.darkFraction,q.brightFraction};auto result=env->NewDoubleArray(5);if(result)env->SetDoubleArrayRegion(result,0,5,values);return result;}catch(const std::exception&e){illegal(env,e.what());return nullptr;}
}
extern "C" JNIEXPORT void JNICALL
Java_com_realityforge_scanner_engine_NativeCore_exportPoints(JNIEnv* env,jobject,jdoubleArray rows,jstring destination,jboolean glb) {
    if(!rows||!destination){illegal(env,"Missing export data");return;}auto count=env->GetArrayLength(rows);if(count%6||count==0||count>120000){illegal(env,"Invalid point buffer");return;}
    std::vector<double>data(static_cast<std::size_t>(count));env->GetDoubleArrayRegion(rows,0,count,data.data());if(env->ExceptionCheck())return;
    const char* raw=env->GetStringUTFChars(destination,nullptr);if(!raw)return;std::string path(raw);env->ReleaseStringUTFChars(destination,raw);
    try{rf::Result result;for(int i=0;i<count;i+=6){rf::Point p{{data[i],data[i+1],data[i+2]},{},0,0,0};for(int c=0;c<3;++c){double x=data[i+3+c];if(!std::isfinite(x)||x<0||x>255)throw rf::Error("Invalid color");p.color[c]=static_cast<std::uint8_t>(x);}result.points.push_back(p);}if(glb)rf::writeGlb(result,path);else rf::writePly(result,path);}catch(const std::exception&e){illegal(env,e.what());}
}
