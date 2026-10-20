#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/dense.hpp"
#include <sys/wait.h>
#include <sys/file.h>
#include <unistd.h>
#include <fcntl.h>
#include <signal.h>
#include <poll.h>
#include <filesystem>
#include <iostream>
#include <fstream>
#include <stdexcept>
#include <chrono>
#include <array>
namespace {
int notifyFd=-1;unsigned stoppedStage=0;
void require(bool v,const char* m){if(!v)throw std::runtime_error(m);}
constexpr auto HASH="abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789";
struct Temp{std::filesystem::path dir=std::filesystem::temp_directory_path()/("rf-process-cache-"+std::to_string(::getpid())+"-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));Temp(){std::filesystem::create_directory(dir);}~Temp(){std::error_code ec;std::filesystem::remove_all(dir,ec);}};
void waitOkay(pid_t child){int status=0;require(::waitpid(child,&status,0)==child&&WIFEXITED(status)&&WEXITSTATUS(status)==0,"cache subprocess failed");}
rf::DenseDepthMap patch(float z=4){return {32,48,std::vector<float>(32*48,z),std::vector<float>(32*48,.8f)};}
rf::DenseDepthMap map(float z=4){return {64,48,std::vector<float>(64*48,z),std::vector<float>(64*48,.8f)};}
}
extern "C" void rf_depth_checkpoint_test_stage(unsigned stage){
 if(notifyFd>=0&&stage==stoppedStage){char c='R';if(::write(notifyFd,&c,1)!=1)::_exit(91);for(;;)::pause();}
}
struct ChildPool {std::vector<pid_t> pids;void track(pid_t p){if(p>0)pids.push_back(p);}~ChildPool(){for(auto p:pids){int status=0;if(::waitpid(p,&status,WNOHANG)==0){::kill(p,SIGKILL);(void)::waitpid(p,&status,0);}}}};
int main(){ChildPool pool;try{
 Temp t;const auto original=map();
 {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);require(rf::depth_checkpoint::store(0,original),"seed prior reference failed");}
 rf::depth_checkpoint::TileKey first{0,0,64,48,0,0,32,48,3,1,"cpu-v1 near=3 far=7 planes=56 tile=32"};
 auto second=first;second.tile=1;second.x=32;
 {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);require(rf::depth_checkpoint::storeTile(first,patch()),"seed completed tile failed");}
 for(unsigned stage:{1u,2u}){
  std::filesystem::remove(t.dir/"tile-0-1.bin");int pipeFds[2];require(::pipe(pipeFds)==0,"pipe failed");pid_t child=::fork();pool.track(child);require(child>=0,"fork failed");
  if(child==0){::close(pipeFds[0]);notifyFd=pipeFds[1];stoppedStage=stage;rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);(void)rf::depth_checkpoint::storeTile(second,patch(6));::_exit(92);}
  ::close(pipeFds[1]);pollfd ready{pipeFds[0],POLLIN,0};require(::poll(&ready,1,10000)>0,"child did not reach exact write boundary");char c=0;require(::read(pipeFds[0],&c,1)==1&&c=='R',"bad child barrier");::close(pipeFds[0]);
  require(::kill(child,SIGKILL)==0,"kill failed");int status=0;require(::waitpid(child,&status,0)==child&&WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL,"child was not actually terminated");
  auto previous=map(0),recovered=map(0);bool orphan=false;for(const auto&e:std::filesystem::directory_iterator(t.dir))if(e.path().filename().string().find(".partial-")!=std::string::npos)orphan=true;require(orphan,"termination did not preserve test orphan");
  {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);require(rf::depth_checkpoint::load(0,previous)&&previous.depth==original.depth,"kill damaged previously accepted reference");require(rf::depth_checkpoint::loadTile(first,recovered)&&recovered.depth==patch().depth,"kill damaged earlier completed tile");require(!rf::depth_checkpoint::loadTile(second,recovered),"partial tile accepted after process kill");require(rf::depth_checkpoint::storeTile(second,patch(6)),"relaunch recomputation failed");require(rf::depth_checkpoint::loadTile(second,recovered)&&recovered.depth==patch(6).depth,"relaunch differs from clean computation");auto stale=second;stale.solverIdentity+=" changed";require(!rf::depth_checkpoint::loadTile(stale,recovered),"changed solver settings reused tile");stale=second;stale.stride=2;require(!rf::depth_checkpoint::loadTile(stale,recovered),"changed stride reused tile");stale=second;stale.x=31;require(!rf::depth_checkpoint::loadTile(stale,recovered),"changed tile origin reused tile");}
  for(const auto&e:std::filesystem::directory_iterator(t.dir))require(e.path().filename().string().find(".partial-")==std::string::npos,"orphan remained after relaunch");
 }
 {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH);auto recovered=patch(0);
  const auto tileFile=t.dir/"tile-0-1.bin";
  {std::fstream file(tileFile,std::ios::in|std::ios::out|std::ios::binary);file.seekp(-1,std::ios::end);file.put('X');}
  require(!rf::depth_checkpoint::loadTile(second,recovered),"finite payload bit flip accepted");
  require(rf::depth_checkpoint::storeTile(second,patch(6)),"tile repair failed");
  {std::ofstream file(tileFile,std::ios::binary|std::ios::app);file.put('X');}
  require(!rf::depth_checkpoint::loadTile(second,recovered),"appended tile data accepted");
  require(rf::depth_checkpoint::storeTile(second,patch(6)),"tile recompute after append failed");}
 {std::string changed(HASH);changed[0]='1';rf::depth_checkpoint::Scope scope(t.dir.string(),changed);auto recovered=patch(0);require(!rf::depth_checkpoint::loadTile(first,recovered),"changed global input SHA reused tile");}
 {rf::depth_checkpoint::Scope scope(t.dir.string(),HASH,32);require(!rf::depth_checkpoint::storeTile(second,patch(6)),"oversized tile escaped cache byte admission");}
 // Real independent writers share only the filesystem; the directory inode
 // flock serializes admission/eviction/publication across processes.
 Temp budget;{std::ofstream originalFile(budget.dir/"original.jpg");originalFile<<"preserve";}
 std::array<pid_t,2> children{};
 for(int i=0;i<2;++i){children[i]=::fork();pool.track(children[i]);require(children[i]>=0,"budget fork failed");if(children[i]==0){rf::depth_checkpoint::Scope scope(budget.dir.string(),HASH,96+64*48*8);::_exit(rf::depth_checkpoint::store(i,map(4+i))?0:93);}}
 for(auto child:children)waitOkay(child);
 std::size_t bytes=0,count=0;for(const auto&e:std::filesystem::directory_iterator(budget.dir))if(e.path().extension()==".bin"){bytes+=e.file_size();++count;}
 require(count==1&&bytes==96+64*48*8,"concurrent writers exceeded byte budget");require(std::filesystem::exists(budget.dir/"original.jpg"),"concurrent eviction removed original");
 // Pause an ACTUAL production load after its shared lease is acquired.
 int existing=0;for(const auto&e:std::filesystem::directory_iterator(budget.dir))if(e.path().extension()==".bin")existing=e.path().filename().string()[4]-'0';
 int readerPipe[2];require(::pipe(readerPipe)==0,"reader barrier pipe failed");pid_t reader=::fork();pool.track(reader);require(reader>=0,"reader fork failed");
 if(reader==0){::close(readerPipe[0]);notifyFd=readerPipe[1];stoppedStage=3;rf::depth_checkpoint::Scope scope(budget.dir.string(),HASH);auto output=map(0);(void)rf::depth_checkpoint::load(existing,output);::_exit(95);}
 ::close(readerPipe[1]);pollfd readerReady{readerPipe[0],POLLIN,0};require(::poll(&readerReady,1,10000)>0,"actual reader did not acquire lease");char readerSignal=0;require(::read(readerPipe[0],&readerSignal,1)==1,"reader lease signal absent");::close(readerPipe[0]);
 int signalPipe[2];require(::pipe(signalPipe)==0,"writer signal pipe failed");pid_t writer=::fork();pool.track(writer);require(writer>=0,"writer fork failed");
 if(writer==0){::close(signalPipe[0]);char c='W';(void)::write(signalPipe[1],&c,1);rf::depth_checkpoint::Scope scope(budget.dir.string(),HASH,96+64*48*8);::_exit(rf::depth_checkpoint::store(2,map(7))?0:94);}
 ::close(signalPipe[1]);char started=0;require(::read(signalPipe[0],&started,1)==1&&started=='W',"writer did not start");::close(signalPipe[0]);
 // Lock acquisition, not a timing delay, is the deterministic assertion.
 int competing=::open(budget.dir.c_str(),O_RDONLY|O_DIRECTORY);require(competing>=0&&::flock(competing,LOCK_EX|LOCK_NB)!=0,"exclusive writer ignored actual reader lease");::close(competing);
 require(::kill(reader,SIGKILL)==0,"reader kill failed");int readerStatus=0;require(::waitpid(reader,&readerStatus,0)==reader&&WIFSIGNALED(readerStatus),"reader did not terminate");waitOkay(writer);
 std::cout<<"SIGKILL at temporary-create and fsync boundaries; exact relaunch; independent-process budget and reader leases passed\n";
 return 0;
 }catch(const std::exception&e){std::cerr<<e.what()<<'\n';return 1;}}
