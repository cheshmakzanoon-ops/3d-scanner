#!/usr/bin/env python3
import sys
sys.dont_write_bytecode=True
import os,pathlib,hashlib,json,tarfile,resource
ROOT=pathlib.Path(__file__).resolve().parent

def need(b,m):
    if not b:raise RuntimeError(m)
def sha(p):return hashlib.sha256(pathlib.Path(p).read_bytes()).hexdigest()
def main():
    need(len(sys.argv)==6,'usage: python -B run.py REPO FIXTURE FRESH_OUTPUT CMAKE QA_PYTHON')
    repo,fixture,out,cmake=map(lambda x:pathlib.Path(x).resolve(),sys.argv[1:5]);python=pathlib.Path(sys.argv[5]).absolute();need(not out.exists() and not any(out.is_relative_to(p) for p in (ROOT,repo,fixture)),'fresh external output required')
    manifest=sha(ROOT/'SHA256SUMS');pins={}
    for line in (ROOT/'SHA256SUMS').read_text().splitlines():
        h,n=line.split('  ',1);p=(ROOT/n).resolve();need(p.is_relative_to(ROOT) and p.is_file() and sha(p)==h,'PACKAGE_IDENTITY:'+n);pins[n]=h
    need({'source/cell_observer.hpp','source/cell_patch.py','source/cell_audit.py','source/observer.hpp','source/witness.hpp','source/harness.cpp','source/bounded.py','source/patch.py','source/audit.py','source/witness_audit.py','source/prepare.py','source/cuboid_evaluate.py','source/evaluate_sealed.py','config.json','run.py','predecessor-artifact-pins.json'}<=pins.keys(),'missing source')
    sys.path.insert(0,str(ROOT/'source'));from bounded import run
    from patch import patch
    from cell_patch import patch_cells
    from audit import audit
    from witness_audit import audit as witness_audit
    cfg=json.loads((ROOT/'config.json').read_text());out.mkdir();env=os.environ.copy()
    # Preserve inherited runtime environment. Unexpected overrides are a
    # prerequisite refusal, never silently removed to rescue execution.
    forbidden=('LD_LIBRARY_PATH','LD_PRELOAD','LD_AUDIT','LD_RUN_PATH','CXX','CC','CXXFLAGS','CFLAGS','LDFLAGS','CPPFLAGS','CMAKE_TOOLCHAIN_FILE','CMAKE_PREFIX_PATH','CMAKE_GENERATOR','PYTHONPATH','PYTHONHOME','PYTHONUSERBASE','PYTHONSTARTUP','PYTHONINSPECT','RF_LEDGER_OBSERVER_DIR')
    overrides={k:env[k] for k in forbidden if env.get(k)}
    need(not overrides,'UNEXPECTED_RUNTIME_OVERRIDE_PREREQUISITE:'+','.join(overrides))
    resource.setrlimit(resource.RLIMIT_AS,(1073741824,1073741824));calls=[]
    def call(argv,name,seconds=60,extra=None):
        e=env.copy();e.update(extra or {});calls.append(run(list(map(str,argv)),out/(name+'.log'),seconds,e))
    try:
        for name,path in [('compiler',pathlib.Path('/usr/bin/c++')),('cmake',cmake),('python',python),('prlimit',pathlib.Path('/usr/bin/prlimit'))]:
            need(path.is_file(),'tool missing:'+str(path));call([path,'--version'],name+'-version')
        call(['/usr/bin/prlimit','--as=1073741824','--cpu=60','--',python,'-B',ROOT/'source/prepare.py',fixture,out/'input'],'prepare',60)
        inputFile=out/'input/solver-input.bits';inputHash=sha(inputFile);fixtureHash=sha(fixture/'SHA256SUMS');fixturePins={n:h for h,n in (line.split('  ',1) for line in (fixture/'SHA256SUMS').read_text().splitlines())}
        archive=out/'pinned-source.tar';call(['/usr/bin/git','-C',repo,'archive','--format=tar','-o',archive,cfg['sourceCommit'],'CMakeLists.txt','reconstruction','tools/qa/visible_surface_baseline/evaluate.py'],'archive')
        need(archive.stat().st_size<=134217728,'SOURCE_ARCHIVE_BUDGET')
        sources={};builds={};bins={};libs={}
        for arm in ('baseline','observer'):
            src=out/(arm+'-source');src.mkdir()
            with tarfile.open(archive) as t:t.extractall(src,filter='data')
            need((src/'tools/qa/visible_surface_baseline/evaluate.py').read_bytes()==(ROOT/'source/cuboid_evaluate.py').read_bytes(),'EVALUATOR_SOURCE_BINDING')
            if arm=='observer':
                patch(src,ROOT/'source');patch_cells(src,ROOT/'source')
            build=out/(arm+'-build');call([cmake,'-S',src,'-B',build,'-DCMAKE_BUILD_TYPE=Release','-DCMAKE_CXX_COMPILER=/usr/bin/c++','-DBUILD_TESTING=OFF'],arm+'-configure',900)
            call([cmake,'--build',build,'--target','realityforge','--parallel','2'],arm+'-build',900)
            binary=out/(arm+'-harness');call(['/usr/bin/c++','-std=c++20','-O2','-I'+str(src/'reconstruction/include'),'-I'+str(ROOT/'source'),ROOT/'source/harness.cpp','-L'+str(build),'-Wl,-rpath,'+str(build),'-lrealityforge','-o',binary],arm+'-compile',120)
            call(['/usr/bin/ldd',binary],arm+'-ldd');lib=(build/'librealityforge.so').resolve();need(('librealityforge.so => '+str(lib)) in (out/(arm+'-ldd.log')).read_text(),'LOADER_BINDING');sources[arm]=src;builds[arm]=build;bins[arm]=binary;libs[arm]=lib
        (out/'premeasurement.json').write_text(json.dumps({'packageManifest':manifest,'config':sha(ROOT/'config.json'),'archive':sha(archive),'sourceCommit':cfg['sourceCommit'],'input':inputHash,'fixtureManifest':fixtureHash,'native':{a:{'library':sha(libs[a]),'denseSource':sha(sources[a]/'reconstruction/src/dense.cpp'),'surfaceSource':sha(sources[a]/'reconstruction/src/surface.cpp')} for a in sources},'oracleCameraExtrinsicsInput':True,'imageDerivedRegistration':False,'geometryMasksInput':False},indent=2)+'\n')
        for arm in ('baseline','observer'):
            target=out/arm;extra={'RF_LEDGER_OBSERVER_DIR':str(target)} if arm=='observer' else {}
            call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=268435456','--',bins[arm],inputFile,target],arm+'-run',600,extra)
            need(sha(target/'inputs.bits')==inputHash and sha(target/'after/inputs.bits')==inputHash,'INPUT_MUTATION');need(sha(target/'dense.bits')==sha(target/'after/dense.bits'),'DENSE_MUTATED_BY_SURFACE')
        base=out/'baseline';obs=out/'observer'
        expected=('inputs.bits','dense.bits','dense.json','mesh.bits','mesh.json','mesh.ply','mesh.glb','expected-prepared.tsv','normal-audit.json','stage-summary.json','after/inputs.bits','after/dense.bits')
        for name in expected:need((base/name).is_file() and (obs/name).is_file() and sha(base/name)==sha(obs/name),'PARITY:'+name)
        oldpins=json.loads((ROOT/'predecessor-artifact-pins.json').read_text())['observerArtifacts']
        for name,h in oldpins.items():need((obs/name).is_file() and sha(obs/name)==h,'FROZEN_ORACLE_PARITY:'+name)
        need(sha(obs/'prepared.tsv')==sha(base/'expected-prepared.tsv'),'PREPARED_NATIVE_REGENERATION_PARITY')
        call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',python,'-B',ROOT/'source/cell_audit.py',obs],'cell-audit',600)
        results={'witness':witness_audit(obs),'preface':audit(obs),'cells':json.loads((obs/'cell-audit.json').read_text())}
        (out/'parity-audit.json').write_text(json.dumps(results,indent=2)+'\n')
        need(sum(p.stat().st_size for p in obs.rglob('*') if p.is_file())<=cfg['maxScratchCaseBytes'],'OUTPUT_BUDGET')
        seal={'meshBitsSHA256':sha(obs/'mesh.bits'),'inputSHA256':inputHash,'baselineObserverParity':True,'oracleCameraExtrinsicsInput':True,'imageDerivedRegistration':False};(out/'solver-seal.json').write_text(json.dumps(seal,indent=2)+'\n')
        for n,h in fixturePins.items():need(sha(fixture/n)==h,'FIXTURE_CHANGED_BEFORE_EVALUATION:'+n)
        call(['/usr/bin/prlimit','--as=1073741824','--cpu=600','--fsize=16777216','--',python,'-B',ROOT/'source/evaluate_sealed.py',fixture/'evaluation-only/fixture.json',obs/'mesh.bits',out/'solver-seal.json',out/'evaluation.json'],'evaluate',600)
        need(sha(obs/'mesh.bits')==seal['meshBitsSHA256'] and sha(inputFile)==inputHash and sha(fixture/'SHA256SUMS')==fixtureHash,'POST_EVALUATION_IDENTITY')
        for n,h in fixturePins.items():need(sha(fixture/n)==h,'FIXTURE_CHANGED_AFTER_EVALUATION:'+n)
        for n,h in pins.items():need(sha(ROOT/n)==h,'PACKAGE_CHANGED')
        need(sha(ROOT/'SHA256SUMS')==manifest,'MANIFEST_CHANGED')
        artifacts={str(p.relative_to(out)):sha(p) for p in out.rglob('*') if p.is_file() and not any(x.endswith('-build') or x.endswith('-source') for x in p.relative_to(out).parts)}
        receipt={'status':'ASSESSMENT_COMPLETED_NOT_QUALITY_PASS','scope':'ORACLE_CAMERA_DENSE_COMPONENT_ONLY','oracleCameraExtrinsicsInput':True,'imageDerivedRegistration':False,'geometryMasksPassedToSolver':False,'front':'NOT_RUN','sourceCommit':cfg['sourceCommit'],'manifest':manifest,'results':results,'calls':calls,'artifacts':artifacts,'native':{a:{'library':sha(libs[a]),'denseSource':sha(sources[a]/'reconstruction/src/dense.cpp'),'surfaceSource':sha(sources[a]/'reconstruction/src/surface.cpp'),'cmakeCache':sha(builds[a]/'CMakeCache.txt')} for a in sources},'inheritedRuntimeOverrides':overrides,'pythonEntryPoint':str(python),'tools':{str(p):sha(pathlib.Path(p).resolve()) for p in ('/usr/bin/c++',cmake,python,'/usr/bin/prlimit')}}
        (out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({'status':receipt['status'],'results':results}))
    except BaseException as e:
        summary=out/'baseline/stage-summary.json'
        if summary.is_file():
            measured=json.loads(summary.read_text())
            if measured.get('baselineStage') in ('EMPTY_VERIFIED_SAMPLES','NO_VALID_FACES'):
                (out/'outcome.json').write_text(json.dumps({'status':'BASELINE_MEASURED_EMPTY_OR_NO_MESH','baseline':measured,'observer':'NOT_RUN','independentAudits':'NOT_RUN','evaluation':'NOT_RUN','exports':'NATIVE_REFUSAL_NO_FABRICATED_EXPORTS','oracleCameraExtrinsicsInput':True,'imageDerivedRegistration':False,'denseBitsSHA256':sha(out/'baseline/dense.bits'),'inputSHA256':sha(out/'input/solver-input.bits')},indent=2)+'\n')
        (out/'REFUSED.txt').write_text(type(e).__name__+': '+str(e)+'\nNo successful arm publication.\n');raise
if __name__=='__main__':main()
