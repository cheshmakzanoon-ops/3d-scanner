#!/usr/bin/env python3
"""Validate AAB and generate/sign/audit universal and delivered APK splits, offline after tool acquisition."""
from __future__ import annotations
import argparse,json,os,subprocess,sys,zipfile,re,shutil
from pathlib import Path
from apk import validate,archive_policy,elf_alignment,sha256,signature_policy
BUNDLETOOL_VERSION='1.18.2'
BUNDLETOOL_SHA256='378b5434cd1378bef6b2bc527b8c7f0ff2584b273830335bce54d6d0813c8584'
BUNDLETOOL_URL='https://github.com/google/bundletool/releases/download/1.18.2/bundletool-all-1.18.2.jar'
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('aab',type=Path);p.add_argument('--bundletool',type=Path,required=True)
 p.add_argument('--sdk',type=Path,default=Path(os.environ.get('ANDROID_HOME','')));p.add_argument('--output',type=Path,required=True)
 p.add_argument('--keystore',type=Path,required=True);p.add_argument('--key-alias',required=True)
 p.add_argument('--store-pass-file',type=Path,required=True);p.add_argument('--key-pass-file',type=Path,required=True)
 p.add_argument('--signing-mode',choices=['test','production'],default='test');p.add_argument('--certificate-sha256')
 p.add_argument('--require-notices',action='store_true');a=p.parse_args()
 if sha256(a.bundletool)!=BUNDLETOOL_SHA256:raise ValueError('Unapproved bundletool checksum')
 if a.signing_mode=='production' and not a.certificate_sha256:raise ValueError('Production signer identity required')
 a.output.mkdir(parents=True,exist_ok=True)
 def tool(*args):return subprocess.check_output(['java','-jar',str(a.bundletool),*map(str,args)],stderr=subprocess.PIPE,text=True)
 tool('validate','--bundle='+str(a.aab))
 java_bin=Path(os.environ['JAVA_HOME'])/'bin' if os.environ.get('JAVA_HOME') else None
 jarsigner=str(java_bin/'jarsigner') if java_bin else shutil.which('jarsigner')
 keytool=str(java_bin/'keytool') if java_bin else shutil.which('keytool')
 signed=subprocess.check_output([jarsigner,'-J-Duser.language=en','-verify',str(a.aab)],text=True,stderr=subprocess.PIPE)
 if 'jar verified.' not in signed:raise ValueError('AAB signature absent or invalid')
 cert_output=subprocess.check_output([keytool,'-J-Duser.language=en','-printcert','-jarfile',str(a.aab)],text=True,stderr=subprocess.PIPE)
 aab_certificates=[s.replace(':','').lower() for s in re.findall(r'SHA256:\s*([0-9A-Fa-f:]+)',cert_output)]
 if not aab_certificates or a.certificate_sha256 and set(aab_certificates)!={a.certificate_sha256.lower()}:
  raise ValueError('Unexpected AAB signing identity')
 manifest=tool('dump','manifest','--bundle='+str(a.aab),'--module=base')
 from apk import manifest_policy
 identity=manifest_policy(manifest,True)
 with zipfile.ZipFile(a.aab) as z:
  native=[n for n in z.namelist() if n.startswith('base/lib/') and n.endswith('.so')]
  if not native:raise ValueError('AAB lacks native libraries')
  for n in native:elf_alignment(z.read(n))
  if a.require_notices and not any(n.startswith('base/assets/third-party-notices/') for n in z.namelist()):raise ValueError('AAB lacks notices')
 reports=[]
 for mode in ('universal','default'):
  apks=a.output/(mode+'.apks')
  args=['build-apks','--overwrite','--bundle='+str(a.aab),'--output='+str(apks),
        '--ks='+str(a.keystore),'--ks-key-alias='+a.key_alias,'--ks-pass=file:'+str(a.store_pass_file),
        '--key-pass=file:'+str(a.key_pass_file)]
  if mode=='universal':args+=['--mode=universal']
  tool(*args)
  with zipfile.ZipFile(apks) as z:
   for n in sorted(z.namelist()):
    if not n.endswith('.apk'):continue
    path=a.output/mode/n;path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(z.read(n))
    if mode=='universal':
     reports.append(validate(path,a.sdk,release=True,mode=a.signing_mode,certificate=a.certificate_sha256,required_notices=a.require_notices))
    else:
     bt=a.sdk/'build-tools/36.0.0'
     subprocess.run([str(bt/'zipalign'),'-c','-P','16','4',str(path)],check=True,capture_output=True)
     sig=subprocess.check_output([str(bt/'apksigner'),'verify','--verbose','--print-certs',str(path)],text=True)
     cert=signature_policy(sig,a.signing_mode,a.certificate_sha256)
     with zipfile.ZipFile(path) as split:
      libs=[x for x in split.namelist() if x.startswith('lib/') and x.endswith('.so')]
      for lib in libs:elf_alignment(split.read(lib))
     reports.append({'artifact':str(path.relative_to(a.output)),'sha256':sha256(path),'signerCertificateSha256':cert,'nativeLibraries':libs})
 result=dict(identity,schemaVersion=1,aabSha256=sha256(a.aab),bundletoolVersion=BUNDLETOOL_VERSION,
             bundletoolSha256=BUNDLETOOL_SHA256,aabSignerCertificateSha256=aab_certificates,signingMode=a.signing_mode,splits=reports)
 (a.output/'bundle-validation.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result,indent=2))
if __name__=='__main__':main()
