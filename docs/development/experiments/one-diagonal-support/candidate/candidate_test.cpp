// External candidate tests only. No production file edits or full-suite PASS claim.
#include "baseline_adapter.hpp"
#include <array>
#include <bit>
#include <cstdint>
#include <iostream>
#include <limits>
#include <string>
#include <vector>
namespace {
using namespace rf;
void need(bool value,const char* message){if(!value)throw Error(message);}
struct Fixture {
 DenseView view;DenseDepthMap map;int x=32,y=32,step=3;
 Fixture(){view.width=64;view.height=64;view.fx=1000;view.fy=1000;view.cx=32;view.cy=32;view.pose.rotation={1,0,0,0,1,0,0,0,1};view.pose.translation={0,0,0};map.width=64;map.height=64;map.depth.assign(64*64,2.f);map.confidence.assign(64*64,1.f);}
 std::size_t id(int dx,int dy)const{return std::size_t(y+dy*step)*map.width+x+dx*step;}
 void gap(int dx,int dy){map.depth[id(dx,dy)]=0;map.confidence[id(dx,dy)]=0;}
 ObservedNormal baseline()const{return baselineDepthNeighborhoodNormal(view,map,x,y,step,.08);}
 ObservedNormal candidate()const{return estimateDepthNeighborhoodNormal(view,map,x,y,step,.08);}
};
template<class T>void integer(std::vector<std::uint8_t>& out,T value){for(std::size_t i=0;i<sizeof(T);++i)out.push_back(static_cast<std::uint8_t>(value>>(8*i)));}
void real(std::vector<std::uint8_t>& out,double value){integer(out,std::bit_cast<std::uint64_t>(value));}
std::vector<std::uint8_t> normalBits(const ObservedNormal& n){
 std::vector<std::uint8_t> out;integer(out,static_cast<std::uint32_t>(n.validity));for(auto v:{n.direction.x,n.direction.y,n.direction.z,n.uncertainty.planeResidual,n.uncertainty.conditioning,n.uncertainty.estimatedPositionSigma,n.uncertainty.maximumParallaxRadians,n.uncertainty.effectiveSupport})real(out,v);integer(out,static_cast<std::uint8_t>(n.uncertainty.calibrated));integer(out,static_cast<std::uint8_t>(n.uncertainty.triangulationSigma.has_value()));if(n.uncertainty.triangulationSigma)real(out,*n.uncertainty.triangulationSigma);integer(out,static_cast<std::uint64_t>(n.supportingObservationIds.size()));for(auto id:n.supportingObservationIds)integer(out,id);return out;
}
void notSupported(const Fixture& f){need(f.candidate().validity!=NormalValidity::Supported,"Forbidden neighborhood accepted");}
}
int main(){try{
 std::vector<std::string> passed;
 auto record=[&](const char* name){passed.emplace_back(name);};
 auto sameNine=[&](const Fixture& f){need(normalBits(f.baseline())==normalBits(f.candidate()),"Complete-nine logical normal bits changed");};
 Fixture flat;sameNine(flat);record("complete_nine_flat_exact_bits");
 Fixture slope;for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx){const double u=slope.x+dx*slope.step,v=slope.y+dy*slope.step;slope.map.depth[slope.id(dx,dy)]=static_cast<float>(2./(1.-.2*(u-slope.view.cx)/slope.view.fx+.1*(v-slope.view.cy)/slope.view.fy));}sameNine(slope);record("complete_nine_slanted_exact_bits");
 Fixture weighted=slope;unsigned wi=0;for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx)weighted.map.confidence[weighted.id(dx,dy)]=.4f+.06f*wi++;sameNine(weighted);record("complete_nine_weighted_exact_bits");
 Fixture unavailable;unavailable.map.confidence[unavailable.id(1,1)]=0;sameNine(unavailable);record("complete_nine_zero_confidence_exact_bits");
 Fixture jump;jump.map.depth[jump.id(0,1)]=3;sameNine(jump);record("complete_nine_observed_jump_exact_bits");
 const std::array<std::pair<int,int>,4> diagonals{{{-1,-1},{1,-1},{-1,1},{1,1}}};
 const std::array<std::pair<int,int>,5> cardinals{{{0,-1},{-1,0},{0,0},{1,0},{0,1}}};
 for(auto [dx,dy]:diagonals){Fixture f;f.gap(dx,dy);need(f.baseline().validity==NormalValidity::Discontinuity,"Baseline one-diagonal policy unexpectedly changed");const auto result=f.candidate();need(result.validity==NormalValidity::Supported&&result.supportingObservationIds.size()==8,"Candidate flat one-diagonal support failed");need(std::find(result.supportingObservationIds.begin(),result.supportingObservationIds.end(),f.id(dx,dy))==result.supportingObservationIds.end(),"Unavailable diagonal fabricated into support");}
 record("each_single_zero_diagonal_eight_real_support_ids");
 for(auto [dx,dy]:cardinals){Fixture f;f.gap(dx,dy);notSupported(f);}record("missing_center_or_cardinal_rejected");
 for(auto [dx,dy]:cardinals){Fixture f;f.gap(-1,-1);f.gap(dx,dy);notSupported(f);}record("missing_cardinal_plus_diagonal_rejected");
 for(std::size_t i=0;i<diagonals.size();++i)for(std::size_t j=i+1;j<diagonals.size();++j){Fixture f;f.gap(diagonals[i].first,diagonals[i].second);f.gap(diagonals[j].first,diagonals[j].second);notSupported(f);}record("every_pair_missing_diagonals_rejected");
 Fixture allGap;for(auto [dx,dy]:diagonals)allGap.gap(dx,dy);notSupported(allGap);record("four_missing_diagonals_rejected");
 for(auto bad:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),-1.f}){Fixture f;f.gap(-1,-1);f.map.depth[f.id(1,1)]=bad;notSupported(f);}record("nonfinite_or_negative_observed_depth_rejected");
 for(auto bad:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),-1.f}){Fixture f;f.map.depth[f.id(-1,-1)]=bad;notSupported(f);}record("nonzero_invalid_diagonal_never_treated_as_missing");
 for(auto bad:{std::numeric_limits<float>::quiet_NaN(),std::numeric_limits<float>::infinity(),-.1f,1.1f}){Fixture f;f.gap(-1,-1);f.map.confidence[f.id(-1,-1)]=bad;notSupported(f);}record("malformed_missing_diagonal_confidence_rejected");
 Fixture observedJump;observedJump.gap(-1,-1);observedJump.map.depth[observedJump.id(1,1)]=2.5f;notSupported(observedJump);record("observed_diagonal_jump_rejected");
 Fixture occlusion;occlusion.gap(-1,-1);occlusion.map.depth[occlusion.id(0,1)]=3;notSupported(occlusion);record("observable_cardinal_occlusion_jump_rejected");
 Fixture zeroConfidence;zeroConfidence.gap(-1,-1);zeroConfidence.map.confidence[zeroConfidence.id(1,1)]=0;notSupported(zeroConfidence);record("zero_observed_confidence_rejected");
 Fixture invalidConfidence;invalidConfidence.gap(-1,-1);invalidConfidence.map.confidence[invalidConfidence.id(1,1)]=std::numeric_limits<float>::quiet_NaN();bool threw=false;try{(void)invalidConfidence.candidate();}catch(const Error& e){threw=std::string(e.what())=="Invalid depth normal confidence";}need(threw,"Malformed observed confidence not rejected by original check");record("nonfinite_observed_confidence_original_error");
 Fixture line;line.view.fx=1e12;sameNine(line);need(line.baseline().validity==NormalValidity::IllConditioned,"Baseline ill-conditioned fixture invalid");line.gap(-1,-1);need(line.candidate().validity==NormalValidity::IllConditioned,"Eight-point ill-conditioned fit accepted");record("ill_conditioned_eight_point_fit_rejected");
 Fixture sheets;for(int dy=-1;dy<=1;++dy)for(int dx=-1;dx<=1;++dx)sheets.map.depth[sheets.id(dx,dy)]=((dx+dy)&1)?2.04f:2.f;sameNine(sheets);need(sheets.baseline().validity!=NormalValidity::Supported,"Baseline observed-layer fixture not rejecting");sheets.gap(-1,-1);notSupported(sheets);record("observed_alternating_thin_depth_layers_rejected");
 // Explicit epistemic limit: no test invents the hidden value of an unavailable corner.
 // Eight identical observed plane points cannot distinguish a plane from a hidden-only edge.
 Fixture hiddenOnly;hiddenOnly.gap(-1,-1);need(hiddenOnly.candidate().validity==NormalValidity::Supported,"Observed-plane control failed");record("unavailable_only_edge_is_not_observable_no_rejection_claim");
 std::cout<<"{\"status\":\"PASS_CANDIDATE_SYNTHETIC_GUARDS_ONLY\",\"productionChanged\":false,\"originalSuitePassClaim\":false,\"tests\":[";for(std::size_t i=0;i<passed.size();++i){if(i)std::cout<<',';std::cout<<'"'<<passed[i]<<'"';}std::cout<<"],\"limitations\":[\"The unchanged original evidence suite intentionally expects a missing diagonal to reject; this candidate changes that policy and does not claim that suite passes.\",\"An edge existing only in the unavailable diagonal is unobservable from the same eight plane samples.\",\"These are synthetic guard tests, not geometry-quality evidence or production approval.\"]}\n";
 }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}}
