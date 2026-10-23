"""Negative tests for the synthetic reproduction verifier, not mesh-quality tests."""
from pathlib import Path
import json,os,shutil,subprocess,sys,tempfile,time
ROOT=Path(__file__).resolve().parent

def require(condition,message):
    if not condition:raise AssertionError(message)
def run_negative(command,expected):
    result=subprocess.run(command,capture_output=True,text=True,cwd=ROOT)
    require(result.returncode!=0,'Corruption incorrectly returned success')
    require(expected in result.stderr,f'Wrong rejection reason: {result.stderr[-1000:]}')
    return {'exitCode':result.returncode,'expectedFailureObserved':True,'diagnostic':expected}
def scratch_writer(data,result,limits,scratch):
    # Deterministically simulate SIGTERM while a native-stage mesh exists.
    os.setsid();path=Path(scratch)/'native.mesh';path.write_text('owned diagnostic scratch\n')
    (Path(scratch).parent/'ready.json').write_text(json.dumps({'scratch':scratch,'meshWritten':True}))
    time.sleep(30)
def main():
    original=Path(sys.argv[1]).resolve();report={}
    with tempfile.TemporaryDirectory(prefix='.verification-',dir=ROOT) as temporary:
        scratch=Path(temporary)
        for name,diagnostic in [('ledger','immutable source ledger mismatch'),('support','declared support/topology invariant failed'),('duplicate_ledger','duplicate source ledger IDs'),('native_topology','independent native topology defect'),('permutation','Permutation triangle-coordinate sets differ')]:
            altered=scratch/name;altered.mkdir()
            for pattern in ('*-candidate.json','*-input.json'):
                for path in original.glob(pattern):shutil.copy2(path,altered/path.name)
            target=altered/('permuted-candidate.json' if name=='permutation' else 'translated-candidate.json')
            data=json.loads(target.read_text())
            if name=='ledger':data['observations'][0]['confidence']=-1.
            elif name=='support':data['metrics']['lostSupportChartArea']=.01
            elif name=='duplicate_ledger':data['observations'].append(data['observations'][0].copy())
            elif name=='native_topology':data['triangles'].append(data['triangles'][0].copy())
            else:
                for vertex in data['vertices']:vertex[0]+=.01
            target.write_text(json.dumps(data))
            report[name]=run_negative([sys.executable,str(ROOT/'audit_results.py'),str(altered)],diagnostic)
            if name=='ledger':report['ledger_with_python_optimized']=run_negative([sys.executable,'-O',str(ROOT/'audit_results.py'),str(altered)],diagnostic)
        bad=scratch/'unexpected-status'
        code="import sys,run_controls; run_controls.run=lambda *args,**kwargs: ('refused',{'reason':'injected unexpected status','exception':'Refused'}); sys.argv=['run_controls.py',sys.argv[1]]; run_controls.main()"
        report['unexpected_control_status']=run_negative([sys.executable,'-c',code,str(bad)],'expected declared success')
        import run_controls
        oldroot,oldworker=run_controls.ROOT,run_controls.worker
        cancellation=scratch/'cancel';cancellation.mkdir()
        try:
            run_controls.ROOT=cancellation;run_controls.worker=scratch_writer
            status,_=run_controls.run({},cancel_after=.08)
            require(status=='cancelled','Forced scratch-stage cancellation did not cancel')
            marker=json.loads((cancellation/'ready.json').read_text())
            require(marker['meshWritten'],'Cancellation test never reached mesh-writing stage')
            require(not Path(marker['scratch']).exists(),'Cancelled worker scratch leaked')
            require(not list(cancellation.glob('.worker-*')),'Parent-owned worker directory leaked')
            report['cancelled_mesh_cleanup']={'status':'PASS','meshWrittenBeforeCancellation':True,'parentRemovedWorkerScratch':True,'temporaryMeshOutsideRun':False}
        finally:run_controls.ROOT=oldroot;run_controls.worker=oldworker
    (original/'fail-closed-tests.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))
if __name__=='__main__':main()
