from pathlib import Path
import json,hashlib,subprocess,time,multiprocessing as mp,queue,math,tempfile,os,sys,signal
import numpy as np
from chart_overlay import overlay,Refused,Cancelled
from make_controls import fixture,write_native
from invariant_checks import verify_control_report
from shapely import Polygon,unary_union,STRtree
ROOT=Path(__file__).resolve().parent

def worker(data,result,limits=None,scratch=None):
    os.setsid() # isolate native diagnostic descendants for cancellation
    try:
        output=overlay(data,limits=limits)
        uv=np.array(output.pop('_chartVertices'));original=output.pop('_chartPolygons');cells=output.pop('_chartCells')
        polygons=[Polygon(uv[t]) for t in output['triangles']]
        before=unary_union(original);after=unary_union(polygons)
        edges={}
        for face in output['triangles']:
            for a,b in zip(face,face[1:]+face[:1]):edges[tuple(sorted((a,b)))]=edges.get(tuple(sorted((a,b))),0)+1
        tree=STRtree(polygons);overlap_area=0.;overlap_pairs=0
        for i,p in enumerate(polygons):
            for j in tree.query(p):
                j=int(j)
                if j<=i:continue
                area=p.intersection(polygons[j]).area
                if area>0:overlap_pairs+=1;overlap_area+=area
        output['metrics'].update({'sourceUnionChartArea':before.area,'candidateUnionChartArea':after.area,'lostSupportChartArea':before.difference(after).area,'addedUnsupportedChartArea':after.difference(before).area,'nonmanifoldEdges':sum(n>2 for n in edges.values()),'boundaryEdges':sum(n==1 for n in edges.values()),'positiveAreaOverlapPairs':overlap_pairs,'summedOverlapChartArea':overlap_area})
        # Same native diagnostic acceptance used by the app, before publication.
        temporary_path=str(Path(scratch)/'native.mesh')
        with open(temporary_path,'w') as temporary:
            temporary.write(f"{len(output['vertices'])} {len(output['triangles'])}\n")
            for p in output['vertices']:temporary.write(' '.join(map(str,p))+'\n')
            for t in output['triangles']:temporary.write(' '.join(map(str,t))+'\n')
        try:
            checked=subprocess.run([str(ROOT/'native_topology'),temporary_path],capture_output=True,text=True,check=True)
            native=json.loads(checked.stdout)
        finally:os.unlink(temporary_path)
        output['metrics']['nativeTopology']=native
        if any(native[k] for k in ['nonmanifoldEdges','nonmanifoldVertices','degenerateFaces','duplicateFaces','windingConflicts','intersections','incomplete']):
            error=Refused('native coherent-topology guard failed; candidate not published');error.details=native;raise error
        result.put(('success',output))
    except Exception as error:result.put(('refused' if isinstance(error,Refused) else 'error',{'reason':str(error),'exception':type(error).__name__,'details':getattr(error,'details',None)}))

def stop_worker(process):
    # Kill the isolated group even if its leader already exited; native children
    # must not outlive cancellation or retain files in the parent-owned scratch.
    try:os.killpg(process.pid,signal.SIGTERM)
    except ProcessLookupError:
        if process.is_alive():process.terminate()
    process.join()

def run(data,limits=None,cancel_after=None):
    # Parent owns scratch cleanup even when SIGTERM skips the worker's finally.
    # ROOT is copied inside the fresh external RUN by run-experiment.sh.
    with tempfile.TemporaryDirectory(prefix='.worker-',dir=ROOT) as scratch:
        result=mp.Queue();process=mp.Process(target=worker,args=(data,result,limits,scratch));start=time.monotonic();process.start()
        try:
            while True:
                elapsed=time.monotonic()-start
                if cancel_after is not None and elapsed>=cancel_after:
                    return 'cancelled',{'reason':'external cancellation; no mesh published','seconds':elapsed}
                if elapsed>=30:
                    return 'refused',{'reason':'30second worker budget; no mesh published','seconds':elapsed}
                try:
                    status,value=result.get(timeout=.02)
                    value['seconds']=time.monotonic()-start;return status,value
                except queue.Empty:
                    if not process.is_alive():return 'refused',{'reason':'worker ended without result','seconds':elapsed}
        finally:
            stop_worker(process);result.close()

def main():
    out=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else ROOT/'results'
    out.mkdir(parents=True,exist_ok=False);report={}
    for name in ['identical','translated','rotated','permuted','partial','complementary','hole','curved_identical','curved_translated','step','thin_sheets']:
        data=fixture(name);write_native(data,out/f'{name}.txt');(out/f'{name}-input.json').write_text(json.dumps(data))
        native=subprocess.run([str(ROOT/'native_baseline'),str(out/f'{name}.txt'),str(out/f'{name}-native.json')],capture_output=True,text=True)
        if native.returncode:raise RuntimeError(f"Native baseline exited {native.returncode}: {native.stderr}")
        base=json.loads((out/f'{name}-native.json').read_text());status,value=run(data)
        record={'status':status,'nativeBaseline':{k:v for k,v in base.items() if k not in ['vertices','triangles']},'candidate':value['metrics'] if status=='success' else value,'seconds':value.get('seconds')}
        if status=='success':
            (out/f'{name}-candidate.json').write_text(json.dumps(value));record['sourceIdsPreserved']=sorted(o['id'] for o in data['observations'])==sorted(o['id'] for o in value['observations'])
        report[name]=record;print(name,json.dumps(record),flush=True);(out/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    for name,limits in [('source_budget',{'inputTriangles':1}),('pair_budget',{'candidatePairs':1}),('fragment_budget',{'cells':1}),('vertex_budget',{'vertices':1}),('face_budget',{'faces':1}),('contributor_budget',{'links':1})]:
        status,value=run(fixture('translated'),limits=limits);report[name]={'status':status,'result':value};print(name,status,value,flush=True)
    status,value=run(fixture('translated'),cancel_after=.03);report['cancelled']={'status':status,'result':value}
    malformed=fixture('identical');malformed['observations'][0]['xyz'][0]=float('nan');status,value=run(malformed);report['nonfinite']={'status':status,'result':value}
    for name in ['same_view_overlap','duplicate_source_id','opposite_oriented_coincident_sheet']:
        data=fixture('identical')
        if name=='same_view_overlap':data['triangles'].append(data['triangles'][0].copy())
        elif name=='duplicate_source_id':data['observations'].append(data['observations'][0].copy())
        else:
            for triangle in data['triangles']:
                if triangle['view']==1:triangle['sources'].reverse()
        status,value=run(data);report[name]={'status':status,'result':value}
    try:
        overlay(fixture('identical'),cancel=lambda:True)
        report['cooperative_precheck']={'status':'unexpected_success'}
    except Cancelled:report['cooperative_precheck']={'status':'cancelled','resultProduced':False}
    (out/'summary.json').write_text(json.dumps(report,indent=2)+'\n')
    verify_control_report(report)
if __name__=='__main__':main()
