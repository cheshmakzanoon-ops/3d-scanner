// External candidate scope/complete-nine regression on the exact admitted input.
#include "snapshot.hpp"
#include "baseline_adapter.hpp"
#include <iostream>
#include <sstream>
namespace {
std::vector<std::uint8_t> bits(const rf::ObservedNormal& n){
 using namespace rf::trace_test;Writer w;w.integer(static_cast<std::uint32_t>(n.validity));w.vector(n.direction);
 const auto& u=n.uncertainty;w.real(u.planeResidual);w.real(u.conditioning);w.real(u.estimatedPositionSigma);w.real(u.maximumParallaxRadians);
 w.integer(static_cast<std::uint8_t>(u.triangulationSigma.has_value()));if(u.triangulationSigma)w.real(*u.triangulationSigma);
 w.integer(static_cast<std::uint8_t>(u.calibrated));w.real(u.effectiveSupport);w.count(n.supportingObservationIds.size());for(auto id:n.supportingObservationIds)w.integer(id);return w.bytes;
}
}
int main(int argc,char** argv){try{
 using namespace rf;using namespace rf::trace_test;require(argc==4,"usage support snapshot expected-depth-id fresh-output");
 const auto before=secureRead(argv[1]);auto s=decodeSnapshot(before,argv[2]);require(s.settings.pixelStride==3,"fixed actual stride");Writer output;output.text("{\"schema\":1,\"rows_source_camera_x_y_originalValidity_candidateValidity_eligible_completeNine\":[");
 std::size_t complete=0,eligibleCount=0,additional=0;std::array<std::size_t,5> originalCounts{},candidateCounts{};
 for(std::size_t i=0;i<s.dense.verifiedSamples.size();++i){
  const auto& p=s.dense.verifiedSamples[i];const auto& view=s.views[p.camera];const auto& map=s.dense.maps[p.camera];const auto& settings=s.settings;
  const auto original=baselineDepthNeighborhoodNormal(view,map,p.x,p.y,settings.pixelStride,settings.maximumRelativeDepthJump);
  const auto candidate=estimateDepthNeighborhoodNormal(view,map,p.x,p.y,settings.pixelStride,settings.maximumRelativeDepthJump);
  const auto ov=static_cast<unsigned>(original.validity),cv=static_cast<unsigned>(candidate.validity);require(ov<5&&cv<5,"normal enum");++originalCounts[ov];++candidateCounts[cv];
  unsigned depthMask=0;bool observedPass=true;const double center=map.depth[std::size_t(p.y)*map.width+p.x];unsigned bit=0;
  for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx,++bit){
   const int x=int(p.x)+dx*settings.pixelStride,y=int(p.y)+dy*settings.pixelStride;
   if(x<0||y<0||x>=map.width||y>=map.height){observedPass=false;continue;}
   const auto index=std::size_t(y)*map.width+x;const double depth=map.depth[index],confidence=map.confidence[index];
   if(std::isfinite(depth)&&depth>0){depthMask|=1u<<bit;if(!std::isfinite(confidence)||confidence<=0||confidence>1||center<=0||std::abs(depth-center)/center>settings.maximumRelativeDepthJump)observedPass=false;}
   else if(depth!=0||!std::isfinite(confidence)||confidence<0||confidence>1)observedPass=false;
  }
  const bool full=depthMask==511;const bool eligible=(depthMask&186)==186&&std::popcount(depthMask)==8&&observedPass;
  if(full){++complete;require(bits(original)==bits(candidate),"complete-nine actual evidence bit mismatch");}
  if(eligible)++eligibleCount;
  if(candidate.validity==NormalValidity::Supported&&original.validity!=NormalValidity::Supported){require(eligible,"new support outside exact one-diagonal scope");++additional;}
  if(original.validity==NormalValidity::Supported)require(bits(original)==bits(candidate),"original supported evidence changed");
  std::ostringstream row;if(i)row<<',';row<<'['<<i<<','<<unsigned(p.camera)<<','<<p.x<<','<<p.y<<','<<ov<<','<<cv<<','<<eligible<<','<<full<<']';output.text(row.str());
 }
 std::ostringstream tail;tail<<"],\"completeNineBitIdentical\":"<<complete<<",\"eligibleOneDiagonal\":"<<eligibleCount<<",\"additionalSupportedNormals\":"<<additional<<",\"originalValidityCounts\":[";
 for(unsigned i=0;i<5;++i){if(i)tail<<',';tail<<originalCounts[i];}tail<<"],\"candidateValidityCounts\":[";for(unsigned i=0;i<5;++i){if(i)tail<<',';tail<<candidateCounts[i];}tail<<"]}\n";output.text(tail.str());
 require(before==secureRead(argv[1])&&before==encodeSnapshot(s.views,s.dense,s.settings,s.depthIdentity),"snapshot changed");secureWriteFresh(argv[3],output.bytes,SNAPSHOT_MAX);
 std::cout<<"EXPERIMENT_SCOPE_REGRESSION_READY_NOT_QUALITY_ACCEPTED\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
