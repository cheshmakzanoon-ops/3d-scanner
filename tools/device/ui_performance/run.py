#!/usr/bin/env python3
"""Run fixed physical-device scenarios and collect immutable context. No emulator qualification."""
import argparse,hashlib,json,os,re,subprocess
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--serial',required=True);p.add_argument('--apk',type=Path,required=True)
 p.add_argument('--ambient-c',type=float,required=True);p.add_argument('--refresh-rate-hz',type=float,required=True)
 p.add_argument('--unprofiled-apk',type=Path);p.add_argument('--output',type=Path,required=True);p.add_argument('--gradle',default='gradle');a=p.parse_args()
 env=dict(os.environ,ANDROID_SERIAL=a.serial)
 def adb(*args):return subprocess.check_output(['adb','-s',a.serial,*args],text=True,env=env).strip()
 if adb('get-state')!='device':raise ValueError('Selected device unavailable')
 if adb('shell','getprop','ro.kernel.qemu')=='1':raise ValueError('Physical hardware required')
 abi=adb('shell','getprop','ro.product.cpu.abi')
 if abi!='arm64-v8a':raise ValueError('Protocol requires ARM64 device')
 def thermal():
  value=adb('shell','dumpsys','thermalservice');match=re.search(r'Thermal Status:\s*(\d+)',value)
  if not match:raise ValueError('Thermal status unavailable; do not guess')
  return int(match.group(1))
 battery=adb('shell','dumpsys','battery');level=re.search(r'level:\s*(\d+)',battery)
 if not level:raise ValueError('Battery unavailable')
 context=dict(physical=True,abi=abi,deviceFingerprint=adb('shell','getprop','ro.build.fingerprint'),
  appSha256=hashlib.sha256(a.apk.read_bytes()).hexdigest(),
  modelManifestSha256=hashlib.sha256(Path('macrobenchmark/target/assets/a097-fixtures.json').read_bytes()).hexdigest(),
  thermalBefore=thermal(),batteryPercent=int(level.group(1)),ambientC=a.ambient_c,refreshRateHz=a.refresh_rate_hz,
  protocolSha256=hashlib.sha256(Path(__file__).with_name('protocol.json').read_bytes()).hexdigest())
 if context['thermalBefore']>1 or context['batteryPercent']<30:raise ValueError('Device conditions outside frozen protocol')
 if a.unprofiled_apk:context['unprofiledAppSha256']=hashlib.sha256(a.unprofiled_apk.read_bytes()).hexdigest()
 a.output.mkdir(parents=True,exist_ok=True)
 adb('install','-r',str(a.apk))
 # Three explicitly recorded warmups use separate output and do not count as measured samples.
 for i in range(3):
  with (a.output/f'warmup-{i}.log').open('w') as log:
   subprocess.run([a.gradle,'--no-daemon',':macrobenchmark:connectedBenchmarkAndroidTest',
    '-Pandroid.testInstrumentationRunnerArguments.class=com.realityforge.scanner.macrobenchmark.UiPerformanceBenchmark'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
 with (a.output/'measured.log').open('w') as log:
  subprocess.run([a.gradle,'--no-daemon',':macrobenchmark:connectedBenchmarkAndroidTest','--rerun-tasks','-Pandroid.testInstrumentationRunnerArguments.class=com.realityforge.scanner.macrobenchmark.UiPerformanceBenchmark'],env=env,stdout=log,stderr=subprocess.STDOUT,check=True)
 context['thermalAfter']=thermal();context['memorySnapshot']=adb('shell','dumpsys','meminfo','com.realityforge.scanner')
 context['unavailableCounters']=['inputResponseP95Ms','peakPssByFixture']
 (a.output/'context.json').write_text(json.dumps(context,indent=2)+'\n')
 print('AndroidX benchmark JSON is under macrobenchmark/build/outputs/connected_android_test_additional_output; evaluate with report.py. Peak/input counters require renderer instrumentation (097-to-069).')
if __name__=='__main__':main()
