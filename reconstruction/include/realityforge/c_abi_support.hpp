#pragma once
#include <cstddef>
#include <cstdlib>
#include <cstring>

namespace rf::abi {
// Error reporting at a C boundary must remain safe when C++ allocation itself failed.
// The bounded stack encoder cannot throw; malloc failure is represented by nullptr.
inline char* error(const char* code, const char* message) noexcept {
    char output[1024]{};
    std::size_t used = 0;
    auto append = [&](const char* text) noexcept {
        while (*text && used + 1 < sizeof(output)) output[used++] = *text++;
    };
    append("{\"errorCode\":\"");
    append(code);
    append("\",\"error\":\"");
    for (std::size_t i = 0; message && message[i] && used + 8 < sizeof(output) && i < 400; ++i) {
        const auto c = static_cast<unsigned char>(message[i]);
        if (c < 32) continue;
        if (c == '"' || c == '\\') output[used++] = '\\';
        output[used++] = static_cast<char>(c);
    }
    append("\"}");
    auto* result = static_cast<char*>(std::malloc(used + 1));
    if (result) std::memcpy(result, output, used + 1);
    return result;
}
}
