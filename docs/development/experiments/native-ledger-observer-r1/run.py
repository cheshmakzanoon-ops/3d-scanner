#!/usr/bin/env python3
import sys
sys.dont_write_bytecode=True
import os,pathlib,hashlib,json,tarfile,shutil
ROOT=pathlib.Path(__file__).resolve().parent

def need(b,m):
    if not b:raise RuntimeError(m)
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
    need(len(sys.argv)==4,'usage: python -B run.py REPO FRESH_OUTPUT CMAKE_EXECUTABLE');cmake=str(pathlib.Path(sys.argv[3]).resolve())
    repo=pathlib.Path(sys.argv[1]).resolve();out=pathlib.Path(sys.argv[2]).resolve();need(not out.exists() and not out.is_relative_to(ROOT),'fresh external output required')
    manifest=sha(ROOT/'SHA256SUMS');pins={}
    for line in (ROOT/'SHA256SUMS').read_text().splitlines():
        h,n=line.split('  ',1);p=(ROOT/n).resolve();need(p.is_relative_to(ROOT) and p.is_file() and sha(p)==h,'PACKAGE_IDENTITY:'+n);pins[n]=h
    need({'source/observer.hpp','source/harness.cpp','source/bounded.py','source/patch.py','source/audit.py','source/plane-generator.inc','config.json','run.py'}<=pins.keys(),'missing source')
    sys.path.insert(0,str(ROOT/'source'));from bounded import run
    from patch import patch
    from audit import audit
    cfg=json.loads((ROOT/'config.json').read_text());out.mkdir();env=os.environ.copy()
    for k in ('LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT','CXX','CC','CXXFLAGS','CFLAGS','LDFLAGS','CPPFLAGS','CMAKE_TOOLCHAIN_FILE','CMAKE_PREFIX_PATH','PYTHONOPTIMIZE'):env.pop(k,None)
    env.update(PYTHONDONTWRITEBYTECODE='1',LC_ALL='C',OMP_NUM_THREADS='1')
    calls=[]
    def call(argv,name,seconds=60,extra=None):
        e=env.copy();e.update(extra or {});calls.append(run(list(map(str,argv)),out/(name+'.log'),seconds,e))
    try:
        for name,path in [('compiler','/usr/bin/c++'),('cmake',cmake),('prlimit','/usr/bin/prlimit')]:
            need(pathlib.Path(path).is_file(),'tool missing:'+path)
            call([path,'--version'],name+'-version')
        archive=out/'pinned-source.tar';call(['/usr/bin/git','-C',repo,'archive','--format=tar','-o',archive,cfg['sourceCommit'],'CMakeLists.txt','reconstruction'],'archive')
        need(archive.stat().st_size<=134217728,'SOURCE_ARCHIVE_BUDGET')
        sources={};builds={};bins={};libs={}
        for arm in ('baseline','observer'):
            src=out/(arm+'-source');src.mkdir()
            with tarfile.open(archive) as t:t.extractall(src,filter='data')
            parent=src/'reconstruction/tests/surface_fusion_test.cpp';need(parent.read_bytes()==(ROOT/'source/generator-parent.cpp').read_bytes(),'GENERATOR_PARENT_IDENTITY')
            text=parent.read_text();begin=text.index('std::pair<std::vector<rf::DenseView>,rf::DenseResult> plane(');end=text.index('\nstd::pair',begin+1);need(text[begin:end]+'\n'==(ROOT/'source/plane-generator.inc').read_text(),'GENERATOR_EXTRACTION')
            if arm=='observer':patch(src,ROOT/'source/observer.hpp')
            build=out/(arm+'-build');call([cmake,'-S',src,'-B',build,'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_CXX_COMPILER=/usr/bin/c++','-DBUILD_TESTING=OFF'],arm+'-configure',900)
            call([cmake,'--build',build,'--target','realityforge','--parallel','2'],arm+'-build',900)
            binary=out/(arm+'-harness');call(['/usr/bin/c++','-std=c++20','-O2','-I'+str(src/'reconstruction/include'),'-I'+str(ROOT/'source'),ROOT/'source/harness.cpp','-L'+str(build),'-Wl,-rpath,'+str(build),'-lrealityforge','-o',binary],arm+'-compile',120)
            call(['/usr/bin/ldd',binary],arm+'-ldd');lib=(build/'librealityforge.so').resolve();need(('librealityforge.so => '+str(lib)) in (out/(arm+'-ldd.log')).read_text(),'LOADER_BINDING')
            sources[arm]=src;builds[arm]=build;bins[arm]=binary;libs[arm]=lib
            # Freeze input identity before either measured fusion run.
            if arm=='baseline':
                inputs={}
                for case in cfg['cases']:
                    target=out/('input-'+case);call(['/usr/bin/prlimit','--as=1073741824','--cpu=60','--fsize=16777216','--',binary,'prepare',case,target],'prepare-'+case,60);inputs[case]=sha(target/'inputs.bits')
                (out/'premeasurement.json').write_text(json.dumps({'packageManifest':manifest,'config':sha(ROOT/'config.json'),'archive':sha(archive),'sourceCommit':cfg['sourceCommit'],'inputs':inputs},indent=2)+'\n')
        results={}
        for case in cfg['cases']:
            for arm in ('baseline','observer'):
                target=out/(arm+'-'+case);extra={'RF_LEDGER_OBSERVER_DIR':str(target)} if arm=='observer' else {}
                call(['/usr/bin/prlimit','--as=1073741824','--cpu=60','--fsize=16777216','--',bins[arm],'run',case,target],arm+'-'+case,60,extra)
                need(sha(target/'inputs.bits')==inputs[case] and sha(target/'after/inputs.bits')==inputs[case],'INPUT_MUTATION')
            base=out/('baseline-'+case);obs=out/('observer-'+case)
            for name in ('inputs.bits','mesh.bits','mesh.json','mesh.ply','mesh.glb','expected-prepared.tsv','normal-audit.json','after/inputs.bits'):
                need((base/name).is_file() and (obs/name).is_file() and sha(base/name)==sha(obs/name),'PARITY:'+case+':'+name)
            need(sha(obs/'prepared.tsv')==sha(base/'expected-prepared.tsv'),'PREPARED_NATIVE_REGENERATION_PARITY')
            results[case]=audit(obs)
            need(sum(p.stat().st_size for p in obs.rglob('*') if p.is_file())<=cfg['maxCaseOutputBytes'],'OUTPUT_BUDGET')
        for n,h in pins.items():need(sha(ROOT/n)==h,'PACKAGE_CHANGED')
        need(sha(ROOT/'SHA256SUMS')==manifest,'MANIFEST_CHANGED')
        artifacts={str(p.relative_to(out)):sha(p) for p in out.rglob('*') if p.is_file() and not any(x.endswith('-build') or x.endswith('-source') for x in p.relative_to(out).parts)}
        receipt={'status':'PASS_DIRECT_DEPTH_OBSERVER_ONLY','photo':'NOT_RUN','front':'NOT_RUN','reciprocalWitnessRegeneration':'NOT_VERIFIED','sourceCommit':cfg['sourceCommit'],'manifest':manifest,'results':results,'calls':calls,'artifacts':artifacts,'native':{a:{'library':sha(libs[a]),'surfaceSource':sha(sources[a]/'reconstruction/src/surface.cpp'),'cmakeCache':sha(builds[a]/'CMakeCache.txt')} for a in sources},'tools':{p:sha(pathlib.Path(p).resolve()) for p in ('/usr/bin/c++',cmake,'/usr/bin/prlimit')}}
        (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':receipt['status'],'results':results}))
    except BaseException as e:
        (out/'REFUSED.txt').write_text(type(e).__name__+': '+str(e)+'\nNo successful case publication.\n');raise
if __name__=='__main__':main()
