"""All traces in this file are explicitly SYNTHETIC parser fixtures."""
import importlib.util
import copy
from pathlib import Path
import pytest
spec=importlib.util.spec_from_file_location('resources',Path(__file__).resolve().parents[1]/'tools/device/resource_profiling/profile.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)

def trace():
    return {'schema':1,'identity':{'appSha256':'a'*64,'inputSha256':'b'*64,'settingsSha256':'c'*64,'serial':'SYNTHETIC_PARSER_TEST','fingerprint':'synthetic','abi':'arm64-v8a','emulator':False},'intervalSeconds':1,
            'samples':[{'pid':123,'processStartTicks':99,'elapsedSeconds':float(i),'pssBytes':1024+i*1024,'rssBytes':8192,'thermalStatus':i,'battery':{'level':90,'scale':100,'temperatureC':30}} for i in range(3)]}

def test_memory_units_and_missing_counters_are_explicit():
    row=m.meminfo('TOTAL PSS: 123 TOTAL RSS: 456\n Native Heap: 42\n Java Heap: 21\n')
    assert row['pssBytes']==123*1024 and row['nativeHeapPssBytes']==42*1024
    assert m.meminfo('Permission denied')['pssBytes'] is None
    report=m.analyze(trace());assert report['peakPssBytes']==3072
    assert report['energyJoules'] is None and 'freeStorageBytes' in report['unsupportedCounters']

@pytest.mark.parametrize('field,value',[('pid',124),('processStartTicks',100),('elapsedSeconds',8),('pssBytes',-1),('pssBytes',2**41),('rssBytes',1),('thermalStatus',7),('counterError','permission denied')])
def test_gaps_restarts_and_impossible_counters_rejected(field,value):
    data=trace();data['samples'][1][field]=value
    with pytest.raises(ValueError):m.analyze(data)

def test_duplicate_time_missing_pss_and_bad_battery_rejected():
    for field,value in [('elapsedSeconds',0),('pssBytes',None),('battery',{'level':101,'scale':100})]:
        data=trace();data['samples'][1][field]=value
        with pytest.raises(ValueError):m.analyze(data)

def test_stale_debug_pid_cannot_be_correlated():
    data=trace();data['debugEvents']=[{'pid':999,'stage':'depth','elapsedRealtimeNanos':1000}]
    with pytest.raises(ValueError):m.analyze(data)


def test_debug_stage_and_run_durations_use_actual_device_clock():
    data=trace();data['debugEvents']=[{'pid':123,'stage':stage,'elapsedRealtimeNanos':nanos} for stage,nanos in [('run-0:start',1_000_000_000),('run-0:depth',2_000_000_000),('run-0:depth',4_000_000_000),('run-0:end',5_000_000_000)]]
    report=m.analyze(data)
    assert report['runDurationsSeconds']=={'run-0':4.0}
    assert report['stageObservedSpanSeconds']['run-0:depth']==2.0


def test_debug_allocated_bytes_are_distinct_from_meminfo_heap_pss():
    data=trace();data['debugEvents']=[{'pid':123,'stage':'depth','elapsedRealtimeNanos':1000,'nativeAllocatedBytes':100000,'javaAllocatedBytes':50000,'pssBytes':8000}]
    report=m.analyze(data)
    assert report['peakNativeAllocatedBytes']==100000 and report['peakJavaAllocatedBytes']==50000
    data['debugEvents'][0]['nativeAllocatedBytes']=-1
    with pytest.raises(ValueError):m.analyze(data)


def test_device_monotonic_clock_reversal_rejected():
    data=trace()
    for index,row in enumerate(data['samples']):row['deviceElapsedRealtimeNanos']=(index+1)*1_000_000_000
    data['samples'][1]['deviceElapsedRealtimeNanos']=1
    with pytest.raises(ValueError):m.analyze(data)
