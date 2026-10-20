#include "realityforge/texture.hpp"
#include <atomic>
#include <cstdlib>
#include <new>
static std::atomic_bool failAlloc{false};
void* operator new(std::size_t size){if(failAlloc.load())throw std::bad_alloc();if(auto* p=std::malloc(size?size:1))return p;throw std::bad_alloc();}
void operator delete(void* p) noexcept {std::free(p);}
void operator delete(void* p,std::size_t) noexcept {std::free(p);}
int main(){
 bool escaped=false;char* result=nullptr;failAlloc.store(true);
 try{result=rf_textured_surface_json(nullptr,0,0,nullptr,0,0,nullptr,0,0,0,0,0,nullptr,nullptr,nullptr,nullptr);}catch(...){escaped=true;}
 failAlloc.store(false);std::free(result);return escaped?1:0;
}
