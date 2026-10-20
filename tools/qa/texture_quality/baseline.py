"""Unchanged Middlebury photographic gate plus independent GLB/PNG measurements."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from .assets import load
from .metrics import uv_metrics


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output-dir',type=Path,required=True);p.add_argument('--native-library',type=Path,required=True)
    args=p.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True);root=Path(__file__).resolve().parents[3]
    report_file=args.output_dir/'middlebury.json'
    if report_file.exists():report_file.unlink()
    command=[sys.executable,str(root/'tools/benchmark_texture_middlebury.py'),'--gate','--output',str(report_file)]
    environment=os.environ.copy();environment['RF_NATIVE_LIBRARY']=str(args.native_library.resolve())
    run=subprocess.run(command,cwd=root,env=environment,capture_output=True,text=True)
    (args.output_dir/'middlebury.log').write_text(run.stdout+run.stderr)
    report={'source_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),'command':command,'exit_code':run.returncode,
            'engine_sha256':hashlib.sha256(args.native_library.read_bytes()).hexdigest(),'status':'PASS' if run.returncode==0 and report_file.is_file() else 'FAIL','held_out_status':'NOT_RUN','physical_gate':'BLOCKED_EXTERNAL'}
    if report_file.is_file():
        report['baseline_report_sha256']=hashlib.sha256(report_file.read_bytes()).hexdigest()
    if report['status']=='PASS':
        glb=root/'build/host/middlebury-photographic-texture.glb';png=root/'build/host/middlebury-photographic-atlas.png'
        v,f,uv,tex,asset=load(glb,png)
        report.update(independent_metrics=uv_metrics(v,f,uv,tex),asset=asset,candidate_sha256=hashlib.sha256(glb.read_bytes()).hexdigest(),atlas_sha256=hashlib.sha256(png.read_bytes()).hexdigest())
    config=json.loads((root/'benchmarks/quality_protocols/texture.json').read_text())
    metrics=report.get('independent_metrics',{})
    failed=[]
    for key in ('uv_out_of_bounds_vertices','degenerate_uv_faces','uv_overlap_pairs','distortion_anisotropy_p95','seam_linear_rgb_abs_p95'):
        value=metrics.get(key)
        if value is not None and value>config[key+'_max']:failed.append(key)
    report['independent_uv_gate']='FAIL' if failed else 'PASS_LIMITED_UV'
    report['independent_uv_failures']=failed
    (args.output_dir/'baseline-receipt.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2));return int(report['status']!='PASS')


if __name__=='__main__':raise SystemExit(main())
