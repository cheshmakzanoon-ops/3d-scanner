#!/usr/bin/env python3
"""Run fixed physical scenarios with source, local APK and installed APK identities."""
import argparse,hashlib,json,os,re,subprocess
from pathlib import Path

PACKAGES=('com.realityforge.scanner','com.realityforge.scanner.macrobenchmark')

def sha(path):
 with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def source_snapshot(clean=True):
 source=subprocess.check_output(['git','rev-parse','HEAD'],text=True).strip()
 if not re.fullmatch('[0-9a-f]{40}',source):raise ValueError('Exact source commit required')
 if clean:
  if subprocess.check_output(['git','status','--porcelain'],text=True).strip():raise ValueError('Clean source checkout required; archive generated schemas outside source first')
 else:subprocess.run(['git','diff','--exit-code','HEAD'],check=True,stdout=subprocess.DEVNULL)
 return source

def installed_hash(adb,package):
 rows=adb('shell','pm','path',package).splitlines()
 if len(rows)!=1 or not rows[0].startswith('package:'):raise ValueError('Exact single installed APK required: '+package)
 path=rows[0][8:]
 if not re.fullmatch(r'/(?:data/app|mnt/expand)/[A-Za-z0-9._/~=+/-]+\.apk',path):raise ValueError('Unsafe installed APK path')
 digest=adb('shell','sha256sum',path).split()[0]
 if not re.fullmatch('[0-9a-f]{64}',digest):raise ValueError('Installed APK hash unavailable')
 return digest

def run(args):
 if not re.fullmatch('[A-Za-z0-9._:-]+',args.serial):raise ValueError('Explicit safe device serial required')
 if args.output.exists() and any(args.output.iterdir()):raise ValueError('Refusing to overwrite an existing run')
 env=dict(os.environ,ANDROID_SERIAL=args.serial)
 def adb(*parts):return subprocess.check_output(['adb','-s',args.serial,*parts],text=True,env=env,timeout=60).strip()
 source=source_snapshot();expected={package:sha(path) for package,path in zip(PACKAGES,(args.apk,args.benchmark_apk))}
 def identity():
  if source_snapshot(clean=False)!=source:raise ValueError('Source changed during benchmark execution')
  if any(sha(path)!=expected[package] for package,path in zip(PACKAGES,(args.apk,args.benchmark_apk))):raise ValueError('Gradle changed a frozen candidate APK')
  actual={package:installed_hash(adb,package) for package in PACKAGES}
  if actual!=expected:raise ValueError('Installed target/driver APK differs from frozen candidate')
  return actual
 if adb('get-state')!='device':raise ValueError('Selected device unavailable')
 if adb('shell','getprop','ro.kernel.qemu')=='1' or adb('shell','getprop','ro.boot.qemu')=='1':raise ValueError('Physical hardware required')
 abi=adb('shell','getprop','ro.product.cpu.abi')
 if abi!='arm64-v8a':raise ValueError('Protocol requires ARM64 device')
 def thermal():
  match=re.search(r'Thermal Status:\s*(\d+)',adb('shell','dumpsys','thermalservice'))
  if not match:raise ValueError('Thermal status unavailable; do not guess')
  return int(match[1])
 level=re.search(r'level:\s*(\d+)',adb('shell','dumpsys','battery'))
 if not level:raise ValueError('Battery unavailable')
 context=dict(physical=True,abi=abi,sourceSha=source,deviceFingerprint=adb('shell','getprop','ro.build.fingerprint'),
  appSha256=expected[PACKAGES[0]],benchmarkApkSha256=expected[PACKAGES[1]],
  modelManifestSha256=sha(Path('macrobenchmark/target/assets/a097-fixtures.json')),
  thermalBefore=thermal(),batteryPercent=int(level[1]),ambientC=args.ambient_c,refreshRateHz=args.refresh_rate_hz,
  protocolSha256=sha(Path(__file__).with_name('protocol.json')))
 if context['thermalBefore']>1 or context['batteryPercent']<30:raise ValueError('Device conditions outside frozen protocol')
 if args.unprofiled_apk:context['unprofiledAppSha256']=sha(args.unprofiled_apk)
 args.output.mkdir(parents=True,exist_ok=True)
 try:
  for path in (args.apk,args.benchmark_apk):
   if 'Success' not in adb('install','-r',str(path)):raise ValueError('Candidate APK installation failed')
  context['installedIdentityObservations']=[dict(phase='initial',apkSha256=identity())]
  for phase in ('warmup-0','warmup-1','warmup-2','measured'):
   command=[args.gradle,'--no-daemon','--dependency-verification','strict',':macrobenchmark:connectedBenchmarkAndroidTest',
    '-Pandroid.testInstrumentationRunnerArguments.class=com.realityforge.scanner.macrobenchmark.UiPerformanceBenchmark']
   if phase=='measured':command.append('--rerun-tasks')
   with (args.output/(phase+'.log')).open('x') as log:subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=7200)
   context['installedIdentityObservations'].append(dict(phase=phase,apkSha256=identity()))
  context['installedAppSha256']=expected[PACKAGES[0]];context['installedBenchmarkApkSha256']=expected[PACKAGES[1]]
  context['thermalAfter']=thermal();context['memorySnapshot']=adb('shell','dumpsys','meminfo',PACKAGES[0])
  context['unavailableCounters']=['inputResponseP95Ms','peakPssByFixture']
  context['executionStatus']='COMPLETE'
 except (ValueError,OSError,subprocess.SubprocessError) as error:
  context.update(executionStatus='FAIL',error=str(error));raise
 finally:
  (args.output/'context.json').write_text(json.dumps(context,indent=2)+'\n')
 return context

def main():
 p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--apk',type=Path,required=True);p.add_argument('--benchmark-apk',type=Path,required=True)
 p.add_argument('--ambient-c',type=float,required=True);p.add_argument('--refresh-rate-hz',type=float,required=True)
 p.add_argument('--unprofiled-apk',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--gradle',default='gradle');args=p.parse_args()
 run(args)
 print('Actual AndroidX JSON remains under macrobenchmark/build/outputs/connected_android_test_additional_output; peak/input counters require source-owner adapters. Source and installed target/driver identities are in context.json.')
if __name__=='__main__':main()
