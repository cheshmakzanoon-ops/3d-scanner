#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/texture_png.hpp"
#include <cassert>
#include <fstream>
int main(int argc,char** argv){
 assert(argc==3);std::vector<std::uint8_t> rgba(127*89*4);for(std::size_t i=0;i<rgba.size();++i)rgba[i]=static_cast<std::uint8_t>(i*37);
 std::ofstream f(argv[1],std::ios::binary);std::size_t largest=0,total=0;
 rf::encodeRgbaPng(127,89,rgba,[&](std::span<const std::uint8_t> b){largest=std::max(largest,b.size());total+=b.size();f.write(reinterpret_cast<const char*>(b.data()),b.size());});assert(largest<=65552);f.close();
 std::ofstream reference(argv[2],std::ios::binary);std::size_t rawSize=0;
 rf::encodeRgbaPngStoredReference(127,89,rgba,[&](auto b){rawSize+=b.size();reference.write(reinterpret_cast<const char*>(b.data()),b.size());});reference.close();assert(total<rawSize);
 bool failed=false;try{rf::encodeRgbaPng(127,89,rgba,[](auto){throw rf::Error("sink full");});}catch(const rf::Error&){failed=true;}assert(failed);
 std::atomic_bool midStop{false};std::size_t calls=0;failed=false;
 try{rf::cancellation::Scope scope(&midStop);rf::encodeRgbaPng(127,89,rgba,[&](auto){if(++calls==3)midStop.store(true);});}catch(const rf::cancellation::Cancelled&){failed=true;}assert(failed&&calls>=3);
 std::atomic_bool flag{true};failed=false;try{rf::cancellation::Scope scope(&flag);rf::encodeRgbaPng(127,89,rgba,[](auto){});}catch(const rf::cancellation::Cancelled&){failed=true;}assert(failed);
}
