from pathlib import Path
import importlib.util,json,tempfile
root=Path('/workspace/agent-workspaces/082')
spec=importlib.util.spec_from_file_location('review082',root/'tools/qa/fuzz/run.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
test=Path(tempfile.mkdtemp(prefix='082-hardened-',dir='/tmp/quality-review'));build=test/'build';(build/'native').mkdir(parents=True)
(build/'native/librealityforge.so').write_bytes(b'FAKE uncompiled review fixture')
for kind in module.KINDS:
 p=build/('rf_fuzz_'+kind);p.write_text('#!/bin/sh\necho "Done 0 runs in 0 second(s)"\n');p.chmod(0o755)
output=test/'evidence'
code=module.main(['--output',str(output),'--build',str(build),'--compiler','/workspace/.tools/native-qa/llvm/usr/bin/clang++-19','--skip-build','--runs','1000','--seconds','1'])
r=json.loads((output/'evidence.json').read_text())
assert code==1 and r['status']=='FAIL' and not r['steps']
assert 'No regular buildstamp' in r['blocker']
assert not (output/'numeric.log').exists()
print('PASS_ORIGINAL_UNCOMPILED_ZERO_FUZZ_COUNTEREXAMPLE_REJECTED',output)
