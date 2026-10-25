#!/usr/bin/env python3
"""Opt-in HOST adapter capability with explicit opaque-only Bitmap bridge; never builds/downloads dependencies."""
import argparse,hashlib,json,os,subprocess,zipfile
from pathlib import Path
SDK_NAME='android-all-15-robolectric-12650502.jar'
SDK_SHA='6c0670454f6fcee9f1b17aa393e81d71bf26e7e6ae7c5777ed3924b062191d5b'
FIXTURE_SHA='66fa84499c73e4a6cbb4af03880e6494433f7d4d32f288476c575a71aa82ab7c'
SOURCES=['engine/NativeCore.kt','engine/ImageLoader.kt','engine/LensGeometry.kt','engine/features/FeatureExtractor.kt','engine/features/GrayFeatureExtraction.kt','engine/features/FeatureFrame.kt','imaging/AnalysisPixels.kt','imaging/AndroidImageDecoder.kt','imaging/ImageContracts.kt','imaging/OwnedImage.kt']
HELPERS=['AdapterRunner.kt','HostOpenCvLoader.kt','OpaqueBitmapBridge.kt','FeatureAdapterProbeTest.kt']
def require(ok,message):
 if not ok:raise ValueError(message)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(path,value):
 tmp=path.with_name(path.name+'.partial');tmp.write_text(json.dumps(value,indent=2,sort_keys=True)+'\n');tmp.replace(path)
def hashes(root):
 files=sorted(root.rglob('*'));require(not any(p.is_symlink() for p in files),'Symlink class output')
 result={str(p.relative_to(root)):sha(p) for p in files if p.is_file()};require(bool(result),'Empty class output');return result
def jars(path):
 result=[Path(x).resolve(strict=True) for x in path.read_text().strip().split(os.pathsep)]
 require(all(x.is_file() and x.suffix=='.jar' for x in result),'Only explicit dependency JARs; no app class directories')
 return result
def parser():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['repo','work','java','compiler-cp','runtime-cp','android-sdk-jar','offline-sdk-dir','capability-dir','aar','sift-linkage','dependency-inventory']:p.add_argument('--'+name,type=Path,required=True)
 p.add_argument('--dependency-inventory-sha256',required=True);return p
def main(argv=None):
 a=parser().parse_args(argv);here=Path(__file__).resolve().parent;repo=a.repo.resolve(strict=True);work=a.work.resolve()
 require(work!=repo and repo not in work.parents and work!=here and here not in work.parents,'Use external fresh work directory')
 work.mkdir(exist_ok=False)
 compiler=jars(a.compiler_cp);runtime=jars(a.runtime_cp)
 require(sha(a.dependency_inventory)==a.dependency_inventory_sha256,'Inventory hash mismatch')
 inventory=json.loads(a.dependency_inventory.read_text())
 require(inventory['schemaVersion']==1 and inventory['task']==':app:hostIntegrationTest','Unsupported inventory')
 require(inventory['runtimeJars']==[{'path':str(x),'sha256':sha(x)} for x in runtime],'Inventory classpath/order/hash mismatch')
 require(bool(inventory['omitted']) and all(x.get('path') and x.get('reason') for x in inventory['omitted']),'Missing omission record')
 require(a.android_sdk_jar.name==SDK_NAME and sha(a.android_sdk_jar)==SDK_SHA,'Unexpected HOST SDK')
 require(sorted(x.name for x in a.offline_sdk_dir.iterdir())==[SDK_NAME],'Unexpected offline SDK contents')
 require(sha(a.offline_sdk_dir/SDK_NAME)==SDK_SHA,'Offline SDK hash mismatch')
 for jar in runtime:
  with zipfile.ZipFile(jar) as z:
   names=z.namelist()
   require(not any(x.endswith('robolectric-deps.properties') for x in names),'Forbidden resolver override')
   require(not any(x.startswith(('org/opencv/','com/realityforge/scanner/')) and x.endswith('.class') for x in names),'Duplicate OpenCV/app bytecode')
 cap=a.capability_dir.resolve(strict=True);receipt=json.loads((cap/'validation.json').read_text())
 require(receipt.get('schema')==1 and receipt.get('classification')=='HOST_OPENCV_JAVA_JNI_CAPABILITY_ONLY','Unsupported capability receipt')
 require(receipt['official_source']['commit']=='49486f61fb25722cbcf586b7f4320921d46fb38e' and receipt['build']['exit_code']==0,'Unverified native build')
 capfiles=[]
 for rel,h in receipt['sha256'].items():
  target=(cap/rel).resolve(strict=True);require(cap in target.parents and sha(target)==h,'Capability file mismatch');capfiles.append(target)
 require(receipt['linkage']['native_version']=='4.12.0','Wrong native version')
 ai=json.loads((cap/'smoke/aar-input.json').read_text());require(sha(a.aar)==ai['aar_sha256'],'AAR mismatch')
 extracted=work/'aar-classes.jar';extracted.write_bytes(zipfile.ZipFile(a.aar).read('classes.jar'));require(sha(extracted)==ai['classes_jar_sha256'],'AAR classes mismatch')
 lib=cap/'build/lib/libopencv_java4120.so';link=json.loads(a.sift_linkage.read_text())
 require(link.get('schema')==1 and link['classification']=='HOST_SIFT_ADDITIONAL_LINKAGE_ONLY','Unsupported SIFT receipt')
 require(set(link['classes'])=={'org.opencv.features2d.SIFT','org.opencv.features2d.Feature2D','org.opencv.core.MatOfKeyPoint'},'Incomplete SIFT linkage')
 require(all(x['equal'] and not x['missingSymbols'] for x in link['classes'].values()),'SIFT linkage mismatch')
 for f,h in {**link['inputs'],**link['files']}.items():require(sha(f)==h,'SIFT artifact mismatch')
 require(link['inputs'].get(str(lib))==sha(lib) and link['inputs'].get(str(cap/'validation.json'))==sha(cap/'validation.json'),'SIFT capability binding mismatch')
 source=[repo/'app/src/main/java/com/realityforge/scanner'/x for x in SOURCES]+[here/x for x in HELPERS]
 fixture=repo/'benchmarks/fixtures/relief/view-01.jpg';require(sha(fixture)==FIXTURE_SHA,'Fixture mismatch')
 files=[*capfiles,*map(Path,{**link['inputs'],**link['files']}),*source,Path(__file__),a.java,a.java.parent.parent/'release',a.compiler_cp,a.runtime_cp,a.dependency_inventory,a.android_sdk_jar,a.aar,a.sift_linkage,cap/'validation.json',cap/'smoke/aar-input.json',lib,extracted,fixture,*compiler,*runtime,a.offline_sdk_dir/SDK_NAME]
 def inputs():
  require(sorted(x.name for x in a.offline_sdk_dir.iterdir())==[SDK_NAME],'Offline SDK contents changed')
  return {str(x):sha(x) for x in files}
 before=inputs();out=work/'classes';cp=os.pathsep.join(map(str,[extracted,a.android_sdk_jar,*runtime]));env=os.environ.copy()
 for key in ['JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS','LD_PRELOAD']:env.pop(key,None)
 env['OPENCV_FOR_THREADS_NUM']='2'
 cmd=[str(a.java),'-Xmx384m','-XX:ActiveProcessorCount=2','-cp',os.pathsep.join(map(str,compiler)),'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-jvm-target','17','-cp',cp,'-d',str(out),*map(str,source)]
 with (work/'compile.log').open('w') as f:subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,env=env,timeout=120,check=True)
 require(inputs()==before,'Inputs changed during compile');compiled=hashes(out)
 write(work/'compile-receipt.json',{'inputs':before,'classes':compiled,'compileLog':sha(work/'compile.log')})
 props={'robolectric.offline':'true','robolectric.usePreinstrumentedJars':'false','robolectric.dependency.dir':str(a.offline_sdk_dir),'rf.decoder.fixtureDir':str(fixture.parent),'rf.adapter.opencvLibrary':str(lib),'rf.adapter.opencvLibrarySha256':sha(lib),'rf.adapter.opencvClasses':str(extracted),'rf.adapter.opencvClassesSha256':sha(extracted)}
 cmd=[str(a.java),'-Xmx1g','-XX:ActiveProcessorCount=2','--add-opens=java.base/java.io=ALL-UNNAMED',*[f'-D{k}={v}' for k,v in props.items()],'-cp',str(out)+os.pathsep+cp,'org.junit.runner.JUnitCore','hostadapter.FeatureAdapterProbeTest']
 with (work/'run.log').open('w') as f:result=subprocess.run(cmd,stdout=f,stderr=subprocess.STDOUT,env=env,timeout=180)
 require(inputs()==before and hashes(out)==compiled,'Inputs or class outputs changed during run')
 success=result.returncode==0 and 'OK (1 test)' in (work/'run.log').read_text()
 observation={'inputs':before,'classes':compiled,'compileReceipt':sha(work/'compile-receipt.json'),'runLog':sha(work/'run.log'),'exitCode':result.returncode,'status':'PASS' if success else 'FAIL','limits':{'compileSeconds':120,'compileHeap':'384m','runSeconds':180,'runHeap':'1g','processors':2,'opencvThreads':2}}
 write(work/'observation.json',observation)
 require(success,'Actual adapter experiment failed; inspect run.log')
 write(work/'run-receipt.json',observation);print('HOST adapter PASS (1 ordered test)')
if __name__=='__main__':main()
