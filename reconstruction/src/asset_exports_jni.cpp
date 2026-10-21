#include "realityforge/legacy_asset_export.hpp"
#include "realityforge/jni_support.hpp"
#include <fstream>
#include <streambuf>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
namespace {
// Exclusive descriptor: even a racing or symlink target cannot overwrite input.
class Output final:public std::streambuf {
    int fd_;
public:
    explicit Output(const std::string& path):fd_(::open(path.c_str(),O_WRONLY|O_CREAT|O_EXCL|O_NOFOLLOW|O_CLOEXEC,0600)){
        if(fd_<0)throw rf::Error("EXPORT_TARGET_EXISTS_OR_UNSAFE");}
    ~Output(){if(fd_>=0)::close(fd_);}
    std::streamsize xsputn(const char* data,std::streamsize count)override{
        std::streamsize done=0;while(done<count){auto n=::write(fd_,data+done,static_cast<std::size_t>(count-done));
            if(n<0&&errno==EINTR)continue;
            if(n<=0)break;
            done+=n;}return done;}
    int_type overflow(int_type c)override{if(traits_type::eq_int_type(c,traits_type::eof()))return traits_type::not_eof(c);
        char b=traits_type::to_char_type(c);return xsputn(&b,1)==1?c:traits_type::eof();}
    int sync()override{return ::fsync(fd_);}
};
}
extern "C" JNIEXPORT void JNICALL
Java_com_realityforge_scanner_export_NativeAssetExports_writeObserved(
    JNIEnv* env,jobject,jstring source,jstring destination,jint format,jstring hash,jobject checkpoint){
    rf::jni::boundaryVoid(env,[&]{
        if(!checkpoint)throw rf::Error("MISSING_CANCELLATION_CHECKPOINT");
        auto klass=env->GetObjectClass(checkpoint);if(!klass)throw rf::jni::PendingJavaException();
        auto run=env->GetMethodID(klass,"run","()V");env->DeleteLocalRef(klass);if(!run)throw rf::jni::PendingJavaException();
        auto cancel=[&]{env->CallVoidMethod(checkpoint,run);if(env->ExceptionCheck())throw rf::jni::PendingJavaException();return false;};
        cancel();auto inputPath=rf::jni::string(env,source),outputPath=rf::jni::string(env,destination),identity=rf::jni::string(env,hash);
        if(inputPath==outputPath)throw rf::Error("SOURCE_OVERWRITE");
        std::ifstream input(inputPath,std::ios::binary);if(!input)throw rf::Error("SOURCE_UNAVAILABLE");
        Output buffer(outputPath);std::ostream output(&buffer);
        rf::asset::exportLegacySurface(input,output,format,identity,cancel);output.flush();if(!output)throw rf::Error("EXPORT_FLUSH_FAILED");cancel();
    });
}
