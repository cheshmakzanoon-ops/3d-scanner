#include "realityforge/core.hpp"
#include "realityforge/pnp.hpp"
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
