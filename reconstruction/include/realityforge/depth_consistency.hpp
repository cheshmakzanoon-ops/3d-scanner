#pragma once
#include "realityforge/dense.hpp"
#include <algorithm>
#include <cmath>
#include <array>
namespace rf::consistency {
enum class Visibility { Unknown, Supported, Occluded, Inconsistent, Discontinuity, Outside };
struct Sample { Visibility classification{Visibility::Unknown}; double depth{},confidence{},x{},y{}; };
// Interpolate only within a continuous cell of independently observed samples.
// Missing support is unknown; it is never filled by a convenient nearby layer.
inline Sample sampleDepth(const DenseDepthMap& map,double x,double y,int step,int origin,double maximumRelativeSpread=.055){
    if(map.width<=0||map.height<=0||step<=0||origin<0||!std::isfinite(x)||!std::isfinite(y)||
       !std::isfinite(maximumRelativeSpread)||maximumRelativeSpread<0||maximumRelativeSpread>1||
       map.depth.size()!=static_cast<std::size_t>(map.width)*map.height||map.confidence.size()!=map.depth.size())return {};
    if(x<origin||y<origin||x>=map.width||y>=map.height)return {Visibility::Outside};
    const double gx=(x-origin)/step,gy=(y-origin)/step;
    if(gx>map.width||gy>map.height)return {Visibility::Outside};
    const int ix=origin+static_cast<int>(std::floor(gx))*step,iy=origin+static_cast<int>(std::floor(gy))*step;
    const double fx=(x-ix)/step,fy=(y-iy)/step;
    const std::array<double,4>weight{(1-fx)*(1-fy),fx*(1-fy),(1-fx)*fy,fx*fy};
    const std::array<int,4>xs{ix,ix+step,ix,ix+step},ys{iy,iy,iy+step,iy+step};
    double lo=1e300,hi=0,z=0,c=0;
    for(int k=0;k<4;++k){
        if(weight[k]<1e-12)continue;
        if(xs[k]<0||ys[k]<0||xs[k]>=map.width||ys[k]>=map.height)return {Visibility::Outside};
        const auto i=static_cast<std::size_t>(ys[k])*map.width+xs[k];const double d=map.depth[i],q=map.confidence[i];
        if(!std::isfinite(d)||!std::isfinite(q)||d<=0||q<0||q>1)return {};
        lo=std::min(lo,d);hi=std::max(hi,d);z+=weight[k]*d;c+=weight[k]*q;
    }
    if((hi-lo)/hi>maximumRelativeSpread)return {Visibility::Discontinuity};
    return {Visibility::Supported,z,c,x,y};
}
struct Verification { Visibility classification{Visibility::Unknown}; double relativeError{},reprojectionError{}; };
inline Verification verify(const Sample& sample,double predictedDepth,double reprojectionError,double relativeTolerance,double pixelTolerance){
    if(sample.classification!=Visibility::Supported)return {sample.classification};
    if(!std::isfinite(predictedDepth)||predictedDepth<=0||!std::isfinite(reprojectionError)||reprojectionError<0||
       !std::isfinite(relativeTolerance)||relativeTolerance<=0||!std::isfinite(pixelTolerance)||pixelTolerance<=0)return {};
    const double error=std::abs(predictedDepth-sample.depth)/std::max(predictedDepth,sample.depth);
    if(error>relativeTolerance)return {predictedDepth>sample.depth?Visibility::Occluded:Visibility::Inconsistent,error,reprojectionError};
    return {reprojectionError<=pixelTolerance?Visibility::Supported:Visibility::Inconsistent,error,reprojectionError};
}
}
