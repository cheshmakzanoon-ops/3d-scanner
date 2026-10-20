#include "realityforge/depth_cache_manifest.hpp"
#ifdef NDEBUG
#undef NDEBUG
#endif
#include <cassert>
#include <fstream>
#include <chrono>
#include <iostream>
int main(){using namespace rf::depth_cache;std::string hash(64,'a');
 Entry e{0,0,64,48,96+64*48*8,hash};std::array<Entry,1>a{e};assert(validate(a,hash,100000));a[0].bytes++;assert(!validate(a,hash,100000));a[0]=e;assert(!validate(a,std::string(64,'b'),100000));std::array<Entry,2>duplicate{e,e};assert(!validate(duplicate,hash,100000));
 auto dir=std::filesystem::temp_directory_path()/("rf-cache-budget-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));std::filesystem::create_directory(dir);
 for(auto name:{"ref-0.bin","ref-1.bin","original.jpg","model.glb"}){std::ofstream f(dir/name);f<<std::string(100,'x');}
 assert(evictFor(dir,150,100,dir/"ref-2.bin"));assert(std::filesystem::exists(dir/"original.jpg")&&std::filesystem::exists(dir/"model.glb"));assert(!std::filesystem::exists(dir/"ref-0.bin")&&!std::filesystem::exists(dir/"ref-1.bin"));
 assert(!evictFor(dir,50,100,dir/"ref-2.bin"));std::filesystem::remove_all(dir);
 std::cout<<"strict cache identity, duplicate manifests, byte budget and original preservation checks passed\n";
}
