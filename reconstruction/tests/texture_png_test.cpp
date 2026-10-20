#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_png.hpp"
#include <cassert>
#include <fstream>
int main(int argc,char** argv){
 assert(argc==2);std::vector<std::uint8_t> rgba(127*89*4);for(std::size_t i=0;i<rgba.size();++i)rgba[i]=static_cast<std::uint8_t>(i*37);
 std::ofstream f(argv[1],std::ios::binary);std::size_t largest=0;
 rf::encodeRgbaPng(127,89,rgba,[&](std::span<const std::uint8_t> b){largest=std::max(largest,b.size());f.write(reinterpret_cast<const char*>(b.data()),b.size());});assert(largest<=65552);f.close();
 bool failed=false;try{rf::encodeRgbaPng(127,89,rgba,[](auto){throw rf::Error("sink full");});}catch(const rf::Error&){failed=true;}assert(failed);
 std::atomic_bool flag{true};failed=false;try{rf::cancellation::Scope scope(&flag);rf::encodeRgbaPng(127,89,rgba,[](auto){});}catch(const rf::cancellation::Cancelled&){failed=true;}assert(failed);
}
