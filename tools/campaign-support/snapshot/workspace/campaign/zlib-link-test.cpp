#include <zlib.h>
#include <array>
#include <cstring>
extern "C" int rf_zlib_link_check() {
    const std::array<unsigned char,6> original{'p','i','x','e','l','s'};
    std::array<unsigned char,128> compressed{};
    std::array<unsigned char,6> decoded{};
    uLongf encodedSize=compressed.size(), decodedSize=decoded.size();
    if(compress2(compressed.data(), &encodedSize, original.data(), original.size(), 1)!=Z_OK) return 1;
    if(uncompress(decoded.data(), &decodedSize, compressed.data(), encodedSize)!=Z_OK) return 2;
    return decodedSize!=original.size() || std::memcmp(decoded.data(),original.data(),original.size());
}
#ifndef __ANDROID__
int main() { return rf_zlib_link_check(); }
#endif
