#include "realityforge/core.hpp"
#include <cmath>
#include <functional>
#include <iostream>
#include <limits>
#include <random>
#include <vector>
namespace {
int passed=0,failed=0;
void require(bool ok,const char*message){if(!ok)throw std::runtime_error(message);}
void test(const char*name,const std::function<void()>& f){try{f();++passed;std::cout<<"PASS "<<name<<'\n';}catch(const std::exception&e){++failed;std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';}}
void mustReject(const std::function<void()>& f){bool rejected=false;try{f();}catch(const rf::Error&){rejected=true;}require(rejected,"Expected an explicit rejection");}
std::vector<rf::Match> fixture(int n,bool noise=false,int outliers=0){std::mt19937 rng(137);std::uniform_real_distribution<double>u(-1,1),z(3,8);std::normal_distribution<double>eps(0,noise?0.00015:0);std::vector<rf::Match>m;double a=0.08;rf::Mat3 r{std::cos(a),0,std::sin(a),0,1,0,-std::sin(a),0,std::cos(a)};
for(int i=0;i<n;++i){rf::Vec3 p{u(rng),u(rng),z(rng)};auto q=rf::multiply(r,p);q.x-=1;m.push_back({{p.x/p.z+eps(rng),p.y/p.z+eps(rng)},{q.x/q.z+eps(rng),q.y/q.z+eps(rng)}});}for(int i=0;i<outliers;++i)m[i].b={u(rng)*0.5,u(rng)*0.5};return m;}
}
int main(){
 test("identity",[]{auto i=rf::identity();auto p=rf::multiply(i,rf::Vec3{1,2,3});require(p.x==1&&p.y==2&&p.z==3,"matrix identity");});
 test("transpose multiplication",[]{auto i=rf::identity();require(std::abs(rf::determinant(rf::multiply(i,rf::transpose(i)))-1)<1e-12,"determinant");});
 test("triangulation known point",[]{rf::Match m{{0.05,0.075},{-0.2,0.075}};auto p=rf::triangulate(m,rf::identity(),{-1,0,0});require(std::abs(p.xyz.x-.2)<1e-8&&std::abs(p.xyz.z-4)<1e-8,"triangulation accuracy");});
 test("behind-camera rejection",[]{mustReject([]{rf::triangulate({{.05,.075},{-.2,.075}},rf::identity(),{1,0,0});});});
 test("quality uniform",[]{std::vector<std::uint8_t>p(256,128);auto q=rf::quality(p,16,16,16);require(q.mean==128&&q.laplacianVariance==0&&q.standardDeviation==0,"uniform image statistics");});
 test("quality texture",[]{std::vector<std::uint8_t>p(256);for(int y=0;y<16;++y)for(int x=0;x<16;++x)p[y*16+x]=(x+y)%2?255:0;auto q=rf::quality(p,16,16,16);require(q.laplacianVariance>1000&&q.darkFraction==.5&&q.brightFraction==.5,"checkerboard quality");});
 test("quality bad buffer",[]{mustReject([]{std::vector<std::uint8_t>p(3);rf::quality(p,30,30,30);});});
 test("quality padded stride",[]{std::vector<std::uint8_t>p(80,100);auto q=rf::quality(p,8,8,10);require(q.mean==100,"stride");});
 test("clean two-view recovery",[]{auto r=rf::reconstruct(fixture(100));require(r.points.size()>=95,"registration lost clean points");require(r.medianError<1e-7,"high reprojection error");require(r.translation.x<-.99,"wrong translation");require(std::abs(r.rotation[2]-std::sin(.08))<1e-5,"wrong rotation");});
 test("noisy outlier robust recovery",[]{auto r=rf::reconstruct(fixture(180,true,45));require(r.points.size()>110,"not robust to outliers");require(r.translation.x<-.98,"translation corrupted");require(r.medianError<.002,"large normalized error");});
 test("deterministic result",[]{auto m=fixture(80);auto a=rf::reconstruct(m),b=rf::reconstruct(m);require(rf::resultJson(a)==rf::resultJson(b),"nondeterministic");});
 test("too few matches",[]{mustReject([]{rf::reconstruct(fixture(6));});});
 test("nan rejection",[]{auto m=fixture(50);m[0].a.x=std::numeric_limits<double>::quiet_NaN();mustReject([&]{rf::reconstruct(m);});});
 test("invalid threshold",[]{auto m=fixture(50);rf::Settings s;s.threshold=0;mustReject([&]{rf::reconstruct(m,s);});});
 test("constant-feature rejection",[]{std::vector<rf::Match>m(40,{{.1,.2},{.1,.2}});mustReject([&]{rf::reconstruct(m);});});
 test("empty GLB rejected",[]{mustReject([]{rf::writeGlb({},"must_not_exist.glb");});});
 test("C ABI invalid input",[]{char*json=rf_reconstruct_json(nullptr,0,.002,nullptr,nullptr);require(json&&std::string(json).find("error")!=std::string::npos,"C ABI error contract");rf_free(json);});
 std::cout<<"\n"<<passed<<" passed; "<<failed<<" failed\n";return failed?1:0;
}
