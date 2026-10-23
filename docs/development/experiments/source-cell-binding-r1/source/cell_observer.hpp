#pragma once
#include "dense_witness.hpp"
namespace cell_observer {
inline bool stage=false;inline std::thread::id owner;inline std::size_t cells=0,triangles=0;
inline std::array<std::uint64_t,40> cell{};
inline std::array<int,3> local{};inline bool open=false,proposalReady=false;
inline void check(){if(!stage||owner!=std::this_thread::get_id())throw std::runtime_error("cell observer stage/thread");}
inline void begin(){if(stage||cells||triangles)throw std::runtime_error("cell observer repeated stage");stage=true;owner=std::this_thread::get_id();}
template<class Pixels,class Ids>void start(std::size_t cam,int x,int y,const Pixels& pix,const Ids& ids){
 check();if(open||cells>=34048)throw std::runtime_error("cell count/state");cell={};cell[0]=cells++;cell[1]=cam;cell[2]=x;cell[3]=y;
 for(int j=0;j<4;++j){cell[4+j]=pix[j];cell[8+j]=static_cast<std::uint64_t>(static_cast<std::int64_t>(ids[j]));}open=true;
}
inline void finish(){check();if(!open||proposalReady)throw std::runtime_error("cell end state");dense_witness::words("cells.bin",cell.data(),cell.size());open=false;}
template<class Depths>void diagonal(const Depths& d,double d03,double d12,bool diag03){check();if(!open)throw std::runtime_error("cell diagonal state");for(int j=0;j<4;++j)cell[12+j]=dense_witness::f32(d[j]);cell[16]=1;cell[17]=diag03;cell[18]=dense_witness::d64(d03);cell[19]=dense_witness::d64(d12);}
inline void proposal(int a,int b,int c){check();if(!open||!cell[16]||proposalReady)throw std::runtime_error("cell proposal state");local={a,b,c};proposalReady=true;}
struct Triangle {
 std::array<std::uint64_t,32> r{};
 template<class Ids,class Depths>Triangle(std::size_t cam,const Ids& ids,const Depths& d){check();if(!proposalReady||triangles>=68096)throw std::runtime_error("triangle count/state");proposalReady=false;r[0]=triangles++;r[1]=cell[0];r[2]=cam;r[14]=255;
  for(int j=0;j<3;++j){r[3+j]=local[j];r[6+j]=static_cast<std::uint64_t>(static_cast<std::int64_t>(ids[j]));r[9+j]=dense_witness::f32(d[j]);}}
 ~Triangle() noexcept(false){if(std::uncaught_exceptions())return;check();if(r[14]==255)throw std::runtime_error("triangle unclassified");dense_witness::words("triangles.bin",r.data(),r.size());}
 bool test(unsigned bit,bool result){check();if(bit>6||(r[12]&(1ull<<bit)))throw std::runtime_error("triangle repeated predicate");r[12]|=1ull<<bit;if(result)r[13]|=1ull<<bit;return result;}
 bool reject(unsigned code){r[14]=code;return false;}
 void depths(double hi,double lo){r[15]=dense_witness::d64(hi);r[16]=dense_witness::d64(lo);}
 void scale(double footprint,double limit){r[17]=dense_witness::d64(footprint);r[18]=dense_witness::d64(limit);}
 template<class V>void normal(const V& v){r[19]=dense_witness::d64(v.x);r[20]=dense_witness::d64(v.y);r[21]=dense_witness::d64(v.z);}
 template<class V>void centroid(const V& v){r[22]=dense_witness::d64(v.x);r[23]=dense_witness::d64(v.y);r[24]=dense_witness::d64(v.z);}
 bool winding(bool flip){check();r[27]=1;r[25]=flip;return flip;}
 bool accepted(std::size_t index){r[14]=0;r[26]=index;return true;}
};
inline void end(){check();if(open||proposalReady)throw std::runtime_error("cell open at stage end");auto o=ledger_observer::stream();o<<cells<<' '<<triangles<<'\n';ledger_observer::emit("cell-counts.tsv",o.str());stage=false;}
}
