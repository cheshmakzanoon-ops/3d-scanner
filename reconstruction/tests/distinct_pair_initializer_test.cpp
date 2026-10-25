// Synthetic regression: distinct coordinate pairs are not a statistical independence claim.
#include "calibrated_initializer_fixtures.hpp"
#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <algorithm>
#include <atomic>
#include <cassert>
#include <cmath>
#include <iostream>
#include <limits>
#include <map>
#include <random>
using namespace rf;
using Key = std::array<double,4>;
Key key(const Match& m) { return {m.a.x,m.a.y,m.b.x,m.b.y}; }
const std::array<CalibratedImageDomain,2> domains{{
    {1200,1200,799.5,599.5,0,0,0,0,0,1600,1200},
    {1200,1200,799.5,599.5,0,0,0,0,0,1600,1200}}};
std::vector<ObservedMatch> pixels(const std::vector<Match>& ms,
    const std::array<CalibratedImageDomain,2>& k=domains) {
    std::vector<ObservedMatch> out;
    for(auto m:ms) out.push_back({{k[0].fx*m.a.x+k[0].cx,k[0].fy*m.a.y+k[0].cy},
        {k[1].fx*m.b.x+k[1].cx,k[1].fy*m.b.y+k[1].cy}});
    return out;
}
std::vector<Match> scene(int count) {
    std::mt19937 gen(137); std::uniform_real_distribution<double> u(-1,1), z(3,8);
    std::normal_distribution<double> noise(0,0);
    const double a=.08; Mat3 r{cos(a),0,sin(a),0,1,0,-sin(a),0,cos(a)};
    std::vector<Match> out;
    for(int i=0;i<count;++i) {
        Vec3 p{u(gen),u(gen),z(gen)}; auto q=multiply(r,p);q.x-=1;
        out.push_back({{p.x/p.z+noise(gen),p.y/p.z+noise(gen)},
            {q.x/q.z+noise(gen),q.y/q.z+noise(gen)},
            {std::uint8_t(i),std::uint8_t(i+1),std::uint8_t(i+2)}});
    }
    return out;
}
template<class F> void invalid(F&& run) {
    bool failed=false;try {run();} catch(const Error&){failed=true;} assert(failed);
}
void equalGeometry(const Result& a,const Result& b,const std::vector<std::size_t>& original) {
    assert(a.rotation==b.rotation);assert(a.translation.x==b.translation.x);
    assert(a.translation.y==b.translation.y&&a.translation.z==b.translation.z);
    assert(a.epipolarInliers==b.epipolarInliers&&a.points.size()==b.points.size());
    assert(a.medianError==b.medianError&&a.medianParallax==b.medianParallax);
    for(std::size_t i=0;i<a.points.size();++i) {
        auto p=a.points[i],q=b.points[i];
        assert(q.matchIndex==original[p.matchIndex]);
        assert(p.xyz.x==q.xyz.x&&p.xyz.y==q.xyz.y&&p.xyz.z==q.xyz.z);
        assert(p.color==q.color&&p.error==q.error&&p.parallax==q.parallax);
    }
}
std::string abi(const std::vector<Match>& ms,std::vector<ObservedMatch> obs,bool calibrated,
    bool invalidColor=false) {
    std::vector<double> rows,pix,dom;
    for(auto m:ms)rows.insert(rows.end(),{m.a.x,m.a.y,m.b.x,m.b.y,
        double(m.color[0]),double(m.color[1]),double(m.color[2])});
    if(invalidColor)rows[rows.size()-1]=std::numeric_limits<double>::quiet_NaN();
    for(auto p:obs)pix.insert(pix.end(),{p.a.x,p.a.y,p.b.x,p.b.y});
    for(auto k:domains)dom.insert(dom.end(),{k.fx,k.fy,k.cx,k.cy,k.k1,k.k2,k.p1,k.p2,k.k3,
        double(k.width),double(k.height)});
    char* json=calibrated?rf_reconstruct_calibrated_json(rows.data(),ms.size(),pix.data(),pix.size(),dom.data(),dom.size(),.00125):
        rf_reconstruct_json(rows.data(),ms.size(),.00125,nullptr,nullptr);
    assert(json);std::string result(json);rf_free(json);return result;
}
int main() {
    Settings s;s.threshold=.00125;
    // Exact original audit: 12 distinct pairs alone, replicated 3x and 15x;
    // all six fixed order/seed combinations must reject, in both API routes.
    for(int repeat:{1,3,15})for(int order=0;order<3;++order)for(unsigned seed:{20261009u,20261010u}) {
        auto base=scene(12),ms=base;for(int i=1;i<repeat;++i)ms.insert(ms.end(),base.begin(),base.end());
        if(order==1)std::reverse(ms.begin(),ms.end());
        if(order==2){std::mt19937 rng(17200);std::shuffle(ms.begin(),ms.end(),rng);}
        s.seed=seed;
        std::cerr<<"Replication regression repeat="<<repeat<<" order="<<order<<" seed="<<seed<<"\n";
        invalid([&]{reconstruct(ms,s);});
        invalid([&]{reconstructCalibrated(ms,pixels(ms),domains,s);});
    }
    s.seed=20261009;
    // Repeated rows interleaved and then reordered: compare against the exact
    // first-seen distinct sequence, preserving original row IDs and first colors.
    for(bool outliers:{false,true})for(int order=0;order<3;++order) {
        auto source=scene(180);std::vector<Match> raw;
        if(outliers){std::mt19937 rng(137);std::uniform_real_distribution<double> u(-.4,.4);
            for(int i=0;i<45;++i)source[i].b={u(rng),u(rng)};}
        for(std::size_t i=0;i<source.size();++i){auto m=source[i];raw.push_back(m);m.color={251,252,253};
            for(int j=0;j<(i>=45&&i<57?15:2);++j)raw.push_back(m);}
        if(order==1)std::reverse(raw.begin(),raw.end());
        if(order==2){std::mt19937 rng(17200);std::shuffle(raw.begin(),raw.end(),rng);}
        std::map<Key,std::size_t> seen;std::vector<Match> distinct;std::vector<std::size_t> ids;
        for(std::size_t i=0;i<raw.size();++i)if(seen.emplace(key(raw[i]),i).second){distinct.push_back(raw[i]);ids.push_back(i);}
        auto legacy=reconstruct(distinct,s), repeated=reconstruct(raw,s);
        assert((outliers?legacy.points.size()>=130:legacy.points.size()==180)&&repeated.inputMatches==raw.size());
        equalGeometry(legacy,repeated,ids);
        auto obs=pixels(raw); // valid sub-pixel metadata differences cannot add geometry
        for(std::size_t i=0;i<raw.size();++i)if(seen.at(key(raw[i]))!=i)obs[i].a.x+=.001;
        auto calibrated=reconstructCalibrated(distinct,pixels(distinct),domains,s);
        auto calibratedRepeated=reconstructCalibrated(raw,obs,domains,s);
        assert(calibratedRepeated.inputMatches==raw.size());
        equalGeometry(calibrated,calibratedRepeated,ids);
        if(order==0) {
            // C ABI serialization must also retain non-dense original row IDs.
            assert(abi(raw,obs,false)==resultJson(repeated));
            assert(abi(raw,obs,true)==resultJson(calibratedRepeated));
        }
    }
    // Signed zero is one coordinate value; neighboring finite values are NOT merged.
    auto zero=scene(40);zero[0]={{0.,0.},{-.2,0.},{1,2,3}};
    auto positive=reconstruct(zero,s);auto repeatedZero=zero;
    auto neg=zero[0];neg.a.x=-0.;neg.a.y=-0.;neg.b.y=-0.;neg.color={9,8,7};repeatedZero.push_back(neg);
    std::vector<std::size_t> ids;for(std::size_t i=0;i<zero.size();++i)ids.push_back(i);
    equalGeometry(positive,reconstruct(repeatedZero,s),ids);
    equalGeometry(reconstructCalibrated(zero,pixels(zero),domains,s),
        reconstructCalibrated(repeatedZero,pixels(repeatedZero),domains,s),ids);
    auto near=zero;auto next=zero[1];next.a.x=std::nextafter(next.a.x,1.);near.push_back(next);
    assert(reconstruct(near,s).epipolarInliers==41);
    assert(reconstructCalibrated(near,pixels(near),domains,s).epipolarInliers==41);
    // Sharing either one endpoint is not an exact duplicate pair.
    next=zero[1];next.b.x=std::nextafter(next.b.x,1.);near.push_back(next);
    assert(reconstruct(near,s).epipolarInliers==42);
    assert(reconstructCalibrated(near,pixels(near),domains,s).epipolarInliers==42);
    // Every raw row must be validated, including rows otherwise discarded.
    for(double bad:{std::numeric_limits<double>::infinity(),std::numeric_limits<double>::quiet_NaN(),101.}) {
        auto ms=repeatedZero;ms.back().a.x=bad;
        invalid([&]{reconstruct(ms,s);});invalid([&]{reconstructCalibrated(ms,pixels(ms),domains,s);});
    }
    auto obs=pixels(repeatedZero);obs.back().a.x=std::numeric_limits<double>::quiet_NaN();
    invalid([&]{reconstructCalibrated(repeatedZero,obs,domains,s);});
    assert(abi(repeatedZero,obs,true).find("INVALID_INPUT")!=std::string::npos);
    assert(abi(repeatedZero,pixels(repeatedZero),true,true).find("INVALID_INPUT")!=std::string::npos);
    assert(abi(repeatedZero,pixels(repeatedZero),false,true)=="{\"error\":\"Non-finite color\"}");
    // Raw count/work admission cannot be bypassed by collapsing duplicates.
    auto huge=scene(40);huge.resize(20001,huge.front());
    invalid([&]{reconstruct(huge,s);});invalid([&]{reconstructCalibrated(huge,pixels(huge),domains,s);});
    huge.resize(20000);auto budget=s;budget.iterations=2501;
    invalid([&]{reconstruct(huge,budget);});invalid([&]{reconstructCalibrated(huge,pixels(huge),domains,budget);});
    budget.iterations=2494;invalid([&]{reconstructCalibrated(huge,pixels(huge),domains,budget);});
    std::atomic_bool stop{true};
    {cancellation::Scope scope(&stop);for(bool calibrated:{false,true}){
        bool cancelled=false;try{if(calibrated)reconstructCalibrated(huge,pixels(huge),domains,s);else reconstruct(huge,s);}
        catch(const cancellation::Cancelled&){cancelled=true;}assert(cancelled);
    }}
    // H model selection, consensus and published point IDs use the same distinct pairs.
    std::vector<Match> plane;for(std::size_t i=0;i<fixtures::anchor.size();i+=4)
        plane.push_back({{fixtures::anchor[i],fixtures::anchor[i+1]},
            {fixtures::anchor[i+2],fixtures::anchor[i+3]},{std::uint8_t(i),2,3}});
    auto k=domains;for(auto& c:k){c.fx=c.fy=480;c.cx=191.5;c.cy=143.5;c.width=384;c.height=288;}
    auto hs=s;hs.threshold=.003125;auto h=reconstructCalibrated(plane,pixels(plane,k),k,hs);
    assert(h.initializerModel==InitializerModel::Homography);
    std::vector<Match> repeatedPlane;ids.clear();
    for(auto m:plane){ids.push_back(repeatedPlane.size());repeatedPlane.push_back(m);m.color={9,9,9};repeatedPlane.push_back(m);}
    equalGeometry(h,reconstructCalibrated(repeatedPlane,pixels(repeatedPlane,k),k,hs),ids);
    auto belowH=plane;belowH.resize(40);auto replicatedH=belowH;
    for(int repeat=1;repeat<4;++repeat)replicatedH.insert(replicatedH.end(),belowH.begin(),belowH.end());
    invalid([&]{reconstructCalibrated(belowH,pixels(belowH,k),k,hs);});
    invalid([&]{reconstructCalibrated(replicatedH,pixels(replicatedH,k),k,hs);});
    auto capped=plane;while(capped.size()<20000)capped.push_back(plane[capped.size()%plane.size()]);
    auto cappedSettings=hs;cappedSettings.iterations=2493;
    auto state=std::make_shared<progress::State>();
    {progress::Scope scope(state);try{reconstructCalibrated(capped,pixels(capped,k),k,cappedSettings);}catch(const Error&) {}}
    auto progress=state->snapshot();
    assert(progress.tile==1&&progress.total==1&&progress.completed==1);
    std::cout<<"Distinct-pair support, metadata, original IDs, signed zero, finite validation, raw budgets and cancellation passed\n";
}
