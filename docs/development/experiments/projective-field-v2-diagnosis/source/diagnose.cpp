#include "observer.hpp"
#include <iostream>
int main(int argc,char** argv){try{
 require(argc==4,"usage: diagnose ABS_SNAPSHOT DEPTH_ID FRESH_DIR");auto s=trace_test::readSnapshot(argv[1],argv[2]);Builder b(s);b.prepare();b.integrate();b.extract();diag::Observer observer(b);observer.captureBeforeCompact();b.compact();b.audit();require(std::chrono::duration<double>(std::chrono::steady_clock::now()-b.begin).count()<=SECONDS,"budget_exceeded: terminal deadline");require(b.w->facesN>0,"no_supported_surface: no success output");
 trace_test::freshDirectory(argv[3]);std::filesystem::path dir=argv[3];serialize(b,(dir/"authoritative").c_str());
 observer.uniqueEligibility();observer.tetraStages();observer.exhaustive();observer.ply(dir/"pre-witness.ply",false);observer.ply(dir/"post-witness.ply",true);observer.report(dir/"diagnostic.json");observer.witnessRows(dir/"rejected-face-witness-search.json");std::cout<<"observer finished; EXTERNAL BYTE PARITY STILL REQUIRED\n";return 0;
 }catch(const std::exception& e){std::cerr<<"FAIL CLOSED DIAGNOSTIC UNKNOWN: "<<e.what()<<'\n';return 1;}}
