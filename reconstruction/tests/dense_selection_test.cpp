#include "realityforge/dense_selection.hpp"
#include "realityforge/photometric.hpp"
#include "realityforge/texture.hpp"
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
void rejects(auto fn){bool rejected=false;try{fn();}catch(const rf::Error&){rejected=true;}require(rejected,"Input should be rejected");}
std::vector<rf::DenseView> views(std::size_t count){
    std::vector<rf::DenseView> result(count);
    for(std::size_t i=0;i<count;++i){
        auto& v=result[i];v.width=96;v.height=64;v.fx=v.fy=100;v.cx=47.5;v.cy=31.5;
        v.pose={rf::identity(),{-.55*i,0,0}};
        v.rgba.assign(96*64*4,128);
        for(std::size_t k=3;k<v.rgba.size();k+=4)v.rgba[k]=255;
    }
    return result;
}
std::vector<double> packedCameras(const std::vector<rf::DenseView>& input){
    std::vector<double> rows(input.size()*16);
    for(std::size_t i=0;i<input.size();++i){const auto& v=input[i];
        std::copy(v.pose.rotation.begin(),v.pose.rotation.end(),rows.begin()+static_cast<std::ptrdiff_t>(i*16));
        rows[16*i+9]=v.pose.translation.x;rows[16*i+10]=v.pose.translation.y;rows[16*i+11]=v.pose.translation.z;
        rows[16*i+12]=v.fx;rows[16*i+13]=v.fy;rows[16*i+14]=v.cx;rows[16*i+15]=v.cy;
    }
    return rows;
}
}
int main(){
    int count=0;
    auto test=[&](const char* name,auto fn){fn();++count;std::cout<<"PASS "<<name<<'\n';};
    test("approved adjacency is copied and drives the real solver, including empty rows",[]{
        const auto v=views(3);auto rows=packedCameras(v);
        std::vector<std::vector<std::size_t>> plan{{2},{},{0}};const auto expected=plan;
        const auto baseline=rf::detail::denseSourcePlan(v,3,8);
        {
            rf::detail::DenseSourcePlanScope scope(plan,rows);
            plan[0]={1};rows[9]=1000;
            require(rf::detail::denseSourcePlan(v,3,8)==expected,"Caller mutation escaped approved snapshot");
            const auto result=rf::reconstructDense(v);
            require(result.sourceViews==expected&&result.points.empty(),"Actual solver ignored approved source edges");
            auto coarse=v;
            for(auto& image:coarse){image.width=48;image.height=32;image.fx*=.5;image.fy*=.5;
                image.cx=(image.cx+.5)*.5-.5;image.cy=(image.cy+.5)*.5-.5;image.rgba.resize(48*32*4);}
            require(rf::detail::denseSourcePlan(coarse,3,8)==expected,"Coarse pixels incorrectly invalidated same camera plan");
            std::vector<std::vector<std::size_t>> other{{1},{0},{1}};
            {rf::detail::DenseSourcePlanScope nested(other,packedCameras(v));
                require(rf::detail::denseSourcePlan(v,3,8)==other,"Nested scope ignored");}
            require(rf::detail::denseSourcePlan(v,3,8)==expected,"Nested scope did not restore outer snapshot");
            auto changed=v;std::swap(changed[0],changed[1]);
            rejects([&]{rf::detail::denseSourcePlan(changed,3,8);});
            changed=v;changed[1].pose.translation.y=.001;
            rejects([&]{rf::detail::denseSourcePlan(changed,3,8);});
        }
        require(rf::detail::denseSourcePlan(v,3,8)==baseline,"Scope leaked into next operation");
    });
    test("invalid approved adjacency rejects before changing the current scope",[]{
        const auto v=views(3);const auto rows=packedCameras(v);
        std::vector<std::vector<std::size_t>> good{{1},{0},{1}};
        rf::detail::DenseSourcePlanScope scope(good,rows);
        for(const auto& bad:std::vector<std::vector<std::vector<std::size_t>>>{{{0},{0},{1}},{{1,1},{0},{1}},{{3},{0},{1}},{{1,2,1,2},{0},{1}},{{1},{0}}})
            rejects([&]{rf::detail::DenseSourcePlanScope invalid(bad,rows);});
        auto badRows=rows;badRows[0]=2;
        rejects([&]{rf::detail::DenseSourcePlanScope invalid(good,badRows);});
        require(rf::detail::denseSourcePlan(v,3,8)==good,"Failed construction changed active scope");
        auto coincident=v;coincident[1].pose=coincident[0].pose;
        {rf::detail::DenseSourcePlanScope invalid(good,packedCameras(coincident));
            rejects([&]{rf::detail::denseSourcePlan(coincident,3,8);});}
    });
    test("eight-view source lists are bounded, distinct and deterministic",[]{
        const auto v=views(8);const auto a=rf::detail::denseSourcePlan(v,3,8);
        require(a==rf::detail::denseSourcePlan(v,3,8),"Source plan is not deterministic");
        for(std::size_t i=0;i<a.size();++i){
            require(a[i].size()==3,"Missing source limit");
            for(auto s:a[i])require(s<8&&s!=i,"Self/invalid source");
            require(a[i][0]!=a[i][1]&&a[i][0]!=a[i][2]&&a[i][1]!=a[i][2],"Duplicate source");
        }
        require(a[7][0]>=4,"Distant source chosen over overlapping views");
    });
    test("a disjoint eighth camera has no supported source frusta",[]{
        auto v=views(8);v[7].pose.translation.x=-1000;
        require(rf::detail::denseSourcePlan(v,3,8)[7].empty(),"Disjoint camera should be unsupported");
    });
    test("opposing cameras cannot be mistaken for nearby source views",[]{
        auto v=views(8);v[7].pose.rotation={-1,0,0,0,1,0,0,0,-1};
        require(rf::detail::denseSourcePlan(v,3,8)[7].empty(),"Opposing direction accepted");
    });
    test("small batches retain all original pair candidates",[]{
        for(std::size_t n=2;n<=4;++n){
            const auto p=rf::detail::denseSourcePlan(views(n),3,8);
            for(std::size_t i=0;i<n;++i){std::vector<std::size_t> expected;
                for(std::size_t j=0;j<n;++j)if(j!=i)expected.push_back(j);
                require(p[i]==expected,"Original stereo candidates changed");
            }
        }
    });
    test("oversized and nonfinite source plans rejected",[]{
        rejects([]{rf::detail::denseSourcePlan(views(9),3,8);});
        rejects([]{rf::detail::denseSourcePlan(views(8),3,std::numeric_limits<double>::infinity());});
    });
    test("eight uniform inputs cannot invent depth and only cache four luminance planes",[]{
        auto r=rf::reconstructDense(views(8));
        require(r.maps.size()==8&&r.sourceViews.size()==8,"Lost a reference map");
        require(r.points.empty()&&r.verifiedSamples.empty(),"Textureless input created geometry");
        require(r.peakLuminanceBytes<=4*96*64*sizeof(float),"Too many active luminance images");
    });
    test("eight rendered buffers fuse and texture without losing source seven",[]{
        auto v=views(8);
        for(std::size_t cam=0;cam<v.size();++cam)for(int y=0;y<v[cam].height;++y)for(int x=0;x<v[cam].width;++x){
            auto& image=v[cam];
            const double wx=(x-image.cx)*5/image.fx+cam*.55,wy=(y-image.cy)*5/image.fy;
            const auto c=static_cast<std::uint8_t>(std::clamp(120+45*std::sin(wx*10+wy*3)+
                42*std::cos(wx*20-wy*12)+27*std::sin(wx*37+wy*25),0.,255.));
            const auto i=(y*image.width+x)*4;
            image.rgba[i]=c;image.rgba[i+1]=c;image.rgba[i+2]=c;
        }
        rf::DenseSettings s;s.nearDepth=3.2;s.farDepth=7.2;
        const auto d=rf::reconstructDense(v,s);
        require(d.consistentPixelsPerView[7]>20,"Last view discarded");
        auto mesh=rf::fuseDepthSurfaces(v,d);
        require(mesh.triangles.size()>100,"Eight-view surface missing");
        std::vector<rf::TextureSource> photos;
        for(const auto& image:v)photos.push_back({image.width,image.height,image.fx,image.fy,
            image.cx,image.cy,image.pose,image.rgba});
        const auto atlas=rf::bakePhotographicAtlas(mesh,v,d.maps,photos);
        require(atlas.sourceTriangleCounts.size()==8&&atlas.indices.size()>300,"Eight-view atlas missing");
        require(atlas.atlasWidth==3*(v[0].width+6)&&atlas.atlasHeight==3*(v[0].height+6),"Wrong tile layout");
    });
    test("exposure solve spans eight cameras without a four-camera fixed-array overflow",[]{
        std::vector<rf::ExposureSample> input;
        for(std::size_t i=0;i<7;++i)for(int k=0;k<64;++k){
            double base=.07+k*.003;
            input.push_back({i,i+1,{base,base*.8,base*.9},{base*1.05,base*.84,base*.945}});
        }
        const auto r=rf::balanceExposure(8,input);
        require(r.supportedPairs==7,"Exposure graph disconnected");
        for(std::size_t i=0;i<8;++i)for(double gain:r.gains[i])
            require(std::abs(gain-std::pow(1.05,-static_cast<double>(i)))<1e-10,"Wrong exposure gain");
        require(r.medianLogErrorAfter<r.medianLogErrorBefore,"Exposure regression");
        rejects([&]{rf::balanceExposure(9,input);});
    });
    test("verified-track selection is permutation invariant and rejects disconnected scenes",[]{
        std::vector<rf::detail::DenseCameraEvidence> evidence;
        for(int c=0;c<12;++c){rf::detail::DenseCameraEvidence e;e.photoId="photo-"+std::to_string(c);e.centre={c*.1,0,0};e.sharpness=.7;
            for(std::size_t t=0;t<40;++t){e.verifiedTracks.push_back(t+(c/4)*20);}
            evidence.push_back(e);}
        const auto a=rf::detail::selectDenseEvidence(evidence,8,3);
        std::reverse(evidence.begin(),evidence.end());const auto b=rf::detail::selectDenseEvidence(evidence,8,3);
        require(a.references==b.references&&a.sources==b.sources,"Permutation changed stable selection");
        require(a.references.size()==8&&a.uniqueSparseTracks==80,"Set cover lost observed tracks");
        for(const auto& sources:a.sources){require(sources.size()<=3&&!sources.empty(),"Source cap violated");
            for(const auto& id:sources)require(std::find(a.references.begin(),a.references.end(),id)!=a.references.end(),"Hidden source outside admitted working set");}
        evidence[0].photoId="unconnected";evidence[0].verifiedTracks={1000,1001};
        const auto c=rf::detail::selectDenseEvidence(evidence);bool excluded=false;
        for(const auto& x:c.excluded)if(x.first=="unconnected")excluded=x.second=="insufficient_verified_overlap_or_baseline";
        require(excluded,"Disconnected camera not diagnosed");
    });
    std::cout<<count<<" bounded multi-camera cases passed\n";
}
