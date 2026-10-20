#pragma once
#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <filesystem>
#include <span>
#include <string>
#include <vector>
#include <stdexcept>
namespace rf::depth_cache {
inline constexpr unsigned MANIFEST_VERSION=1;
struct Entry { std::uint32_t reference{},tile{},width{},height{};std::uint64_t bytes{};std::string identity; };
inline bool validIdentity(const std::string& s){return s.size()==64&&std::all_of(s.begin(),s.end(),[](char c){return (c>='0'&&c<='9')||(c>='a'&&c<='f');});}
inline bool validate(std::span<const Entry> entries,const std::string& expected,std::uint64_t budget){
 if(!validIdentity(expected)||entries.size()>1024||budget==0)return false;
 std::uint64_t bytes=0;
 for(std::size_t i=0;i<entries.size();++i){const auto& e=entries[i];
  if(e.identity!=expected||e.reference>=8||!e.width||!e.height||e.width>640||e.height>640||static_cast<std::uint64_t>(e.width)*e.height>320000||e.bytes>budget-bytes)return false;
  if(e.bytes!=96+static_cast<std::uint64_t>(e.width)*e.height*8)return false;
  bytes+=e.bytes;for(std::size_t j=0;j<i;++j)if(entries[j].reference==e.reference&&entries[j].tile==e.tile)return false;
 }return true;
}
// Only our exact reference cache names are eligible. Never traverse directories,
// follow symlinks, or touch originals/models/unknown files in a shared directory.
inline bool referenceName(const std::filesystem::path& p){const auto s=p.filename().string();return s.size()==9&&s.starts_with("ref-")&&s[4]>='0'&&s[4]<='7'&&s.substr(5)==".bin";}
inline bool tileName(const std::filesystem::path& p){
 const auto s=p.filename().string();
 if(s.size()<12||!s.starts_with("tile-")||s[5]<'0'||s[5]>'7'||s[6]!='-'||!s.ends_with(".bin"))return false;
 const auto digits=s.substr(7,s.size()-11);if(digits.empty()||digits.size()>4||(digits.size()>1&&digits[0]=='0'))return false;
 unsigned value=0;for(char c:digits){if(c<'0'||c>'9')return false;value=value*10+unsigned(c-'0');}return value<4096;
}
inline bool cacheName(const std::filesystem::path& p){return referenceName(p)||tileName(p);}
inline bool evictFor(const std::filesystem::path& directory,std::uint64_t budget,std::uint64_t incoming,const std::filesystem::path& replacing){
 if(incoming>budget)return false;
 struct Item{std::filesystem::path p;std::uint64_t bytes;std::filesystem::file_time_type time;};std::vector<Item> items;std::uint64_t total=0;std::error_code ec;
 for(std::filesystem::directory_iterator it(directory,ec),end;!ec&&it!=end;it.increment(ec)){
  const auto& e=*it;if(!cacheName(e.path())||e.path()==replacing||e.is_symlink(ec)||!e.is_regular_file(ec))continue;
  auto bytes=e.file_size(ec);if(ec||total>std::numeric_limits<std::uint64_t>::max()-bytes)return false;
  auto time=e.last_write_time(ec);if(ec)return false;total+=bytes;items.push_back({e.path(),bytes,time});
 }
 if(ec)return false;
 std::sort(items.begin(),items.end(),[](const Item&a,const Item&b){return a.time==b.time?a.p<b.p:a.time<b.time;});
 for(const auto& e:items){if(total<=budget-incoming)break;if(!std::filesystem::remove(e.p,ec)||ec)return false;total-=e.bytes;}
 return total<=budget-incoming;
}
}
