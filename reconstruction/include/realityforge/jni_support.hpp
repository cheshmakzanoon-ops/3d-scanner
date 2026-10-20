#pragma once
#include "realityforge/cancellation.hpp"
#include <jni.h>
#include <cmath>
#include <cstring>
#include <memory>
#include <new>
#include <stdexcept>
#include <string>

namespace rf::jni {
struct PendingJavaException {};
inline void raise(JNIEnv* env,const char* type,const char* message) noexcept {
    if(env->ExceptionCheck())return;
    auto cls=env->FindClass(type);
    if(cls){env->ThrowNew(cls,message);env->DeleteLocalRef(cls);}
}
template<class T,class F>T boundary(JNIEnv* env,T fallback,F&& work) noexcept {
    if(env->ExceptionCheck())return fallback;
    try{return work();}
    catch(const PendingJavaException&){}
    catch(const cancellation::Cancelled&){raise(env,"java/util/concurrent/CancellationException","Reconstruction cancelled");}
    catch(const std::bad_alloc&){raise(env,"java/lang/OutOfMemoryError","Native allocation failed");}
    catch(const std::invalid_argument& e){raise(env,"java/lang/IllegalArgumentException",e.what());}
    catch(const Error& e){raise(env,"java/lang/IllegalArgumentException",e.what());}
    catch(const std::exception& e){raise(env,"java/lang/IllegalStateException",e.what());}
    catch(...){raise(env,"java/lang/IllegalStateException","Unknown native failure");}
    return fallback;
}
template<class F>void boundaryVoid(JNIEnv* env,F&& work) noexcept {
    (void)boundary<int>(env,0,[&]{work();return 1;});
}
class UtfChars final {
public:
    UtfChars(JNIEnv* env,jstring value):env_(env),value_(value){
        if(!value)throw std::invalid_argument("Missing native string");
        if(env_->GetStringLength(value)>4096)throw std::invalid_argument("Native string exceeds bounded length");
        chars_=env_->GetStringUTFChars(value,nullptr);
        if(!chars_)throw PendingJavaException();
    }
    ~UtfChars() noexcept {if(chars_)env_->ReleaseStringUTFChars(value_,chars_);}
    UtfChars(const UtfChars&)=delete;
    UtfChars& operator=(const UtfChars&)=delete;
    std::string string()const{return std::string(chars_);}
private:JNIEnv* env_;jstring value_;const char* chars_{};
};
inline std::string string(JNIEnv* env,jstring value){return UtfChars(env,value).string();}
inline void doubles(JNIEnv* env,jdoubleArray value,jsize offset,jsize count,jdouble* output){
    env->GetDoubleArrayRegion(value,offset,count,output);
    if(env->ExceptionCheck())throw PendingJavaException();
    for(jsize i=0;i<count;++i)if(!std::isfinite(output[i]))throw std::invalid_argument("Non-finite native geometry buffer");
}
inline jstring response(JNIEnv* env,char* raw){
    const std::unique_ptr<char,decltype(&rf_free)> owned(raw,&rf_free);
    if(!raw)throw std::bad_alloc();
    cancellation::checkpoint();
    if(std::strstr(raw,"\"error\":\"Reconstruction cancelled\""))throw cancellation::Cancelled();
    return env->NewStringUTF(raw);
}
}
