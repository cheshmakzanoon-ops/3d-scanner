#include "realityforge/core.hpp"
#include "realityforge/cancellation.hpp"
#include "realityforge/progress.hpp"
#include <algorithm>
#include <cmath>
#include <cstring>
#include <cstdlib>
#include <fstream>
#include <iomanip>
#include <limits>
#include <locale>
#include <numeric>
#include <random>
#include <sstream>

namespace rf {
namespace {
constexpr double pi = 3.14159265358979323846;
double dot(Vec3 a, Vec3 b) { return a.x*b.x+a.y*b.y+a.z*b.z; }
Vec3 sub(Vec3 a,Vec3 b) { return {a.x-b.x,a.y-b.y,a.z-b.z}; }
Vec3 add(Vec3 a,Vec3 b) { return {a.x+b.x,a.y+b.y,a.z+b.z}; }
Vec3 scale(Vec3 a,double s) { return {a.x*s,a.y*s,a.z*s}; }
Vec3 cross(Vec3 a,Vec3 b) { return {a.y*b.z-a.z*b.y,a.z*b.x-a.x*b.z,a.x*b.y-a.y*b.x}; }
double length(Vec3 a) { return std::sqrt(dot(a,a)); }
Vec3 unit(Vec3 a) { double n=length(a); if(n<1e-14) throw Error("Degenerate vector"); return scale(a,1/n); }
Vec3 column(const Mat3& a,int i) { return {a[i],a[3+i],a[6+i]}; }
void setColumn(Mat3& a,int i,Vec3 v) { a[i]=v.x;a[3+i]=v.y;a[6+i]=v.z; }

// Jacobi symmetric eigensolver for fixed-size matrices (3, 4, 9). Small and dependency-free.
// Columns of vectors are eigenvectors, ordered by ascending eigenvalue.
template<std::size_t N> struct Eigen { std::array<double,N> values; std::array<double,N*N> vectors; };
template<std::size_t N> Eigen<N> eigen(std::array<double,N*N> a) {
    std::array<double,N*N> v{}; for(std::size_t i=0;i<N;++i)v[i*N+i]=1;
    for(std::size_t sweep=0;sweep<70;++sweep) {
        double diagonal=0, off=0;
        for(std::size_t i=0;i<N;++i) { diagonal+=std::abs(a[i*N+i]); for(std::size_t j=i+1;j<N;++j)off+=std::abs(a[i*N+j]); }
        if(off<1e-13*(diagonal+1e-20))break;
        for(std::size_t p=0;p<N;++p)for(std::size_t q=p+1;q<N;++q) {
            double apq=a[p*N+q]; if(std::abs(apq)<1e-16*(diagonal+1e-20))continue;
            double tau=(a[q*N+q]-a[p*N+p])/(2*apq);
            double t=std::copysign(1.0,tau)/(std::abs(tau)+std::sqrt(1+tau*tau));
            double c=1/std::sqrt(1+t*t),s=t*c;
            double app=a[p*N+p],aqq=a[q*N+q];
            a[p*N+p]=app-t*apq;a[q*N+q]=aqq+t*apq;a[p*N+q]=a[q*N+p]=0;
            for(std::size_t k=0;k<N;++k) {
                if(k!=p&&k!=q){double x=a[k*N+p],y=a[k*N+q];a[k*N+p]=a[p*N+k]=c*x-s*y;a[k*N+q]=a[q*N+k]=s*x+c*y;}
                double x=v[k*N+p],y=v[k*N+q];v[k*N+p]=c*x-s*y;v[k*N+q]=s*x+c*y;
            }
        }
    }
    std::array<std::size_t,N> order{};std::iota(order.begin(),order.end(),0);
    std::sort(order.begin(),order.end(),[&](auto a1,auto b1){return a[a1*N+a1]<a[b1*N+b1];});
    Eigen<N> out{};
    for(std::size_t j=0;j<N;++j){out.values[j]=a[order[j]*N+order[j]];for(std::size_t i=0;i<N;++i)out.vectors[i*N+j]=v[i*N+order[j]];}
    return out;
}
struct Svd { Mat3 u{},v{}; std::array<double,3> s{}; };
Svd svd(const Mat3& a) {
    auto e=eigen<3>(multiply(transpose(a),a)); Svd out;
    for(int i=0;i<3;++i){out.s[i]=std::sqrt(std::max(0.0,e.values[2-i]));setColumn(out.v,i,column(e.vectors,2-i));}
    auto u0=unit(multiply(a,column(out.v,0)));
    auto raw=multiply(a,column(out.v,1));auto u1=unit(sub(raw,scale(u0,dot(raw,u0))));
    auto u2=cross(u0,u1);setColumn(out.u,0,u0);setColumn(out.u,1,u1);setColumn(out.u,2,u2);
    // Third singular value is discarded for essential matrices. Choose proper rotations.
    if(determinant(out.v)<0)setColumn(out.v,2,scale(column(out.v,2),-1));
    return out;
}
Mat3 essentialConstraint(const Mat3& a) {
    auto s=svd(a);double mean=(s.s[0]+s.s[1])/2;
    Mat3 diagonal{mean,0,0,0,mean,0,0,0,0};
    return multiply(multiply(s.u,diagonal),transpose(s.v));
}
struct Normalization { Mat3 matrix; Vec2 center; double factor; };
Normalization normalizer(std::span<const Match> matches, const std::vector<std::size_t>& ids,bool second) {
    Vec2 center{};for(auto id:ids){auto p=second?matches[id].b:matches[id].a;center.x+=p.x;center.y+=p.y;}
    center.x/=static_cast<double>(ids.size());center.y/=static_cast<double>(ids.size());double d=0;
    for(auto id:ids){auto p=second?matches[id].b:matches[id].a;d+=std::hypot(p.x-center.x,p.y-center.y);}
    d/=static_cast<double>(ids.size());if(d<1e-7)throw Error("Insufficient feature spread");
    double s=std::sqrt(2.0)/d;return {{s,0,-s*center.x,0,s,-s*center.y,0,0,1},center,s};
}
Mat3 fitEssential(std::span<const Match> matches,const std::vector<std::size_t>& ids) {
    auto na=normalizer(matches,ids,false),nb=normalizer(matches,ids,true);
    std::array<double,81> ata{};
    for(auto id:ids) {
        auto m=matches[id];double x=(m.a.x-na.center.x)*na.factor,y=(m.a.y-na.center.y)*na.factor;
        double u=(m.b.x-nb.center.x)*nb.factor,v=(m.b.y-nb.center.y)*nb.factor;
        std::array<double,9> row{u*x,u*y,u,v*x,v*y,v,x,y,1};
        for(int i=0;i<9;++i)for(int j=0;j<9;++j)ata[i*9+j]+=row[i]*row[j];
    }
    auto ev=eigen<9>(ata);
    if(ev.values[1]<1e-10*std::max(ev.values[8],1e-20))throw Error("Planar or collinear sample");
    Mat3 f{};for(int i=0;i<9;++i)f[i]=ev.vectors[i*9];
    f=multiply(multiply(transpose(nb.matrix),f),na.matrix);
    return essentialConstraint(f);
}
double sampson(const Mat3& e,const Match& m) {
    Vec3 a{m.a.x,m.a.y,1},b{m.b.x,m.b.y,1};auto ea=multiply(e,a),eb=multiply(transpose(e),b);
    double top=dot(b,ea),bottom=ea.x*ea.x+ea.y*ea.y+eb.x*eb.x+eb.y*eb.y;
    return bottom>1e-20?top*top/bottom:std::numeric_limits<double>::infinity();
}
double median(std::vector<double> values) {
    if(values.empty())return 0;
    auto n=values.size()/2;std::nth_element(values.begin(),values.begin()+static_cast<std::ptrdiff_t>(n),values.end());return values[n];
}
std::vector<std::size_t> inliers(std::span<const Match> m,const Mat3& e,double threshold) {
    std::vector<std::size_t> out; for(std::size_t i=0;i<m.size();++i)if(sampson(e,m[i])<threshold*threshold)out.push_back(i);return out;
}
void validateResult(const Result& r) {
    if(r.representation!=PointRepresentation::SparsePoints && r.representation!=PointRepresentation::DensePoints)throw Error("Unsupported point representation");
    if(r.points.empty())throw Error("No reconstructed points to export");
    if(r.points.size()>10000000)throw Error("Point count exceeds export limit");
    for(auto& p:r.points)if(!std::isfinite(p.xyz.x)||!std::isfinite(p.xyz.y)||!std::isfinite(p.xyz.z)||length(p.xyz)>1e20)throw Error("Non-finite export geometry");
}
void u32(std::ostream& s,std::uint32_t v) {for(int i=0;i<4;++i)s.put(static_cast<char>((v>>(8*i))&255));}
void f32(std::ostream& s,float v) {std::uint32_t bits;std::memcpy(&bits,&v,4);u32(s,bits);}
} // namespace
Mat3 identity(){return {1,0,0,0,1,0,0,0,1};}
Mat3 transpose(const Mat3& a){return {a[0],a[3],a[6],a[1],a[4],a[7],a[2],a[5],a[8]};}
Mat3 multiply(const Mat3& a,const Mat3& b){Mat3 o{};for(int i=0;i<3;++i)for(int j=0;j<3;++j)for(int k=0;k<3;++k)o[i*3+j]+=a[i*3+k]*b[k*3+j];return o;}
Vec3 multiply(const Mat3& a,Vec3 b){return {a[0]*b.x+a[1]*b.y+a[2]*b.z,a[3]*b.x+a[4]*b.y+a[5]*b.z,a[6]*b.x+a[7]*b.y+a[8]*b.z};}
double determinant(const Mat3& a){return a[0]*(a[4]*a[8]-a[5]*a[7])-a[1]*(a[3]*a[8]-a[5]*a[6])+a[2]*(a[3]*a[7]-a[4]*a[6]);}
Point triangulate(const Match& m,const Mat3& r,Vec3 t) {
    double p[12]={r[0],r[1],r[2],t.x,r[3],r[4],r[5],t.y,r[6],r[7],r[8],t.z};
    std::array<double,16>a{ -1,0,m.a.x,0, 0,-1,m.a.y,0, 0,0,0,0, 0,0,0,0 };
    for(int k=0;k<4;++k){a[8+k]=m.b.x*p[8+k]-p[k];a[12+k]=m.b.y*p[8+k]-p[4+k];}
    std::array<double,16>ata{};for(int i=0;i<4;++i)for(int j=0;j<4;++j)for(int k=0;k<4;++k)ata[i*4+j]+=a[k*4+i]*a[k*4+j];
    auto eig=eigen<4>(ata);double w=eig.vectors[12];if(std::abs(w)<1e-12)throw Error("Point at infinity");
    Vec3 x{eig.vectors[0]/w,eig.vectors[4]/w,eig.vectors[8]/w};auto x2=add(multiply(r,x),t);
    if(x.z<=1e-8||x2.z<=1e-8)throw Error("Point is behind camera");
    double e1=std::hypot(x.x/x.z-m.a.x,x.y/x.z-m.a.y),e2=std::hypot(x2.x/x2.z-m.b.x,x2.y/x2.z-m.b.y);
    auto ray1=unit(Vec3{m.a.x,m.a.y,1}),ray2=unit(multiply(transpose(r),Vec3{m.b.x,m.b.y,1}));
    double angle=std::acos(std::clamp(dot(ray1,ray2),-1.0,1.0))*180/pi;
    return {x,m.color,std::max(e1,e2),angle,0};
}
Result reconstruct(std::span<const Match> matches,const Settings& s) {
    if(matches.size()<std::max(std::size_t{8},s.minInliers))throw Error("Too few matches; capture overlapping, textured views");
    if(matches.size()>20000)throw Error("Too many matches for bounded preview");
    if(!std::isfinite(s.threshold)||s.threshold<=0||s.threshold>0.05||s.iterations<1||s.iterations>100000||
       matches.size()*static_cast<std::size_t>(s.iterations)>50000000||!std::isfinite(s.minParallaxDegrees)||s.minParallaxDegrees<=0||s.minParallaxDegrees>30||s.minInliers<8)
        throw InitializationError(InitializationFailure::InvalidInput,"Invalid reconstruction settings or excessive work budget");
    for(auto& m:matches)for(double x:{m.a.x,m.a.y,m.b.x,m.b.y})if(!std::isfinite(x)||std::abs(x)>100)throw Error("Invalid normalized image coordinates");
    cancellation::checkpoint();
    // Reject collinear image support before sampling. A count alone is not rank.
    for(bool second:{false,true}){
        Vec2 center{};for(auto& m:matches){auto p=second?m.b:m.a;center.x+=p.x;center.y+=p.y;}
        center.x/=matches.size();center.y/=matches.size();double xx=0,xy=0,yy=0;
        for(auto& m:matches){auto p=second?m.b:m.a;double x=p.x-center.x,y=p.y-center.y;xx+=x*x;xy+=x*y;yy+=y*y;}
        if(xx+yy<1e-12||xx*yy-xy*xy<=1e-10*(xx+yy)*(xx+yy))throw InitializationError(InitializationFailure::CollinearFeatures,"Collinear image support");
    }
    // A calibrated pure rotation explains bearing directions without a baseline.
    Mat3 correlation{};
    for(auto& m:matches){auto a=unit(Vec3{m.a.x,m.a.y,1}),b=unit(Vec3{m.b.x,m.b.y,1});double av[3]{a.x,a.y,a.z},bv[3]{b.x,b.y,b.z};for(int i=0;i<3;++i)for(int j=0;j<3;++j)correlation[i*3+j]+=bv[i]*av[j];}
    try {auto d=svd(correlation);auto rotation=multiply(d.u,transpose(d.v));std::size_t supported=0;
        for(auto& m:matches){auto q=multiply(rotation,Vec3{m.a.x,m.a.y,1});if(q.z>0&&std::hypot(q.x/q.z-m.b.x,q.y/q.z-m.b.y)<s.threshold*.5)++supported;}
        if(supported>=matches.size()*.95)throw InitializationError(InitializationFailure::RotationOnly,"Rotation-only or unobservable baseline");
    } catch(const InitializationError&){throw;}catch(const Error&){}
    std::mt19937 rng(s.seed);std::uniform_int_distribution<std::size_t> distribution(0,matches.size()-1);
    Mat3 best{};std::vector<std::size_t>bestInliers;double bestResidual=std::numeric_limits<double>::infinity();int validHypotheses=0;
    // Sparse work-unit tile0 is initializer RANSAC hypotheses; total is
    // unknown because statistically sufficient consensus can terminate early.
    progress::begin(progress::Stage::Sparse,0,0);
    for(int trial=0;trial<s.iterations;++trial) {
        cancellation::checkpoint();
        std::vector<std::size_t>ids;ids.reserve(8);while(ids.size()<8){auto i=distribution(rng);if(std::find(ids.begin(),ids.end(),i)==ids.end())ids.push_back(i);}
        try{auto e=fitEssential(matches,ids);++validHypotheses;auto good=inliers(matches,e,s.threshold);double residual=0;for(auto i:good)residual+=sampson(e,matches[i]);
            if(good.size()>bestInliers.size()||(good.size()==bestInliers.size()&&residual<bestResidual)){best=e;bestResidual=residual;bestInliers=std::move(good);}}
        catch(const cancellation::Cancelled&){throw;}catch(const Error&){} // Degenerate samples are expected in robust sampling.
        progress::advance(static_cast<std::uint64_t>(trial+1));
        if(trial>=80&&bestInliers.size()>matches.size()*0.98)break;
    }
    if(bestInliers.size()<s.minInliers)throw InitializationError(validHypotheses==0?InitializationFailure::PlanarAmbiguity:InitializationFailure::InsufficientConsensus,
        validHypotheses==0?"Planar or rank-deficient two-view geometry":"Insufficient verified consensus; repeated texture or mismatches remain possible");
    // Refit on consensus, without accepting a large loss of support.
    for(int round=0;round<3;++round){cancellation::checkpoint();try{auto e=fitEssential(matches,bestInliers);auto good=inliers(matches,e,s.threshold);if(good.size()<bestInliers.size()*0.9)break;best=e;bestInliers=std::move(good);}catch(const Error&){break;}}
    auto decomp=svd(best);Mat3 w{0,-1,0,1,0,0,0,0,1};
    std::array<Mat3,2>rotations{multiply(multiply(decomp.u,w),transpose(decomp.v)),multiply(multiply(decomp.u,transpose(w)),transpose(decomp.v))};
    Result out;out.inputMatches=matches.size();out.epipolarInliers=bestInliers.size();
    for(auto r:rotations)for(int sign:{-1,1}) {
        cancellation::checkpoint();
        Vec3 t=scale(column(decomp.u,2),sign);std::vector<Point>points;
        for(auto i:bestInliers)try{if(i%128==0)cancellation::checkpoint();auto p=triangulate(matches[i],r,t);p.matchIndex=i;if(p.error<3*s.threshold&&p.parallax>=s.minParallaxDegrees&&length(p.xyz)<10000)points.push_back(p);}catch(const cancellation::Cancelled&){throw;}catch(const Error&){}
        if(points.size()>out.points.size()){out.rotation=r;out.translation=t;out.points=std::move(points);}
    }
    if(out.points.size()<s.minInliers||out.points.size()<bestInliers.size()*0.65)throw InitializationError(InitializationFailure::WeakBaseline,"Weak baseline or ambiguous pose; move sideways and keep the object in view");
    std::vector<double>errors,angles;for(auto& p:out.points){errors.push_back(p.error);angles.push_back(p.parallax);}
    out.medianError=median(errors);out.medianParallax=median(angles);
    return out;
}
Quality quality(std::span<const std::uint8_t> pixels,int width,int height,int stride) {
    if(width<3||height<3||stride<width||width>32768||height>32768||pixels.size()<static_cast<std::size_t>(stride)*static_cast<std::size_t>(height))throw Error("Invalid grayscale buffer");
    double sum=0,sum2=0,lap=0,lap2=0,dark=0,bright=0,n=static_cast<double>(width)*height;
    for(int y=0;y<height;++y)for(int x=0;x<width;++x){double v=pixels[static_cast<std::size_t>(y)*stride+x];sum+=v;sum2+=v*v;dark+=v<8;bright+=v>247;}
    for(int y=1;y<height-1;++y)for(int x=1;x<width-1;++x){auto i=static_cast<std::size_t>(y)*stride+x;double v=4.0*pixels[i]-pixels[i-1]-pixels[i+1]-pixels[i-stride]-pixels[i+stride];lap+=v;lap2+=v*v;}
    double count=static_cast<double>(width-2)*(height-2);return {sum/n,std::sqrt(std::max(0.0,sum2/n-(sum/n)*(sum/n))),std::max(0.0,lap2/count-(lap/count)*(lap/count)),dark/n,bright/n};
}
std::string resultJson(const Result& r) {
    if(r.representation!=PointRepresentation::SparsePoints)throw Error("Dense points are not a two-view reconstruction result; use typed point export");
    std::ostringstream o;o.imbue(std::locale::classic());o<<std::setprecision(12);
    o<<"{\"schema\":1,\"representation\":\"sparse_points\",\"scale\":\"arbitrary\",\"registeredCameras\":2,\"inputMatches\":"<<r.inputMatches<<",\"inliers\":"<<r.epipolarInliers<<",\"pointCount\":"<<r.points.size()<<",\"medianErrorNormalized\":"<<r.medianError<<",\"medianParallaxDegrees\":"<<r.medianParallax<<",\"rotation\":[";
    for(std::size_t i=0;i<9;++i){if(i)o<<',';o<<r.rotation[i];}o<<"],\"translation\":["<<r.translation.x<<','<<r.translation.y<<','<<r.translation.z<<"],\"points\":[";
    for(std::size_t i=0;i<r.points.size();++i){auto&p=r.points[i];if(i)o<<',';o<<'['<<p.xyz.x<<','<<p.xyz.y<<','<<p.xyz.z<<','<<int(p.color[0])<<','<<int(p.color[1])<<','<<int(p.color[2])<<','<<p.error<<','<<p.parallax<<','<<p.matchIndex<<']';}o<<"]}";return o.str();
}
void writePly(const Result& r,const std::string& path) {
    validateResult(r);std::ofstream o(path);if(!o)throw Error("Cannot open PLY destination");o.imbue(std::locale::classic());o<<std::setprecision(9);
    o<<"ply\nformat ascii 1.0\ncomment RealityForge "<<(r.representation==PointRepresentation::DensePoints?"observed dense points":"sparse reconstruction")
     <<"; arbitrary scale; x-right y-down z-forward\ncomment representation "<<(r.representation==PointRepresentation::DensePoints?"dense_points":"sparse_points")<<"\nelement vertex "<<r.points.size()<<"\nproperty float x\nproperty float y\nproperty float z\nproperty uchar red\nproperty uchar green\nproperty uchar blue\nend_header\n";
    for(auto&p:r.points)o<<p.xyz.x<<' '<<p.xyz.y<<' '<<p.xyz.z<<' '<<int(p.color[0])<<' '<<int(p.color[1])<<' '<<int(p.color[2])<<'\n';
    o.flush();if(!o)throw Error("PLY write failed; storage may be full");
}
void writeGlb(const Result& r,const std::string& path) {
    validateResult(r);std::array<float,3>lo{INFINITY,INFINITY,INFINITY},hi{-INFINITY,-INFINITY,-INFINITY};
    for(auto&p:r.points){std::array<float,3>x{static_cast<float>(p.xyz.x),static_cast<float>(-p.xyz.y),static_cast<float>(-p.xyz.z)};for(int j=0;j<3;++j){lo[j]=std::min(lo[j],x[j]);hi[j]=std::max(hi[j],x[j]);}}
    auto n=static_cast<std::uint32_t>(r.points.size()),positionBytes=n*12,colorBytes=n*4,binBytes=positionBytes+colorBytes;
    std::ostringstream j;j.imbue(std::locale::classic());j<<std::setprecision(9);
    j<<"{\"asset\":{\"version\":\"2.0\",\"generator\":\"RealityForge point export\"},\"extras\":{\"representation\":\""
     <<(r.representation==PointRepresentation::DensePoints?"dense_points":"sparse_points")
     <<"\",\"scale\":\"arbitrary\",\"notSurfaceMesh\":true},\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\""
     <<(r.representation==PointRepresentation::DensePoints?"Observed dense points":"Sparse diagnostic points")<<"\"}],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":0,\"COLOR_0\":1},\"mode\":0}]}],\"buffers\":[{\"byteLength\":"<<binBytes<<"}],\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":"<<positionBytes<<",\"target\":34962},{\"buffer\":0,\"byteOffset\":"<<positionBytes<<",\"byteLength\":"<<colorBytes<<",\"target\":34962}],\"accessors\":[{\"bufferView\":0,\"componentType\":5126,\"count\":"<<n<<",\"type\":\"VEC3\",\"min\":["<<lo[0]<<','<<lo[1]<<','<<lo[2]<<"],\"max\":["<<hi[0]<<','<<hi[1]<<','<<hi[2]<<"]},{\"bufferView\":1,\"componentType\":5121,\"normalized\":true,\"count\":"<<n<<",\"type\":\"VEC4\"}]}";
    std::string json=j.str();while(json.size()%4)json+=' ';std::ofstream out(path,std::ios::binary);if(!out)throw Error("Cannot open GLB destination");
    u32(out,0x46546C67);u32(out,2);u32(out,static_cast<std::uint32_t>(12+8+json.size()+8+binBytes));u32(out,static_cast<std::uint32_t>(json.size()));u32(out,0x4E4F534A);out.write(json.data(),static_cast<std::streamsize>(json.size()));u32(out,binBytes);u32(out,0x004E4942);
    for(auto&p:r.points){f32(out,static_cast<float>(p.xyz.x));f32(out,static_cast<float>(-p.xyz.y));f32(out,static_cast<float>(-p.xyz.z));}
    for(auto&p:r.points){for(auto c:p.color)out.put(static_cast<char>(c));out.put(static_cast<char>(255));}out.flush();if(!out)throw Error("GLB write failed; storage may be full");
}
} // namespace rf
extern "C" char* rf_reconstruct_json(const double* data,std::size_t count,double threshold,const char* glb,const char* ply) noexcept {
    try { // Error JSON allocation and serialization are also inside the no-throw boundary.
    std::string text;
    try {
        if(!data||count>20000)throw rf::Error("Invalid input buffer");
        std::vector<rf::Match>matches;matches.reserve(count);
        for(std::size_t i=0;i<count;++i){const auto*p=data+i*7;rf::Match m{{p[0],p[1]},{p[2],p[3]},{}};for(int c=0;c<3;++c){if(!std::isfinite(p[4+c]))throw rf::Error("Non-finite color");m.color[c]=static_cast<std::uint8_t>(std::clamp(p[4+c],0.0,255.0));}matches.push_back(m);}
        rf::Settings s;s.threshold=threshold;auto result=rf::reconstruct(matches,s);if(glb&&*glb)rf::writeGlb(result,glb);if(ply&&*ply)rf::writePly(result,ply);text=rf::resultJson(result);
    }catch(const std::exception&e){text="{\"error\":\"";for(char c:std::string(e.what())){if(c=='"'||c=='\\')text+='\\';if(static_cast<unsigned char>(c)>=32)text+=c;}text+="\"";
        if(auto failure=dynamic_cast<const rf::InitializationError*>(&e)){
            const char* code="INVALID_INPUT";
            switch(failure->code){case rf::InitializationFailure::InvalidInput:break;
                case rf::InitializationFailure::CollinearFeatures:code="COLLINEAR_FEATURES";break;
                case rf::InitializationFailure::PlanarAmbiguity:code="PLANAR_AMBIGUITY";break;
                case rf::InitializationFailure::RotationOnly:code="ROTATION_ONLY";break;
                case rf::InitializationFailure::InsufficientConsensus:code="INSUFFICIENT_CONSENSUS";break;
                case rf::InitializationFailure::WeakBaseline:code="WEAK_BASELINE";break;}
            text+=",\"errorCode\":\"";text+=code;text+="\"";
        }text+="}";}
    auto*p=static_cast<char*>(std::malloc(text.size()+1));if(!p)return nullptr;std::memcpy(p,text.c_str(),text.size()+1);return p;
    }catch(...){return nullptr;}
}
extern "C" void rf_free(void* p) noexcept {std::free(p);}
