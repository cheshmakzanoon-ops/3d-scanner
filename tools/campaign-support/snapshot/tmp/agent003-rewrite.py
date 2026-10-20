from pathlib import Path
p=Path('reconstruction/src/jni.cpp');s=p.read_text();s=s.replace('#include "realityforge/depth_checkpoint.hpp"','#include "realityforge/depth_checkpoint.hpp"\n#include "realityforge/jni_support.hpp"\n#include "realityforge/progress.hpp"')
a=s.index('namespace {\nvoid illegal');b=s.index('// Resource-bounded',a)
s=s[:a]+'''namespace {
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
    rf::cancellation::checkpoint();
    auto result=work();
    if(!env->ExceptionCheck())rf::cancellation::checkpoint();
    return result;
}
}
''' +s[b:]
s=s.replace('operations.emplace(id,std::make_shared<std::atomic_bool>(false));','operations.emplace(id,std::make_shared<Operation>());')
s=s.replace('NativeCore_cancelReconstruction(JNIEnv*,jobject,jlong token)', 'NativeCore_cancelReconstruction(JNIEnv* env,jobject,jlong token)')
s=s.replace('NativeCore_finishReconstruction(JNIEnv*,jobject,jlong token)', 'NativeCore_finishReconstruction(JNIEnv* env,jobject,jlong token)')
s=s.replace('it->second->store(true,std::memory_order_relaxed);','it->second->flag->store(true,std::memory_order_relaxed);')
s=s.replace('return operations.erase(static_cast<std::uint64_t>(token)) ? JNI_TRUE : JNI_FALSE;', 'const auto it=operations.find(static_cast<std::uint64_t>(token));\n    if(it==operations.end())return JNI_FALSE;\n    it->second->flag->store(true,std::memory_order_relaxed);operations.erase(it);return JNI_TRUE;')
s=s.replace('env->GetDoubleArrayRegion(', 'rf::jni::doubles(env,')
s=s.replace('auto result=env->NewStringUTF(json);rf_free(json);return result;', 'return rf::jni::response(env,json);')
s=s.replace('jstring text=env->NewStringUTF(result);\n    rf_free(result);\n    return text;', 'return rf::jni::response(env,result);')
s=s.replace('const char* rawGlb=env->GetStringUTFChars(glbPath,nullptr);\n    if(!rawGlb)return nullptr;\n    const char* rawPly=env->GetStringUTFChars(plyPath,nullptr);\n    if(!rawPly){env->ReleaseStringUTFChars(glbPath,rawGlb);return nullptr;}\n    // Own the strings before release; a native failure still produces explicit JSON.\n    std::string glb(rawGlb),ply(rawPly);\n    env->ReleaseStringUTFChars(glbPath,rawGlb);\n    env->ReleaseStringUTFChars(plyPath,rawPly);', 'const auto glb=jString(env,glbPath),ply=jString(env,plyPath);')
s=s.replace('const char* text=env->GetStringUTFChars(jpaths[i],nullptr);\n        if(!text)return nullptr;\n        paths[i]=text;env->ReleaseStringUTFChars(jpaths[i],text);','paths[i]=jString(env,jpaths[i]);')
s=s.replace('const char* raw=env->GetStringUTFChars(destination,nullptr);if(!raw)return;std::string path(raw);env->ReleaseStringUTFChars(destination,raw);','const auto path=jString(env,destination);')
# Replace cancellable wrappers, eliminating reentry/busy-token acquisition.
s=s.replace('auto flag=operationFor(env,token);if(!flag)return nullptr;\n    rf::cancellation::Scope scope(flag.get());\n    return Java_com_', 'return withOperation(env,token,[&]{return Java_com_')
s=s.replace('env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);\n}', 'env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);});\n}')
s=s.replace('meshGlb,meshPly,texturedGlb,png);\n}', 'meshGlb,meshPly,texturedGlb,png);});\n}')
# Old non-RAII jString helper removed entirely.
a=s.index('namespace {\nstd::string jString',s.index('// M18:'));b=s.index('extern "C" JNIEXPORT',a);s=s[:a]+s[b:]
# Cache scopes and leases are acquired in one wrapper, before any cache work.
s=s.replace('try{\n        rf::depth_checkpoint::Scope cache', 'return withOperation(env,token,[&]{\n        rf::depth_checkpoint::Scope cache')
s=s.replace('NativeCore_reconstructSurfaceCancellable(\n            env,receiver', 'NativeCore_reconstructSurface(\n            env,receiver')
s=s.replace('env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply,token);\n    }catch(const std::exception& e){illegal(env,e.what());return nullptr;}', 'env,receiver,rgba,w,h,cameras,near,far,planes,stride,glb,ply);});')
s=s.replace('NativeCore_reconstructTexturedSurfaceCancellable(\n            env,receiver', 'NativeCore_reconstructTexturedSurface(\n            env,receiver')
s=s.replace('meshGlb,meshPly,texturedGlb,png,token);\n    }catch(const std::exception& e){illegal(env,e.what());return nullptr;}', 'meshGlb,meshPly,texturedGlb,png);});')
# Do not map allocation or cancellation as ordinary invalid-input failures.
s=s.replace('try{auto q=rf::quality', 'auto q=rf::quality').replace('return result;}catch(const std::exception&e){illegal(env,e.what());return nullptr;}', 'return result;')
s=s.replace('try{rf::Result result;', 'rf::Result result;').replace('if(glb)rf::writeGlb(result,path);else rf::writePly(result,path);}catch(const std::exception&e){illegal(env,e.what());}', 'if(glb)rf::writeGlb(result,path);else rf::writePly(result,path);')
s=s.replace('width<3||height<3||static_cast<long long>(width)*height!=', 'width<3||height<3||width>8192||height>8192||static_cast<long long>(width)*height>16777216||static_cast<long long>(width)*height!=')
# Null C allocation responses always represent resource exhaustion.
import re
s=re.sub(r'if\(!(json|result)\)\{illegal\(env,"[^"]*alloc[^"]*"\);return nullptr;\}',r'if(!\1)throw std::bad_alloc();',s)
# Every JNI export has an outer, noexcept guard, including existing legacy methods.
pattern=re.compile(r'extern "C" JNIEXPORT (\w+) JNICALL\s*\n(Java_com_realityforge_scanner_engine_NativeCore_\w+)\(')
chunks=[];end=0
for m in list(pattern.finditer(s)):
 start=s.index('{',m.end());depth=1;i=start+1
 while depth:
  if s[i]=='{':depth+=1
  elif s[i]=='}':depth-=1
  i+=1
 body=s[start+1:i-1];typ=m.group(1)
 # Strings contain braces but balanced JSON literals, so scanner remains balanced.
 wrapper=('rf::jni::boundaryVoid(env,[&]{'+body+'\n    });') if typ=='void' else ('return rf::jni::boundary<'+typ+'>(env,'+('JNI_FALSE' if typ=='jboolean' else '0' if typ=='jlong' else 'nullptr')+',[&]() -> '+typ+' {'+body+'\n    });')
 chunks.append(s[end:start]+'{\n    '+wrapper+'\n}');end=i
s=''.join(chunks)+s[end:]
p.write_text(s)
