#include "realityforge/dense.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <iostream>
#include <random>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
void requireCompleteDiagnostics(const rf::DenseResult& result){
    const auto& d=result.photometricDiagnostics;
    require(d.available&&d.evaluatedSamples==result.sampledPixels,"Fresh photometric coverage is incomplete");
    require(d.acceptedSamples==result.photometricPixels,"Photometric accepted count differs from observed output");
    require(d.acceptedSamples+d.lowVariance+d.invalidOrBoundaryBest+d.ncc+d.uniqueness+d.invalidInverseDepth==d.evaluatedSamples,
            "Photometric branch accounting is not exhaustive and exclusive");
    const auto json=rf::denseJson(result);
    require(json.find(R"("coverage":"COMPLETE","unknownSamples":0)")!=std::string::npos,"Fresh diagnostic coverage serialized incorrectly");
    require(json.find(R"("scope":"ORIGINATING_DENSE_EVALUATION")")!=std::string::npos,"Diagnostic scope missing");
    const auto& loss=result.lossDiagnostics;
    require(loss.available&&loss.viewCount==result.maps.size()&&loss.viewCount<=rf::MAX_DENSE_VIEWS,"New dense diagnostic view bound missing");
    std::size_t sampled=0,evaluated=0,accepted=0,lowVariance=0,reciprocal=0,confirmed=0;
    for(std::size_t ref=0;ref<loss.viewCount;++ref){
        const auto& v=loss.views[ref];
        require(v.sampled==v.evaluated,"Fresh per-view photometric coverage is incomplete");
        require(v.photometricAccepted+v.invalidReferencePatch+v.validLowVariance+v.invalidOrBoundaryBest+v.ncc+v.uniqueness+v.invalidInverseDepth==v.evaluated,
                "Per-view photometric accounting is not exhaustive and exclusive");
        std::size_t validReference=0,validLowVariance=0;
        for(std::size_t k=0;k<v.validReferenceMeanHistogram.size();++k){
            validReference+=v.validReferenceMeanHistogram[k];validLowVariance+=v.validLowVarianceMeanHistogram[k];
            require(v.validLowVarianceMeanHistogram[k]<=v.validReferenceMeanHistogram[k],"Low-variance patch histogram exceeds valid reference histogram");
        }
        require(validReference==v.evaluated-v.invalidReferencePatch&&validLowVariance==v.validLowVariance,
                "Nonspatial patch-mean histogram accounting differs from originating branch evidence");
        require(v.invalidWorld+v.noConfirmation+v.reciprocalAccepted==v.reciprocalEvaluated,"Reciprocal sample reasons do not reconcile");
        require(v.invalidSourceProjection+v.sourceDepthUnavailable+v.depthMismatch+v.invalidReverseProjection+v.reverseReprojection+v.normalMismatch+v.confirmedPairs==v.sourcePairs,
                "Reciprocal first-reason pair accounting is not exhaustive and exclusive");
        require(v.sourceOutsideImage+v.sourceGridRejected+v.sourceMissingDepth+v.sourceInterpolationUnsupported==v.sourceDepthUnavailable,
                "Unavailable source depth subreasons do not reconcile");
        require(v.sourcePairs==(v.reciprocalEvaluated-v.invalidWorld)*result.sourceViews[ref].size(),"Not every selected reciprocal source pair was accounted");
        std::size_t histogram=0,pairs=0;
        for(std::size_t k=0;k<v.confirmationHistogram.size();++k){histogram+=v.confirmationHistogram[k];pairs+=k*v.confirmationHistogram[k];}
        require(histogram+v.invalidWorld==v.reciprocalEvaluated&&v.confirmationHistogram[0]==v.noConfirmation&&pairs==v.confirmedPairs,
                "Confirmation histogram does not reconcile");
        require(v.reciprocalAccepted==result.consistentPixelsPerView[ref],"Per-view accepted diagnostic changed geometry evidence");
        sampled+=v.sampled;evaluated+=v.evaluated;accepted+=v.photometricAccepted;lowVariance+=v.invalidReferencePatch+v.validLowVariance;
        reciprocal+=v.reciprocalEvaluated;confirmed+=v.reciprocalAccepted;
    }
    require(sampled==result.sampledPixels&&evaluated==d.evaluatedSamples&&accepted==d.acceptedSamples&&lowVariance==d.lowVariance,
            "New split counters changed legacy aggregate accounting");
    require(reciprocal==result.photometricPixels&&confirmed==result.consistentPixels,"Reciprocal totals do not match originating result");
    require(json.find(R"("lossDiagnostics":{"schema":2,"scope":"ORIGINATING_DENSE_BRANCHES","available":true)")!=std::string::npos,
            "Versioned loss diagnostics missing");
}
void expectError(auto&& callback){bool failed=false;try{callback();}catch(const rf::Error&){failed=true;}require(failed,"invalid geometry accepted");}
// A textured physically planar surface, observed by cameras at different positions.
// Images are actually rendered from world coordinates, not copied and shifted arbitrarily.
std::vector<rf::DenseView> fixture(int count=3,bool texture=true,bool occlude=false){
    constexpr int w=104,h=76;
    std::mt19937 engine(504);std::uniform_int_distribution<int> rgb(0,255);
    std::vector<std::array<int,3>> surface(256*256);
    for(auto& p:surface)p={rgb(engine),rgb(engine),rgb(engine)};
    auto sample=[&](double x,double y,int channel){
        x=std::clamp(x*31.+128.,0.,254.999);y=std::clamp(y*31.+128.,0.,254.999);
        int ix=static_cast<int>(x),iy=static_cast<int>(y);double a=x-ix,b=y-iy;
        auto pix=[&](int dx,int dy){return surface[(iy+dy)*256+ix+dx][channel];};
        return (1-a)*(1-b)*pix(0,0)+a*(1-b)*pix(1,0)+(1-a)*b*pix(0,1)+a*b*pix(1,1);
    };
    std::vector<rf::DenseView> views;
    for(int cam=0;cam<count;++cam){
        rf::DenseView view;view.width=w;view.height=h;
        view.fx=116;view.fy=116;view.cx=(w-1)/2.;view.cy=(h-1)/2.;
        view.pose={rf::identity(),{-cam*.55,0,0}};
        view.rgba.resize(w*h*4);
        for(int y=0;y<h;++y)for(int x=0;x<w;++x){
            std::size_t idx=(y*w+x)*4;
            double wx=(x-view.cx)/view.fx*5.0+cam*.55;
            double wy=(y-view.cy)/view.fy*5.0;
            for(int c=0;c<3;++c)view.rgba[idx+c]=texture?static_cast<std::uint8_t>(std::round(sample(wx,wy,c))):128;
            view.rgba[idx+3]=occlude && cam==2 && x>20&&x<70&&y>15&&y<60?0:255;
        }
        views.push_back(std::move(view));
    }
    return views;
}
}
int main(){
    int pass=0;
    auto test=[&](const char* name,auto&& fn){try{fn();std::cout<<"PASS "<<name<<'\n';++pass;}catch(const std::exception& e){std::cerr<<"FAIL "<<name<<": "<<e.what()<<'\n';throw;}};
    test("plane sweep derives dense geometry from overlapping rendered images",[]{
        const auto images=fixture();
        rf::DenseSettings s;s.nearDepth=3.1;s.farDepth=7.0;s.depthPlanes=64;s.pixelStride=2;
        auto r=rf::reconstructDense(images,s);
        std::cout<<"found "<<r.points.size()<<" candidate="<<r.photometricPixels<<" reciprocal="<<r.consistentPixels<<" confidence="<<r.medianConfidence<<"\n";
        requireCompleteDiagnostics(r);
        const auto& diagnostics=r.photometricDiagnostics;
        require(diagnostics.invalidOrBoundaryBest>0&&diagnostics.ncc>0&&diagnostics.uniqueness>0,
                "Rendered fixture did not exercise reachable minimum/NCC/uniqueness rejection branches");
        // Positive admitted depth planes do not exercise this defensive guard.
        // Zero is observed here, not a claim of invalid-inverse branch coverage.
        require(diagnostics.invalidInverseDepth==0,"Admitted plane unexpectedly produced invalid inverse depth");
        std::cout<<"diagnostic branches minimum="<<diagnostics.invalidOrBoundaryBest<<" ncc="<<diagnostics.ncc
                 <<" uniqueness="<<diagnostics.uniqueness<<" lowVariance="<<diagnostics.lowVariance<<'\n';
        require(r.maps.size()==3,"lost image depth maps");
        require(r.points.size()>100,"insufficient reciprocal depth support");
        std::vector<double> depths;for(auto& p:r.points)depths.push_back(p.xyz.z);
        std::sort(depths.begin(),depths.end());double median=depths[depths.size()/2];
        std::cout<<"median depth="<<median<<" ground truth 5\n";
        require(std::abs(median-5.0)<.35,"depth too far from measured synthetic plane");
        require(rf::denseJson(r).find(R"("triangleCount":0)")!=std::string::npos,"incorrect output type");
    });
    test("uniform image contains no invented surface",[]{
        auto r=rf::reconstructDense(fixture(3,false));
        require(r.points.empty(),"textureless input generated fictitious geometry");
        requireCompleteDiagnostics(r);
        require(r.photometricDiagnostics.lowVariance==r.sampledPixels&&r.sampledPixels>0,
                "Uniform input rejection was not attributed entirely to observed low variance");
        for(std::size_t i=0;i<r.lossDiagnostics.viewCount;++i){const auto& d=r.lossDiagnostics.views[i];
            require(d.invalidReferencePatch==0&&d.validLowVariance==d.evaluated,"Valid uniform patches confused with invalid reference masks");
            require(d.validReferenceMeanHistogram[8]==d.evaluated&&d.validLowVarianceMeanHistogram[8]==d.validLowVariance,
                    "Uniform luminance 128 was assigned to the wrong nonspatial mean bin");}
    });
    test("occluded view does not corrupt nonoccluded geometry",[]{
        auto r=rf::reconstructDense(fixture(3,true,true));
        require(r.points.size()>50,"occlusion unexpectedly erased reliable regions");
        requireCompleteDiagnostics(r);
        require(r.lossDiagnostics.views[2].invalidReferencePatch>0,"Masked reference patches were not diagnosed");
        require(r.lossDiagnostics.views[0].invalidReferencePatch==0&&r.lossDiagnostics.views[1].invalidReferencePatch==0,
                "Reference mask rejection leaked between views");
    });
    test("legacy low variance preserves invalid mask counts while split is explicit",[]{
        auto r=rf::reconstructDense(fixture(3,false,true));requireCompleteDiagnostics(r);
        require(r.points.empty()&&r.photometricDiagnostics.lowVariance==r.sampledPixels,"Diagnostic split changed uniform masked geometry");
        const auto& masked=r.lossDiagnostics.views[2];
        require(masked.invalidReferencePatch>0&&masked.validLowVariance>0,"Masked uniform fixture did not distinguish both legacy branches");
    });
    test("nonspatial valid patch mean bins include dark and bright endpoints",[]{
        auto images=fixture(2,false);
        for(std::size_t camera=0;camera<images.size();++camera)
            for(std::size_t pixel=0;pixel<images[camera].rgba.size();pixel+=4)
                for(std::size_t channel=0;channel<3;++channel)images[camera].rgba[pixel+channel]=camera?255:0;
        const auto result=rf::reconstructDense(images);requireCompleteDiagnostics(result);
        require(result.points.empty(),"Endpoint histogram collection changed untextured geometry");
        require(result.lossDiagnostics.views[0].validReferenceMeanHistogram[0]==result.lossDiagnostics.views[0].evaluated&&
                result.lossDiagnostics.views[1].validReferenceMeanHistogram[15]==result.lossDiagnostics.views[1].evaluated,
                "Patch-mean endpoint bins were not bounded correctly");
    });
    test("unobserved branch diagnostics serialize unknown rather than measured zeros",[]{
        rf::DenseResult absent;
        const auto json=rf::denseJson(absent);
        require(json.find(R"("lossDiagnostics":{"schema":2,"scope":"ORIGINATING_DENSE_BRANCHES","available":false,"viewCount":null,"views":null})")!=std::string::npos,
                "Recovered/absent diagnostics invented measured zeros");
    });
    test("diagnostic JSON rejects impossible view and counter bounds",[]{
        rf::DenseResult invalid;invalid.lossDiagnostics.available=true;invalid.lossDiagnostics.viewCount=rf::MAX_DENSE_VIEWS+1;
        expectError([&]{(void)rf::denseJson(invalid);});
        invalid.lossDiagnostics.viewCount=2;invalid.lossDiagnostics.views[0].sampled=rf::MAX_DENSE_SAMPLES+1;
        expectError([&]{(void)rf::denseJson(invalid);});
        invalid.lossDiagnostics.views[0]={};invalid.lossDiagnostics.views[0].sourcePairs=1;
        expectError([&]{(void)rf::denseJson(invalid);});
        invalid.lossDiagnostics.views[0]={};invalid.lossDiagnostics.views[0].validReferenceMeanHistogram[0]=1;
        expectError([&]{(void)rf::denseJson(invalid);});
    });
    test("diagnostics do not bypass cancellation or allocation admission",[]{
        auto images=fixture(2);std::atomic_bool cancelled=true;bool observed=false;
        {rf::cancellation::Scope scope(&cancelled);
         try{(void)rf::reconstructDense(images);}catch(const rf::cancellation::Cancelled&){observed=true;}}
        require(observed,"Pre-cancelled reconstruction produced diagnostic success");
        rf::DenseSettings settings;settings.workingSetBudgetBytes=1;
        bool rejected=false;try{(void)rf::reconstructDense(images,settings);}catch(const std::invalid_argument&){rejected=true;}
        require(rejected,"Diagnostics bypassed fixed working-set admission");
    });
    test("coincident camera centers explicitly rejected",[]{
        auto images=fixture(2);images[1].pose=images[0].pose;
        expectError([&]{rf::reconstructDense(images);});
    });
    test("invalid or mismatched buffers rejected",[]{
        auto images=fixture(2);images[1].rgba.pop_back();
        expectError([&]{rf::reconstructDense(images);});
        auto regular=fixture(2);
        rf::DenseSettings s;s.depthPlanes=256;
        expectError([&]{rf::reconstructDense(regular,s);});
    });
    test("C ABI invalid input returns explicit JSON error",[]{
        char* p=rf_dense_json(nullptr,104,76,nullptr,3,2,8,32,3);
        require(p && std::string(p).find("error")!=std::string::npos,"no ABI rejection");rf_free(p);
    });
    test("deterministic input produces stable point cloud",[]{
        auto images=fixture(2);
        auto a=rf::reconstructDense(images),b=rf::reconstructDense(images);
        require(rf::denseJson(a)==rf::denseJson(b),"nondeterministic stereo output");
    });
    std::cout<<pass<<" CPU dense MVS cases passed\n";
}
