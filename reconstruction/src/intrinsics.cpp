#include "realityforge/intrinsics.hpp"
#include <algorithm>
#include <cmath>

namespace rf {
void validateIntrinsicModel(const IntrinsicModel& m) {
    if(m.schema!=1||m.width<2||m.height<2||m.width>100000||m.height>100000||
       m.lensCropIdentity.empty()||m.lensCropIdentity.size()>1024) throw Error("Invalid intrinsic identity or dimensions");
    for(double v:m.parameters) if(!std::isfinite(v)) throw Error("Nonfinite intrinsic parameter");
    const auto& p=m.parameters;
    if(p[0]<1||p[1]<1||p[0]>1e7||p[1]>1e7||p[2]<-double(m.width)||p[2]>2.*m.width||
       p[3]<-double(m.height)||p[3]>2.*m.height) throw Error("Intrinsic pixel parameters out of bounds");
    for(std::size_t i=4;i<9;++i) if(std::abs(p[i])>2.)throw Error("Distortion outside supported model");
}
IntrinsicProjection projectIntrinsic(const IntrinsicModel& m,Vec2 q) {
    validateIntrinsicModel(m);
    if(!std::isfinite(q.x)||!std::isfinite(q.y)||std::hypot(q.x,q.y)>10.)throw Error("Intrinsic ray outside supported domain");
    const auto& p=m.parameters; const double x=q.x,y=q.y,r2=x*x+y*y,r4=r2*r2,r6=r4*r2;
    const double radial=1+p[4]*r2+p[5]*r4+p[8]*r6;
    const double xd=x*radial+2*p[6]*x*y+p[7]*(r2+2*x*x);
    const double yd=y*radial+p[6]*(r2+2*y*y)+2*p[7]*x*y;
    const double g=p[4]+2*p[5]*r2+3*p[8]*r4;
    IntrinsicProjection out;
    out.pixel={p[0]*xd+p[2],p[1]*yd+p[3]};
    out.parameterJacobian={xd,0,1,0,p[0]*x*r2,p[0]*x*r4,p[0]*2*x*y,p[0]*(r2+2*x*x),p[0]*x*r6,
        0,yd,0,1,p[1]*y*r2,p[1]*y*r4,p[1]*(r2+2*y*y),p[1]*2*x*y,p[1]*y*r6};
    out.rayJacobian={p[0]*(radial+2*x*x*g+2*p[6]*y+6*p[7]*x),p[0]*(2*x*y*g+2*p[6]*x+2*p[7]*y),
        p[1]*(2*x*y*g+2*p[6]*x+2*p[7]*y),p[1]*(radial+2*y*y*g+6*p[6]*y+2*p[7]*x)};
    if(!std::isfinite(out.pixel.x)||!std::isfinite(out.pixel.y))throw Error("Nonfinite intrinsic projection");
    return out;
}
Vec2 unprojectIntrinsic(const IntrinsicModel& m,Vec2 pixel,double tolerance) {
    validateIntrinsicModel(m);
    if(!std::isfinite(pixel.x)||!std::isfinite(pixel.y)||!std::isfinite(tolerance)||tolerance<=0||tolerance>.01)
        throw Error("Invalid unprojection request");
    Vec2 q{(pixel.x-m.parameters[2])/m.parameters[0],(pixel.y-m.parameters[3])/m.parameters[1]};
    for(int i=0;i<40;++i) {
        const auto p=projectIntrinsic(m,q);const double ex=p.pixel.x-pixel.x,ey=p.pixel.y-pixel.y;
        const auto& j=p.rayJacobian;const double det=j[0]*j[3]-j[1]*j[2];
        if(!std::isfinite(det)||det<=1e-8*m.parameters[0]*m.parameters[1])throw Error("Noninvertible intrinsic model");
        if(std::hypot(ex,ey)<=tolerance)return q;
        const Vec2 step{(j[3]*ex-j[1]*ey)/det,(-j[2]*ex+j[0]*ey)/det};
        bool improved=false;
        for(double scale=1.;scale>=1./1024;scale*=.5) {
            Vec2 candidate{q.x-scale*step.x,q.y-scale*step.y};
            if(std::hypot(candidate.x,candidate.y)>10.)continue;
            const auto v=projectIntrinsic(m,candidate).pixel;
            if(std::hypot(v.x-pixel.x,v.y-pixel.y)<std::hypot(ex,ey)){q=candidate;improved=true;break;}
        }
        if(!improved)throw Error("Intrinsic inversion failed to converge");
    }
    throw Error("Intrinsic inversion iteration limit");
}
IntrinsicObservability intrinsicObservability(const IntrinsicModel& m,const IntrinsicEvidence& e) {
    validateIntrinsicModel(m);
    if(std::none_of(m.refinable.begin(),m.refinable.end(),[](bool x){return x;}))return IntrinsicObservability::Fixed;
    if(!std::isfinite(e.normalizedRadiusSpan)||!std::isfinite(e.depthRatio)||e.normalizedRadiusSpan<0||e.depthRatio<1)
        throw Error("Invalid intrinsic observability evidence");
    if(e.independentViews<3||e.observations<30)return IntrinsicObservability::InsufficientViews;
    if(e.normalizedRadiusSpan<.2)return IntrinsicObservability::WeakSpatialExtent;
    if(e.depthRatio<1.2)return IntrinsicObservability::WeakDepthExtent;
    return IntrinsicObservability::Observable;
}
IntrinsicModel applyIntrinsicStep(const IntrinsicModel& m,const IntrinsicPrior& prior,std::span<const double,9> delta,IntrinsicObservability state) {
    validateIntrinsicModel(m); auto next=m;
    for(std::size_t i=0;i<9;++i) {
        if(!std::isfinite(delta[i]))throw Error("Nonfinite intrinsic step");
        if(!m.refinable[i]||state!=IntrinsicObservability::Observable)continue;
        if(!std::isfinite(prior.centre[i])||!std::isfinite(prior.sigma[i])||prior.sigma[i]<=0||
           !std::isfinite(prior.lower[i])||!std::isfinite(prior.upper[i])||prior.lower[i]>prior.upper[i])throw Error("Invalid intrinsic prior");
        next.parameters[i]=std::clamp(m.parameters[i]+delta[i],std::max(prior.lower[i],prior.centre[i]-3*prior.sigma[i]),
            std::min(prior.upper[i],prior.centre[i]+3*prior.sigma[i]));
    }
    validateIntrinsicModel(next);return next;
}
double intrinsicPriorCost(const IntrinsicModel& m,const IntrinsicPrior& prior) {
    validateIntrinsicModel(m);double cost=0;
    for(std::size_t i=0;i<9;++i)if(m.refinable[i]){
        if(!std::isfinite(prior.centre[i])||!std::isfinite(prior.sigma[i])||prior.sigma[i]<=0)throw Error("Invalid intrinsic prior");
        const double r=(m.parameters[i]-prior.centre[i])/prior.sigma[i];cost+=.5*r*r;
    }
    return cost;
}
}
