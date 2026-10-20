#!/usr/bin/env python3
"""Consume actual AndroidX benchmark runs and freeze context; missing evidence never passes."""
import argparse,hashlib,json,math,re
from pathlib import Path

def percentile(values,p):
 if not values or any(not isinstance(v,(int,float)) or not math.isfinite(v) or v<0 for v in values):raise ValueError('Missing/nonfinite/negative measurement')
 ordered=sorted(values);return ordered[max(0,math.ceil(len(ordered)*p)-1)]
def evaluate(document,context,protocol):
 missing=[k for k in protocol['requiredContext'] if k not in context]
 if missing:raise ValueError('Missing context: '+', '.join(missing))
 for key in ('appSha256','benchmarkApkSha256','installedAppSha256','installedBenchmarkApkSha256','modelManifestSha256','protocolSha256'):
  if not isinstance(context[key],str) or not re.fullmatch('[0-9a-f]{64}',context[key]):raise ValueError('Invalid identity: '+key)
 if not isinstance(context['sourceSha'],str) or not re.fullmatch('[0-9a-f]{40}',context['sourceSha']):raise ValueError('Exact source identity required')
 if context['executionStatus']!='COMPLETE' or context['installedAppSha256']!=context['appSha256'] or context['installedBenchmarkApkSha256']!=context['benchmarkApkSha256']:raise ValueError('Benchmark source/installed candidate execution was not verified')
 if not isinstance(context['deviceFingerprint'],str) or not context['deviceFingerprint']:raise ValueError('Missing device fingerprint')
 if not isinstance(context['ambientC'],(int,float)) or not math.isfinite(context['ambientC']):raise ValueError('Invalid ambient temperature')
 if not isinstance(context['refreshRateHz'],(int,float)) or not math.isfinite(context['refreshRateHz']) or context['refreshRateHz']<=0:raise ValueError('Invalid refresh rate')
 if context.get('physical') is not True or context.get('abi')!='arm64-v8a':raise ValueError('Physical ARM64 evidence required')
 if context['thermalBefore']>1 or context['thermalAfter']>1:raise ValueError('Thermal conditions outside protocol')
 if context['batteryPercent']<30:raise ValueError('Battery below protocol minimum')
 results={};benchmarks=document.get('benchmarks',[])
 for name in protocol['scenarios']:
  matches=[b for b in benchmarks if b.get('name','').split('[')[0]==name]
  if len(matches)!=1:raise ValueError('Missing/duplicate scenario: '+name)
  b=matches[0];metrics=b.get('metrics',{});sample={}
  for metric,record in metrics.items():
   runs=record.get('runs',[]);values=[v for run in runs for v in (run if isinstance(run,list) else [run])]
   if len(values)<protocol['iterations']:raise ValueError('Insufficient measured samples: '+name+'/'+metric)
   sample[metric]={'p95':percentile(values,.95),'p99':percentile(values,.99),'count':len(values)}
  if not sample:raise ValueError('No measured metrics: '+name)
  results[name]=sample
 failures=[];budgets=protocol['budgets']
 for name,budget in [('coldLaunch','coldStartupP95Ms'),('warmLaunch','warmStartupP95Ms')]:
  if results[name].get('timeToInitialDisplayMs',{}).get('p95',math.inf)>budgets[budget]:failures.append(budget)
 for name in protocol['scenarios'][2:]:
  frame=results[name].get('frameDurationCpuMs',{})
  if frame.get('p95',math.inf)>budgets['frameCpuP95Ms']:failures.append(name+':frameP95')
  if frame.get('p99',math.inf)>budgets['frameCpuP99Ms']:failures.append(name+':frameP99')
 unavailable=[]
 for key,budget in [('inputResponseP95Ms','inputResponseP95Ms'),('smallPssMaxMb','smallPssMaxMb'),('largePssMaxMb','largePssMaxMb')]:
  value=context.get(key)
  if value is None:unavailable.append(key)
  elif not math.isfinite(value) or value<0 or value>budgets[budget]:failures.append(key)
 return dict(schemaVersion=1,protocol=protocol['protocol'],context=context,results=results,failures=failures,
             unavailableCounters=unavailable,status='FAIL' if failures else 'NOT_RUN' if unavailable else 'PASS')
def compare(before,after):
 if before['status']!='PASS' or after['status']!='PASS':raise ValueError('Both measured qualification runs must pass')
 keys=['deviceFingerprint','benchmarkApkSha256','modelManifestSha256','ambientC','refreshRateHz','protocolSha256']
 if any(before['context'][k]!=after['context'][k] for k in keys):raise ValueError('Noncomparable baseline-profile conditions')
 if before['context'].get('unprofiledAppSha256')!=after['context'].get('unprofiledAppSha256') or not before['context'].get('unprofiledAppSha256'):raise ValueError('Same unprofiled build identity required')
 improvements=[]
 for scenario,metrics in before['results'].items():
  for metric,base in metrics.items():
   candidate=after['results'][scenario][metric]
   if candidate['p99']>base['p99']*1.02:raise ValueError('Tail regression: '+scenario+'/'+metric)
   if candidate['p95']<base['p95']*.95:improvements.append(scenario+'/'+metric)
 return dict(retainBaselineProfile=bool(improvements),improvedMetrics=improvements)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('benchmark',type=Path);p.add_argument('--context',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
 p.add_argument('--protocol',type=Path,default=Path(__file__).with_name('protocol.json'));a=p.parse_args()
 context=json.loads(a.context.read_text());actual=hashlib.sha256(a.protocol.read_bytes()).hexdigest()
 if context.get('protocolSha256')!=actual:raise ValueError('Protocol identity changed')
 report=evaluate(json.loads(a.benchmark.read_text()),context,json.loads(a.protocol.read_text()));a.output.write_text(json.dumps(report,indent=2)+'\n')
 raise SystemExit(0 if report['status']=='PASS' else 1)
