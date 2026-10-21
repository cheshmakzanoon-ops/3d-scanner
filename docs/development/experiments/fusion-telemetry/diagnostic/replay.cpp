// External replay process: compile twice, link exactly one native engine per executable.
#include "snapshot.hpp"
#include <iostream>
#include <charconv>
int main(int argc,char** argv){try{
 using namespace rf::trace_test;require(argc==5,"usage: replay snapshot expected-depth-identity expected-pixel-stride fresh-output-directory");
 require(std::getenv("RF_TRACE_SNAPSHOT")==nullptr,"replay must not capture recursively");
 const std::filesystem::path input=argv[1],output=argv[4];const auto before=secureRead(input);auto snapshot=decodeSnapshot(before,argv[2]);
 int expectedStride=0;const std::string stride=argv[3];const auto parsed=std::from_chars(stride.data(),stride.data()+stride.size(),expectedStride);require(parsed.ec==std::errc{}&&parsed.ptr==stride.data()+stride.size()&&expectedStride>=2&&expectedStride<=8&&snapshot.settings.pixelStride==expectedStride,"independently captured stride mismatch");freshDirectory(output);
 // No depth solver, no regenerated images, no inferred camera calibration. Public original fuser.
 const auto mesh=rf::fuseDepthSurfaces(snapshot.views,snapshot.dense,snapshot.settings);
 secureWriteFresh(output/"mesh.bits",meshBits(mesh),SNAPSHOT_MAX);
 rf::writeSurfacePly(mesh,(output/"mesh.ply").string());(void)secureRead(output/"mesh.ply");
 const auto json=rf::surfaceJson(mesh);secureWriteFresh(output/"mesh.json",std::span(reinterpret_cast<const std::uint8_t*>(json.data()),json.size()),SNAPSHOT_MAX);
 require(before==secureRead(input),"snapshot changed during replay");
 require(before==encodeSnapshot(snapshot.views,snapshot.dense,snapshot.settings,snapshot.depthIdentity),"fuser mutated input snapshot");
 std::cout<<"REPLAY_OUTPUT_READY_NOT_TELEMETRY_VALIDATED\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
