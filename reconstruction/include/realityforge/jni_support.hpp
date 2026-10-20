#pragma once
#include <jni.h>
#include <new>
#include <stdexcept>
#include <string>

namespace rf::jni {
// Never allocate while mapping an allocation failure, and never replace a VM exception.
inline void raise(JNIEnv* env, const char* typeName, const char* message) noexcept {
    if (!env || env->ExceptionCheck()) return;
    const auto type = env->FindClass(typeName);
    if (!type) return;
    env->ThrowNew(type, message);
    env->DeleteLocalRef(type);
}

class UtfChars final {
public:
    UtfChars(JNIEnv* env, jstring value) : env_(env), value_(value) {
        if (!value || env->ExceptionCheck()) throw std::invalid_argument("RF_INVALID_INPUT");
        const auto length = env->GetStringUTFLength(value);
        if (env->ExceptionCheck()) throw std::runtime_error("RF_VM_EXCEPTION");
        if (length <= 0 || length > 32768) throw std::invalid_argument("RF_INVALID_STRING_LENGTH");
        chars_ = env->GetStringUTFChars(value, nullptr);
        if (!chars_) throw std::bad_alloc();
    }
    ~UtfChars() noexcept { if (chars_) env_->ReleaseStringUTFChars(value_, chars_); }
    UtfChars(const UtfChars&) = delete;
    UtfChars& operator=(const UtfChars&) = delete;
    const char* get() const noexcept { return chars_; }
private:
    JNIEnv* env_;
    jstring value_;
    const char* chars_ = nullptr;
};

inline std::string string(JNIEnv* env, jstring value) {
    UtfChars chars(env, value);
    return std::string(chars.get());
}
} // namespace rf::jni
