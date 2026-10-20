#include "realityforge/pnp.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <iomanip>
#include <limits>
#include <locale>
#include <numeric>
#include <random>
#include <sstream>

namespace rf {
namespace {
double dot(Vec3 a, Vec3 b){return a.x*b.x+a.y*b.y+a.z*b.z;}
Vec3 add(Vec3 a, Vec3 b){return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 sub(Vec3 a, Vec3 b){return {a.x-b.x,a.y-b.y,a.z-b.z};}
Vec3 mul(Vec3 a, double k){return {a.x*k,a.y*k,a.z*k};}
Vec3 cross(Vec3 a,Vec3 b){return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x};}
double norm(Vec3 a){return std::sqrt(dot(a,a));}
Vec3 normalized(Vec3 a){double s=norm(a);if(s<1e-14)throw Error("Degenerate pose vector");return mul(a,1/s);}
Vec3 row(const Mat3& r,int i){return {r[3*i],r[3*i+1],r[3*i+2]};}
void setRow(Mat3& r,int i,Vec3 a){r[3*i]=a.x;r[3*i+1]=a.y;r[3*i+2]=a.z;}

// Self-contained symmetric Jacobi eigensolver for the 12x12 homogeneous DLT system.
// Iterations and data size are fixed; RANSAC never allocates an unbounded matrix.
template<int N> struct Eigensystem {std::array<double,N> values;std::array<double,N*N> vectors;};
template<int N> Eigensystem<N> jacobi(std::array<double,N*N> a){
    std::array<double,N*N> v{};for(int i=0;i<N;++i)v[i*N+i]=1;
    for(int sweep=0;sweep<90;++sweep){
        double diag=0,off=0;
        for(int i=0;i<N;++i){diag+=std::abs(a[i*N+i]);for(int j=i+1;j<N;++j)off+=std::abs(a[i*N+j]);}
        if(off<1e-13*(diag+1e-22))break;
        for(int p=0;p<N;++p)for(int q=p+1;q<N;++q){
            double apq=a[p*N+q];if(std::abs(apq)<1e-17*(diag+1e-22))continue;
            double tau=(a[q*N+q]-a[p*N+p])/(2*apq);
            double t=std::copysign(1.,tau)/(std::abs(tau)+std::sqrt(1.+tau*tau));
            double c=1./std::sqrt(1+t*t),s=t*c;
            double app=a[p*N+p],aqq=a[q*N+q];
            a[p*N+p]=app-t*apq;a[q*N+q]=aqq+t*apq;a[p*N+q]=a[q*N+p]=0;
            for(int k=0;k<N;++k){
                if(k!=p&&k!=q){double x=a[k*N+p],y=a[k*N+q];a[k*N+p]=a[p*N+k]=c*x-s*y;a[k*N+q]=a[q*N+k]=s*x+c*y;}
                double x=v[k*N+p],y=v[k*N+q];v[k*N+p]=c*x-s*y;v[k*N+q]=s*x+c*y;
            }
        }
    }
    std::array<int,N> order{};std::iota(order.begin(),order.end(),0);
    std::sort(order.begin(),order.end(),[&](int a1,int b1){return a[a1*N+a1]<a[b1*N+b1];});
    Eigensystem<N> out{};
    for(int j=0;j<N;++j){out.values[j]=a[order[j]*N+order[j]];for(int i=0;i<N;++i)out.vectors[i*N+j]=v[i*N+order[j]];}
    return out;
}

// A plane supplies a homography rather than a full-rank 3D DLT system. The
// metric, calibrated decomposition preserves a proper SO(3) pose and tests both
// signs against positive depth. Collinear/ambiguous layouts remain unsupported.
CameraPose planar(std::span<const Observation> points,const std::vector<std::size_t>& ids,Vec3 center,
                  const Eigensystem<3>& covariance){
    Vec3 a{covariance.vectors[2],covariance.vectors[5],covariance.vectors[8]};
    Vec3 b{covariance.vectors[1],covariance.vectors[4],covariance.vectors[7]};
    Vec3 c=cross(a,b);std::array<double,81> normal{};
    double spread=std::sqrt(std::max(covariance.values[2]/ids.size(),1e-30));
    for(auto i:ids){auto w=sub(points[i].world,center);double x=dot(w,a)/spread,y=dot(w,b)/spread;
        double u=points[i].image.x,v=points[i].image.y;
        std::array<double,9> r{-x,-y,-1,0,0,0,u*x,u*y,u};
        std::array<double,9> t{0,0,0,-x,-y,-1,v*x,v*y,v};
        for(int k=0;k<9;++k)for(int j=0;j<9;++j)normal[k*9+j]+=r[k]*r[j]+t[k]*t[j];
    }
    auto e=jacobi<9>(normal);
    if(e.values[1]<1e-11*std::max(e.values[8],1e-30))throw Error("Ambiguous planar PnP geometry");
    Vec3 h1{e.vectors[0],e.vectors[27],e.vectors[54]},h2{e.vectors[9],e.vectors[36],e.vectors[63]},h3{e.vectors[18],e.vectors[45],e.vectors[72]};
    double k=(norm(h1)+norm(h2))/(2*spread);
    if(k<1e-14)throw Error("Ill-conditioned planar pose");
    CameraPose best;std::size_t positive=0;
    Mat3 basis{a.x,b.x,c.x,a.y,b.y,c.y,a.z,b.z,c.z};
    for(double sign:{1.,-1.}){
        auto u=normalized(mul(h1,sign));auto v=normalized(sub(mul(h2,sign),mul(u,dot(u,mul(h2,sign)))));auto n=cross(u,v);
        Mat3 local{u.x,v.x,n.x,u.y,v.y,n.y,u.z,v.z,n.z};auto r=multiply(local,transpose(basis));
        Vec3 t=sub(mul(h3,sign/k),multiply(r,center));std::size_t good=0;
        for(auto i:ids)if(add(multiply(r,points[i].world),t).z>1e-8)++good;
        if(good>positive){positive=good;best={r,t,{},points.size(),0};}
    }
    if(positive*10<ids.size()*9)throw Error("Planar pose has ambiguous cheirality");
    return best;
}

// Solve calibrated PnP with normalized 2D and 3D DLT, then project to SO(3).
CameraPose dlt(std::span<const Observation> points,const std::vector<std::size_t>& ids){
    if(ids.size()<8)throw Error("PnP needs 8 nondegenerate points");
    Vec3 center{};Vec2 uv{};
    for(auto i:ids){center=add(center,points[i].world);uv.x+=points[i].image.x;uv.y+=points[i].image.y;}
    double n=static_cast<double>(ids.size());center=mul(center,1/n);uv.x/=n;uv.y/=n;
    double spread3=0,spread2=0;
    for(auto i:ids){spread3+=norm(sub(points[i].world,center));spread2+=std::hypot(points[i].image.x-uv.x,points[i].image.y-uv.y);}
    spread3/=n;spread2/=n;
    std::array<double,9> covariance{};
    for(auto i:ids){auto w=sub(points[i].world,center);double v[3]{w.x,w.y,w.z};for(int j=0;j<3;++j)for(int k=0;k<3;++k)covariance[j*3+k]+=v[j]*v[k];}
    auto shape=jacobi<3>(covariance);
    if(shape.values[1]<1e-10*std::max(shape.values[2],1e-30))throw Error("Collinear PnP geometry");
    if(shape.values[0]<1e-8*shape.values[2])return planar(points,ids,center,shape);
    if(spread3<1e-8||spread2<1e-6)throw Error("Insufficient pose spread");
    double s3=std::sqrt(3.)/spread3,s2=std::sqrt(2.)/spread2;
    std::array<double,144> normal{};
    for(auto i:ids){
        const auto& o=points[i];auto w=mul(sub(o.world,center),s3);
        double x=(o.image.x-uv.x)*s2,y=(o.image.y-uv.y)*s2;
        std::array<double,12> a{-w.x,-w.y,-w.z,-1,0,0,0,0,x*w.x,x*w.y,x*w.z,x};
        std::array<double,12> b{0,0,0,0,-w.x,-w.y,-w.z,-1,y*w.x,y*w.y,y*w.z,y};
        for(int p=0;p<12;++p)for(int q=0;q<12;++q)normal[p*12+q]+=a[p]*a[q]+b[p]*b[q];
    }
    auto ev=jacobi<12>(normal);
    if(ev.values[1] < 1e-10*std::max(1e-25,ev.values[11]))throw Error("Degenerate PnP geometry");
    std::array<double,12> p{};for(int k=0;k<12;++k)p[k]=ev.vectors[k*12];
    // P = inv(T_image) * P_normalized * T_world.
    Mat3 m{};Vec3 t{};
    for(int j=0;j<3;++j){
        m[j]=s3*(p[j]/s2+uv.x*p[8+j]);
        m[3+j]=s3*(p[4+j]/s2+uv.y*p[8+j]);
        m[6+j]=s3*p[8+j];
    }
    t.x=p[3]/s2+uv.x*p[11]-dot(row(m,0),center);
    t.y=p[7]/s2+uv.y*p[11]-dot(row(m,1),center);
    t.z=p[11]-dot(row(m,2),center);
    double k=(norm(row(m,0))+norm(row(m,1))+norm(row(m,2)))/3.;
    if(k<1e-14||!std::isfinite(k))throw Error("Degenerate camera matrix");
    if(determinant(m)<0)k=-k;
    for(auto &x:m) { x/=k; }
    t=mul(t,1/k);
    // Gram-Schmidt rows, preserving a proper rotation without external SVD.
    Vec3 a=normalized(row(m,0));
    Vec3 b=normalized(sub(row(m,1),mul(a,dot(a,row(m,1)))));
    Vec3 c=cross(a,b);
    Mat3 r{};setRow(r,0,a);setRow(r,1,b);setRow(r,2,c);
    return {r,t,{},points.size(),0};
}

// Exp SO(3); post-multiplication not assumed. Pose increments act in camera coordinates.
Mat3 exponential(Vec3 w){
    double a=norm(w);Mat3 r=identity();
    double f=a<1e-7?1-a*a/6:std::sin(a)/a;
    double g=a<1e-7?0.5-a*a/24:(1-std::cos(a))/(a*a);
    Mat3 crossm{0,-w.z,w.y,w.z,0,-w.x,-w.y,w.x,0};
    auto sq=multiply(crossm,crossm);
    for(int i=0;i<9;++i) { r[i]+=f*crossm[i]+g*sq[i]; }
    return r;
}
bool solve6(double a[6][7], double x[6]){
    for(int i=0;i<6;++i){
        int pivot=i;for(int k=i+1;k<6;++k)if(std::abs(a[k][i])>std::abs(a[pivot][i]))pivot=k;
        if(std::abs(a[pivot][i])<1e-14)return false;
        for(int j=i;j<=6;++j)std::swap(a[i][j],a[pivot][j]);
        double factor=a[i][i];for(int j=i;j<=6;++j)a[i][j]/=factor;
        for(int k=0;k<6;++k)if(k!=i){factor=a[k][i];for(int j=i;j<=6;++j)a[k][j]-=factor*a[i][j];}
    }
    for(int i=0;i<6;++i) { x[i]=a[i][6]; }
    return true;
}
void refine(CameraPose& pose, std::span<const Observation> points,const std::vector<std::size_t>& ids,double threshold){
    auto objective=[&](const CameraPose& p){
        double cost=0;
        for(auto i:ids){auto q=add(multiply(p.rotation,points[i].world),p.translation);
            if(q.z<=1e-8||!std::isfinite(q.z))return std::numeric_limits<double>::infinity();
            double e=std::hypot(q.x/q.z-points[i].image.x,q.y/q.z-points[i].image.y);
            cost+=e<=threshold?.5*e*e:threshold*(e-.5*threshold);
        }return cost;
    };
    for(int iter=0;iter<25;++iter){
        cancellation::checkpoint();
        double m[6][7]{};int valid=0;
        for(auto i:ids){
            const auto &o=points[i];Vec3 q=add(multiply(pose.rotation,o.world),pose.translation);
            if(q.z<=1e-8||!std::isfinite(q.z))continue;
            double u=q.x/q.z,v=q.y/q.z,du=u-o.image.x,dv=v-o.image.y;
            double err=std::hypot(du,dv);double weight=err<=threshold?1:threshold/std::max(err,1e-12);
            double j[2][6]={{-u*v,1+u*u,-v,1/q.z,0,-u/q.z},{-1-v*v,u*v,u,0,1/q.z,-v/q.z}};
            for(int a=0;a<6;++a){
                m[a][6]-=weight*(j[0][a]*du+j[1][a]*dv);
                for(int b=0;b<6;++b)m[a][b]+=weight*(j[0][a]*j[0][b]+j[1][a]*j[1][b]);
            }
            ++valid;
        }
        if(valid<8)throw Error("Insufficient points in front of new camera");
        for(int i=0;i<6;++i)m[i][i]+=1e-6*(1+m[i][i]);
        double delta[6]{};if(!solve6(m,delta))throw Error("Ill-conditioned pose refinement");
        double length=0;for(double v:delta)length+=v*v;length=std::sqrt(length);
        if(length>0.2){for(auto& v:delta)v*=0.2/length;}
        const double old=objective(pose);
        bool accepted=false;
        for(double factor:{1.,.5,.25,.125,.0625}){
            auto d=exponential({factor*delta[0],factor*delta[1],factor*delta[2]});
            auto candidate=pose;
            candidate.rotation=multiply(d,pose.rotation);
            candidate.translation=add(multiply(d,pose.translation),{factor*delta[3],factor*delta[4],factor*delta[5]});
            if(objective(candidate)<old){pose=std::move(candidate);accepted=true;break;}
        }
        if(!accepted||length<1e-9)break;
    }
}
double reprojection(const CameraPose& pose, const Observation& o){
    auto q=add(multiply(pose.rotation,o.world),pose.translation);
    if(q.z<=1e-8||!std::isfinite(q.z))return std::numeric_limits<double>::infinity();
    return std::hypot(q.x/q.z-o.image.x,q.y/q.z-o.image.y);
}
std::vector<std::size_t> inliers(const CameraPose& pose,std::span<const Observation> rows,double threshold){
    std::vector<std::size_t> ids;for(std::size_t i=0;i<rows.size();++i)if(reprojection(pose,rows[i])<threshold)ids.push_back(i);return ids;
}
double median(std::vector<double> x){if(x.empty())return std::numeric_limits<double>::infinity();std::nth_element(x.begin(),x.begin()+x.size()/2,x.end());return x[x.size()/2];}
}
CameraPose registerCamera(std::span<const Observation> observations,double threshold,std::uint32_t seed){
    if(observations.size()<16||observations.size()>5000||!std::isfinite(threshold)||threshold<=1e-6||threshold>.05)throw Error("Invalid PnP input size or threshold");
    for(auto& o:observations){
        for(double v:{o.world.x,o.world.y,o.world.z,o.image.x,o.image.y})if(!std::isfinite(v)||std::abs(v)>100000)throw Error("Non-finite or unreasonable PnP coordinate");
    }
    std::mt19937 random(seed);std::uniform_int_distribution<std::size_t> pick(0,observations.size()-1);
    CameraPose best;best.inputCount=observations.size();
    const std::size_t minValid=std::max<std::size_t>(16,static_cast<std::size_t>(std::ceil(.28*observations.size())));
    // Sparse work-unit1 counts tested PnP hypotheses; adaptive termination
    // means remaining work is unknown, never a fabricated whole-scan percentage.
    progress::begin(progress::Stage::Sparse,0,1);
    for(int iter=0;iter<450;++iter){
        cancellation::checkpoint();
        std::vector<std::size_t> sample;sample.reserve(8);
        while(sample.size()<8){auto i=pick(random);if(std::find(sample.begin(),sample.end(),i)==sample.end())sample.push_back(i);}
        try {
            auto pose=dlt(observations,sample);
            auto hits=inliers(pose,observations,threshold);
            if(hits.size()>best.inliers.size()) {best=std::move(pose);best.inliers=std::move(hits);}
        }catch(const cancellation::Cancelled&){throw;}catch(const Error&){}
        progress::advance(static_cast<std::uint64_t>(iter+1));
        if(iter>=60 && best.inliers.size()>=observations.size()*.93)break;
    }
    if(best.inliers.size()<minValid)throw Error("Third-camera registration failed: insufficient consistent 2D-3D tracks");
    // Reestimate from all inliers and minimize the actual reprojection error.
    for(int pass=0;pass<3;++pass){
        try{
            auto candidate=dlt(observations,best.inliers);
            refine(candidate,observations,best.inliers,threshold);
            auto newInliers=inliers(candidate,observations,threshold);
            if(newInliers.size()<minValid||newInliers.size()+3<best.inliers.size())break;
            best=std::move(candidate);best.inliers=std::move(newInliers);
        }catch(const cancellation::Cancelled&){throw;}catch(const Error&){break;}
    }
    // Final refit on the stable consensus, no unverified extra points introduced.
    refine(best,observations,best.inliers,threshold);
    best.inliers=inliers(best,observations,threshold);
    if(best.inliers.size()<minValid)throw Error("Pose refinement lost consensus");
    std::vector<double> errors;errors.reserve(best.inliers.size());for(auto i:best.inliers)errors.push_back(reprojection(best,observations[i]));
    best.medianError=median(errors);
    if(determinant(best.rotation)<.99||determinant(best.rotation)>1.01)throw Error("Nonrigid camera pose");
    return best;
}
std::string cameraJson(const CameraPose& p){
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(12);
    o<<"{\"schema\":2,\"representation\":\"camera_pose\",\"scale\":\"arbitrary\",\"inputTracks\":"<<p.inputCount
     <<",\"inlierCount\":"<<p.inliers.size()<<",\"medianErrorNormalized\":"<<p.medianError<<",\"rotation\":[";
    for(int i=0;i<9;++i){if(i)o<<',';o<<p.rotation[i];}
    o<<"],\"translation\":["<<p.translation.x<<','<<p.translation.y<<','<<p.translation.z<<"],\"inlierIndices\":[";
    for(std::size_t i=0;i<p.inliers.size();++i){if(i)o<<',';o<<p.inliers[i];}
    o<<"]}";return o.str();
}
} // namespace rf
extern "C" char* rf_register_camera_json(const double* data,std::size_t count,double threshold) noexcept {
    try { // Includes error serialization: allocation failures must never escape the C ABI.
    std::string text;
    try{
        if(!data||count<16||count>5000)throw rf::Error("Invalid camera input buffer");
        std::vector<rf::Observation> observations;observations.reserve(count);
        for(std::size_t i=0;i<count;++i){const double* p=data+i*5;observations.push_back({{p[0],p[1],p[2]},{p[3],p[4]}});}
        text=rf::cameraJson(rf::registerCamera(observations,threshold));
    }catch(const std::exception& e){text="{\"error\":\"";for(char c:std::string(e.what())){if(c=='\"'||c=='\\')text+='\\';if(static_cast<unsigned char>(c)>=32)text+=c;}text+="\"}";}
    auto* out=static_cast<char*>(std::malloc(text.size()+1));if(!out)return nullptr;
    std::memcpy(out,text.c_str(),text.size()+1);return out;
    } catch (...) { return nullptr; }
}
