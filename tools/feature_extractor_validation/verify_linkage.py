#!/usr/bin/env python3
"""Additional SIFT declaration/export evidence; never modifies the base capability receipt."""
import argparse,hashlib,json,re,subprocess,uuid
from pathlib import Path
CLASSES=['org.opencv.features2d.SIFT','org.opencv.features2d.Feature2D','org.opencv.core.MatOfKeyPoint']
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
 p=argparse.ArgumentParser()
 for arg in ['java','capability-dir','out']:p.add_argument('--'+arg,type=Path,required=True)
 a=p.parse_args();cap=a.capability_dir.resolve(strict=True);out=a.out.resolve()
 repo=Path(__file__).resolve().parents[2]
 if out==cap or cap in out.parents or out==repo or repo in out.parents:raise ValueError('Linkage output must be outside capability and source tree')
 out.mkdir(parents=True,exist_ok=True)
 receipt_path=out/'sift-linkage.json'
 if receipt_path.exists():receipt_path.rename(out/('superseded-'+uuid.uuid4().hex+'-sift-linkage.json'))
 javap=a.java.resolve(strict=True).with_name('javap');lib=cap/'build/lib/libopencv_java4120.so'
 jars={'aar':cap/'smoke/aar-classes.jar','generated':cap/'build/bin/opencv-4120.jar'}
 inputs=[lib,cap/'validation.json',javap,Path(__file__).resolve(),*jars.values()]
 before={str(x):sha(x) for x in inputs};files={};result={};total=0
 symbols=subprocess.check_output(['nm','-D','--defined-only',str(lib)],text=True,timeout=30)
 symbolfile=out/'sift-exported-symbols.txt';symbolfile.write_text(symbols);files[str(symbolfile)]=sha(symbolfile)
 exports={line.split()[-1] for line in symbols.splitlines() if line.strip()}
 for cls in CLASSES:
  declarations={}
  for kind,jar in jars.items():
   text=subprocess.check_output([str(javap),'-classpath',str(jar),'-private','-s',cls],text=True,timeout=15)
   target=out/f'api-{kind}-{cls}.txt';target.write_text(text);files[str(target)]=sha(target)
   lines=text.splitlines();declarations[kind]=[(line.strip(),lines[i+1].strip()) for i,line in enumerate(lines) if ' native ' in line]
  if declarations['aar']!=declarations['generated']:raise ValueError('SIFT declaration mismatch: '+cls)
  missing=[]
  for decl,_ in declarations['aar']:
   name=re.search(r'(\w+)\(',decl).group(1)
   symbol='Java_'+cls.replace('_','_1').replace('.','_')+'_'+name.replace('_','_1')
   if symbol not in exports:missing.append(symbol)
  if missing:raise ValueError('Missing native symbols: '+repr(missing))
  result[cls]={'equal':True,'nativeDeclarations':len(declarations['aar']),'missingSymbols':missing};total+=len(declarations['aar'])
 if before!={str(x):sha(x) for x in inputs}:raise ValueError('Linkage inputs changed')
 receipt={'schema':1,'classification':'HOST_SIFT_ADDITIONAL_LINKAGE_ONLY','inputs':before,'files':files,'classes':result,'nativeDeclarations':total}
 temporary=out/('sift-linkage-'+uuid.uuid4().hex+'.partial')
 temporary.write_text(json.dumps(receipt,indent=2,sort_keys=True)+'\n');temporary.replace(receipt_path)
 print(f'SIFT additional linkage PASS: {total} native declarations')
if __name__=='__main__':main()
