#pragma once
#include <fstream>
#include <sstream>
#include <iomanip>
#include <filesystem>
#include <cstdlib>
#include <stdexcept>
#include <string>
#include <locale>
namespace ledger_observer {
inline constexpr std::size_t MAX_BYTES=8388608;
inline std::size_t bytes=0,preparedCount=0,membershipCount=0,accumulatorCount=0,supportCount=0;
inline std::ostringstream stream(){std::ostringstream s;s.imbue(std::locale::classic());s<<std::setprecision(17);return s;}
inline void emit(const char* name,const std::string& row){
 const char* directory=std::getenv("RF_LEDGER_OBSERVER_DIR");if(!directory)throw std::runtime_error("Observer destination absent");
 if(row.size()>MAX_BYTES-bytes)throw std::runtime_error("Observer output budget");bytes+=row.size();
 std::ofstream f(std::filesystem::path(directory)/name,std::ios::app);f<<row;if(!f)throw std::runtime_error("Observer write failed");
}
template<class Sample,class Evidence,class Prepared>
inline std::string preparedRow(std::size_t id,const Sample& p,const Evidence& e,const Prepared& s){
 auto o=stream();o<<id<<' '<<+p.camera<<' '<<p.x<<' '<<p.y<<' '<<p.xyz.x<<' '<<p.xyz.y<<' '<<p.xyz.z;
 for(auto c:p.color)o<<' '<<+c;
 o<<' '<<p.confidence<<' '<<+p.supportingViews<<' '<<p.worldNormal.x<<' '<<p.worldNormal.y<<' '<<p.worldNormal.z<<' '<<p.normalConfidence<<' '<<+p.normalSupportingViews;
 o<<' '<<static_cast<int>(e.validity)<<' '<<e.direction.x<<' '<<e.direction.y<<' '<<e.direction.z<<' '<<s.supported<<' '<<s.normal.x<<' '<<s.normal.y<<' '<<s.normal.z<<' '<<s.frontRadius<<' '<<s.bound;
 const auto& u=e.uncertainty;o<<' '<<u.planeResidual<<' '<<u.conditioning<<' '<<u.estimatedPositionSigma<<' '<<u.maximumParallaxRadians<<' '<<u.triangulationSigma.has_value()<<' '<<u.triangulationSigma.value_or(0)<<' '<<u.calibrated<<' '<<u.effectiveSupport<<' '<<e.supportingObservationIds.size();
 for(auto x:e.supportingObservationIds)o<<' '<<x;o<<'\n';return o.str();
}
template<class Sample,class Evidence,class Prepared>
inline void prepared(std::size_t id,const Sample& p,const Evidence& e,const Prepared& s){
 if(preparedCount++>=162||e.supportingObservationIds.size()>1458-supportCount)throw std::runtime_error("Prepared budget");supportCount+=e.supportingObservationIds.size();emit("prepared.tsv",preparedRow(id,p,e,s));
}
inline void membership(std::size_t aid,std::size_t sid,double w){if(membershipCount>=162)throw std::runtime_error("Membership budget");auto o=stream();o<<membershipCount++<<' '<<aid<<' '<<sid<<' '<<w<<'\n';emit("memberships.tsv",o.str());}
template<class Accum,class Vertices>
inline void accumulators(const Accum& aa,const Vertices& vv){
 if(aa.size()>162||aa.size()!=vv.size())throw std::runtime_error("Accumulator budget");
 for(std::size_t i=0;i<aa.size();++i){const auto& a=aa[i];const auto& v=vv[i];auto o=stream();o<<i<<' '<<a.supportedNormal<<' '<<a.weightedPosition.x<<' '<<a.weightedPosition.y<<' '<<a.weightedPosition.z<<' '<<a.weightedNormal.x<<' '<<a.weightedNormal.y<<' '<<a.weightedNormal.z;
 for(auto c:a.weightedColor)o<<' '<<c;
 o<<' '<<a.weight<<' '<<a.maxConfidence<<' '<<+a.viewMask<<' '<<a.fusionRadius<<' '<<a.radiusLevel<<' '<<a.cell.x<<' '<<a.cell.y<<' '<<a.cell.z<<' '<<v.xyz.x<<' '<<v.xyz.y<<' '<<v.xyz.z;
 for(auto c:v.color)o<<' '<<+c;
 o<<' '<<v.confidence<<' '<<+v.supportingViews<<' '<<v.normal.x<<' '<<v.normal.y<<' '<<v.normal.z<<' '<<+a.sourceCount;
 for(std::size_t j=0;j<a.sourceCount;++j)o<<' '<<a.sourceIds[j];o<<'\n';emit("accumulators.tsv",o.str());++accumulatorCount;}
}
}
