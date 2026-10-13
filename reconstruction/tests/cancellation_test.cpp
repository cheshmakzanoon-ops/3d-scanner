#include "realityforge/cancellation.hpp"
#include "realityforge/dense.hpp"
#include <atomic>
#include <chrono>
#include <future>
#include <iostream>
#include <stdexcept>
#include <thread>
#include <vector>

namespace {
void require(bool condition,const char* what){if(!condition)throw std::runtime_error(what);}
std::vector<rf::DenseView> fixture(){
    std::vector<rf::DenseView> views;
    constexpr int w=128,h=96;
    for(int i=0;i<2;++i){
        rf::DenseView view;
        view.width=w;view.height=h;
        view.fx=view.fy=150;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-i*.5,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            const auto p=static_cast<std::size_t>(y*w+x)*4;
            const unsigned u=static_cast<unsigned>((x+i*14)*37+y*17);
            view.rgba[p]=static_cast<std::uint8_t>((u^(u>>5))&255);
            view.rgba[p+1]=static_cast<std::uint8_t>((u*13)&255);
            view.rgba[p+2]=static_cast<std::uint8_t>((u*7)&255);
            view.rgba[p+3]=255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
}
int main(){
    int cases=0;
    const auto test=[&](const char* name,auto&& callback){
        callback();++cases;std::cout<<"PASS "<<name<<'\n';
    };
    test("cancelled operation fails before allocating per-view image buffers",[]{
        std::atomic_bool cancelled=true;
        rf::cancellation::Scope scoped(&cancelled);
        bool caught=false;
        try{(void)rf::reconstructDense(fixture());}
        catch(const rf::cancellation::Cancelled& e){caught=std::string(e.what())=="Reconstruction cancelled";}
        require(caught,"expected explicit cancel error");
    });
    test("nested scopes restore caller's previous cancellation state",[]{
        std::atomic_bool cancelled=true,allowed=false;
        rf::cancellation::Scope outer(&cancelled);
        {rf::cancellation::Scope inner(&allowed);rf::cancellation::checkpoint();}
        bool restored=false;
        try{rf::cancellation::checkpoint();}catch(const rf::cancellation::Cancelled&){restored=true;}
        require(restored,"nested scope leaked into parent");
    });
    test("native C ABI reports cancellation without a partial JSON result",[]{
        auto views=fixture();
        std::vector<std::uint8_t> pixels;
        double poses[32]={};
        for(std::size_t i=0;i<views.size();++i){
            pixels.insert(pixels.end(),views[i].rgba.begin(),views[i].rgba.end());
            for(int k=0;k<9;++k)poses[i*16+k]=views[i].pose.rotation[k];
            poses[i*16+9]=views[i].pose.translation.x;
            poses[i*16+12]=views[i].fx;poses[i*16+13]=views[i].fy;
            poses[i*16+14]=views[i].cx;poses[i*16+15]=views[i].cy;
        }
        std::atomic_bool cancelled=true;
        rf::cancellation::Scope scoped(&cancelled);
        auto text=rf_dense_json(pixels.data(),128,96,poses,2,3.0,7.0,56,3);
        require(text!=nullptr,"cancel response allocation failed");
        const std::string response(text);rf_free(text);
        require(response==R"({"error":"Reconstruction cancelled"})","partial results leaked after cancellation");
    });
    test("operation cancellation is isolated per thread and registry owner",[]{
        std::atomic_bool stopped=true,healthy=false;
        auto blocked=std::async(std::launch::async,[&]{
            rf::cancellation::Scope scope(&stopped);
            try{rf::cancellation::checkpoint();return false;}
            catch(const rf::cancellation::Cancelled&){return true;}
        });
        auto unaffected=std::async(std::launch::async,[&]{
            rf::cancellation::Scope scope(&healthy);
            rf::cancellation::checkpoint();return true;
        });
        require(blocked.get()&&unaffected.get(),"one operation interfered with another");
        rf::cancellation::checkpoint(); // current thread still has no operation
    });
    test("long-running dense scan observes cancellation while computing",[]{
        std::atomic_bool stop=false;
        auto images=fixture();
        auto work=std::async(std::launch::async,[&]{
            rf::cancellation::Scope scope(&stop);
            try{
                rf::DenseSettings s;s.depthPlanes=96;s.pixelStride=2;
                (void)rf::reconstructDense(images,s);
                return false;
            }catch(const rf::cancellation::Cancelled&){return true;}
        });
        std::this_thread::sleep_for(std::chrono::milliseconds(12));
        stop.store(true);
        require(work.get(),"running CPU scan ignored cancellation");
    });
    std::cout<<cases<<" cancellation regressions passed\n";
}
