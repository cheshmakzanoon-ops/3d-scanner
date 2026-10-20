#!/usr/bin/env python3
"""Audit actual minified/profileable benchmark target and self-instrumenting driver APKs."""
import argparse,hashlib,json,os,re,subprocess,zipfile
from pathlib import Path
from xml.etree import ElementTree as ET
A='{http://schemas.android.com/apk/res/android}'
def sha(path):
 with open(path,'rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def manifests(target,test):
 t=ET.fromstring(target);a=t.find('application');driver=ET.fromstring(test);instrument=driver.find('instrumentation')
 if t.get('package')!='com.realityforge.scanner' or a is None or a.get(A+'debuggable','false')!='false':raise ValueError('Target must be non-debuggable app')
 if a.get(A+'testOnly','false')!='false':raise ValueError('Target cannot be testOnly')
 profile=a.find('profileable')
 if profile is None or profile.get(A+'shell')!='true':raise ValueError('Target must be shell-profileable')
 if not any(e.get(A+'name')=='com.realityforge.scanner.bench97.BenchmarkViewerActivity' for e in a.findall('activity')):raise ValueError('Benchmark-only production-viewer adapter missing')
 if any(p.get(A+'name')=='android.permission.INTERNET' for p in t.findall('uses-permission')):raise ValueError('Target network permission forbidden')
 if instrument is None or instrument.get(A+'targetPackage')!=driver.get('package'):raise ValueError('Driver must be self-instrumenting')
 if instrument.get(A+'name')!='androidx.test.runner.AndroidJUnitRunner':raise ValueError('Unexpected driver runner')
 return dict(targetPackage=t.get('package'),driverPackage=driver.get('package'),targetNonDebuggable=True,targetProfileable=True,selfInstrumenting=True)
def main():
 p=argparse.ArgumentParser();p.add_argument('--target-apk',type=Path,required=True);p.add_argument('--benchmark-apk',type=Path,required=True);p.add_argument('--mapping',type=Path,required=True)
 p.add_argument('--fixtures',type=Path,default=Path('macrobenchmark/target/assets/a097-fixtures.json'));p.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','')));p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 analyzer=a.sdk/'cmdline-tools/latest/bin/apkanalyzer';signer=a.sdk/'build-tools/36.0.0/apksigner'
 def run(args):return subprocess.check_output(list(map(str,args)),text=True,stderr=subprocess.PIPE)
 result=manifests(run([analyzer,'manifest','print',a.target_apk]),run([analyzer,'manifest','print',a.benchmark_apk]))
 for apk in (a.target_apk,a.benchmark_apk):
  output=run([signer,'verify','--verbose','--print-certs',apk])
  if not re.search(r'Verified using v[234][^:]*:\s*true',output) or 'CN=Android Debug' not in output:raise ValueError('Verified test signing identity required')
 mapping=a.mapping.read_text()
 if '# compiler: R8' not in mapping or 'PhotographicRenderer ->' not in mapping:raise ValueError('R8 mapping for actual renderer missing')
 fixture=json.loads(a.fixtures.read_text())
 with zipfile.ZipFile(a.target_apk) as z:
  for name,record in fixture['fixtures'].items():
   if hashlib.sha256(z.read('assets/a097-'+name+'.glb')).hexdigest()!=record['sha256']:raise ValueError('Packaged model differs from frozen fixture')
  if z.read('assets/a097-fixtures.json')!=a.fixtures.read_bytes():raise ValueError('Packaged fixture manifest differs')
 result.update(schemaVersion=1,status='PASS',targetSha256=sha(a.target_apk),benchmarkSha256=sha(a.benchmark_apk),mappingSha256=sha(a.mapping),modelManifestSha256=sha(a.fixtures),deviceExecution='NOT_RUN')
 a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':main()
