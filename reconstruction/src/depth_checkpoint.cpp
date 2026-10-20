#include "realityforge/depth_checkpoint.hpp"
#include "realityforge/dense.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/depth_cache_manifest.hpp"
#include <cerrno>
#include <chrono>
#if defined(__unix__) || defined(__ANDROID__)
#include <fcntl.h>
#include <unistd.h>
#include <sys/file.h>
#endif
#include <algorithm>
#include <array>
#include <bit>
#include <cstdint>
#include <cstring>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <functional>
#include <utility>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#ifdef RF_CHECKPOINT_TEST_HOOKS
extern "C" void rf_depth_checkpoint_test_stage(unsigned);
#endif
namespace rf::depth_checkpoint {
namespace {
thread_local const Scope* active = nullptr;
constexpr std::array<char,8> MAGIC{'R','F','D','P','T','0','1','8'};
constexpr std::size_t HEADER = 8+4*6+64;
constexpr std::size_t MAX_PIXELS = 320000;
#if defined(__unix__) || defined(__ANDROID__)
bool lockDirectory(int fd,int mode){
    // Cache contention is bounded optional work, never an uninterruptible wait
    // that can hold a reconstruction alive behind an abandoned/stopped writer.
    for(unsigned attempt=0;attempt<100;++attempt){
        cancellation::checkpoint();
        if(::flock(fd,mode|LOCK_NB)==0)return true;
        if(errno!=EWOULDBLOCK&&errno!=EAGAIN&&errno!=EINTR)return false;
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    return false;
}
#endif

void append32(std::vector<std::uint8_t>& out,std::uint32_t n){
    for(int i=0;i<4;++i)out.push_back(static_cast<std::uint8_t>(n>>(8*i)));
}
std::uint32_t read32(const std::vector<std::uint8_t>& data,std::size_t offset){
    return std::uint32_t(data[offset])|(std::uint32_t(data[offset+1])<<8)|
        (std::uint32_t(data[offset+2])<<16)|(std::uint32_t(data[offset+3])<<24);
}
std::uint32_t crc32(const std::uint8_t* data,std::size_t size){
    std::uint32_t crc=~0u;
    for(std::size_t i=0;i<size;++i){
        crc^=data[i];
        for(int k=0;k<8;++k)crc=(crc>>1)^(0xedb88320u & (0u-(crc&1u)));
    }
    return ~crc;
}
bool publish(const std::filesystem::path&,const std::vector<std::uint8_t>&,std::size_t);
std::filesystem::path pathFor(const Scope& scoped,std::size_t index){
    return std::filesystem::path(scoped.directory())/("ref-"+std::to_string(index)+".bin");
}
}
Scope::Scope(std::string directory,std::string sha256Identity,std::size_t byteBudget):
    previous_(active),directory_(std::move(directory)),identity_(std::move(sha256Identity)),byteBudget_(byteBudget){
    if(byteBudget_==0||byteBudget_>256u*1024u*1024u||directory_.empty()||directory_.size()>1000||identity_.size()!=64||
        !std::all_of(identity_.begin(),identity_.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');}))
        throw std::invalid_argument("Invalid bounded depth checkpoint identity");
    active=this;
}
Scope::~Scope() noexcept {active=previous_;}

bool load(std::size_t index,DenseDepthMap& map){
    if(!active)return false;
    cancellation::checkpoint();
    if(index>=MAX_DENSE_VIEWS||map.width<32||map.height<32||map.width>640||map.height>640)return false;
    const std::size_t count=static_cast<std::size_t>(map.width)*map.height;
    if(count>MAX_PIXELS||HEADER+count*8>active->byteBudget_)return false;
#if defined(__unix__) || defined(__ANDROID__)
    struct ReadLease {int fd{-1};~ReadLease(){if(fd>=0)::close(fd);}} lease;
    lease.fd=::open(active->directory().c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(lease.fd<0||!lockDirectory(lease.fd,LOCK_SH)){if(status)*status=TileLoadStatus::Unavailable;return false;}
#ifdef RF_CHECKPOINT_TEST_HOOKS
    rf_depth_checkpoint_test_stage(3);
#endif
#endif
    const auto file=pathFor(*active,index);
    std::error_code ec;
    if(!std::filesystem::is_regular_file(file,ec)||std::filesystem::is_symlink(file,ec))return false;
    if(std::filesystem::file_size(file,ec)!=HEADER+count*8||ec)return false;
    std::ifstream stream(file,std::ios::binary);
    if(!stream)return false;
    std::vector<std::uint8_t> bytes(HEADER+count*8);
    stream.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
    if(!stream || stream.gcount()!=static_cast<std::streamsize>(bytes.size()))return false;
    if(!std::equal(MAGIC.begin(),MAGIC.end(),bytes.begin()))return false;
    if(read32(bytes,8)!=1||read32(bytes,12)!=static_cast<std::uint32_t>(map.width)||
       read32(bytes,16)!=static_cast<std::uint32_t>(map.height)||
       read32(bytes,20)!=index||read32(bytes,24)!=count*8)return false;
    if(!std::equal(active->identity().begin(),active->identity().end(),bytes.begin()+28))return false;
    if(read32(bytes,28+64)!=crc32(bytes.data()+HEADER,count*8))return false;
    // Decode into temporary buffers, only publish fully validated maps.
    std::vector<float> depth(count),confidence(count);
    for(std::size_t i=0;i<count;++i){
        if((i&8191)==0)cancellation::checkpoint();
        const auto d=std::bit_cast<float>(read32(bytes,HEADER+i*8));
        const auto c=std::bit_cast<float>(read32(bytes,HEADER+i*8+4));
        if(!std::isfinite(d)||!std::isfinite(c)||d<0||d>100000||c<0||c>1||
           (d==0&&c!=0))return false;
        depth[i]=d;confidence[i]=c;
    }
    map.depth=std::move(depth);map.confidence=std::move(confidence);
    return true;
}

bool store(std::size_t index,const DenseDepthMap& map){
    if(!active)return false;
    cancellation::checkpoint();
    if(index>=MAX_DENSE_VIEWS||map.width<32||map.height<32||map.width>640||map.height>640)return false;
    const std::size_t count=static_cast<std::size_t>(map.width)*map.height;
    if(count>MAX_PIXELS||HEADER+count*8>active->byteBudget_||map.depth.size()!=count||map.confidence.size()!=count)return false;
    std::vector<std::uint8_t> bytes;
    bytes.reserve(HEADER+count*8);
    bytes.insert(bytes.end(),MAGIC.begin(),MAGIC.end());
    append32(bytes,1);append32(bytes,static_cast<std::uint32_t>(map.width));
    append32(bytes,static_cast<std::uint32_t>(map.height));
    append32(bytes,static_cast<std::uint32_t>(index));
    append32(bytes,static_cast<std::uint32_t>(count*8));
    bytes.insert(bytes.end(),active->identity().begin(),active->identity().end());
    // CRC slot after the 64-character SHA identity.
    append32(bytes,0);
    for(std::size_t i=0;i<count;++i){
        if((i&8191)==0)cancellation::checkpoint();
        float d=map.depth[i],c=map.confidence[i];
        if(!std::isfinite(d)||!std::isfinite(c)||d<0||d>100000||c<0||c>1||(d==0&&c!=0))return false;
        append32(bytes,std::bit_cast<std::uint32_t>(d));
        append32(bytes,std::bit_cast<std::uint32_t>(c));
    }
    auto crc=crc32(bytes.data()+HEADER,count*8);
    for(int i=0;i<4;++i)bytes[HEADER-4+i]=static_cast<std::uint8_t>(crc>>(8*i));
    return publish(pathFor(*active,index),bytes,active->byteBudget_);
}

namespace {
bool publish(const std::filesystem::path& target,const std::vector<std::uint8_t>& bytes,std::size_t budget){
    std::error_code ec;
    std::filesystem::create_directories(target.parent_path(),ec);
    if(ec)return false;
#if defined(__unix__) || defined(__ANDROID__)
    // Lock the directory inode: independent processes cannot race eviction or
    // publish competing partial names. FDs are closed on every failure path.
    struct FD {int value{-1};~FD(){if(value>=0)::close(value);}} directoryFd;
    directoryFd.value=::open(target.parent_path().c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(directoryFd.value<0||!lockDirectory(directoryFd.value,LOCK_EX))return false;
    if(std::filesystem::is_symlink(target,ec))return false;
    ec.clear();
    // Holding the cross-process directory lock proves no current writer owns
    // these exact-format orphan temporary files. Unknown files are untouched.
    for(std::filesystem::directory_iterator it(target.parent_path(),ec),end;!ec&&it!=end;it.increment(ec)){
        const auto name=it->path().filename().string();
        const auto marker=name.find(".partial-");
        if(marker!=std::string::npos&&depth_cache::cacheName(name.substr(0,marker))&&
           !it->is_symlink(ec)&&it->is_regular_file(ec))std::filesystem::remove(it->path(),ec);
    }
    if(ec)return false;
    if(!depth_cache::evictFor(target.parent_path(),budget,bytes.size(),target))return false;
    std::string temporary=target.string()+".partial-XXXXXX";
    FD output;output.value=::mkstemp(temporary.data());
    if(output.value<0)return false;
#ifdef RF_CHECKPOINT_TEST_HOOKS
    rf_depth_checkpoint_test_stage(1);
#endif
    const auto discard=[&]{std::error_code error;std::filesystem::remove(temporary,error);};
    try{
        std::size_t done=0;
        while(done<bytes.size()){
            cancellation::checkpoint();
            auto countWritten=::write(output.value,bytes.data()+done,bytes.size()-done);
            if(countWritten<0&&errno==EINTR)continue;
            if(countWritten<=0){discard();return false;}
            done+=static_cast<std::size_t>(countWritten);
        }
        if(::fsync(output.value)!=0){discard();return false;}
#ifdef RF_CHECKPOINT_TEST_HOOKS
        rf_depth_checkpoint_test_stage(2);
#endif
        cancellation::checkpoint();
        std::filesystem::rename(temporary,target,ec);
        if(ec){discard();return false;}
        // Success means file AND rename metadata reached supported fsync calls.
        // Actual power-cut behavior still requires storage/device qualification.
        return ::fsync(directoryFd.value)==0;
    }catch(...){discard();throw;}
#else
    // Unsupported durable filesystem primitives: normal computation continues;
    // never advertise a non-durable write as an accepted checkpoint.
    return false;
#endif
}
bool validTile(const TileKey& k){
 return k.reference<8&&k.tile<4096&&k.fullWidth>=32&&k.fullWidth<=640&&k.fullHeight>=32&&k.fullHeight<=640&&
  static_cast<std::size_t>(k.fullWidth)*k.fullHeight<=MAX_PIXELS&&k.width>0&&k.height>0&&k.x<k.fullWidth&&k.y<k.fullHeight&&
  k.width<=k.fullWidth-k.x&&k.height<=k.fullHeight-k.y&&k.stride>=2&&k.stride<=8&&k.solverVersion>0&&k.solverVersion<1000000&&
  !k.solverIdentity.empty()&&k.solverIdentity.size()<=512&&std::all_of(k.solverIdentity.begin(),k.solverIdentity.end(),[](unsigned char c){return c>=32&&c<127;});
}
std::vector<std::uint8_t> tilePrefix(const TileKey& k,const std::string& identity){
 std::vector<std::uint8_t> out{'R','F','T','I','L','E','0','1'};
 for(auto v:{1u,k.reference,k.tile,k.fullWidth,k.fullHeight,k.x,k.y,k.width,k.height,k.stride,k.solverVersion,static_cast<std::uint32_t>(k.solverIdentity.size()),k.width*k.height*8u})append32(out,v);
 out.insert(out.end(),identity.begin(),identity.end());out.insert(out.end(),k.solverIdentity.begin(),k.solverIdentity.end());return out;
}
std::filesystem::path tilePath(const TileKey& k){return std::filesystem::path(active->directory())/("tile-"+std::to_string(k.reference)+"-"+std::to_string(k.tile)+".bin");}
}
bool enabled() noexcept{return active!=nullptr;}
bool loadTile(const TileKey& key,DenseDepthMap& map,TileLoadStatus* status){
 if(status)*status=TileLoadStatus::Rejected;
 if(!active){if(status)*status=TileLoadStatus::Unavailable;return false;}
 if(!validTile(key))return false;
 cancellation::checkpoint();
 auto prefix=tilePrefix(key,active->identity());const auto count=static_cast<std::size_t>(key.width)*key.height,header=prefix.size()+4,total=header+count*8;
 if(total>active->byteBudget_)return false;
#if defined(__unix__) || defined(__ANDROID__)
 struct Lease{int fd{-1};~Lease(){if(fd>=0)::close(fd);}} lease;
 lease.fd=::open(active->directory().c_str(),O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
 if(lease.fd<0||!lockDirectory(lease.fd,LOCK_SH)){if(status)*status=TileLoadStatus::Unavailable;return false;}
#endif
 const auto path=tilePath(key);std::error_code ec;if(!std::filesystem::exists(path,ec)&&!ec){if(status)*status=TileLoadStatus::Missing;return false;}if(std::filesystem::is_symlink(path,ec)||!std::filesystem::is_regular_file(path,ec)||std::filesystem::file_size(path,ec)!=total||ec)return false;
 std::ifstream file(path,std::ios::binary);std::vector<std::uint8_t> bytes(total);file.read(reinterpret_cast<char*>(bytes.data()),static_cast<std::streamsize>(bytes.size()));
 if(!file||file.gcount()!=static_cast<std::streamsize>(bytes.size())||!std::equal(prefix.begin(),prefix.end(),bytes.begin())||read32(bytes,prefix.size())!=crc32(bytes.data()+header,count*8))return false;
 std::vector<float> depth(count),confidence(count);
 for(std::size_t i=0;i<count;++i){if((i&8191)==0)cancellation::checkpoint();float d=std::bit_cast<float>(read32(bytes,header+i*8)),c=std::bit_cast<float>(read32(bytes,header+i*8+4));if(!std::isfinite(d)||!std::isfinite(c)||d<0||d>100000||c<0||c>1||(d==0&&c!=0))return false;depth[i]=d;confidence[i]=c;}
 map.width=static_cast<int>(key.width);map.height=static_cast<int>(key.height);map.depth=std::move(depth);map.confidence=std::move(confidence);if(status)*status=TileLoadStatus::Hit;return true;
}
bool storeTile(const TileKey& key,const DenseDepthMap& map){
 if(!active||!validTile(key)||map.width!=static_cast<int>(key.width)||map.height!=static_cast<int>(key.height))return false;
 cancellation::checkpoint();
 const auto count=static_cast<std::size_t>(key.width)*key.height;if(map.depth.size()!=count||map.confidence.size()!=count)return false;
 auto bytes=tilePrefix(key,active->identity());const auto checksum=bytes.size();append32(bytes,0);const auto header=bytes.size();if(header+count*8>active->byteBudget_)return false;bytes.reserve(header+count*8);
 for(std::size_t i=0;i<count;++i){if((i&8191)==0)cancellation::checkpoint();const float d=map.depth[i],c=map.confidence[i];if(!std::isfinite(d)||!std::isfinite(c)||d<0||d>100000||c<0||c>1||(d==0&&c!=0))return false;append32(bytes,std::bit_cast<std::uint32_t>(d));append32(bytes,std::bit_cast<std::uint32_t>(c));}
 const auto crc=crc32(bytes.data()+header,count*8);for(int i=0;i<4;++i)bytes[checksum+i]=static_cast<std::uint8_t>(crc>>(8*i));
 return publish(tilePath(key),bytes,active->byteBudget_);
}
} // namespace rf::depth_checkpoint
