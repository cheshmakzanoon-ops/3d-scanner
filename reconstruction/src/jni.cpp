#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
#include "realityforge/multiview.hpp"
#include "realityforge/bundle_adjustment.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/surface.hpp"
#include <cmath>
#include <jni.h>
#include <vector>
namespace {
void illegal(JNIEnv* env,const char* message){auto type=env->FindClass("java/lang/IllegalArgumentException");if(type)env->ThrowNew(type,message);}
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
    if(n<32 || n>64 || n%16!=0 ||
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
    if(count<32||count>64||count%16!=0||
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
