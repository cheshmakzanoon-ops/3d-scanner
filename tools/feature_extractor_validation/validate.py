#!/usr/bin/env python3
"""Opt-in production extractor host gate; no downloads, native builds, Gradle or app-class reuse."""
import argparse,hashlib,json,os,subprocess,uuid,zipfile
from pathlib import Path
SOURCES=['LensGeometry.kt','features/FeatureFrame.kt','features/GrayFeatureExtraction.kt']
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def hashes(root):
 result={}
 for p in sorted(root.rglob('*')):
  if p.is_symlink():raise ValueError('Symlink in class output')
  if p.is_file():result[p.relative_to(root).as_posix()]=sha(p)
 if not result:raise ValueError('Empty output tree')
 return result
def write(p,value):
 t=p.with_name(p.name+'.'+uuid.uuid4().hex+'.partial');t.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n');t.replace(p)
def invalidate(p):
 if p.exists():p.rename(p.with_name('superseded-'+uuid.uuid4().hex+'-'+p.name))
def parser():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['compile','run'])
 for name in ['repo','work','java','compiler-classpath','runtime-classpath','aar','capability-dir','linkage-receipt']:p.add_argument('--'+name,type=Path,required=True)
 return p
def main(argv=None):
 a=parser().parse_args(argv);repo=a.repo.resolve(strict=True);work=a.work.resolve();here=Path(__file__).resolve().parent
 if work==repo or repo in work.parents or work==here or here in work.parents:raise ValueError('Use independent external work directory')
 work.mkdir(parents=True,exist_ok=True);invalidate(work/'run-receipt.json')
 if a.mode=='compile':invalidate(work/'compile-receipt.json')
 java=a.java.resolve(strict=True);cap=a.capability_dir.resolve(strict=True);aar=a.aar.resolve(strict=True)
 def jars(path):
  result=[Path(x).resolve(strict=True) for x in path.read_text().strip().split(os.pathsep)]
  if not result or any(not x.is_file() for x in result):raise ValueError('Invalid jar classpath')
  return result
 compiler=jars(a.compiler_classpath);runtime=jars(a.runtime_classpath)
 sources=[repo/'app/src/main/java/com/realityforge/scanner/engine'/r for r in SOURCES]+[here/'ProductionExtractorHostTest.kt',here/'HostRunner.kt']
 lib=cap/'build/lib/libopencv_java4120.so'
 def inputs():
  receipt=json.loads((cap/'validation.json').read_text())
  if receipt.get('schema')!=1 or receipt.get('classification')!='HOST_OPENCV_JAVA_JNI_CAPABILITY_ONLY':raise ValueError('Unsupported capability receipt')
  if receipt.get('official_source',{}).get('commit')!='49486f61fb25722cbcf586b7f4320921d46fb38e' or receipt.get('build',{}).get('exit_code')!=0:raise ValueError('Unverified official-source build receipt')
  for rel,h in receipt['sha256'].items():
   target=(cap/rel).resolve(strict=True)
   if cap not in target.parents or sha(target)!=h:raise ValueError('Capability hash mismatch: '+rel)
  if receipt['linkage']['native_version']!='4.12.0':raise ValueError('Wrong native version')
  ai=json.loads((cap/'smoke/aar-input.json').read_text())
  if sha(aar)!=ai['aar_sha256']:raise ValueError('Wrong AAR input')
  data=zipfile.ZipFile(aar).read('classes.jar')
  if hashlib.sha256(data).hexdigest()!=ai['classes_jar_sha256']:raise ValueError('Wrong AAR classes')
  return {'sources':{str(x):sha(x) for x in sources},'helper':sha(Path(__file__)),'java':sha(java),'javaRelease':sha(java.parent.parent/'release'),'compilerCp':sha(a.compiler_classpath),'runtimeCp':sha(a.runtime_classpath),'compilerJars':{str(x):sha(x) for x in compiler},'runtimeJars':{str(x):sha(x) for x in runtime},'aar':sha(aar),'aarClasses':ai['classes_jar_sha256'],'capabilityReceipt':sha(cap/'validation.json'),'native':sha(lib)}
 base_inputs=inputs
 def inputs():
  result=base_inputs();link=json.loads(a.linkage_receipt.read_text())
  if link.get('schema')!=1 or link.get('classification')!='HOST_SIFT_ADDITIONAL_LINKAGE_ONLY':raise ValueError('Invalid SIFT linkage receipt')
  expected={'org.opencv.features2d.SIFT','org.opencv.features2d.Feature2D','org.opencv.core.MatOfKeyPoint'}
  if set(link['classes'])!=expected or any(not x['equal'] or x['missingSymbols'] for x in link['classes'].values()):raise ValueError('Incomplete SIFT linkage')
  for path,digest in {**link['inputs'],**link['files']}.items():
   if sha(path)!=digest:raise ValueError('SIFT linkage input changed: '+path)
  if link['inputs'].get(str(lib))!=result['native'] or link['inputs'].get(str(cap/'validation.json'))!=result['capabilityReceipt']:raise ValueError('SIFT linkage capability mismatch')
  result['siftLinkageReceipt']=sha(a.linkage_receipt)
  return result
 before=inputs();extracted=work/'aar-classes.jar'
 if extracted.exists():
  if sha(extracted)!=before['aarClasses']:raise ValueError('Existing AAR extraction changed')
 else:extracted.write_bytes(zipfile.ZipFile(aar).read('classes.jar'))
 cp=os.pathsep.join(map(str,[extracted]+runtime));env=os.environ.copy()
 for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD']:env.pop(key,None)
 env['OPENCV_FOR_THREADS_NUM']='2'
 if a.mode=='compile':
  output=work/('classes-'+uuid.uuid4().hex)
  cmd=[str(java),'-Xmx384m','-XX:ActiveProcessorCount=2','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-cp',cp,'-d',str(output),*map(str,sources)]
  with (work/'compile.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,env=env,timeout=120,check=True)
  if inputs()!=before or sha(extracted)!=before['aarClasses']:raise ValueError('Inputs changed during compilation')
  write(work/'compile-receipt.json',{'inputs':before,'output':str(output),'classes':hashes(output),'compileLog':sha(work/'compile.log'),'limits':{'seconds':120,'heap':'384m','processors':2}})
 else:
  receipt=json.loads((work/'compile-receipt.json').read_text());output=Path(receipt['output']).resolve(strict=True)
  if output.parent!=work or not output.name.startswith('classes-'):raise ValueError('Invalid output path')
  if receipt['inputs']!=before or receipt['classes']!=hashes(output):raise ValueError('Stale compile receipt')
  cmd=[str(java),'-Xmx256m','-XX:ActiveProcessorCount=2','-cp',str(output)+os.pathsep+cp,'HostRunnerKt',str(lib)]
  with (work/'run.log').open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,env=env,timeout=90,check=True)
  if inputs()!=before or receipt['classes']!=hashes(output) or sha(extracted)!=before['aarClasses']:raise ValueError('Inputs or outputs changed during run')
  write(work/'run-receipt.json',{'inputs':before,'classes':receipt['classes'],'compileReceipt':sha(work/'compile-receipt.json'),'runLog':sha(work/'run.log'),'status':'PASS','expectedTests':8,'limits':{'seconds':90,'heap':'256m','processors':2,'opencvThreads':2}})
 print(a.mode+' PASS')
if __name__=='__main__':main()
