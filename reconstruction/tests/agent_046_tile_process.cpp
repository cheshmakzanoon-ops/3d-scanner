#include "realityforge/dense.hpp"
#include "realityforge/depth_checkpoint.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <unistd.h>
#include <signal.h>
#include <sys/wait.h>
#include <poll.h>
#include <filesystem>
#include <chrono>
#include <stdexcept>
#include <iostream>
std::vector<rf::DenseView> fixture(){
    std::vector<rf::DenseView> views;
    constexpr int w=72,h=56;
    for(int cam=0;cam<3;++cam){
        rf::DenseView v;v.width=w;v.height=h;
        v.fx=v.fy=93.;v.cx=(w-1)/2.;v.cy=(h-1)/2.;
        v.pose={rf::identity(),{-cam*.4,0.,0.}};
        v.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            auto world=(x-v.cx)/v.fx*5.+cam*.4;
            auto wave=std::sin(world*15.+y*.12)*38.+std::cos(world*29.-y*.21)*43.+128.;
            auto val=static_cast<std::uint8_t>(std::clamp(wave,0.,255.));
            const auto offset=(y*w+x)*4;
            v.rgba[offset]=val;v.rgba[offset+1]=val;v.rgba[offset+2]=val;v.rgba[offset+3]=255;
        }
        views.push_back(std::move(v));
    }
    return views;
}
namespace {int notify=-1;unsigned creates=0;void require(bool v,const char*m){if(!v)throw std::runtime_error(m);}}
extern "C" void rf_depth_checkpoint_test_stage(unsigned stage){if(notify>=0&&stage==1&&++creates==2){char c='R';if(::write(notify,&c,1)!=1)::_exit(91);for(;;)::pause();}}
struct ChildPool {std::vector<pid_t> pids;void track(pid_t p){if(p>0)pids.push_back(p);}~ChildPool(){for(auto p:pids){int status=0;if(::waitpid(p,&status,WNOHANG)==0){::kill(p,SIGKILL);(void)::waitpid(p,&status,0);}}}};
int main(){ChildPool pool;try{
 const auto dir=std::filesystem::temp_directory_path()/("rf-dense-kill-"+std::to_string(::getpid())+"-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));std::filesystem::create_directory(dir);
 auto images=fixture();rf::DenseSettings settings;settings.nearDepth=3.2;settings.farDepth=7.2;settings.depthPlanes=36;settings.tileEdge=24;
 int fd[2];require(::pipe(fd)==0,"pipe failed");pid_t child=::fork();pool.track(child);require(child>=0,"fork failed");
 if(child==0){::close(fd[0]);notify=fd[1];rf::depth_checkpoint::Scope cache(dir.string(),std::string(64,'a'));(void)rf::reconstructDense(images,settings);::_exit(92);}
 ::close(fd[1]);pollfd ready{fd[0],POLLIN,0};require(::poll(&ready,1,30000)>0,"dense solver did not reach second tile publication");char c=0;require(::read(fd[0],&c,1)==1&&c=='R',"tile barrier absent");::close(fd[0]);
 require(::kill(child,SIGKILL)==0,"kill failed");int status=0;require(::waitpid(child,&status,0)==child&&WIFSIGNALED(status),"dense process was not terminated");
 require(std::filesystem::exists(dir/"tile-0-0.bin")&&!std::filesystem::exists(dir/"tile-0-1.bin")&&!std::filesystem::exists(dir/"ref-0.bin"),"partial reference incorrectly accepted or older tile lost");
 rf::DenseResult resumed;{rf::depth_checkpoint::Scope cache(dir.string(),std::string(64,'a'));resumed=rf::reconstructDense(images,settings);}
 const auto clean=rf::reconstructDense(images,settings);require(resumed.resumedDepthTiles==1&&resumed.recomputedDepthTiles+1==resumed.totalTiles,"completed tile was not skipped or interrupted tile not recomputed");
 for(std::size_t i=0;i<clean.maps.size();++i)require(clean.maps[i].depth==resumed.maps[i].depth&&clean.maps[i].confidence==resumed.maps[i].confidence,"actual resumed dense output differs from clean solve");
 for(const auto&e:std::filesystem::directory_iterator(dir)){require(e.path().filename().string().find(".partial-")==std::string::npos,"killed tile orphan remained");}std::filesystem::remove_all(dir);
 std::cout<<"actual dense SIGKILL skips older completed tile, recomputes interrupted tile and reproduces clean maps\n";return 0;
 }catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
