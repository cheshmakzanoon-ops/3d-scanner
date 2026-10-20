"""Evaluate normalized JSON, Wavefront OBJ or ASCII PLY against independent samples."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np
from .metrics import evaluate, camera_metrics, similarity, mesh


def load(path):
    if path.suffix=='.glb':
        from .glb import geometry
        return mesh(*geometry(path))
    if path.suffix=='.json':
        data=json.loads(path.read_text());return mesh(data['vertices'],data.get('faces',[]))
    if path.suffix=='.obj':
        vertices=[];faces=[]
        for line in path.read_text().splitlines():
            fields=line.split()
            if not fields:continue
            if fields[0]=='v':vertices.append(list(map(float,fields[1:4])))
            if fields[0]=='f':
                if len(fields)!=4:raise ValueError('only triangular OBJ supported')
                row=[int(p.split('/')[0]) for p in fields[1:]]
                faces.append([i-1 if i>0 else len(vertices)+i for i in row])
        return mesh(vertices,faces)
    if path.suffix=='.ply':
        lines=path.read_text().splitlines()
        if lines[:2]!=['ply','format ascii 1.0']:raise ValueError('only ASCII PLY supported')
        stop=lines.index('end_header');header=lines[:stop]
        nv=int(next(x.split()[-1] for x in header if x.startswith('element vertex ')))
        nf=int(next((x.split()[-1] for x in header if x.startswith('element face ')),'0'))
        # Native PLY writes x/y/z first; reject differently ordered layouts.
        props=[x.split()[-1] for x in header[header.index('element vertex '+str(nv))+1:] if x.startswith('property ')][:3]
        if props!=['x','y','z']:raise ValueError('unsupported PLY vertex layout')
        vertices=[list(map(float,x.split()[:3])) for x in lines[stop+1:stop+1+nv]]
        faces=[]
        for x in lines[stop+1+nv:stop+1+nv+nf]:
            row=list(map(int,x.split()))
            if len(row)!=4 or row[0]!=3:raise ValueError('only triangular PLY supported')
            faces.append(row[1:])
        if len(vertices)!=nv or len(faces)!=nf:raise ValueError('truncated PLY')
        return mesh(vertices,faces)
    raise ValueError('unsupported asset format; use OBJ/ASCII PLY/normalized JSON')


def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('scene',type=Path,help='evaluation descriptor, paths relative to descriptor')
    p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();scene=json.loads(args.scene.read_text());root=args.scene.parent
    protocol=Path(__file__).resolve().parents[3]/'benchmarks/quality_protocols/geometry.json'
    config=json.loads(protocol.read_text())
    receipts=[]
    real_manifests=[]
    for case in scene['scenes']:
        try:
            candidate,reference,eligible=[root/case[k] for k in ('candidate','reference','eligible_reference_points')]
            settings,engine=[root/case[k] for k in ('settings','engine_artifact')]
            if case['kind'] not in ('real','synthetic'):raise ValueError('unknown dataset kind')
            if not isinstance(case.get('candidate_source_sha'),str) or len(case['candidate_source_sha'])!=40 or any(c not in '0123456789abcdef' for c in case['candidate_source_sha']):raise ValueError('candidate source SHA required')
            manifest_receipt=None
            if 'manifest' in case:
                from tools.qa.corpus.manifest import validate
                manifest_path=root/case['manifest'];manifest=json.loads(manifest_path.read_text())
                manifest_receipt=validate(manifest,manifest_path.parent)
                if manifest['kind']!=case['kind']:raise ValueError('manifest kind mismatch')
                if digest(reference)!=manifest['reference']['sha256']:raise ValueError('reference does not match corpus manifest')
                if manifest['kind']=='real':real_manifests.append(manifest)
            elif case['kind']=='real':raise ValueError('real scenes require validated corpus v1 manifest')
            cv,cf=load(candidate);rv,rf=load(reference)
            landmarks=case.get('alignment_landmarks')
            align=similarity(landmarks['candidate'],landmarks['reference']) if landmarks else None
            result=evaluate(cv,cf,rv,rf,np.asarray(json.loads(eligible.read_text())),config['completeness_tolerance_bbox_diagonal'],align)
            cams=case.get('cameras')
            if cams:result['cameras']=camera_metrics(**cams,alignment=align)
            failures=[]
            for ok,message in ((result['accuracy_p95']<=config['accuracy_p95_bbox_diagonal_max'],'accuracy'),(result['completeness_fraction']>=config['observable_completeness_min'],'completeness'),(cams is not None and result['cameras']['registration_fraction']>=config['registration_fraction_min'],'camera_registration')):
                if not ok:failures.append(message)
            top=result['topology']
            for metric in ('duplicate_triangles','nonmanifold_edges','self_intersection_pairs'):
                if top.get(metric)!=0:failures.append(metric)
            receipts.append({'id':case['id'],'status':'FAIL' if failures else 'PASS','failures':failures,'metrics':result,
                             'candidate_sha256':digest(candidate),'reference_sha256':digest(reference),'eligibility_sha256':digest(eligible),
                             'kind':case['kind'],'settings_sha256':digest(settings),'engine_sha256':digest(engine),'candidate_source_sha':case['candidate_source_sha'],'manifest_sha256':manifest_receipt['manifest_sha256'] if manifest_receipt else None})
        except (ValueError,KeyError,OSError,ImportError) as exc:
            receipts.append({'id':case.get('id'),'status':'FAIL','error':str(exc)})
    status='FAIL' if any(r['status']=='FAIL' for r in receipts) or not receipts else 'PASS'
    full_objects={m['dataset_id'] for m in real_manifests if m['scene']=='matte_textured' and m['capture'].get('coverage_degrees')==360}
    workloads={sum(p['role']=='reconstruction' for p in m['photos']) for m in real_manifests}
    kinds={m['scene'] for m in real_manifests}
    physical_complete=len(full_objects)>=2 and {12,30,100,250}<=workloads and {'matte_textured','thin','flat','glossy','transparent','low_texture','moving'}<=kinds
    physical_status=status if physical_complete else 'BLOCKED_EXTERNAL'
    report={'schema_version':1,'source_sha':subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip(),
            'protocol_sha256':digest(protocol),'descriptor_sha256':digest(args.scene),'scenes':receipts,'status':status,
            'physical_gate':physical_status,'physical_blocker':'Validated corpus v1 physical manifests and two authorized independent full-object references, held-out/workload matrix required; synthetic/stereo cannot close gate'}
    args.output.parent.mkdir(parents=True,exist_ok=True);args.output.write_text(json.dumps(report,indent=2,sort_keys=True)+'\n')
    print(json.dumps(report,indent=2));return int(status!='PASS')


if __name__=='__main__':raise SystemExit(main())
