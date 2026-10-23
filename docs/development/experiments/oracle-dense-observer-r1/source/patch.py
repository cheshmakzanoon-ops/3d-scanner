"""Exact source-anchor logging additions; no new photometric/normal queries."""

def replace(s,a,b):
    if s.count(a)!=1:raise RuntimeError('patch anchor not unique:'+a)
    return s.replace(a,b)
def patch(root,package):
    p=root/'reconstruction/src/surface.cpp';s=p.read_text()
    for a,b in [('#include "realityforge/progress.hpp"','#include "realityforge/progress.hpp"\n#include "ledger_observer.hpp"'),('        progress::advance(i+1);','        ledger_observer::prepared(i,sample,evidence,source);\n        progress::advance(i+1);'),('        mesh.observations.push_back({assigned,sample.camera,sample.x,sample.y,sourceId});','        mesh.observations.push_back({assigned,sample.camera,sample.x,sample.y,sourceId});\n        ledger_observer::membership(assigned,sourceId,w);'),('    std::unordered_set<FaceKey,FaceHash> faces;','    ledger_observer::accumulators(accum,mesh.vertices);\n    std::unordered_set<FaceKey,FaceHash> faces;')]:s=replace(s,a,b)
    p.write_text(s);(p.parent/'ledger_observer.hpp').write_bytes((package/'observer.hpp').read_bytes());(p.parent/'dense_witness.hpp').write_bytes((package/'witness.hpp').read_bytes())
    p=root/'reconstruction/src/dense.cpp';s=p.read_text()
    pairs=[
    ('#include "realityforge/depth_consistency.hpp"','#include "realityforge/depth_consistency.hpp"\n#include "dense_witness.hpp"'),
    ('float relativeDifference(double a,double b){return static_cast<float>(std::abs(a-b)/std::max(a,b));}','float relativeDifference(double a,double b){const float result=static_cast<float>(std::abs(a-b)/std::max(a,b));dense_witness::relative(result);return result;}'),
    ('    if(ix<0||iy<0||ix>=depth.width||iy>=depth.height||','    dense_witness::selected(ix,iy);\n    if(ix<0||iy<0||ix>=depth.width||iy>=depth.height||'),
    ('    return depth.depth[static_cast<std::size_t>(iy)*depth.width+ix];','    dense_witness::mapRead();\n    return depth.depth[static_cast<std::size_t>(iy)*depth.width+ix];'),
    ('    std::vector<double> scores;','    dense_witness::begin(settings,result.sourceViews);\n    std::vector<double> scores;'),
    ('                auto q=cameraPoint(views[src],world);if(q.z<=.01||!finite(q))continue;','                dense_witness::Attempt witness(ref,src,x,y,depth);\n                auto q=cameraPoint(views[src],world);witness.q(q);if(q.z<=.01||!finite(q)){witness.fail(1);continue;}'),
    ('                const auto continuous=settings.discontinuityAwareConsistency?','                witness.projection(sx,sy);\n                const auto continuous=settings.discontinuityAwareConsistency?'),
    ('                if(d<=0||relativeDifference(q.z,d)>settings.depthConsistencyFraction)continue;','                witness.depth(d);\n                if(d<=0||relativeDifference(q.z,d)>settings.depthConsistencyFraction){witness.fail(d<=0?2:3);continue;}'),
    ('                if(refCamera.z<=0||!finite(refCamera))continue;','                witness.reverse(back,refCamera);\n                if(refCamera.z<=0||!finite(refCamera)){witness.fail(4);continue;}'),
    ('                if(std::hypot(px-x,py-y)>step*1.6)continue;','                const auto observedRoundtrip=std::hypot(px-x,py-y);witness.reprojection(px,py,observedRoundtrip);\n                if(observedRoundtrip>step*1.6){witness.fail(5);continue;}'),
    ('                ++confirmations;','                ++confirmations;witness.fail(0);'),
    ('            if(result.verifiedSamples.size()<MAX_DENSE_SAMPLES)\n                result.verifiedSamples.push_back','            if(result.verifiedSamples.size()<MAX_DENSE_SAMPLES){\n                result.verifiedSamples.push_back'),
    ('normalConfirmations?static_cast<float>(normalConfidence):0.f,static_cast<std::uint8_t>(normalConfirmations)});','normalConfirmations?static_cast<float>(normalConfidence):0.f,static_cast<std::uint8_t>(normalConfirmations)});\n                dense_witness::sample(result.verifiedSamples.size()-1,ref,x,y,confirmations);\n            }'),
    ('    // Select representatives AFTER all views were verified.','    dense_witness::end();\n    // Select representatives AFTER all views were verified.')]
    for a,b in pairs:s=replace(s,a,b)
    p.write_text(s)
