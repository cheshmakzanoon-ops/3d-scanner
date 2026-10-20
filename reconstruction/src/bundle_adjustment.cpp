#include "realityforge/bundle_adjustment.hpp"
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
#include <vector>

namespace rf {
namespace {
constexpr double kPi = 3.141592653589793238462643383279502884;
using V3 = std::array<double,3>;
using V6 = std::array<double,6>;
using M3 = std::array<double,9>;
using M6 = std::array<double,36>;
using M63 = std::array<double,18>; // J_camera^T * J_point, row-major 6 x 3
Vec3 add(Vec3 a, Vec3 b) {return {a.x+b.x,a.y+b.y,a.z+b.z};}
Vec3 mul(Vec3 a,double b) {return {a.x*b,a.y*b,a.z*b};}
Vec3 sub(Vec3 a, Vec3 b) {return {a.x-b.x,a.y-b.y,a.z-b.z};}
double dot(Vec3 a,Vec3 b) {return a.x*b.x+a.y*b.y+a.z*b.z;}
double norm(Vec3 a) {return std::sqrt(dot(a,a));}
bool finite(Vec3 a) {return std::isfinite(a.x)&&std::isfinite(a.y)&&std::isfinite(a.z);}
Vec3 cameraCenter(const ViewPose& c) {return mul(multiply(transpose(c.rotation),c.translation),-1.);}
Vec3 cameraPoint(const ViewPose& c,Vec3 x) {return add(multiply(c.rotation,x),c.translation);}
double residual(const ViewPose& c, Vec3 x, Vec2 uv) {
    const auto p=cameraPoint(c,x);
    if(!finite(p)||p.z<=1e-8)return std::numeric_limits<double>::infinity();
    return std::hypot(p.x/p.z-uv.x,p.y/p.z-uv.y);
}
double huber(double r,double delta) {return r<=delta?.5*r*r:delta*(r-.5*delta);}
double huberWeight(double r,double delta) {return r<=delta?1.:delta/std::max(r,1e-15);}
double median(std::vector<double> v) {
    if(v.empty())return std::numeric_limits<double>::infinity();
    std::nth_element(v.begin(),v.begin()+static_cast<std::ptrdiff_t>(v.size()/2),v.end());
    return v[v.size()/2];
}
double parallax(const ViewPose& a,const ViewPose& b,Vec3 x) {
    const auto p=sub(x,cameraCenter(a)),q=sub(x,cameraCenter(b));
    const double n=norm(p)*norm(q);
    if(n<1e-14)return 0.;
    return 180./kPi*std::acos(std::clamp(dot(p,q)/n,-1.,1.));
}
Mat3 rotExp(Vec3 w){
    const double theta=norm(w);
    const double f=theta<1e-7?1.-theta*theta/6.:std::sin(theta)/theta;
    const double g=theta<1e-7?.5-theta*theta/24.:(1.-std::cos(theta))/(theta*theta);
    const Mat3 k{0,-w.z,w.y,w.z,0,-w.x,-w.y,w.x,0};
    const auto sq=multiply(k,k);
    auto r=identity();
    for(int i=0;i<9;++i)r[i]+=f*k[i]+g*sq[i];
    return r;
}
// Pivoted Gaussian elimination for SPD-like systems; explicit singular/nonfinite rejection.
bool solveLinear(std::vector<double> a,std::vector<double> b,std::vector<double>& x,std::size_t n){
    if(n==0){x.clear();return true;}
    for(std::size_t k=0;k<n;++k){
        std::size_t pivot=k;
        for(std::size_t row=k+1;row<n;++row)if(std::abs(a[row*n+k])>std::abs(a[pivot*n+k]))pivot=row;
        if(!std::isfinite(a[pivot*n+k])||std::abs(a[pivot*n+k])<1e-16)return false;
        if(pivot!=k){for(std::size_t col=k;col<n;++col)std::swap(a[k*n+col],a[pivot*n+col]);std::swap(b[k],b[pivot]);}
        for(std::size_t row=k+1;row<n;++row){
            const double factor=a[row*n+k]/a[k*n+k];
            if(!std::isfinite(factor))return false;
            for(std::size_t col=k+1;col<n;++col)a[row*n+col]-=factor*a[k*n+col];
            b[row]-=factor*b[k];
        }
    }
    x.assign(n,0.);
    for(std::size_t rev=0;rev<n;++rev){
        const std::size_t k=n-1-rev;
        double value=b[k];for(std::size_t col=k+1;col<n;++col)value-=a[k*n+col]*x[col];
        x[k]=value/a[k*n+k];if(!std::isfinite(x[k]))return false;
    }
    return true;
}
bool inverse3(const M3& a,M3& inverse){
    // Adjugate inverse, guarded against singular/ill-conditioned 3x3 matrices.
    const double d=a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6]);
    if(!std::isfinite(d)||d<=1e-22)return false;
    inverse={ (a[4]*a[8]-a[5]*a[7])/d,(a[2]*a[7]-a[1]*a[8])/d,(a[1]*a[5]-a[2]*a[4])/d,
              (a[5]*a[6]-a[3]*a[8])/d,(a[0]*a[8]-a[2]*a[6])/d,(a[2]*a[3]-a[0]*a[5])/d,
              (a[3]*a[7]-a[4]*a[6])/d,(a[1]*a[6]-a[0]*a[7])/d,(a[0]*a[4]-a[1]*a[3])/d };
    for(const double value:inverse)if(!std::isfinite(value))return false;
    return true;
}
V3 multiply3(const M3& m,const V3& v){V3 out{};for(int i=0;i<3;++i)for(int j=0;j<3;++j)out[i]+=m[i*3+j]*v[j];return out;}
struct ActivePoint {
    BundleSeed seed;
    std::vector<TrackObservation> observations;
};
struct PointSystem {
    M3 hpp{};V3 gp{};
    std::map<std::size_t,M63> cross; // one 6x3 block per free camera
    std::vector<std::size_t> touched; // only cameras observing THIS sparse point
};
struct Linearization {
    std::vector<PointSystem> points;
    std::vector<double> hcc;
    std::vector<double> gc;
};
std::size_t freeCameras(const std::vector<ViewPose>& cameras,std::size_t fixed){return cameras.size()-fixed;}
void verifyCameras(std::span<const ViewPose> cameras){
    if(cameras.size()<3||cameras.size()>32)throw Error("Joint BA needs 3..32 registered cameras; use bounded windows for larger graphs");
    for(const auto& c:cameras){
        if(!finite(c.translation))throw Error("Nonfinite BA translation");
        for(const double v:c.rotation)if(!std::isfinite(v))throw Error("Nonfinite BA rotation");
        if(std::abs(determinant(c.rotation)-1.)>.02)throw Error("Invalid BA camera rotation");
        const auto orth=multiply(c.rotation,transpose(c.rotation));
        for(int j=0;j<9;++j)if(std::abs(orth[j]-(j%4==0?1.:0.))>.025)throw Error("BA rotation is not orthogonal");
    }
    if(norm(sub(cameraCenter(cameras[0]),cameraCenter(cameras[1])))<1e-5)
        throw Error("Coincident anchor cameras cannot define BA gauge");
}
std::vector<ActivePoint> prepare(std::span<const BundleSeed> seeds,
    std::span<const TrackObservation> obs,const std::vector<ViewPose>& cams,double delta,
    std::size_t& rejected){
    if(seeds.empty()||seeds.size()>20000||obs.empty()||obs.size()>100000)
        throw Error("Out of bounds joint BA input");
    std::map<std::size_t,std::size_t> ids;
    std::vector<ActivePoint> rows;
    rows.reserve(seeds.size());
    for(const auto& seed:seeds){
        if(seed.track>10000000||!finite(seed.xyz)||norm(seed.xyz)>100000)
            throw Error("Invalid BA sparse point");
        if(!ids.emplace(seed.track,rows.size()).second)throw Error("Duplicate BA track ID");
        rows.push_back({seed,{}});
    }
    std::vector<std::vector<bool>> seen(seeds.size(),std::vector<bool>(cams.size()));
    std::size_t skipped=0;
    for(const auto& item:obs){
        if(item.camera>=cams.size()||item.track>10000000||!std::isfinite(item.image.x)||!std::isfinite(item.image.y)||
           std::abs(item.image.x)>10||std::abs(item.image.y)>10)throw Error("Invalid BA observation");
        auto it=ids.find(item.track);
        if(it==ids.end()){++skipped;continue;}
        auto slot=seen[it->second][item.camera];
        if(slot)throw Error("Conflicting same-camera BA observations");
        slot=true;
        if(residual(cams[item.camera],rows[it->second].seed.xyz,item.image)>delta*3.){++skipped;continue;}
        rows[it->second].observations.push_back(item);
    }
    std::vector<ActivePoint> accepted;accepted.reserve(rows.size());
    for(auto& row:rows){
        if(row.observations.size()<2){skipped+=row.observations.size();continue;}
        accepted.push_back(std::move(row));
    }
    if(accepted.size()<16)throw Error("Too few stable observations for joint BA");
    rejected=skipped;
    return accepted;
}
// Build shared camera normal equations and per-point blocks from the SAME linearization.
Linearization linearize(const std::vector<ViewPose>& cameras,const std::vector<ActivePoint>& points,double huberDelta,std::size_t fixed){
    const std::size_t nfree=freeCameras(cameras,fixed),n=6*nfree;
    Linearization l;
    l.points.reserve(points.size());l.hcc.assign(n*n,0.);l.gc.assign(n,0.);
    for(const auto& point:points){
        cancellation::checkpoint();
        PointSystem h;
        for(const auto& o:point.observations){
            const auto& c=cameras[o.camera];
            const auto q=cameraPoint(c,point.seed.xyz);
            if(q.z<=1e-8)throw Error("BA point moved behind camera");
            const double u=q.x/q.z,v=q.y/q.z;
            const double r0=u-o.image.x,r1=v-o.image.y;
            const double w=huberWeight(std::hypot(r0,r1),huberDelta);
            V3 jp0{},jp1{};
            for(int j=0;j<3;++j){jp0[j]=(c.rotation[j]-u*c.rotation[6+j])/q.z;jp1[j]=(c.rotation[3+j]-v*c.rotation[6+j])/q.z;}
            for(int j=0;j<3;++j){
                h.gp[j]+=w*(jp0[j]*r0+jp1[j]*r1);
                for(int k=0;k<3;++k)h.hpp[j*3+k]+=w*(jp0[j]*jp0[k]+jp1[j]*jp1[k]);
            }
            if(o.camera<fixed)continue;
            const std::size_t cam=o.camera-fixed,start=6*cam;
            h.touched.push_back(cam);
            const auto jac=bundleCameraJacobian(q);
            V6 jc0{},jc1{};
            std::copy_n(jac.begin(),6,jc0.begin());std::copy_n(jac.begin()+6,6,jc1.begin());
            for(int a=0;a<6;++a){
                l.gc[start+a]+=w*(jc0[a]*r0+jc1[a]*r1);
                for(int b=0;b<6;++b)l.hcc[(start+a)*n+start+b]+=w*(jc0[a]*jc0[b]+jc1[a]*jc1[b]);
                for(int j=0;j<3;++j)h.cross[cam][a*3+j]+=w*(jc0[a]*jp0[j]+jc1[a]*jp1[j]);
            }
        }
        l.points.push_back(std::move(h));
    }
    return l;
}
struct Delta {std::vector<V3> points;std::vector<V6> cameras;double squaredNorm{};};
bool computeStep(const Linearization& l,double lambda,Delta& step){
    const std::size_t nfree=l.gc.size()/6, n=nfree*6;
    std::vector<double> schur=l.hcc,rhs(n,0.);
    for(std::size_t i=0;i<n;++i){schur[i*n+i]+=lambda*(1.+schur[i*n+i]);rhs[i]=-l.gc[i];}
    std::vector<M3> inverses;inverses.reserve(l.points.size());
    for(const auto& point:l.points){
        auto h=point.hpp;
        for(int k=0;k<3;++k)h[k*3+k]+=lambda*(1.+h[k*3+k]);
        M3 inv{};if(!inverse3(h,inv))return false;
        inverses.push_back(inv);
        const auto ig=multiply3(inv,point.gp);
        for(const auto a:point.touched){
            const auto& Aa=point.cross.at(a);
            for(int i=0;i<6;++i){
                double correction=0.;for(int p=0;p<3;++p)correction+=Aa[i*3+p]*ig[p];
                rhs[a*6+i]+=correction;
            }
            for(const auto b:point.touched){
                const auto& Ab=point.cross.at(b);
                for(int i=0;i<6;++i)for(int j=0;j<6;++j){
                    double correction=0.;
                    for(int p=0;p<3;++p)for(int q=0;q<3;++q)
                        correction+=Aa[i*3+p]*inv[p*3+q]*Ab[j*3+q];
                    schur[(a*6+i)*n+(b*6+j)]-=correction;
                }
            }
        }
    }
    std::vector<double> cameraStep;
    if(!solveLinear(std::move(schur),std::move(rhs),cameraStep,n))return false;
    step.cameras.resize(nfree);step.points.resize(l.points.size());step.squaredNorm=0.;
    for(std::size_t a=0;a<nfree;++a){
        for(int i=0;i<6;++i){step.cameras[a][i]=cameraStep[a*6+i];step.squaredNorm+=cameraStep[a*6+i]*cameraStep[a*6+i];}
    }
    for(std::size_t i=0;i<l.points.size();++i){
        V3 negGradient{};
        for(int j=0;j<3;++j)negGradient[j]=-l.points[i].gp[j];
        for(const auto a:l.points[i].touched){
            const auto& cross=l.points[i].cross.at(a);
            for(int k=0;k<3;++k)for(int j=0;j<6;++j)
                negGradient[k]-=cross[j*3+k]*step.cameras[a][j];
        }
        step.points[i]=multiply3(inverses[i],negGradient);
        for(const auto value:step.points[i])step.squaredNorm+=value*value;
    }
    return std::isfinite(step.squaredNorm);
}
void applyStep(std::vector<ViewPose>& cameras,std::vector<ActivePoint>& points,const Delta& step,std::size_t fixed){
    double camScale=1.;
    for(const auto& c:step.cameras){
        double s=0.;for(double x:c)s+=x*x;
        s=std::sqrt(s);
        if(s>0.12)camScale=std::min(camScale,.12/s);
    }
    for(std::size_t j=0;j<step.cameras.size();++j){
        const auto& v=step.cameras[j];const auto r=rotExp({v[0]*camScale,v[1]*camScale,v[2]*camScale});
        auto& c=cameras[j+fixed];
        c.translation=add(multiply(r,c.translation),{v[3]*camScale,v[4]*camScale,v[5]*camScale});
        c.rotation=multiply(r,c.rotation);
    }
    for(std::size_t i=0;i<points.size();++i){
        Vec3 d{step.points[i][0],step.points[i][1],step.points[i][2]};
        const double maxMove=.25*std::max(1.,norm(points[i].seed.xyz));
        if(norm(d)>maxMove)d=mul(d,maxMove/norm(d));
        points[i].seed.xyz=add(points[i].seed.xyz,d);
    }
}
double totalCost(const std::vector<ViewPose>& cameras,const std::vector<ActivePoint>& points,double delta){
    double cost=0.;
    for(const auto& p:points)for(const auto& o:p.observations){
        const double r=residual(cameras[o.camera],p.seed.xyz,o.image);
        if(!std::isfinite(r))return std::numeric_limits<double>::infinity();
        cost+=huber(r,delta);
    }
    return cost;
}
std::vector<double> residuals(const std::vector<ViewPose>& cameras,const std::vector<ActivePoint>& points){
    std::vector<double> result;for(const auto& p:points)for(const auto& o:p.observations)result.push_back(residual(cameras[o.camera],p.seed.xyz,o.image));return result;
}
}
BundleResult bundleAdjust(std::span<const ViewPose> inputCameras,
    std::span<const BundleSeed> initialPoints,std::span<const TrackObservation> inputObservations,
    const BundleSettings& settings){
    if(!std::isfinite(settings.maxReprojectionError)||settings.maxReprojectionError<1e-6||settings.maxReprojectionError>.05||
       settings.maxIterations<0||settings.maxIterations>25)throw Error("Invalid bundle adjustment settings");
    cancellation::checkpoint();
    verifyCameras(inputCameras);
    const std::size_t fixed=settings.gauge==BundleGauge::PoseAndBaseline?1:2;
    const std::size_t cameraDof=6*(inputCameras.size()-fixed);
    // Includes two candidate copies and sparse point/camera cross blocks. Inputs are bounded before multiplication.
    if(initialPoints.size()>20000||inputObservations.size()>100000)throw Error("Out of bounds joint BA input");
    const std::size_t estimated=3*cameraDof*cameraDof*sizeof(double)+initialPoints.size()*1024+inputObservations.size()*512;
    if(estimated>settings.maxWorkingBytes)throw Error("Bundle working-set budget exceeded");
    BundleResult out;out.gauge=settings.gauge;out.estimatedWorkingBytes=estimated;out.termination="iteration_limit";out.cameras.assign(inputCameras.begin(),inputCameras.end());
    out.inputPoints=initialPoints.size();out.inputObservations=inputObservations.size();
    auto points=prepare(initialPoints,inputObservations,out.cameras,settings.maxReprojectionError,out.rejectedObservations);
    for(const auto& p:points)out.usableObservations+=p.observations.size();
    const auto initialErrors=residuals(out.cameras,points);
    out.medianErrorBefore=median(initialErrors);
    out.robustCostBefore=totalCost(out.cameras,points,settings.maxReprojectionError);
    double cost=out.robustCostBefore;
    double lambda=1e-3;
    const double gaugeBaseline=norm(sub(cameraCenter(out.cameras[0]),cameraCenter(out.cameras[1])));
    for(int iter=0;iter<settings.maxIterations;++iter){
        cancellation::checkpoint();
        ++out.attemptedIterations;
        const auto l=linearize(out.cameras,points,settings.maxReprojectionError,fixed);
        bool accepted=false;
        for(int tryLambda=0;tryLambda<7;++tryLambda){
            Delta delta;
            if(!computeStep(l,lambda,delta)){lambda*=8.;continue;}
            if(delta.squaredNorm<1e-18)break;
            auto candidateCameras=out.cameras;
            auto candidatePoints=points;
            applyStep(candidateCameras,candidatePoints,delta,fixed);
            if(fixed==1){
                const auto origin=cameraCenter(candidateCameras[0]);
                const double length=norm(sub(cameraCenter(candidateCameras[1]),origin));
                if(!std::isfinite(length)||length<1e-8){lambda*=8.;continue;}
                const double scale=gaugeBaseline/length;
                for(std::size_t k=1;k<candidateCameras.size();++k){
                    auto& c=candidateCameras[k];const auto centre=add(origin,mul(sub(cameraCenter(c),origin),scale));
                    c.translation=mul(multiply(c.rotation,centre),-1.);
                }
                for(auto& p:candidatePoints)p.seed.xyz=add(origin,mul(sub(p.seed.xyz,origin),scale));
            }
            const auto proposed=totalCost(candidateCameras,candidatePoints,settings.maxReprojectionError);
            if(proposed<cost-1e-15&&std::isfinite(proposed)){
                const double improvement=cost-proposed;
                out.cameras=std::move(candidateCameras);points=std::move(candidatePoints);
                cost=proposed;++out.acceptedSteps;accepted=true;
                lambda=std::max(1e-8,lambda*.4);
                if(improvement<1e-11*std::max(1.,cost))iter=settings.maxIterations;
                break;
            }
            lambda*=8.;
        }
        if(!accepted){out.termination="no_improving_step";break;}
    }
    out.robustCostAfter=totalCost(out.cameras,points,settings.maxReprojectionError);
    out.medianErrorAfter=median(residuals(out.cameras,points));
    if(!std::isfinite(out.robustCostAfter)||out.robustCostAfter>out.robustCostBefore+1e-12)
        throw Error("Joint BA failed to reduce robust reprojection cost");
    for(const auto& active:points){
        const auto& xyz=active.seed.xyz;
        if(!finite(xyz))throw Error("Invalid optimized 3D point");
        double maxAngle=0.;std::vector<double> errors;
        for(std::size_t i=0;i<active.observations.size();++i){
            const auto& u=active.observations[i];errors.push_back(residual(out.cameras[u.camera],xyz,u.image));
            for(std::size_t j=i+1;j<active.observations.size();++j)maxAngle=std::max(maxAngle,
                parallax(out.cameras[u.camera],out.cameras[active.observations[j].camera],xyz));
        }
        if(*std::max_element(errors.begin(),errors.end())>settings.maxReprojectionError*3.||maxAngle<.8)continue;
        out.points.push_back({active.seed,active.observations.size(),median(errors),maxAngle});
    }
    for(std::size_t c=0;c<out.cameras.size();++c){
        std::vector<double> errors;for(const auto& p:points)for(const auto& o:p.observations)if(o.camera==c)errors.push_back(residual(out.cameras[c],p.seed.xyz,o.image));
        out.medianCameraResiduals.push_back(errors.empty()?0.:median(errors));
    }
    if(out.points.size()<16)throw Error("Too few valid sparse points after joint BA");
    return out;
}
std::array<double,12> bundleCameraJacobian(Vec3 q){
    if(!finite(q)||q.z<=1e-8)throw Error("Invalid camera point for BA Jacobian");
    const double u=q.x/q.z,v=q.y/q.z;
    return {-u*v,1+u*u,-v,1/q.z,0.,-u/q.z,-1-v*v,u*v,u,0.,1/q.z,-v/q.z};
}
std::string bundleJson(const BundleResult& r){
    std::ostringstream out;out.imbue(std::locale::classic());out<<std::setprecision(14);
    out<<"{\"schema\":6,\"representation\":\"sparse_points\",\"optimization\":\"coupled_huber_schur_lm\",\"scale\":\"arbitrary\",\"anchorCamerasFixed\":2"
       <<",\"gaugeMode\":\""<<(r.gauge==BundleGauge::PoseAndBaseline?"pose_and_baseline":"two_anchors")<<"\",\"estimatedWorkingBytes\":"<<r.estimatedWorkingBytes<<",\"termination\":\""<<r.termination<<"\""
       <<",\"registeredCameras\":"<<r.cameras.size()<<",\"pointCount\":"<<r.points.size()
       <<",\"inputPoints\":"<<r.inputPoints<<",\"inputObservations\":"<<r.inputObservations
       <<",\"usableObservations\":"<<r.usableObservations<<",\"rejectedObservations\":"<<r.rejectedObservations
       <<",\"acceptedSteps\":"<<r.acceptedSteps<<",\"iterations\":"<<r.attemptedIterations
       <<",\"robustCostBefore\":"<<r.robustCostBefore<<",\"robustCostAfter\":"<<r.robustCostAfter
       <<",\"medianErrorBeforeNormalized\":"<<r.medianErrorBefore<<",\"medianErrorAfterNormalized\":"<<r.medianErrorAfter
       <<",\"cameras\":[";
    for(std::size_t i=0;i<r.cameras.size();++i){
        if(i)out<<',';
        const auto& c=r.cameras[i];
        out<<"{\"fixed\":"<<(i<(r.gauge==BundleGauge::PoseAndBaseline?1U:2U)?"true":"false")<<",\"rotation\":[";
        for(int j=0;j<9;++j){if(j)out<<',';out<<c.rotation[j];}
        out<<"],\"translation\":["<<c.translation.x<<','<<c.translation.y<<','<<c.translation.z<<"]}";
    }
    out<<"],\"points\":[";
    for(std::size_t i=0;i<r.points.size();++i){
        if(i)out<<',';
        const auto& p=r.points[i];
        out<<'['<<p.seed.xyz.x<<','<<p.seed.xyz.y<<','<<p.seed.xyz.z<<','<<int(p.seed.color[0])<<','<<int(p.seed.color[1])<<','<<int(p.seed.color[2])
           <<','<<p.medianErrorNormalized<<','<<p.maxParallaxDegrees<<','<<p.seed.track<<']';
    }
    out<<"]}";return out.str();
}
} // namespace rf
namespace {
std::size_t exactIndex(double x,std::size_t maximum){
    if(!std::isfinite(x)||std::floor(x)!=x||x<0||x>static_cast<double>(maximum))throw rf::Error("Invalid BA index");
    return static_cast<std::size_t>(x);
}
}
extern "C" char* rf_bundle_adjust_json(const double* cameras,std::size_t nc,
    const double* points,std::size_t np,const double* observations,std::size_t no,
    double threshold,int iterations) noexcept {
    try {
    std::string response;
    try{
        if(!cameras||!points||!observations||nc<3||nc>32||np<16||np>20000||no<32||no>100000)
            throw rf::Error("Invalid joint BA buffers");
        std::vector<rf::ViewPose> poses;poses.reserve(nc);
        for(std::size_t i=0;i<nc;++i){rf::ViewPose p;for(int j=0;j<9;++j)p.rotation[j]=cameras[i*12+j];
            p.translation={cameras[i*12+9],cameras[i*12+10],cameras[i*12+11]};poses.push_back(p);}
        std::vector<rf::BundleSeed> seeds;seeds.reserve(np);
        for(std::size_t i=0;i<np;++i){const double* p=points+7*i;rf::BundleSeed s;
            s.track=exactIndex(p[0],10000000);s.xyz={p[1],p[2],p[3]};
            for(int j=0;j<3;++j){if(!std::isfinite(p[4+j])||p[4+j]<0||p[4+j]>255)throw rf::Error("Invalid BA color");s.color[j]=static_cast<std::uint8_t>(p[4+j]);}
            seeds.push_back(s);}
        std::vector<rf::TrackObservation> rows;rows.reserve(no);
        for(std::size_t i=0;i<no;++i){const double* p=observations+7*i;rf::TrackObservation o;
            o.track=exactIndex(p[0],10000000);o.camera=exactIndex(p[1],nc-1);
            o.image={p[2],p[3]};
            for(int j=0;j<3;++j){if(!std::isfinite(p[4+j])||p[4+j]<0||p[4+j]>255)throw rf::Error("Invalid BA observation color");o.color[j]=static_cast<std::uint8_t>(p[4+j]);}
            rows.push_back(o);}
        rf::BundleSettings cfg;cfg.maxReprojectionError=threshold;cfg.maxIterations=iterations;
        response=rf::bundleJson(rf::bundleAdjust(poses,seeds,rows,cfg));
    }catch(const std::exception& e){
        response="{\"error\":\"";
        for(const unsigned char ch:std::string(e.what())){if(ch=='"'||ch=='\\')response+='\\';if(ch>=32)response+=static_cast<char>(ch);}
        response+="\"}";
    }
    auto* p=static_cast<char*>(std::malloc(response.size()+1));if(!p)return nullptr;
    std::memcpy(p,response.c_str(),response.size()+1);return p;
    } catch(...) {return nullptr;}
}
