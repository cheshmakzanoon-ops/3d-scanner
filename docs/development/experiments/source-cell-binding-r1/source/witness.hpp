#pragma once
#include "ledger_observer.hpp"
#include <array>
#include <bit>
#include <thread>
#include <exception>
namespace dense_witness {
inline bool stage=false;inline std::thread::id owner;inline std::size_t attempts=0,generated=0;
struct Attempt;inline thread_local Attempt* active=nullptr;
inline std::uint64_t d64(double d){return std::bit_cast<std::uint64_t>(d);}
inline std::uint64_t f32(float f){return std::bit_cast<std::uint32_t>(f);}
inline void words(const char* name,const std::uint64_t* data,std::size_t count){std::string row;row.reserve(count*8);for(std::size_t i=0;i<count;++i)for(int j=0;j<8;++j)row.push_back(static_cast<char>(data[i]>>(8*j)));ledger_observer::emit(name,row);}
template<class Settings,class Plan>void begin(const Settings& s,const Plan& plan){
 if(stage||active||s.discontinuityAwareConsistency||s.normalConsistency||s.pixelStride!=2||s.patchRadius!=2||plan.size()!=8)throw std::runtime_error("Unexpected witness stage/config");
 stage=true;owner=std::this_thread::get_id();auto o=ledger_observer::stream();
 for(std::size_t ref=0;ref<plan.size();++ref){if(plan[ref].size()>3)throw std::runtime_error("source plan cap");o<<ref<<' '<<plan[ref].size();for(auto src:plan[ref])o<<' '<<src;o<<'\n';}ledger_observer::emit("source-plan.tsv",o.str());
}
struct Attempt {
 std::array<std::uint64_t,24> row{};
 Attempt(std::size_t ref,std::size_t src,int x,int y,float depth){
  if(!stage||owner!=std::this_thread::get_id()||active||attempts>=105336)throw std::runtime_error("witness attempt stage/work cap");
  row[0]=attempts++;row[1]=ref;row[2]=src;row[3]=x;row[4]=y;row[5]=255;row[23]=f32(depth);active=this;
 }
 ~Attempt() noexcept(false){active=nullptr;if(std::uncaught_exceptions())return;if(row[5]==255)throw std::runtime_error("unclassified native attempt");words("witness.bin",row.data(),row.size());}
 void fail(std::uint64_t code){row[5]=code;}
 template<class V>void q(const V& p){row[8]=d64(p.x);row[9]=d64(p.y);row[10]=d64(p.z);row[22]|=1;}
 void projection(double x,double y){row[11]=d64(x);row[12]=d64(y);row[22]|=2;}
 void depth(float d){row[13]=f32(d);row[22]|=4;}
 template<class V>void reverse(const V& back,const V& camera){row[15]=d64(back.x);row[16]=d64(back.y);row[17]=d64(back.z);row[18]=d64(camera.z);row[22]|=8;}
 void reprojection(double x,double y,double distance){row[19]=d64(x);row[20]=d64(y);row[21]=d64(distance);row[22]|=16;}
};
inline void helperThread(){if(stage&&owner!=std::this_thread::get_id())throw std::runtime_error("cross-thread consistency helper");}
inline void selected(int x,int y){helperThread();if(active){active->row[6]=static_cast<std::uint64_t>(static_cast<std::int64_t>(x));active->row[7]=static_cast<std::uint64_t>(static_cast<std::int64_t>(y));active->row[22]|=32;}}
inline void mapRead(){helperThread();if(active)active->row[22]|=64;}
inline void relative(float value){helperThread();if(active){active->row[14]=f32(value);active->row[22]|=128;}}
inline void sample(std::size_t id,std::size_t ref,int x,int y,int confirmations){if(!stage||active||owner!=std::this_thread::get_id()||id!=generated||generated>=35112)throw std::runtime_error("generated witness binding");std::uint64_t row[]{generated++,ref,static_cast<std::uint64_t>(x),static_cast<std::uint64_t>(y),static_cast<std::uint64_t>(confirmations)};words("generated.bin",row,5);}
inline void end(){if(!stage||active||owner!=std::this_thread::get_id())throw std::runtime_error("witness end stage");stage=false;auto o=ledger_observer::stream();o<<attempts<<' '<<generated<<'\n';ledger_observer::emit("witness-counts.tsv",o.str());}
}
