"""Run unchanged existing geometry gates, retaining every command's actual exit."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args();args.output_dir.mkdir(parents=True,exist_ok=True)
    root=Path(__file__).resolve().parents[3];receipts=[]
    library=root/'build/host/librealityforge.so'
    for script in ('benchmark_middlebury','benchmark_dense_middlebury','benchmark_surface_middlebury','benchmark_multiview_coverage'):
        output=args.output_dir/(script+'.json');log=args.output_dir/(script+'.log')
        if output.exists():output.unlink()
        command=[sys.executable,str(root/'tools'/f'{script}.py'),'--gate','--output',str(output)]
        run=subprocess.run(command,cwd=root,capture_output=True,text=True)
        log.write_text(run.stdout+run.stderr)
        receipts.append({'command':command,'exit_code':run.returncode,'status':'PASS' if run.returncode==0 and output.is_file() else 'FAIL',
                         'report_sha256':hashlib.sha256(output.read_bytes()).hexdigest() if output.is_file() else None,'log':str(log)})
    report={'source_sha':subprocess.check_output(['git','rev-parse','HEAD'],cwd=root,text=True).strip(),
            'engine_sha256':hashlib.sha256(library.read_bytes()).hexdigest(),'receipts':receipts,'full_object_gate':'BLOCKED_EXTERNAL'}
    (args.output_dir/'baseline-receipt.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2));return int(any(r['status']=='FAIL' for r in receipts))


if __name__=='__main__':raise SystemExit(main())
