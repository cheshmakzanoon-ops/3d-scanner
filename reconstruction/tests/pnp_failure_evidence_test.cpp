// Synthetic bounded failure evidence; never coordinates or a geometric acceptance change.
#ifdef NDEBUG
#undef NDEBUG
#endif
#include "realityforge/pnp.hpp"
#include "realityforge/cancellation.hpp"
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <locale>
#include <map>
#include <random>
#include <regex>
#include <string>
#include <vector>
using namespace rf;
std::string failure(const std::vector<Observation>& rows,double threshold=.001) {
    try { registerCamera(rows,threshold); }
    catch(const cancellation::Cancelled&) { throw; }
    catch(const Error& e) { return e.what(); }
    assert(false && "Fixture unexpectedly accepted");return {};
}
std::string api(const std::vector<Observation>& rows,double threshold=.001) {
    std::vector<double> data;
    for(const auto& o:rows)for(double v:{o.world.x,o.world.y,o.world.z,o.image.x,o.image.y})data.push_back(v);
    char* out=rf_register_camera_json(data.data(),rows.size(),threshold);
    assert(out);std::string text(out);rf_free(out);return text;
}
std::map<std::string,std::string> evidence(const std::string& message,const std::string& prefix) {
    assert(message.starts_with(prefix+" [pnp1 "));
    assert(message.size()<=200 && message.back()==']');
    for(unsigned char c:message)assert(c>=32 && c<=126 && c!='"' && c!='\\');
    auto suffix=message.substr(prefix.size());
    const std::regex shape(R"( \[pnp1 raw=([0-9]+) distinct=([0-9]+) required=([0-9]+) best=([0-9]+) tested=([0-9]+) valid=([0-9]+) model=(none|dlt|plane)(?: planarity=([01]\.[0-9]{6}))?(?: final=([0-9]+))?\])");
    std::smatch match;assert(std::regex_match(suffix,match,shape));
    std::map<std::string,std::string> out;
    int i=1;for(const char* key:{"raw","distinct","required","best","tested","valid","model","planarity","final"})out[key]=match[i++].str();
    auto n=[&](const char* key){return std::stoul(out[key]);};
    assert(n("raw")<=5000 && n("distinct")<=n("raw") && n("best")<=n("distinct"));
    assert(n("required")==std::max<std::size_t>(16,static_cast<std::size_t>(std::ceil(.28*n("distinct")))));
    assert(n("tested")<=450 && n("valid")<=n("tested"));
    if(out["model"]=="none")assert(out["planarity"].empty());
    else {assert(!out["planarity"].empty());auto value=std::stod(out["planarity"]);assert(std::isfinite(value)&&value>=0&&value<=1);}
    assert(api(std::vector<Observation>{}).find("pnp1")==std::string::npos); // malformed input unchanged
    return out;
}
struct OddNumbers : std::numpunct<char> {
    char do_decimal_point() const override {return ',';}
    char do_thousands_sep() const override {return '_';}
    std::string do_grouping() const override {return "\3";}
};
int main() {
    const std::string insufficient="Third-camera registration failed: insufficient consistent 2D-3D tracks";
    // Enough raw rows but no distinct support: no hypothesis/model was computed.
    std::vector<Observation> duplicate(5000,{{.123456789,.234567891,4.345678912},{.0123456789,.0234567891}});
    const auto message=failure(duplicate);
    const auto dedup=evidence(message,"PnP needs at least 16 distinct 3D-2D observations");
    assert(dedup.at("raw")=="5000" && dedup.at("distinct")=="1");
    assert(dedup.at("best")=="0" && dedup.at("tested")=="0" && dedup.at("valid")=="0" && dedup.at("model")=="none");
    assert(message.find("123456789")==std::string::npos && message.find("234567891")==std::string::npos);
    assert(api(duplicate)=="{\"error\":\""+message+"\"}");
    // Unique collinear geometry exercises all 450 attempts with no valid model.
    std::vector<Observation> line;
    for(int i=0;i<45;++i)line.push_back({{i*.013,0,4},{.03+i*.001,.02}});
    const auto noModel=failure(line);const auto degenerate=evidence(noModel,insufficient);
    assert(degenerate.at("raw")=="45"&&degenerate.at("distinct")=="45"&&degenerate.at("required")=="16");
    assert(degenerate.at("best")=="0"&&degenerate.at("tested")=="450"&&degenerate.at("valid")=="0"&&degenerate.at("model")=="none");
    assert(api(line)=="{\"error\":\""+noModel+"\"}");
    auto maximumLine=line;
    for(int i=45;i<5000;++i)maximumLine.push_back({{i*.013,0,4},{.03+i*.001,.02}});
    const auto maxEvidence=evidence(failure(maximumLine),insufficient);
    assert(maxEvidence.at("raw")=="5000"&&maxEvidence.at("distinct")=="5000");
    // Worst admitted counter widths, longest model enum, ratio and optional
    // final-consensus field still fit the existing Kotlin take(200) boundary.
    const auto worst=insufficient+" [pnp1 raw=5000 distinct=5000 required=5000 best=5000 tested=450 valid=450 model=plane planarity=1.000000 final=5000]";
    assert(worst.size()<=200);
    // Arbitrary mismatches: counts and any available model remain finite and bounded.
    std::mt19937 rng(137);std::uniform_real_distribution<double> unit(-1,1);
    for(bool plane:{false,true}) {
        std::vector<Observation> mismatches;
        for(int i=0;i<45;++i)mismatches.push_back({{unit(rng),unit(rng),plane?4.:4.+unit(rng)},{unit(rng)*.6,unit(rng)*.6}});
        auto original=mismatches;mismatches.insert(mismatches.end(),original.begin(),original.end());
        const auto error=failure(mismatches);const auto stats=evidence(error,insufficient);
        assert(stats.at("raw")=="90"&&stats.at("distinct")=="45"&&stats.at("tested")=="450");
        assert(api(mismatches)=="{\"error\":\""+error+"\"}");
        const auto old=std::locale();std::locale::global(std::locale(old,new OddNumbers));
        const auto localized=failure(mismatches);std::locale::global(old);assert(localized==error);
        std::cout<<error<<'\n';
        // Fixed known-pose observations with deterministic large noise provide
        // a computed but insufficient best model in both native model branches.
        std::vector<Observation> noisy;
        for(int i=0;i<45;++i){Vec3 p{(i%9-4)*.13,(i/9-2)*.11,plane?4.:3.5+(i%7)*.17};
            noisy.push_back({p,{(p.x+.21)/(p.z+.3)+.01*std::sin(i*1.7),
                               (p.y-.12)/(p.z+.3)+.01*std::cos(i*1.3)}});}
        const auto supportedFailure=failure(noisy);
        const auto supported=evidence(supportedFailure,insufficient);
        assert(std::stoul(supported.at("best"))>0 && supported.at("model")== (plane?"plane":"dlt"));
        assert(api(noisy)=="{\"error\":\""+supportedFailure+"\"}");
        const auto saved=std::locale();std::locale::global(std::locale(saved,new OddNumbers));
        const auto fixedDecimal=failure(noisy);std::locale::global(saved);assert(fixedDecimal==supportedFailure);
        std::cout<<supportedFailure<<'\n';
    }
    // Ordinary successful pose bytes match the unchanged public C API; no error diagnostics.
    std::vector<Observation> good;
    for(int i=0;i<45;++i){Vec3 p{(i%9-4)*.13,(i/9-2)*.11,3.5+(i%7)*.17};good.push_back({p,{(p.x+.21)/(p.z+.3),(p.y-.12)/(p.z+.3)}});}
    auto pose=registerCamera(good,.001);assert(pose.inliers.size()==45);
    const auto success=api(good);assert(success==cameraJson(pose)&&success.find("pnp1")==std::string::npos);
    assert(std::abs(pose.translation.x-.21)<1e-5&&std::abs(pose.translation.y+.12)<1e-5&&std::abs(pose.translation.z-.3)<1e-5);
    // Validation/cancellation retain their prior classification and never masquerade as solver failure.
    auto invalid=duplicate;invalid.back().image.x=std::numeric_limits<double>::quiet_NaN();
    assert(failure(invalid)=="Non-finite or unreasonable PnP coordinate");
    assert(api(invalid)=="{\"error\":\"Non-finite or unreasonable PnP coordinate\"}");
    assert(failure(good,0)=="Invalid PnP input size or threshold");
    std::atomic_bool stop{true};
    {cancellation::Scope scope(&stop);bool cancelled=false;try{registerCamera(good);}catch(const cancellation::Cancelled&){cancelled=true;}assert(cancelled);
        assert(api(good)=="{\"error\":\"Reconstruction cancelled\"}");}
    std::cout<<"PnP bounded failure evidence, unchanged success/input/cancellation and C ABI retention passed\n";
}
