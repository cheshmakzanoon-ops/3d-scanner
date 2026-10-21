#!/usr/bin/env python3
"""Offline serial-bound Android resource sampler. Missing counters remain null."""
from __future__ import annotations
import argparse
from datetime import datetime,timezone
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import time


def meminfo(text):
    def field(pattern):
        found=re.search(pattern,text,re.MULTILINE)
        return int(found[1])*1024 if found else None
    return {'pssBytes':field(r'TOTAL PSS:\s*(\d+)'), 'rssBytes':field(r'TOTAL RSS:\s*(\d+)'),
            'nativeHeapPssBytes':field(r'^\s*Native Heap:\s*(\d+)'),
            'javaHeapPssBytes':field(r'^\s*Java Heap:\s*(\d+)')}


def battery(text):
    def field(name):
        found=re.search(r'^\s*'+re.escape(name)+r':\s*(-?\d+)',text,re.MULTILINE)
        return int(found[1]) if found else None
    level=field('level');scale=field('scale');temperature=field('temperature')
    return {'level':level,'scale':scale,'temperatureC':temperature/10 if temperature is not None else None,
            'status':field('status'),'chargeCounterMicroAh':field('Charge counter'),
            'voltageMillivolts':field('voltage')}


def analyze(trace):
    if trace.get('schema')!=1 or any(not re.fullmatch('[0-9a-f]{64}',trace.get('identity',{}).get(key,'')) for key in ('appSha256','inputSha256','settingsSha256')):
        raise ValueError('Exact app/input/settings identities required')
    identity=trace['identity']
    if not identity.get('serial') or not identity.get('fingerprint') or identity.get('abi') not in ('arm64-v8a','x86_64') or type(identity.get('emulator')) is not bool:raise ValueError('Device/runtime identity missing')
    rows=trace.get('samples',[]); interval=trace.get('intervalSeconds',0)
    if not 2<=len(rows)<=3600 or len(trace.get('debugEvents',[]))>2048 or not isinstance(interval,(float,int)) or not math.isfinite(interval) or interval<=0:
        raise ValueError('At least two bounded samples required')
    pids={r.get('pid') for r in rows};starts={r.get('processStartTicks') for r in rows}
    if len(pids)!=1 or None in pids or next(iter(pids))<=1 or len(starts)!=1:
        raise ValueError('Trace mixes stale/restarted process identity')
    for index,row in enumerate(rows):
        elapsed=row.get('elapsedSeconds')
        if type(elapsed) not in (int,float) or not math.isfinite(elapsed) or elapsed<0:raise ValueError('Invalid timestamp')
        if index and not 0<elapsed-rows[index-1]['elapsedSeconds']<=interval*3:
            raise ValueError('Trace has a gap, duplicate, or reversed timestamp')
        nanos=row.get('deviceElapsedRealtimeNanos')
        if nanos is not None:
            if type(nanos) is not int or nanos<0:raise ValueError('Invalid device timestamp')
            previous=rows[index-1].get('deviceElapsedRealtimeNanos') if index else None
            if previous is not None and nanos<=previous:raise ValueError('Reversed/duplicate device clock')
        if row.get('counterError'):raise ValueError('Counter sampling failed')
        for key in ('pssBytes','rssBytes','nativeHeapPssBytes','javaHeapPssBytes','appStorageBytes'):
            value=row.get(key)
            if value is not None and (type(value) is not int or not 0<=value<=2**40):raise ValueError('Impossible memory counter')
        if row.get('pssBytes') is None:raise ValueError('Process PSS unavailable; cannot certify memory')
        if row.get('rssBytes') is not None and row['rssBytes']<row['pssBytes']:raise ValueError('PSS exceeds RSS')
        if row.get('thermalStatus') is not None and row['thermalStatus'] not in range(7):raise ValueError('Impossible thermal status')
        b=row.get('battery',{})
        if b.get('level') is not None and (b.get('scale') is None or not 0<=b['level']<=b['scale']):raise ValueError('Impossible battery level')
        if b.get('temperatureC') is not None and not -40<=b['temperatureC']<=100:raise ValueError('Impossible battery temperature')
    unsupported=[key for key in ('rssBytes','nativeHeapPssBytes','javaHeapPssBytes','thermalStatus','freeStorageBytes','appStorageBytes') if any(row.get(key) is None for row in rows)]
    if None in starts:unsupported.append('processStartTicks')
    stages={}
    for record in trace.get('debugEvents',[]):
        if record.get('pid')!=rows[0]['pid']:raise ValueError('Debug event belongs to stale process')
        stage=record.get('stage');nanos=record.get('elapsedRealtimeNanos')
        if not isinstance(stage,str) or type(nanos) is not int or nanos<0:raise ValueError('Invalid debug stage event')
        for counter in ('javaAllocatedBytes','nativeAllocatedBytes','pssBytes'):
            value=record.get(counter)
            if value is not None and (type(value) is not int or not 0<=value<=2**40):raise ValueError('Impossible debug allocation counter')
        stages.setdefault(stage,[]).append(nanos)
    debug=trace.get('debugEvents',[])
    for counter in ('javaAllocatedBytes','nativeAllocatedBytes'):
        if not any(record.get(counter) is not None for record in debug):unsupported.append(counter)
    run_durations={}
    for key,values in stages.items():
        if key.endswith(':start') and key[:-6]+':end' in stages:
            end=stages[key[:-6]+':end']
            if min(end)<=min(values):raise ValueError('Reversed debug run boundaries')
            run_durations[key[:-6]]=(min(end)-min(values))/1e9
    if any(row.get('deviceElapsedRealtimeNanos') is None for row in rows):unsupported.append('deviceElapsedRealtimeNanos')
    return {'schema':1,'status':'PASS','identity':identity,'kind':'resource_trace_integrity_not_budget_acceptance',
            'sampleCount':len(rows),'sampledDurationSeconds':rows[-1]['elapsedSeconds']-rows[0]['elapsedSeconds'],
            'peakPssBytes':max(row['pssBytes'] for row in rows),
            'peakRssBytes':max((row['rssBytes'] for row in rows if row.get('rssBytes') is not None),default=None),
            'thermalEvents':[{'elapsedSeconds':row['elapsedSeconds'],'status':row['thermalStatus']} for row in rows if row.get('thermalStatus') is not None],
            'peakAppStorageBytes':max((row['appStorageBytes'] for row in rows if row.get('appStorageBytes') is not None),default=None),
            'peakJavaAllocatedBytes':max((record['javaAllocatedBytes'] for record in debug if record.get('javaAllocatedBytes') is not None),default=None),
            'peakNativeAllocatedBytes':max((record['nativeAllocatedBytes'] for record in debug if record.get('nativeAllocatedBytes') is not None),default=None),
            'runDurationsSeconds':run_durations,
            'stageObservedSpanSeconds':{key:(max(values)-min(values))/1e9 for key,values in stages.items()},
            'unsupportedCounters':unsupported,'energyJoules':None,
            'limitations':['Sampled peaks can miss transients','Battery level/charge counters are not precise measured energy','No GPU allocation/counter inference','Process start identity unavailable weakens PID-reuse detection']}


def collect(args):
    def shell(*parts):
        result=subprocess.run(['adb','-s',args.serial,'shell',*parts],capture_output=True,text=True,timeout=30)
        if result.returncode:raise RuntimeError(result.stderr.strip() or result.stdout)
        return result.stdout.strip()
    def optional(*parts):
        try:return shell(*parts)
        except RuntimeError:return None
    app_hash=hashlib.sha256(args.app_apk.read_bytes()).hexdigest()
    installed=shell('pm','path',args.package).splitlines()
    if len(installed)!=1 or not installed[0].startswith('package:') or shell('sha256sum',installed[0][8:]).split()[0]!=app_hash:
        raise ValueError('Installed single APK does not match supplied app candidate')
    trace={'schema':1,'startedAtUtc':datetime.now(timezone.utc).isoformat(),'intervalSeconds':args.interval,'identity':{'appSha256':app_hash,
           'inputSha256':hashlib.sha256(args.input_manifest.read_bytes()).hexdigest(),
           'settingsSha256':hashlib.sha256(args.settings.read_bytes()).hexdigest(),
           'serial':args.serial,'fingerprint':shell('getprop','ro.build.fingerprint'),
           'abi':shell('getprop','ro.product.cpu.abi'),'emulator':shell('getprop','ro.kernel.qemu')=='1',
           'conditions':json.loads(args.conditions.read_text())},'samples':[],'debugEvents':[]}
    start=time.monotonic()
    for _ in range(args.samples):
        pids=shell('pidof',args.package).split()
        if len(pids)!=1:raise ValueError('One active app process required')
        pid=int(pids[0]);stat=optional('cat',f'/proc/{pid}/stat')
        ticks=int(stat.rsplit(')',1)[1].split()[19]) if stat else None
        uptime=optional('cat','/proc/uptime')
        device_nanos=int(float(uptime.split()[0])*1e9) if uptime else None
        row={'pid':pid,'processStartTicks':ticks,'elapsedSeconds':time.monotonic()-start,'deviceElapsedRealtimeNanos':device_nanos,
             **meminfo(shell('dumpsys','meminfo',args.package)),'battery':battery(shell('dumpsys','battery'))}
        thermal=optional('dumpsys','thermalservice') or ''
        status=re.search(r'Thermal Status:\s*(\d+)',thermal);row['thermalStatus']=int(status[1]) if status else None
        disk=optional('df','-k','/data') or ''
        lines=disk.splitlines();columns=lines[-1].split() if len(lines)>1 else []
        row['freeStorageBytes']=int(columns[3])*1024 if len(columns)>3 and columns[3].isdigit() else None
        private_disk=optional('run-as',args.package,'du','-sk','.')
        private_columns=private_disk.split() if private_disk else []
        row['appStorageBytes']=int(private_columns[0])*1024 if private_columns and private_columns[0].isdigit() else None
        trace['samples'].append(row)
        args.output.write_text(json.dumps(trace,indent=2)+'\n')
        if len(trace['samples'])<args.samples:time.sleep(args.interval)
    events=optional('run-as',args.package,'cat','files/qa-resource-probe.jsonl')
    if events:
        if len(events)>1024*1024:raise ValueError('Debug event stream exceeds bound')
        trace['debugEvents']=[json.loads(line) for line in events.splitlines()]
    args.output.write_text(json.dumps(trace,indent=2)+'\n')
    return trace


def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest='mode',required=True)
    p=sub.add_parser('collect')
    p.add_argument('--serial',required=True);p.add_argument('--package',default='com.realityforge.scanner')
    for field in ('app-apk','input-manifest','settings','conditions','output'):p.add_argument('--'+field,type=Path,required=True)
    p.add_argument('--samples',type=int,default=120);p.add_argument('--interval',type=float,default=1)
    a=sub.add_parser('analyze');a.add_argument('trace',type=Path);a.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();args.output.parent.mkdir(parents=True,exist_ok=True)
    try:
        if args.mode=='collect':
            if not 2<=args.samples<=3600 or not .5<=args.interval<=10:raise ValueError('Sampling budget outside 2–3600 samples /0.5–10 seconds')
            trace=collect(args);summary=analyze(trace);summary['sourceTraceSha256']=hashlib.sha256(args.output.read_bytes()).hexdigest();(args.output.with_suffix('.summary.json')).write_text(json.dumps(summary,indent=2)+'\n')
        else:summary=analyze(json.loads(args.trace.read_text()));summary['sourceTraceSha256']=hashlib.sha256(args.trace.read_bytes()).hexdigest();args.output.write_text(json.dumps(summary,indent=2)+'\n')
    except Exception as error:
        args.output.with_suffix('.error.json').write_text(json.dumps({'status':'FAIL','error':str(error)},indent=2)+'\n');raise SystemExit(1)

if __name__=='__main__':main()
