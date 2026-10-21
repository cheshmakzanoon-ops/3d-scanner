#include "realityforge/jni_support.hpp"
#include <cstdlib>
#include <iostream>
#include <new>
#include <stdexcept>
namespace {
bool failAllocation=false,pending=false;
const char* thrownClass=nullptr;
int releases=0,classLookups=0,utfLengthChecks=0;
constexpr char asciiPath[]="A native path longer than the small string optimization capacity, retained while the allocation fails";
jboolean JNICALL exceptionCheck(JNIEnv*){return pending?JNI_TRUE:JNI_FALSE;}
jclass JNICALL findClass(JNIEnv*,const char* name){++classLookups;thrownClass=name;return reinterpret_cast<jclass>(1);}
jint JNICALL throwNew(JNIEnv*,jclass,const char*){pending=true;return JNI_OK;}
void JNICALL deleteLocal(JNIEnv*,jobject){}
// This fixture is ASCII: UTF-16 units and modified-UTF8 bytes agree, but both JNI APIs must exist.
jsize JNICALL stringLength(JNIEnv*,jstring){return sizeof(asciiPath)-1;}
jsize JNICALL utfStringLength(JNIEnv*,jstring){++utfLengthChecks;return sizeof(asciiPath)-1;}
const char* JNICALL getChars(JNIEnv*,jstring,jboolean*){return asciiPath;}
void JNICALL releaseChars(JNIEnv*,jstring,const char*){++releases;}
void check(bool value){if(!value)throw std::runtime_error("JNI boundary regression");}
}
void* operator new(std::size_t size){if(failAllocation){failAllocation=false;throw std::bad_alloc();}if(auto* p=std::malloc(size))return p;throw std::bad_alloc();}
void operator delete(void* p)noexcept{std::free(p);}
void operator delete(void* p,std::size_t)noexcept{std::free(p);}
int main(){
    JNINativeInterface_ table{};table.ExceptionCheck=exceptionCheck;table.FindClass=findClass;table.ThrowNew=throwNew;table.DeleteLocalRef=deleteLocal;table.GetStringLength=stringLength;table.GetStringUTFLength=utfStringLength;table.GetStringUTFChars=getChars;table.ReleaseStringUTFChars=releaseChars;JNIEnv_ env{&table};
    failAllocation=true;
    const auto result=rf::jni::boundary<int>(&env,0,[&]{(void)rf::jni::string(&env,reinterpret_cast<jstring>(1));return 1;});
    check(result==0&&releases==1&&utfLengthChecks==1&&pending&&std::string(thrownClass)=="java/lang/OutOfMemoryError");
    const int previous=classLookups;check(rf::jni::boundary<int>(&env,4,[]{return 8;})==4&&classLookups==previous);
    pending=false;rf::jni::boundaryVoid(&env,[]{throw rf::cancellation::Cancelled();});check(pending&&std::string(thrownClass)=="java/util/concurrent/CancellationException");
    pending=false;rf::jni::boundaryVoid(&env,[]{throw std::invalid_argument("bad");});check(pending&&std::string(thrownClass)=="java/lang/IllegalArgumentException");
    pending=false;rf::jni::boundaryVoid(&env,[]{throw 17;});check(pending&&std::string(thrownClass)=="java/lang/IllegalStateException");
    std::cout<<"injected string-allocation failure releases UTF lease, pending VM exceptions preserved, typed no-throw guards passed\n";
}
