// EXTERNAL READ-ONLY DIAGNOSTIC. No alternate fit, no fuser, no new geometry.
#include "snapshot.hpp"
#include "realityforge/surface_evidence.hpp"
#include <array>
#include <charconv>
#include <iomanip>
#include <iostream>
#include <locale>
#include <optional>
#include <sstream>
namespace {
std::string digestHex(const rf::checkpoint::Digest& digest){
 static constexpr char digits[]="0123456789abcdef";std::string out;out.reserve(64);
 for(auto x:digest){out.push_back(digits[x>>4]);out.push_back(digits[x&15]);}return out;
}
const char* name(rf::NormalValidity validity){
 switch(validity){case rf::NormalValidity::Supported:return "Supported";case rf::NormalValidity::SparseSupport:return "SparseSupport";case rf::NormalValidity::Discontinuity:return "Discontinuity";case rf::NormalValidity::IllConditioned:return "IllConditioned";case rf::NormalValidity::Invalid:return "Invalid";}
 throw rf::Error("Unexpected original normal validity");
}
}
int main(int argc,char** argv){try{
 using namespace rf;using namespace rf::trace_test;
 require(argc==5,"usage: inspect snapshot expected-depth-identity expected-pixel-stride fresh-output-file");
 const std::filesystem::path input=argv[1],output=argv[4];const auto before=secureRead(input,SNAPSHOT_MAX);
 auto snapshot=decodeSnapshot(before,argv[2]);const std::string strideArg=argv[3];int stride=0;
 const auto parsed=std::from_chars(strideArg.data(),strideArg.data()+strideArg.size(),stride);
 require(parsed.ec==std::errc{}&&parsed.ptr==strideArg.data()+strideArg.size()&&stride>=2&&stride<=8&&stride==snapshot.settings.pixelStride,"Independently captured stride mismatch");
 require(before==encodeSnapshot(snapshot.views,snapshot.dense,snapshot.settings,snapshot.depthIdentity),"Initial snapshot bit-roundtrip mismatch");
 Writer report;std::ostringstream header;header.imbue(std::locale::classic());header<<std::setprecision(17);
 header<<"{\"schema\":1,\"status\":\"MEASURE_ONLY_UNCHANGED_ORIGINAL_NORMAL\",\"snapshotSha256\":\""<<digestHex(hash(before))<<"\",\"depthIdentity\":\""<<snapshot.depthIdentity<<"\",\"pixelStride\":"<<stride<<",\"maximumRelativeDepthJump\":"<<snapshot.settings.maximumRelativeDepthJump<<",\"bitOrder\":\"dy=-1..1 outer; dx=-1..1 inner\",\"centerMask\":16,\"cardinalAndCenterMask\":186,\"diagonalMask\":325,\"allNineMask\":511,\"sampleCount\":"<<snapshot.dense.verifiedSamples.size()<<",\"samples\":[";report.text(header.str());
 std::array<std::size_t,5> validityCounts{};
 for(std::size_t sourceId=0;sourceId<snapshot.dense.verifiedSamples.size();++sourceId){
  const auto& sample=snapshot.dense.verifiedSamples[sourceId];const auto& view=snapshot.views[sample.camera];const auto& map=snapshot.dense.maps[sample.camera];
  // Authoritative baseline call: original complete nine-neighbor gate and normal fit unchanged.
  const auto evidence=estimateDepthNeighborhoodNormal(view,map,sample.x,sample.y,snapshot.settings.pixelStride,snapshot.settings.maximumRelativeDepthJump);
  const auto validity=static_cast<unsigned>(evidence.validity);require(validity<validityCounts.size(),"Invalid original normal enum");++validityCounts[validity];
  unsigned inBounds=0,positiveDepth=0,positiveConfidence=0,withinJump=0;
  std::array<std::optional<double>,9> relativeJump{};
  const auto centerIndex=std::size_t(sample.y)*map.width+sample.x;const double center=map.depth[centerIndex];
  const bool centerAvailable=std::isfinite(center)&&center>0;
  unsigned bit=0;
  for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx,++bit){
   const int x=int(sample.x)+dx*stride,y=int(sample.y)+dy*stride;
   if(x<0||y<0||x>=map.width||y>=map.height)continue;
   const unsigned mask=1u<<bit;inBounds|=mask;const auto index=std::size_t(y)*map.width+x;
   const double depth=map.depth[index],confidence=map.confidence[index];
   const bool depthAvailable=std::isfinite(depth)&&depth>0;
   if(depthAvailable)positiveDepth|=mask;
   if(std::isfinite(confidence)&&confidence>0)positiveConfidence|=mask;
   if(centerAvailable&&depthAvailable){
    const double jump=std::abs(depth-center)/center;
    // Never render nonfinite JSON, fill missing values, or treat unavailable depth as support.
    if(std::isfinite(jump)){relativeJump[bit]=jump;if(jump<=snapshot.settings.maximumRelativeDepthJump)withinJump|=mask;}
   }
  }
  std::ostringstream row;row.imbue(std::locale::classic());row<<std::setprecision(17);if(sourceId)row<<',';
  row<<"{\"sourceSampleId\":"<<sourceId<<",\"camera\":"<<unsigned(sample.camera)<<",\"x\":"<<sample.x<<",\"y\":"<<sample.y<<",\"baselineNormalValidity\":"<<validity<<",\"baselineNormalValidityName\":\""<<name(evidence.validity)<<"\",\"inBoundsMask\":"<<inBounds<<",\"finitePositiveDepthMask\":"<<positiveDepth<<",\"positiveFiniteConfidenceMask\":"<<positiveConfidence<<",\"relativeJumpWithinOriginalThresholdMask\":"<<withinJump<<",\"relativeJump\":[";
  for(std::size_t i=0;i<relativeJump.size();++i){if(i)row<<',';if(relativeJump[i])row<<*relativeJump[i];else row<<"null";}row<<"]}";report.text(row.str());
 }
 std::ostringstream tail;tail<<"],\"baselineValidityCounts\":[";for(std::size_t i=0;i<validityCounts.size();++i){if(i)tail<<',';tail<<validityCounts[i];}tail<<"],\"baselineValidityCountOrder\":[\"Supported\",\"SparseSupport\",\"Discontinuity\",\"IllConditioned\",\"Invalid\"],\"limitations\":[\"Masks are observations of the original nine camera-grid locations, not an alternate normal-support policy.\",\"Stored sample worldNormal/normalSupportingViews fields are not used as recomputed normal evidence.\",\"No alternative fit, fuser, mesh, threshold or input geometry change.\"]}\n";report.text(tail.str());
 require(before==secureRead(input,SNAPSHOT_MAX),"Input file changed during inspection");
 require(before==encodeSnapshot(snapshot.views,snapshot.dense,snapshot.settings,snapshot.depthIdentity),"Input values changed during inspection");
 secureWriteFresh(output,report.bytes,SNAPSHOT_MAX);
 require(before==secureRead(input,SNAPSHOT_MAX),"Input file changed during output write");
 std::cout<<"MEASURE_ONLY_ORIGINAL_NORMAL_NEIGHBORHOOD_READY\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
