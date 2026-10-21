#pragma once
// External TEST-ONLY copied-tree instrumentation. Never installed in production.
#include "snapshot.hpp"
#include <array>
#include <sstream>
#include <cstdlib>
#include <numeric>
namespace rf::trace_test {
enum class Reject:unsigned { SameCamera,IncomingNormal,AccumulatorNormal,IncompatibleNormal,NotCloser,IncomingRadius,AccumulatorRadius,IncomingVisibleAverage,ProposedIncomingAnchor,ProposedIncomingVisible,OriginalPair,AnchorSeesSample,SampleSeesAnchor,ProposedPriorAnchor,ProposedPriorVisible,Count };
inline constexpr std::array<const char*,15> rejectNames{"same_camera","incoming_normal","accumulator_normal","incompatible_normal","not_closer","incoming_radius","accumulator_radius","incoming_visible_average","proposed_incoming_anchor","proposed_incoming_visible","original_pair","anchor_sees_sample","sample_sees_anchor","proposed_prior_anchor","proposed_prior_visible"};
enum class NormalEarly:unsigned { Boundary,CenterUnavailable,NeighborNonfinite,NeighborNonpositive,NeighborJump,ConfidenceUnavailable,Count };
inline constexpr std::array<const char*,6> normalNames{"boundary","center_unavailable","neighbor_nonfinite","neighbor_nonpositive","neighbor_jump","confidence_unavailable"};
enum class Outcome:unsigned { Unprocessed,LowConfidence,DuplicatePixel,VertexBudget,Fused,NewAccumulator,Count };
struct SampleTrace {std::uint64_t visits{},passed{};std::uint32_t rejectedMask{};std::uint8_t normalValidity{255};bool normalSupported{};Outcome outcome{};};
struct Counts {std::array<std::uint64_t,15> rejected{};std::array<std::uint64_t,6> normalEarly{};std::array<std::uint64_t,5> normalValidity{};std::uint64_t unitNormalRejected{},planeBoundRejected{},visits{},passed{};std::vector<SampleTrace> samples;std::size_t current{};};
inline thread_local Counts* active=nullptr;
inline void normalEarly(NormalEarly why){if(active)++active->normalEarly.at(static_cast<unsigned>(why));}
inline bool normalRejectIf(NormalEarly why,bool value){if(value)normalEarly(why);return value;}
class Scope {
 Counts counts;bool enabled{};std::string destination;std::span<const VerifiedDepthSample> original;
public:
 Scope(std::span<const DenseView> views,const DenseResult& dense,const SurfaceSettings& settings):original(dense.verifiedSamples){
  capture(views,dense,settings);
  if(const char* path=std::getenv("RF_TRACE_COUNTS");path&&*path){if(active)throw Error("Nested test fusion trace");enabled=true;destination=path;counts.samples.resize(dense.verifiedSamples.size());active=&counts;}
 }
 ~Scope(){if(enabled)active=nullptr;}
 void normalBegin(std::size_t id){if(enabled)counts.current=id;}
 void normalResult(unsigned validity,bool supported){if(!enabled)return;if(validity>=5)throw Error("Unknown normal validity");++counts.normalValidity[validity];auto& row=counts.samples[counts.current];row.normalValidity=static_cast<std::uint8_t>(validity);if(validity==0&&!supported)++counts.unitNormalRejected;}
 void normalBound(bool valid){if(enabled&&!valid)++counts.planeBoundRejected;}
 void normalFinal(bool supported){if(enabled)counts.samples[counts.current].normalSupported=supported;}
 void beginSample(std::size_t id){if(enabled)counts.current=id;}
 void visited(){if(enabled){++counts.visits;++counts.samples[counts.current].visits;}}
 bool rejectIf(Reject reason,bool result){if(enabled&&result){const auto index=static_cast<unsigned>(reason);++counts.rejected[index];counts.samples[counts.current].rejectedMask|=1u<<index;}return result;}
 bool require(Reject reason,bool result){rejectIf(reason,!result);return result;}
 void passed(){if(enabled){++counts.passed;++counts.samples[counts.current].passed;}}
 void outcome(Outcome outcome){if(enabled)counts.samples[counts.current].outcome=outcome;}
 void finish(const SurfaceMesh& mesh){
  if(const char* target=std::getenv("RF_TRACE_CAPTURE_MESH_BITS")){if(!*target||!std::getenv("RF_TRACE_SNAPSHOT"))throw Error("Capture mesh requires snapshot seam");secureWriteFresh(target,meshBits(mesh),SNAPSHOT_MAX);}
  if(!enabled)return;
  if(counts.visits!=counts.passed+std::accumulate(counts.rejected.begin(),counts.rejected.end(),std::uint64_t{}))throw Error("Trace candidate accounting");
  std::array<std::uint64_t,6> outcomes{};std::array<std::uint64_t,15> samplesAnyRejected{};std::uint64_t supported=0;
  for(auto& row:counts.samples){++outcomes[static_cast<unsigned>(row.outcome)];supported+=row.normalSupported;for(unsigned j=0;j<15;++j)samplesAnyRejected[j]+=(row.rejectedMask>>j)&1u;}
  if(outcomes[0]||outcomes[4]!=mesh.fusedObservations||outcomes[4]+outcomes[5]!=mesh.acceptedSamples||std::accumulate(counts.normalValidity.begin(),counts.normalValidity.end(),std::uint64_t{})!=counts.samples.size())throw Error("Trace sample accounting");
  std::ostringstream out;out<<"{\"schema\":1,\"status\":\"UNVERIFIED_UNTIL_ORIGINAL_RAW_MESH_EQUALITY\",\"candidateVisits\":"<<counts.visits<<",\"candidatePassedAllGates\":"<<counts.passed<<",\"candidateFirstReject\":{";
  for(unsigned i=0;i<15;++i){if(i)out<<',';out<<'"'<<rejectNames[i]<<"\":"<<counts.rejected[i];}out<<"},\"samplesWithAnyReject\":{";
  for(unsigned i=0;i<15;++i){if(i)out<<',';out<<'"'<<rejectNames[i]<<"\":"<<samplesAnyRejected[i];}out<<"},\"normalEarlyReturn\":{";
  for(unsigned i=0;i<6;++i){if(i)out<<',';out<<'"'<<normalNames[i]<<"\":"<<counts.normalEarly[i];}out<<"},\"normalValiditySupportedSparseDiscontinuityIllConditionedInvalid\":[";
  for(unsigned i=0;i<5;++i){if(i)out<<',';out<<counts.normalValidity[i];}out<<"],\"unitNormalRejected\":"<<counts.unitNormalRejected<<",\"planeBoundRejected\":"<<counts.planeBoundRejected<<",\"finalNormalSupported\":"<<supported<<",\"outcomeUnprocessedLowConfidenceDuplicatePixelVertexBudgetFusedNewAccumulator\":[";
  for(unsigned i=0;i<6;++i){if(i)out<<',';out<<outcomes[i];}out<<"],\"sampleRows_sourceId_camera_x_y_normalValidity_normalSupported_visits_passed_rejectMask_outcome\":[";
  for(std::size_t i=0;i<counts.samples.size();++i){if(i)out<<',';const auto& row=counts.samples[i];const auto& s=original[i];out<<'['<<i<<','<<unsigned(s.camera)<<','<<s.x<<','<<s.y<<','<<unsigned(row.normalValidity)<<','<<(row.normalSupported?1:0)<<','<<row.visits<<','<<row.passed<<','<<row.rejectedMask<<','<<unsigned(row.outcome)<<']';}out<<"]}\n";
  const auto text=out.str();secureWriteFresh(destination,std::span(reinterpret_cast<const std::uint8_t*>(text.data()),text.size()),12u*1024u*1024u);
 }
};
}
