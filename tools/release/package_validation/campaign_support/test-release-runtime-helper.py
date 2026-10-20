#!/usr/bin/env python3
"""Host-only checks for the external final-candidate runtime orchestrator."""
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time

path=Path('/workspace/campaign/run-final-release-runtime.py')
spec=importlib.util.spec_from_file_location('release_runtime_tests',path)
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
selector='com.realityforge.scanner.qa.ReleaseNativeContractTest#installedCandidateIsMinifiedReleaseAndNativeExportsReopen'
c,t=selector.split('#');rows=[]
for code in (1,0):
 rows += [f'INSTRUMENTATION_STATUS: class={c}','INSTRUMENTATION_STATUS: current=1','INSTRUMENTATION_STATUS: id=AndroidJUnitRunner','INSTRUMENTATION_STATUS: numtests=1','INSTRUMENTATION_STATUS: stream=',f'INSTRUMENTATION_STATUS: test={t}',f'INSTRUMENTATION_STATUS_CODE: {code}']
rows+=['INSTRUMENTATION_RESULT: stream=','Time: 1.234','','OK (1 test)','','INSTRUMENTATION_CODE: -1']
good='\n'.join(rows)+'\n'
assert m.strict_native(good,0,selector)['passed_count']==1
cases={'missing_runner':good.replace('INSTRUMENTATION_STATUS: id=AndroidJUnitRunner\n',''),'missing_stream':good.replace('INSTRUMENTATION_STATUS: stream=\n',''),'unknown_result':good.replace('INSTRUMENTATION_RESULT: stream=','INSTRUMENTATION_RESULT: shortMsg=error'),'unknown_record':good.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_STATUS_UNKNOWN: error\nINSTRUMENTATION_CODE: -1'),'skipped':good.replace('INSTRUMENTATION_STATUS_CODE: 0','INSTRUMENTATION_STATUS_CODE: -3'),'missing_time':good.replace('Time: 1.234',''),'wrong_count':good.replace('OK (1 test)','OK (0 tests)'),'bad_final':good.replace('INSTRUMENTATION_CODE: -1','INSTRUMENTATION_CODE: 0'),'missing_final':good.replace('INSTRUMENTATION_CODE: -1',''),'duplicate_final':good+'INSTRUMENTATION_CODE: -1\n','wrong_method':good.replace(t,'otherMethod'),'duplicate_field':good.replace('INSTRUMENTATION_STATUS: current=1','INSTRUMENTATION_STATUS: current=1\nINSTRUMENTATION_STATUS: current=1')}
for marker in ['FAILURES!!!','Process crashed','AssumptionFailure','AssumptionViolatedException','shortMsg=error']:
 cases['failure_marker_'+marker]=good.replace('INSTRUMENTATION_STATUS: stream=','INSTRUMENTATION_STATUS: stream='+marker,1)
for name,text in cases.items():
 try:m.strict_native(text,0,selector)
 except ValueError:continue
 raise AssertionError('Accepted malformed native transcript: '+name)
try:m.strict_native(good,1,selector)
except ValueError:pass
else:raise AssertionError('Accepted failed adb return code')
with tempfile.TemporaryDirectory(prefix='rf-release-runtime-test-') as td:
 temp=Path(td);child=temp/'child.py';pid=temp/'pid'
 child.write_text('import os,signal,time\nfrom pathlib import Path\nsignal.signal(signal.SIGTERM,signal.SIG_IGN)\nPath('+repr(str(pid))+').write_text(str(os.getpid()))\ntime.sleep(60)\n')
 command=[sys.executable,'-c','import subprocess,sys,time; subprocess.Popen([sys.executable,'+repr(str(child))+']); print("partial output",flush=True); time.sleep(60)']
 try:m.bounded(command,temp/'output.log',1)
 except subprocess.TimeoutExpired:pass
 else:raise AssertionError('Timeout did not occur')
 assert (temp/'output.log').read_text().startswith('partial output')
 stat=Path('/proc')/pid.read_text()/'stat'
 deadline=time.monotonic()+1
 while stat.exists() and stat.read_text().split()[2]!='Z' and time.monotonic()<deadline:
  time.sleep(0.01)
 assert not stat.exists() or stat.read_text().split()[2]=='Z','Surviving child ignored timeout cleanup'
result={'status':'PASS','nativePositiveCases':1,'nativeNegativeCases':len(cases)+1,'processGroupTimeoutCases':1,'runnerSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'deviceActions':0}
print(json.dumps(result,indent=2))
