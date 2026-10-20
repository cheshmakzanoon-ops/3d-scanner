#include "realityforge/matching_cost.hpp"
#include <cassert>
#include <iostream>
int main(){
 std::array<double,9>a{10,30,50,20,40,60,70,90,80},b{};
 for(int i=0;i<9;++i)b[i]=a[i]*1.5+12;
 auto c=rf::matching::zncc(a,b);assert(c.valid&&std::abs(c.value)<1e-12);
 for(int i=0;i<9;++i){b[i]=200-a[i];}assert(rf::matching::zncc(a,b).value>1.999);
 b.fill(255);assert(!rf::matching::zncc(a,b).valid);
 std::array<unsigned char,9>mask{};mask.fill(255);mask[4]=0;assert(!rf::matching::zncc(a,a,mask).valid);
 std::array<rf::matching::Cost,3>costs{{{.1,9,true},{.8,9,true},{.9,9,true}}};
 assert(rf::matching::aggregate(costs).cost==.1);assert(rf::matching::aggregate(costs,true).cost==.8);
 std::array<double,9>curve{1,1,.4,.2,.1,.2,.4,.7,.9};assert(rf::matching::distinctMinimum(curve).margin>.5);
 bool rejected=false;try{rf::matching::zncc(a,std::span<const double>(a).first(2));}catch(const std::invalid_argument&){rejected=true;}assert(rejected);
 std::cout<<"matching cost analytic, exposure, mask, saturation and ambiguity checks passed\n";
}
