#include "realityforge/multiview.hpp"
#include "realityforge/cancellation.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <limits>
#include <locale>
#include <map>
#include <numeric>
#include <sstream>

namespace rf {
namespace {
constexpr double pi=3.14159265358979323846;
Vec3 add(Vec3 a,Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a,Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 times(Vec3 a,double x){return {a.x*x,a.y*x,a.z*x};}
double dot(Vec3 a,Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
double magnitude(Vec3 a){return std::sqrt(dot(a,a));}
bool finite(Vec3 a){return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
Vec3 cameraPoint(const ViewPose& p,Vec3 x){return add(multiply(p.rotation,x),p.translation);}
Vec3 cameraCenter(const ViewPose& p){return times(multiply(transpose(p.rotation),p.translation),-1);}
double error(const ViewPose& p, Vec3 xyz, Vec2 observed){
    auto c=cameraPoint(p,xyz);
    if(c.z<=1e-8||!finite(c))return std::numeric_limits<double>::infinity();
    return std::hypot(c.x/c.z-observed.x,c.y/c.z-observed.y);
}
double median(std::vector<double> values){
    if(values.empty())return std::numeric_limits<double>::infinity();
    std::nth_element(values.begin(),values.begin()+static_cast<std::ptrdiff_t>(values.size()/2),values.end());
    return values[values.size()/2];
}
// Huber loss's 0.5*scale convention; robust against bad feature correspondences.
double loss(double r,double delta){return r<=delta?.5*r*r:delta*(r-.5*delta);}
double weight(double r,double delta){return r<=delta?1:delta/std::max(r,1e-15);}
// Small dense normal equations (n=3 or 6). Pivot, guard non-finite/near-singular systems.
template<int N> bool solve(std::array<double,N*N> a,std::array<double,N> b,std::array<double,N>& x){
    for(int k=0;k<N;++k){
        int pivot=k;
        for(int r=k+1;r<N;++r)if(std::abs(a[r*N+k])>std::abs(a[pivot*N+k]))pivot=r;
        if(std::abs(a[pivot*N+k])<1e-13||!std::isfinite(a[pivot*N+k]))return false;
        for(int c=k;c<N;++c)std::swap(a[k*N+c],a[pivot*N+c]);
        std::swap(b[k],b[pivot]);
        double d=a[k*N+k];for(int c=k;c<N;++c)a[k*N+c]/=d;b[k]/=d;
        for(int r=0;r<N;++r)if(r!=k){
            double f=a[r*N+k];for(int c=k;c<N;++c)a[r*N+c]-=f*a[k*N+c];b[r]-=f*b[k];
        }
    }
    x=b;for(double v:x)if(!std::isfinite(v))return false;return true;
}
Mat3 rotationIncrement(Vec3 w){
    double a=magnitude(w),f=a<1e-7?1-a*a/6:std::sin(a)/a;
    double g=a<1e-7?.5-a*a/24:(1-std::cos(a))/(a*a);
    Mat3 k{0,-w.z,w.y,w.z,0,-w.x,-w.y,w.x,0};auto k2=multiply(k,k);
    Mat3 r=identity();for(int i=0;i<9;++i)r[i]+=f*k[i]+g*k2[i];return r;
}
using Group=std::vector<TrackObservation>;
struct Working {Vec3 xyz; std::size_t id{};std::array<std::uint8_t,3> color{};Group group;double parallax{};};
double costPoint(Vec3 x,const Group& group,const std::vector<ViewPose>& poses,double threshold){
    double sum=0;for(auto& o:group){double e=error(poses[o.camera],x,o.image);if(!std::isfinite(e))return 1e100;sum+=loss(e,threshold);}
    return sum;
}
bool refinePoint(Working& w,const std::vector<ViewPose>& poses,double threshold){
    bool improved=false;
    for(int round=0;round<8;++round){
        cancellation::checkpoint();
        std::array<double,9> H{};std::array<double,3> g{};int valid=0;
        for(auto& o:w.group){
            auto& p=poses[o.camera];auto q=cameraPoint(p,w.xyz);if(q.z<=1e-8)continue;
            double u=q.x/q.z,v=q.y/q.z,du=u-o.image.x,dv=v-o.image.y;
            double ww=weight(std::hypot(du,dv),threshold);
            std::array<double,3> ju{},jv{};
            for(int k=0;k<3;++k){ju[k]=(p.rotation[k]-u*p.rotation[6+k])/q.z;jv[k]=(p.rotation[3+k]-v*p.rotation[6+k])/q.z;}
            for(int i=0;i<3;++i){g[i]-=ww*(ju[i]*du+jv[i]*dv);for(int j=0;j<3;++j)H[i*3+j]+=ww*(ju[i]*ju[j]+jv[i]*jv[j]);}
            ++valid;
        }
        if(valid<2)break;
        double old=costPoint(w.xyz,w.group,poses,threshold);
        bool accepted=false;
        for(double lambda:{1e-5,1e-3,.05,.5}){
            auto damped=H;
            for(int k=0;k<3;++k)damped[k*3+k]+=lambda*(1+H[k*3+k]);
            std::array<double,3> dx{};if(!solve<3>(damped,g,dx))continue;
            Vec3 change{dx[0],dx[1],dx[2]};
            double step=magnitude(change),limit=.3*std::max(1.,magnitude(w.xyz));
            if(step>limit)change=times(change,limit/step);
            Vec3 candidate=add(w.xyz,change);
            if(finite(candidate)&&costPoint(candidate,w.group,poses,threshold)<old-1e-13){
                w.xyz=candidate;accepted=true;improved=true;break;
            }
        }
        if(!accepted)break;
    }
    return improved;
}
double costCamera(std::size_t cam,const ViewPose& pose,const std::vector<Working>& points,double threshold){
    double total=0;
    for(auto& w:points){
        for(auto& o:w.group){
            if(o.camera!=cam)continue;
            double e=error(pose,w.xyz,o.image);
            if(!std::isfinite(e))return 1e100;
            total+=loss(e,threshold);
        }
    }
    return total;
}
bool refineCamera(std::size_t cam,std::vector<ViewPose>& poses,const std::vector<Working>& points,double threshold){
    int count=0;std::array<double,36> H{};std::array<double,6> g{};
    auto& camera=poses[cam];
    for(auto& w:points)for(auto& o:w.group)if(o.camera==cam){
        auto q=cameraPoint(camera,w.xyz);if(q.z<=1e-8)continue;
        double u=q.x/q.z,v=q.y/q.z,du=u-o.image.x,dv=v-o.image.y;
        double ww=weight(std::hypot(du,dv),threshold);
        const std::array<double,6> ju{-u*v,1+u*u,-v,1/q.z,0,-u/q.z};
        const std::array<double,6> jv{-1-v*v,u*v,u,0,1/q.z,-v/q.z};
        for(int i=0;i<6;++i){
            g[i]-=ww*(ju[i]*du+jv[i]*dv);
            for(int j=0;j<6;++j)H[i*6+j]+=ww*(ju[i]*ju[j]+jv[i]*jv[j]);
        }
        ++count;
    }
    if(count<16)return false;
    double old=costCamera(cam,camera,points,threshold);
    for(double lambda:{1e-5,1e-3,.05,.5}){
        auto A=H;for(int k=0;k<6;++k)A[k*6+k]+=lambda*(1+H[k*6+k]);
        std::array<double,6> dx{};if(!solve<6>(A,g,dx))continue;
        double size=0;for(double v:dx)size+=v*v;size=std::sqrt(size);
        if(size>.08)for(double& v:dx)v*=.08/size;
        auto dr=rotationIncrement({dx[0],dx[1],dx[2]});
        ViewPose proposed{multiply(dr,camera.rotation),add(multiply(dr,camera.translation),{dx[3],dx[4],dx[5]})};
        if(costCamera(cam,proposed,points,threshold)<old-1e-13){camera=proposed;return true;}
    }
    return false;
}
double rayAngle(const ViewPose& a,const ViewPose& b,Vec3 x){
    auto p=sub(x,cameraCenter(a)),q=sub(x,cameraCenter(b));
    double denom=magnitude(p)*magnitude(q);
    if(denom<1e-12)return 0;
    return std::acos(std::clamp(dot(p,q)/denom,-1.,1.))*180/pi;
}
// Re-use M01 triangulation from arbitrary registered cameras via correct relative pose.
Vec3 triangulatePair(const TrackObservation& a,const TrackObservation& b,
    const std::vector<ViewPose>& cameras,double& parallax){
    const auto& p=cameras[a.camera];const auto& q=cameras[b.camera];
    auto relative=multiply(q.rotation,transpose(p.rotation));
    auto translation=sub(q.translation,multiply(relative,p.translation));
    Point point=triangulate(Match{a.image,b.image,a.color},relative,translation);
    Vec3 world=multiply(transpose(p.rotation),sub(point.xyz,p.translation));
    parallax=point.parallax;
    if(!finite(world)||magnitude(world)>100000)throw Error("Unbounded triangulation");
    return world;
}
}
MultiViewResult reconstructMultiView(std::span<const ViewPose> views,
    std::span<const TrackObservation> observations,const MultiViewSettings& settings){
    if(views.size()<2||views.size()>12||observations.empty()||observations.size()>100000||
       !std::isfinite(settings.maxReprojectionError)||settings.maxReprojectionError<1e-6||settings.maxReprojectionError>.05||
       !std::isfinite(settings.minParallaxDegrees)||settings.minParallaxDegrees<.1||settings.minParallaxDegrees>30||
       settings.iterations<0||settings.iterations>20)throw Error("Invalid multi-view settings or sizes");
    cancellation::checkpoint();
    MultiViewResult out;out.cameras.assign(views.begin(),views.end());out.inputObservations=observations.size();
    for(auto& p:out.cameras){
        if(!finite(p.translation)||magnitude(p.translation)>100000)throw Error("Invalid or unbounded camera translation");
        for(double d:p.rotation)if(!std::isfinite(d))throw Error("Invalid camera rotation");
        if(std::abs(determinant(p.rotation)-1)>0.02)throw Error("Camera rotation not proper");
        auto orth=multiply(p.rotation,transpose(p.rotation));
        for(int i=0;i<9;++i)if(std::abs(orth[i]-(i%4==0?1.:0.))>.025)throw Error("Camera rotation not orthonormal");
    }
    if(magnitude(sub(cameraCenter(out.cameras[0]),cameraCenter(out.cameras[1])))<1e-6)
        throw Error("Cannot fix multi-view gauge with coincident anchors");
    std::map<std::size_t,Group> groups;
    std::size_t checked=0;
    for(auto& o:observations){
        if((checked++%256)==0)cancellation::checkpoint();
        if(o.camera>=views.size()||!std::isfinite(o.image.x)||!std::isfinite(o.image.y)||
           std::abs(o.image.x)>10||std::abs(o.image.y)>10||o.track>10000000)
            throw Error("Invalid multi-view observation");
        auto& group=groups[o.track];if(group.size()>=12)throw Error("Too many observations per track");
        for(auto& existing:group)if(existing.camera==o.camera)throw Error("Conflicting same-camera observations in track");
        group.push_back(o);
    }
    if(groups.size()>30000)throw Error("Too many tracks");
    out.inputTracks=groups.size();
    std::vector<Working> working;working.reserve(groups.size());
    double gate=settings.maxReprojectionError;
    for(auto& [id,group]:groups){
        cancellation::checkpoint();
        std::sort(group.begin(),group.end(),[](const auto& a,const auto& b){return a.camera<b.camera;});
        if(group.size()<2)continue;
        bool found=false;Vec3 best{};double bestParallax=0;std::size_t support=0;double bestCost=1e100;
        for(std::size_t i=0;i<group.size();++i)for(std::size_t j=i+1;j<group.size();++j){
            try{
                double angle=0;auto xyz=triangulatePair(group[i],group[j],out.cameras,angle);
                if(angle<settings.minParallaxDegrees||angle>120)continue;
                std::size_t hits=0;double sum=0;
                for(auto& o:group){auto e=error(out.cameras[o.camera],xyz,o.image);if(e<=gate*2){++hits;sum+=e;}}
                if(hits<2||hits*3<group.size()*2)continue;
                if(!found||hits>support||(hits==support&&(sum<hits*bestCost-1e-12 || (std::abs(sum-hits*bestCost)<1e-12&&angle>bestParallax)))){
                    best=xyz;bestParallax=angle;support=hits;bestCost=sum/hits;found=true;
                }
            }catch(const cancellation::Cancelled&){throw;}catch(const Error&){}
        }
        if(!found)continue;
        // Drop individually unsupported observations before robust joint refinement.
        Group accepted;accepted.reserve(group.size());
        for(auto& o:group)if(error(out.cameras[o.camera],best,o.image)<gate*2)accepted.push_back(o);
        if(accepted.size()<2)continue;
        working.push_back({best,id,accepted.front().color,std::move(accepted),bestParallax});
    }
    if(working.empty())throw Error("No valid multi-view triangulations; check calibration, parallax and pose");
    out.rejectedTracks=out.inputTracks-working.size();
    std::map<std::size_t,std::vector<double>> initialErrors;
    for(auto& w:working)for(auto& o:w.group)initialErrors[w.id].push_back(error(out.cameras[o.camera],w.xyz,o.image));
    for(int iteration=0;iteration<settings.iterations;++iteration){
        cancellation::checkpoint();
        bool changed=false;
        for(auto& w:working)changed=refinePoint(w,out.cameras,gate)||changed;
        for(std::size_t c=2;c<out.cameras.size();++c)changed=refineCamera(c,out.cameras,working,gate)||changed;
        out.iterations=iteration+1;
        if(!changed)break;
    }
    for(auto& w:working){
        std::vector<double> errors;double maxAngle=0;
        for(std::size_t i=0;i<w.group.size();++i){
            errors.push_back(error(out.cameras[w.group[i].camera],w.xyz,w.group[i].image));
            for(std::size_t j=i+1;j<w.group.size();++j)maxAngle=std::max(maxAngle,rayAngle(out.cameras[w.group[i].camera],out.cameras[w.group[j].camera],w.xyz));
        }
        if(*std::max_element(errors.begin(),errors.end())>gate*2||maxAngle<settings.minParallaxDegrees)continue;
        Point point{w.xyz,w.color,median(errors),maxAngle,w.id};
        std::vector<std::size_t> support;support.reserve(w.group.size());for(auto& observation:w.group)support.push_back(observation.camera);
        out.tracks.push_back({point,w.id,w.group.size(),1/std::max(std::sin(maxAngle*pi/180),1e-12),std::move(support)});
    }
    out.rejectedTracks=out.inputTracks-out.tracks.size();
    if(out.tracks.empty())throw Error("No stable multi-view points after optimization");
    std::vector<double> prior;for(auto& point:out.tracks){auto& values=initialErrors[point.track];prior.insert(prior.end(),values.begin(),values.end());}
    out.medianErrorBefore=median(prior);
    std::vector<double> after;
    for(auto& point:out.tracks){auto it=groups.find(point.track);for(auto& o:it->second)
        if(error(out.cameras[o.camera],point.point.xyz,o.image)<=gate*2)after.push_back(error(out.cameras[o.camera],point.point.xyz,o.image));}
    out.medianErrorAfter=median(after);
    if(!std::isfinite(out.medianErrorAfter))throw Error("Invalid refined geometry");
    return out;
}
std::vector<MappedSparseTrackPoint> reconstructMultiViewWindow(
    std::span<const ViewPose> cameras,std::span<const TrackObservation> observations,
    const NativeWindowMapping& mapping,const MultiViewSettings& settings){
    auto validIds=[](const std::vector<std::string>& ids){std::map<std::string,bool> seen;
        for(auto& id:ids)if(id.empty()||id.size()>1024||!seen.emplace(id,true).second)return false;
        return true;};
    if(mapping.cameraIds.size()!=cameras.size()||mapping.trackIds.size()>30000||
       !validIds(mapping.cameraIds)||!validIds(mapping.trackIds))throw Error("Invalid native local/global mapping");
    for(auto& row:observations)if(row.track>=mapping.trackIds.size())throw Error("Track missing from window mapping");
    auto result=reconstructMultiView(cameras,observations,settings);std::vector<MappedSparseTrackPoint> out;out.reserve(result.tracks.size());
    for(auto& point:result.tracks){MappedSparseTrackPoint mapped{point,mapping.trackIds[point.track],{}};
        for(auto camera:point.supportingCameras)mapped.cameraIds.push_back(mapping.cameraIds[camera]);out.push_back(std::move(mapped));}
    return out;
}
std::string multiViewJson(const MultiViewResult& r){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(12);
    o<<"{\"schema\":3,\"representation\":\"sparse_points\",\"scale\":\"arbitrary\",\"optimization\":\"gauge_fixed_alternating_sparse\",\"anchorCamerasFixed\":2,\"registeredCameras\":"<<r.cameras.size()
     <<",\"inputTracks\":"<<r.inputTracks<<",\"inputObservations\":"<<r.inputObservations<<",\"rejectedTracks\":"<<r.rejectedTracks
     <<",\"pointCount\":"<<r.tracks.size()<<",\"iterations\":"<<r.iterations
     <<",\"medianErrorBeforeNormalized\":"<<r.medianErrorBefore<<",\"medianErrorAfterNormalized\":"<<r.medianErrorAfter<<",\"cameras\":[";
    for(std::size_t i=0;i<r.cameras.size();++i){auto& c=r.cameras[i];if(i)o<<',';o<<"{\"fixed\":"<<(i<2?"true":"false")<<",\"rotation\":[";
        for(int j=0;j<9;++j){if(j)o<<',';o<<c.rotation[j];}
        o<<"],\"translation\":["<<c.translation.x<<','<<c.translation.y<<','<<c.translation.z<<"]}";
    }
    o<<"],\"points\":[";
    for(std::size_t i=0;i<r.tracks.size();++i){auto& p=r.tracks[i];if(i)o<<',';
        o<<'['<<p.point.xyz.x<<','<<p.point.xyz.y<<','<<p.point.xyz.z<<','<<int(p.point.color[0])<<','<<int(p.point.color[1])<<','<<int(p.point.color[2])<<','<<p.point.error<<','<<p.point.parallax<<','<<p.track<<']';
    }
    o<<"],\"trackEvidence\":[";
    for(std::size_t i=0;i<r.tracks.size();++i){if(i)o<<',';auto& p=r.tracks[i];o<<"{\"track\":"<<p.track<<",\"angularCondition\":"<<p.angularCondition<<",\"supportingCameras\":[";
        for(std::size_t j=0;j<p.supportingCameras.size();++j){if(j)o<<',';o<<p.supportingCameras[j];}o<<"]}";}
    o<<"]}";return o.str();
}
} // namespace rf
extern "C" char* rf_multiview_json(const double* cameras,std::size_t camera_count,
    const double* observations,std::size_t observation_count,double threshold,int iterations){
    try { // Allocation and error serialization belong to the C ABI boundary too.
    std::string response;
    try{
        if(!cameras||!observations||camera_count<2||camera_count>12||observation_count==0||observation_count>100000)
            throw rf::Error("Invalid multi-view C ABI buffers");
        std::vector<rf::ViewPose> poses;poses.reserve(camera_count);
        for(std::size_t i=0;i<camera_count;++i){rf::ViewPose p;for(int k=0;k<9;++k)p.rotation[k]=cameras[i*12+k];
            p.translation={cameras[i*12+9],cameras[i*12+10],cameras[i*12+11]};poses.push_back(p);}
        std::vector<rf::TrackObservation> tracks;tracks.reserve(observation_count);
        for(std::size_t i=0;i<observation_count;++i){const auto* p=observations+i*7;
            if(!std::isfinite(p[0])||!std::isfinite(p[1])||p[0]<0||p[0]>10000000||p[1]<0||p[1]>=camera_count||
               std::floor(p[0])!=p[0]||std::floor(p[1])!=p[1])throw rf::Error("Nonintegral or invalid track/camera index");
            rf::TrackObservation t; t.track=static_cast<std::size_t>(p[0]);t.camera=static_cast<std::size_t>(p[1]);t.image={p[2],p[3]};
            for(int k=0;k<3;++k){double col=p[4+k];if(!std::isfinite(col)||col<0||col>255)throw rf::Error("Invalid feature color");t.color[k]=static_cast<std::uint8_t>(col);}
            tracks.push_back(t);
        }
        rf::MultiViewSettings s;s.maxReprojectionError=threshold;s.iterations=iterations;
        response=rf::multiViewJson(rf::reconstructMultiView(poses,tracks,s));
    }catch(const std::exception& e){response="{\"error\":\"";for(char c:std::string(e.what())){if(c=='"'||c=='\\')response+='\\';if(static_cast<unsigned char>(c)>=32)response+=c;}response+="\"}";}
    auto* p=static_cast<char*>(std::malloc(response.size()+1));if(!p)return nullptr;
    std::memcpy(p,response.c_str(),response.size()+1);return p;
    }catch(...){return nullptr;}
}
