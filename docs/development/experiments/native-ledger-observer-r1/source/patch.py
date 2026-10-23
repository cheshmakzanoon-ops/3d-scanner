import pathlib

def patch(root,header):
    p=root/'reconstruction/src/surface.cpp';s=p.read_text()
    replacements=[('#include "realityforge/progress.hpp"','#include "realityforge/progress.hpp"\n#include "ledger_observer.hpp"'),('        progress::advance(i+1);','        ledger_observer::prepared(i,sample,evidence,source);\n        progress::advance(i+1);'),('        mesh.observations.push_back({assigned,sample.camera,sample.x,sample.y,sourceId});','        mesh.observations.push_back({assigned,sample.camera,sample.x,sample.y,sourceId});\n        ledger_observer::membership(assigned,sourceId,w);'),('    std::unordered_set<FaceKey,FaceHash> faces;','    ledger_observer::accumulators(accum,mesh.vertices);\n    std::unordered_set<FaceKey,FaceHash> faces;')]
    for before,after in replacements:
        if s.count(before)!=1:raise RuntimeError('patch anchor not unique:'+before)
        s=s.replace(before,after)
    p.write_text(s);(p.parent/'ledger_observer.hpp').write_bytes(header.read_bytes())
