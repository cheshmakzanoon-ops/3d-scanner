#include "realityforge/dense.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
namespace {
void require(bool value,const char* message){if(!value)throw std::runtime_error(message);}
std::vector<rf::DenseView> photographs(){
    std::vector<rf::DenseView> views;
    for(int camera=0;camera<4;++camera){
        rf::DenseView v;v.width=104;v.height=76;v.fx=v.fy=116;v.cx=51.5;v.cy=37.5;
        v.pose={rf::identity(),{-.35*camera,0,0}};v.rgba.resize(104*76*4);
        // Three cameras see z=5. One photometric outlier has a nearby false
        // peak. No synthetic depth/truth is supplied to the reconstruction.
        const double photographedDepth=camera==3?5.08:5.;
        for(int y=0;y<v.height;++y)for(int x=0;x<v.width;++x){
            const double wx=(x-v.cx)*photographedDepth/v.fx+.35*camera;
            const double wy=(y-v.cy)*photographedDepth/v.fy;
            const double gray=128+42*std::sin(11*wx+3*wy)+35*std::sin(7*wy-2*wx)+20*std::cos(23*wx+17*wy);
            const auto value=static_cast<std::uint8_t>(std::round(std::clamp(gray,0.,255.)));
            const auto p=static_cast<std::size_t>(y*v.width+x)*4;
            for(int c=0;c<3;++c)v.rgba[p+c]=value;
            v.rgba[p+3]=255;
        }
        views.push_back(std::move(v));
    }
    return views;
}
double pixel(const rf::DenseView& v,double x,int y){
    if(x<0||x>=v.width-1||y<0||y>=v.height)return std::numeric_limits<double>::quiet_NaN();
    const auto ix=static_cast<int>(std::floor(x));const double fraction=x-ix;
    return (1-fraction)*v.rgba[(static_cast<std::size_t>(y)*v.width+ix)*4]+fraction*v.rgba[(static_cast<std::size_t>(y)*v.width+ix+1)*4];
}
// Independent scalar ZNCC/median oracle, using original photographs and the
// supplied calibrated rectified geometry, not the implementation's cost code.
double objective(const std::vector<rf::DenseView>& views,int x,int y,double z){
    std::array<double,3> costs{};
    for(int camera=1;camera<4;++camera){
        std::array<double,25> a{},b{};std::size_t next=0;double ma=0,mb=0;
        for(int dy=-2;dy<=2;++dy)for(int dx=-2;dx<=2;++dx){
            a[next]=pixel(views[0],x+dx,y+dy);
            b[next]=pixel(views[camera],x+dx-116*.35*camera/z,y+dy);
            if(!std::isfinite(a[next])||!std::isfinite(b[next]))return 2;
            ma+=a[next];mb+=b[next];++next;
        }
        ma/=25;mb/=25;double va=0,vb=0,cross=0;
        for(std::size_t k=0;k<25;++k){const double da=a[k]-ma,db=b[k]-mb;va+=da*da;vb+=db*db;cross+=da*db;}
        if(va/25<18||vb/25<18)return 2;
        costs[camera-1]=1-std::clamp(cross/std::sqrt(va*vb),-1.,1.);
    }
    std::sort(costs.begin(),costs.end());return costs[1];
}
rf::DenseSettings settings(){rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7;s.depthPlanes=40;s.pixelStride=3;s.tileEdge=24;return s;}
void dump(const rf::DenseResult& r,const std::string& file){
    std::ofstream output(file,std::ios::binary);require(bool(output),"cannot create parity capture");
    for(const auto& map:r.maps){output.write(reinterpret_cast<const char*>(map.depth.data()),static_cast<std::streamsize>(map.depth.size()*sizeof(float)));output.write(reinterpret_cast<const char*>(map.confidence.data()),static_cast<std::streamsize>(map.confidence.size()*sizeof(float)));}
    output<<rf::denseJson(r);require(bool(output),"cannot write parity capture");
}
}
int main(int argc,char** argv){try{
    const auto views=photographs();auto s=settings();
    if(argc==5&&std::string(argv[1])=="capture"){
        const int mode=std::stoi(argv[2]);require(mode>=0&&mode<=2,"invalid capture mode");s.localRefinementIterations=mode?6:0;s.robustSourceAggregation=mode==2;
        std::filesystem::create_directories(argv[3]);rf::depth_checkpoint::Scope scope(argv[3],std::string(64,'7'));
        const auto r=rf::reconstructDense(views,s);dump(r,argv[4]);
        std::cout<<"mode="<<mode<<" tiles="<<r.totalTiles<<" resumedTiles="<<r.resumedDepthTiles<<" resumedMaps="<<r.resumedDepthMaps<<" recomputedTiles="<<r.recomputedDepthTiles<<'\n';return 0;
    }
    require(argc==1,"unexpected test arguments");
    s.robustSourceAggregation=true;const auto sweep=rf::reconstructDense(views,s);
    s.localRefinementIterations=6;const auto refined=rf::reconstructDense(views,s);
    require(sweep.sourceViews[0].size()==3&&refined.sourceViews[0].size()==3,"fixture lost independent source evidence");
    std::size_t compared=0,worsened=0,improved=0;double maximumIncrease=0;
    for(int y=3;y<72;y+=3)for(int x=45;x<100;x+=3){
        const auto p=static_cast<std::size_t>(y)*104+x;const double a=sweep.maps[0].depth[p],b=refined.maps[0].depth[p];if(a<=0||b<=0)continue;
        const double before=objective(views,x,y,a),after=objective(views,x,y,b);if(before>=2||after>=2)continue;
        ++compared;maximumIncrease=std::max(maximumIncrease,after-before);if(after>before+1e-6)++worsened;if(after+1e-6<before)++improved;
    }
    std::cout<<"compared="<<compared<<" worsened="<<worsened<<" improved="<<improved<<" maximumMedianCostIncrease="<<maximumIncrease<<'\n';
    require(compared>=100,"insufficient actual four-view cost comparisons");
    require(worsened==0,"robust local refinement worsened the actual median source objective");
    require(improved>0,"local refinement did not execute a strictly improving source-supported proposal");
}catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
