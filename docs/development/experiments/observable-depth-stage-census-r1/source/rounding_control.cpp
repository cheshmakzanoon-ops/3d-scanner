#include <cmath>
#include <iomanip>
#include <iostream>
#include <limits>
int main(){
 std::cout<<std::hexfloat;
 for(int k=-200;k<=200;++k){
  double tie=k+.5;
  for(double q:{std::nextafter(tie,-INFINITY),tie,std::nextafter(tie,INFINITY)})
   std::cout<<"q "<<q<<" "<<std::round(q)<<"\n";
  double x=2.*k;
  for(double v:{std::nextafter(x,-INFINITY),x,std::nextafter(x,INFINITY)})
   std::cout<<"x "<<v<<" "<<std::round((v-3.)/2.)*2.+3.<<"\n";
 }
}
