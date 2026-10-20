from pathlib import Path
import hashlib,json,subprocess,zipfile,shutil,xml.etree.ElementTree as E
base=Path('/workspace/campaign-061-080/071-picker-slots-compile');base.mkdir(exist_ok=True)
source=Path('/workspace/campaign-061-080/final-validation/3b9f9157753b/source')
aaptjar=next(Path('/workspace/toolchains/gradle-user/caches/modules-2/files-2.1/com.android.tools.build/aapt2/8.11.1-12782657').glob('*/*linux.jar'))
aapt=base/'aapt2'
with zipfile.ZipFile(aaptjar) as z: aapt.write_bytes(z.read('aapt2'))
aapt.chmod(0o755)
res=base/'resources/values/agent_071.xml';res.parent.mkdir(parents=True,exist_ok=True)
shutil.copy2('/workspace/agent-work/071/app/src/main/res/values/agent_071.xml',res)
compiled=base/'compiled';compiled.mkdir(exist_ok=True)
flats=sorted((source/'app/build/intermediates/merged_res/debug/mergeDebugResources').glob('*.flat'))
manifest=source/'app/build/intermediates/packaged_manifests/debug/processDebugManifestForPackage/AndroidManifest.xml'
android=Path('/workspace/toolchains/android-sdk/platforms/android-36/android.jar')
# AGP keeps dependency non-values out of merged_res. Recompile the exact raw
# dependency resources listed by the real3b merger rather than invent resources.
nonvalues=base/'nonvalue-res';nonvalues.mkdir(exist_ok=True)
raw_inputs={}
merger=source/'app/build/intermediates/incremental/debug/mergeDebugResources/merger.xml'
for ds in E.parse(merger).getroot().findall('dataSet'):
 if ds.get('config','').endswith('$Generated') or ds.get('from-dependency')!='true':continue
 for src in ds.findall('source'):
  folder=Path(src.get('path'))
  if not folder.exists():continue
  for raw in sorted(folder.rglob('*')):
   if not raw.is_file():continue
   rel=raw.relative_to(folder)
   if len(rel.parts)!=2 or rel.parts[0].startswith('values'):continue
   dest=nonvalues/rel;dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(raw,dest)
   raw_inputs[str(raw)]=hashlib.sha256(raw.read_bytes()).hexdigest()

java=base/'r-java';java.mkdir(exist_ok=True)
classes=base/'r-classes';classes.mkdir(exist_ok=True)
commands=[
 [str(aapt),'compile',str(res),'-o',str(compiled)],
 [str(aapt),'compile','--dir',str(nonvalues),'-o',str(base/'nonvalues.zip')],
 [str(aapt),'link','-o',str(base/'resources.apk'),'--manifest',str(manifest),'-I',str(android),'--java',str(java),'--custom-package','com.realityforge.scanner','--auto-add-overlay','--non-final-ids',*map(str,flats),'-R',str(base/'nonvalues.zip'),'-R',str(compiled/'values_agent_071.arsc.flat')],
 ['/workspace/toolchains/jdk-17.0.16+8/bin/javac','-source','17','-target','17','-d',str(classes),str(java/'com/realityforge/scanner/R.java')],
 ['/workspace/toolchains/jdk-17.0.16+8/bin/jar','--create','--file',str(base/'R.jar'),'-C',str(classes),'.'],
]
record={'aapt2_sha256':hashlib.sha256(aapt.read_bytes()).hexdigest(),'xml_sha256':hashlib.sha256(res.read_bytes()).hexdigest(),'merged_flat_count':len(flats),'dependency_raw_nonvalue_count':len(raw_inputs),'dependency_raw_inputs':raw_inputs,'merged_manifest_sha256':hashlib.sha256(manifest.read_bytes()).hexdigest(),'commands':commands}
with (base/'resources.log').open('w') as log:
 for command in commands:
  run=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT)
  if run.returncode:raise SystemExit(run.returncode)
record['r_jar_sha256']=hashlib.sha256((base/'R.jar').read_bytes()).hexdigest()
(base/'resource-inputs.json').write_text(json.dumps(record,indent=2,sort_keys=True))
print(json.dumps({k:v for k,v in record.items() if k not in ['commands','dependency_raw_inputs']},indent=2))
